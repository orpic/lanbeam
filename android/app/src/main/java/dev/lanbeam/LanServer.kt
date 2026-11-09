package dev.lanbeam

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

private const val TAG = "LanBeam"
private const val BUF = 1 shl 20 // 1 MiB I/O buffer
private const val STREAM_BUF = 64 shl 10 // per-connection stream buffer
private const val MAX_CONNS = 64 // all clients together
private const val MAX_CONNS_PER_IP = 24 // a paired browser uses up to 18 (6 per port x 3)
private const val MAX_UNPAIRED_PER_IP = 6 // before pairing: page, pairing, port probes
private const val MAX_UNPAIRED_TOTAL = 16 // all unpaired devices together; the rest stay free for paired ones
private const val UPLOAD_IDLE_MS = 10 * 60_000L // an upload with no data for 10 min is abandoned
private const val MAX_PENDING_UPLOADS = 64
private const val MAX_HEADER = 32 * 1024
private const val PORTS = 3
/** First of the fixed ports (47201, 47202, 47203). */
private const val BASE_PORT = 47201

/**
 * A small HTTPS/1.1 server bound only to the phone's Wi-Fi address.
 *
 * - `GET /` serves the web page (no secrets in it).
 * - `POST /api/pair` turns a one-time QR token into a session cookie.
 * - Every other route needs that cookie and a client on the same Wi-Fi subnet.
 *
 * The protocol has no route that lists the Android's storage: the browser only sees files
 * the Android user explicitly offered.
 */
class LanServer(private val ctx: Context, private val lan: LanAddress, ssl: SSLContext) {
    /**
     * Several listening ports on the same address. Browsers cap connections per host:port
     * (WebKit: 6), so extra ports let one page run more transfers in parallel.
     */
    private val servers: List<SSLServerSocket> = List(PORTS) { i ->
        (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            val wanted = arrayOf("TLSv1.3", "TLSv1.2")
            enabledProtocols = wanted.filter { it in supportedProtocols }.toTypedArray()
            receiveBufferSize = 4 shl 20
            reuseAddress = true
            // Fixed ports keep the page's address the same every session, so the browser keeps what the
            // page saved (and its offline copy). If a port is taken, any free port still works.
            try { bind(java.net.InetSocketAddress(lan.address, BASE_PORT + i), 64) }
            catch (_: IOException) { bind(java.net.InetSocketAddress(lan.address, 0), 64) }
        }
    }
    private val server get() = servers[0]
    val ports: List<Int> get() = servers.map { it.localPort }
    private val allowedOrigins: Set<String> by lazy { ports.map { "https://${lan.address.hostAddress}:$it" }.toSet() }
    private val cors = ThreadLocal<String?>()
    private val clientIp = ThreadLocal<String>()
    private val pool = Executors.newCachedThreadPool()
    private val conns = java.util.concurrent.atomic.AtomicInteger()
    private val unpairedConns = java.util.concurrent.atomic.AtomicInteger()
    /** Closes connections that are too slow to send their request (and runs upload cleanup). */
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val connsByIp = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val uploads = ConcurrentHashMap<String, Upload>()
    @Volatile private var running = true

    val port: Int get() = server.localPort
    val baseUrl: String get() = "https://${lan.address.hostAddress}:$port"

    private class Upload(val incoming: Incoming, val pfd: android.os.ParcelFileDescriptor, val channel: java.nio.channels.FileChannel) {
        var lastPublish = 0L
        @Volatile var lastWrite = System.currentTimeMillis()
        /** Completed byte ranges [start, end), merged. Chunks may arrive in any order. */
        val ranges = java.util.TreeMap<Long, Long>()
        var finished = false

        /** Records [a, b) as written and returns the total bytes now covered. */
        fun add(a: Long, b: Long): Long {
            var start = a; var end = b
            ranges.floorEntry(start)?.let { if (it.value >= start) { start = it.key; end = maxOf(end, it.value) } }
            while (true) {
                val next = ranges.ceilingEntry(start) ?: break
                if (next.key > end) break
                end = maxOf(end, next.value); ranges.remove(next.key)
            }
            ranges[start] = end
            return ranges.entries.sumOf { it.value - it.key }
        }
    }

    fun start() = servers.forEach { srv ->
        thread(name = "lanbeam-accept-${srv.localPort}", isDaemon = true) {
            while (running) {
                val s = try { srv.accept() } catch (e: IOException) { if (running) Log.w(TAG, "accept", e); break }
                if (!Net.sameSubnet(s.inetAddress, lan.address, lan.prefix)) {
                    Dbg.log("rejected off-subnet client"); runCatching { s.close() }; continue
                }
                // Limit connections so nobody on the network can crowd out the paired browser.
                // Devices that haven't paired get a few connections each and share a small pool;
                // the rest of the slots stay free for paired devices (which use up to 18 at once).
                val ip = s.inetAddress.hostAddress ?: "?"
                val paired = Hub.isPairedAddress(ip)
                val perIp = connsByIp.computeIfAbsent(ip) { java.util.concurrent.atomic.AtomicInteger() }
                val total = conns.incrementAndGet(); val mine = perIp.incrementAndGet()
                val strangers = if (paired) 0 else unpairedConns.incrementAndGet()
                val over = total > MAX_CONNS || mine > (if (paired) MAX_CONNS_PER_IP else MAX_UNPAIRED_PER_IP) ||
                    (!paired && strangers > MAX_UNPAIRED_TOTAL)
                if (over) {
                    conns.decrementAndGet(); perIp.decrementAndGet(); if (!paired) unpairedConns.decrementAndGet()
                    runCatching { s.close() }; continue
                }
                pool.execute {
                    try { serve(s as SSLSocket) }
                    finally { conns.decrementAndGet(); perIp.decrementAndGet(); if (!paired) unpairedConns.decrementAndGet() }
                }
            }
        }
    }

    fun stop() {
        running = false
        servers.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        timer.shutdownNow()
        // Unfinished uploads: close and delete the half-written files.
        uploads.values.forEach { abandon(it) }
        uploads.clear()
    }

    /** Deletes an upload that will never finish (sharing stopped, or idle for too long). */
    private fun abandon(up: Upload) {
        runCatching { up.channel.close(); up.pfd.close() }
        runCatching { ctx.contentResolver.delete(up.incoming.uri, null, null) }
        uploads.remove(up.incoming.id)
        Hub.incoming.remove(up.incoming)
        Hub.publish()
    }

    init {
        // Every minute: drop uploads nobody has written to for 10 minutes.
        timer.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            uploads.values.filter { !it.finished && now - it.lastWrite > UPLOAD_IDLE_MS }.forEach { abandon(it) }
        }, 1, 1, TimeUnit.MINUTES)
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream) {
        val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: 0L
        fun cookie(name: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun serve(sock: SSLSocket) {
        // Hard deadlines, not just per-read timeouts: a client that trickles one byte at a time is cut off.
        // The TLS handshake plus the first request's headers get 10 s; each later request's headers get 20 s.
        var deadline = timer.schedule({ runCatching { sock.close() } }, 10, TimeUnit.SECONDS)
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000
            sock.sendBufferSize = 4 shl 20
            sock.startHandshake()
            sock.soTimeout = 30_000 // a body read that stalls for 30 s ends the connection
            // Small stream buffers: large body reads and writes (1 MiB arrays, after pairing) bypass them anyway.
            val input = BufferedInputStream(sock.inputStream, STREAM_BUF)
            val out = BufferedOutputStream(sock.outputStream, STREAM_BUF)
            var first = true
            while (running) {
                if (!first) deadline = timer.schedule({ runCatching { sock.close() } }, 20, TimeUnit.SECONDS)
                first = false
                val req = try { readRequest(input) } finally { deadline.cancel(false) } ?: break
                cors.set(req.headers["origin"]?.takeIf { it in allowedOrigins })
                clientIp.set(sock.inetAddress.hostAddress ?: "?")
                Hub.peerRequest(
                    sock.inetAddress.hostAddress ?: "?",
                    PeerScanner.kindFromAgent(req.headers["user-agent"]),
                    Hub.isPaired(req.cookie("lb")),
                )
                val keepAlive = req.headers["connection"]?.equals("close", true) != true
                try {
                    route(req, out)
                } catch (e: HttpError) {
                    if (!Hub.isPaired(req.cookie("lb"))) {
                        // Not paired: answer and hang up. Never read (drain) a stranger's request body.
                        json(out, e.code, JSONObject().put("error", e.message), mapOf("Connection" to "close"))
                        out.flush(); break
                    }
                    drain(req)
                    json(out, e.code, JSONObject().put("error", e.message))
                }
                out.flush()
                if (!keepAlive) break
            }
        } catch (_: SocketException) {
        } catch (e: HttpError) {
            // Malformed request line or headers: there is no request to answer properly. Just close.
            Dbg.log("bad request: ${e.message}")
        } catch (e: IOException) {
            Dbg.log("conn closed: ${e.message}")
        } catch (e: Exception) {
            Dbg.log("serve: $e") // no stack traces in release logs
        } finally {
            deadline.cancel(false)
            runCatching { sock.close() }
        }
    }

    private fun readLine(input: InputStream, budget: IntArray): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val c = input.read()
            if (c == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (--budget[0] < 0) throw HttpError(431, "headers too large")
            if (c == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buf.write(c)
        }
    }

    private fun readRequest(input: InputStream): Req? {
        val budget = intArrayOf(MAX_HEADER)
        val line = readLine(input, budget) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input, budget) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw IOException("chunked bodies not supported")
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = try {
            target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        } catch (_: IllegalArgumentException) { throw HttpError(400, "bad query") }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        return Req(parts[0], path, query, headers, LimitedInput(input, len))
    }

    /** Reads at most [remaining] bytes of the request body from the shared connection stream. */
    private class LimitedInput(private val src: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val c = src.read(); if (c >= 0) remaining--; return c
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    private fun drain(req: Req) {
        val buf = ByteArray(64 * 1024)
        while (req.body.read(buf) > 0) { /* discard */ }
    }

    private fun head(out: OutputStream, code: Int, type: String, length: Long, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        cors.get()?.let { sb.append("Access-Control-Allow-Origin: ").append(it).append("\r\nAccess-Control-Allow-Credentials: true\r\nVary: Origin\r\n") }
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        409 -> "Conflict"; 416 -> "Range Not Satisfiable"; 431 -> "Headers Too Large"; else -> "Error"
    }

    private fun json(out: OutputStream, code: Int, body: JSONObject, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        head(out, code, "application/json; charset=utf-8", bytes.size.toLong(), extra)
        out.write(bytes)
    }

    private fun readJson(req: Req): JSONObject {
        if (req.contentLength > 64 * 1024) throw HttpError(400, "body too large")
        return JSONObject(String(req.body.readBytes(), Charsets.UTF_8).ifBlank { "{}" })
    }

    // ---------------------------------------------------------------- routes

    private fun route(req: Req, out: OutputStream) {
        when {
            req.method == "OPTIONS" -> head(
                out, 204, "text/plain", 0,
                mapOf("Access-Control-Allow-Methods" to "GET, POST, PUT", "Access-Control-Allow-Headers" to "content-type", "Access-Control-Max-Age" to "600"),
            )
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") -> page(out)
            req.method == "GET" && req.path == "/sw.js" -> serviceWorker(out)
            req.method == "POST" && req.path == "/api/pair" -> pair(req, out)
            else -> {
                val sid = req.cookie("lb")
                if (sid == null || !Hub.isPaired(sid)) throw HttpError(401, "not paired")
                when {
                    req.method == "GET" && req.path == "/api/state" -> state(out)
                    req.method == "GET" && req.path == "/api/ping" -> json(out, 200, JSONObject().put("ok", true))
                    req.method == "POST" && req.path == "/api/speed" -> speedUp(req, out)
                    req.method == "GET" && req.path == "/api/speed" -> speedDown(req, out)
                    req.method == "GET" && req.path.startsWith("/dl/") -> download(req, out, req.path.removePrefix("/dl/"))
                    req.method == "POST" && req.path == "/api/upload" -> uploadStart(req, out)
                    req.method == "PUT" && req.path.startsWith("/api/upload/") -> uploadChunk(req, out, req.path.removePrefix("/api/upload/"))
                    req.method == "GET" && req.path.startsWith("/api/upload/") -> uploadStatus(out, req.path.removePrefix("/api/upload/"))
                    req.method == "POST" && req.path == "/api/logout" -> { Hub.endSession(sid); Hub.peerUnpaired(clientIp.get() ?: "?"); json(out, 200, JSONObject().put("ok", true)) }
                    else -> throw HttpError(404, "not found")
                }
            }
        }
    }

    private fun page(out: OutputStream) {
        val bytes = ctx.assets.open("web/index.html").use { it.readBytes() }
        head(
            out, 200, "text/html; charset=utf-8", bytes.size.toLong(),
            mapOf(
                "Content-Security-Policy" to "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ${allowedOrigins.joinToString(" ")}; worker-src 'self'; frame-ancestors 'none'",
                "Cache-Control" to "no-cache",
            ),
        )
        out.write(bytes)
    }

    /** The offline copy of the page (service worker). Browsers may refuse it with a self-signed certificate; that's fine. */
    private fun serviceWorker(out: OutputStream) {
        val bytes = ctx.assets.open("web/sw.js").use { it.readBytes() }
        head(out, 200, "text/javascript; charset=utf-8", bytes.size.toLong(), mapOf("Cache-Control" to "no-cache", "Service-Worker-Allowed" to "/"))
        out.write(bytes)
    }

    private fun pair(req: Req, out: OutputStream) {
        val body = readJson(req)
        val code = body.optString("code")
        val sid = if (code.isNotEmpty()) {
            when (val r = Hub.redeemCode(code, clientIp.get() ?: "?")) {
                is Hub.CodeResult.Ok -> r.sid
                is Hub.CodeResult.Wrong -> throw HttpError(401, r.message)
            }
        } else {
            Hub.redeem(body.optString("token")) ?: throw HttpError(401, "This QR code was already used or has expired. Scan the new one in the LanBeam app.")
        }
        json(
            out, 200, JSONObject().put("ok", true),
            mapOf("Set-Cookie" to "lb=$sid; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=43200"),
        )
    }

    /** The name the user gave the phone (e.g. "Pixel 9"), not the internal model code. */
    private val deviceName: String by lazy {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL
    }

    private fun state(out: OutputStream) {
        val offers = JSONArray()
        Hub.offers.forEach { offers.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("mime", it.mime)) }
        json(out, 200, JSONObject().put("device", deviceName).put("offers", offers).put("ports", JSONArray(ports))
            .put("network", Hub.state.value.hotspotName ?: JSONObject.NULL)
            // Only paired devices reach this endpoint: lets the page show how to rejoin the hotspot.
            .put("wifiPass", Hotspot.passFromQr(Hub.state.value.wifiQr) ?: JSONObject.NULL))
    }

    /** Speed test, upload direction: read and discard the body. */
    private fun speedUp(req: Req, out: OutputStream) {
        val buf = ByteArray(BUF); var n = 0L
        while (true) { val r = req.body.read(buf); if (r <= 0) break; n += r }
        json(out, 200, JSONObject().put("bytes", n))
    }

    /** Speed test, download direction: stream `mb` MiB of random-looking bytes. */
    private fun speedDown(req: Req, out: OutputStream) {
        val mb = (req.query["mb"]?.toLongOrNull() ?: 64L).coerceIn(1, 4096)
        val total = mb shl 20
        head(out, 200, "application/octet-stream", total)
        var left = total
        while (left > 0) { val n = minOf(left, speedBlock.size.toLong()).toInt(); out.write(speedBlock, 0, n); left -= n }
    }

    private val speedBlock = ByteArray(BUF).also { java.util.Random(7).nextBytes(it) }

    private fun download(req: Req, out: OutputStream, id: String) {
        val offer = Hub.offers.firstOrNull { it.id == id } ?: throw HttpError(404, "no longer offered")
        val size = offer.size
        var start = 0L
        var end = size - 1
        var code = 200
        req.headers["range"]?.let { r ->
            val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(r.trim()) ?: return@let
            val (a, b) = m.destructured
            // toLongOrNull: "bytes=-" or absurdly long numbers are answered with 416, not an exception.
            val av = a.toLongOrNull(); val bv = b.toLongOrNull()
            if (a.isEmpty()) {
                if (bv == null) { head(out, 416, "text/plain", 0, mapOf("Content-Range" to "bytes */$size")); return }
                start = (size - bv).coerceAtLeast(0)
            } else {
                if (av == null || (b.isNotEmpty() && bv == null)) { head(out, 416, "text/plain", 0, mapOf("Content-Range" to "bytes */$size")); return }
                start = av; if (bv != null) end = minOf(bv, size - 1)
            }
            if (start > end || start >= size) {
                head(out, 416, "text/plain", 0, mapOf("Content-Range" to "bytes */$size")); return
            }
            code = 206
        }
        val len = end - start + 1
        val disp = "attachment; filename*=UTF-8''" + URLEncoder.encode(offer.name, "UTF-8").replace("+", "%20")
        val extra = mutableMapOf("Content-Disposition" to disp, "Accept-Ranges" to "bytes")
        if (code == 206) extra["Content-Range"] = "bytes $start-$end/$size"
        head(out, code, offer.mime, len, extra)
        ctx.contentResolver.openFileDescriptor(offer.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                ch.position(start)
                val bb = ByteBuffer.allocate(BUF)
                var left = len
                while (left > 0) {
                    bb.clear(); if (left < BUF) bb.limit(left.toInt())
                    val n = ch.read(bb); if (n <= 0) break
                    out.write(bb.array(), 0, n); left -= n
                }
            }
        }
    }

    private fun uploadStart(req: Req, out: OutputStream) {
        val body = readJson(req)
        val name = sanitize(body.optString("name"))
        val size = body.optLong("size", -1)
        if (size < 0) throw HttpError(400, "size required")
        // Refuse what cannot fit: count the space every unfinished upload still needs, and keep 200 MB free.
        val free = android.os.StatFs(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path).availableBytes
        val reserved = uploads.values.sumOf { (it.incoming.size - it.incoming.received).coerceAtLeast(0) }
        if (size > free - reserved - (200L shl 20)) throw HttpError(507, "Not enough free space on the Android for this file.")
        if (uploads.size >= MAX_PENDING_UPLOADS) throw HttpError(429, "Too many unfinished uploads. Wait for the current ones to finish.")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LanBeam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw HttpError(500, "could not create file")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")!!
        val ch = FileOutputStream(pfd.fileDescriptor).channel
        val inc = Incoming(Hub.randomId(9), name, size, uri)
        uploads[inc.id] = Upload(inc, pfd, ch)
        Hub.incoming += inc
        if (size == 0L) uploads[inc.id]!!.let { it.finished = true; finish(it) }
        Hub.publish()
        json(out, 200, JSONObject().put("id", inc.id).put("received", 0))
    }

    private fun uploadStatus(out: OutputStream, id: String) {
        val inc = Hub.incoming.firstOrNull { it.id == id } ?: throw HttpError(404, "unknown upload")
        val ranges = JSONArray()
        uploads[id]?.let { up -> synchronized(up) { up.ranges.forEach { (a, b) -> ranges.put(JSONArray().put(a).put(b)) } } }
        json(out, 200, JSONObject().put("id", id).put("received", inc.received).put("done", inc.done).put("ranges", ranges))
    }

    /** Writes one chunk at its offset. Chunks of one file can arrive in parallel and in any order. */
    private fun uploadChunk(req: Req, out: OutputStream, id: String) {
        val up = uploads[id] ?: run {
            val done = Hub.incoming.firstOrNull { it.id == id }?.done == true
            if (done) { drain(req); json(out, 200, JSONObject().put("received", -1).put("done", true)); return }
            throw HttpError(404, "unknown upload")
        }
        val offset = req.query["offset"]?.toLongOrNull() ?: throw HttpError(400, "offset required")
        val inc = up.incoming
        val len = req.contentLength
        if (offset < 0 || len < 0 || offset > inc.size - len) throw HttpError(400, "chunk outside the file") // written so it cannot overflow
        val buf = ByteArray(BUF)
        var pos = offset
        while (true) {
            val n = req.body.read(buf); if (n <= 0) break
            val bb = ByteBuffer.wrap(buf, 0, n)
            while (bb.hasRemaining()) pos += up.channel.write(bb, pos) // positional write: safe in parallel
            up.lastWrite = System.currentTimeMillis()
        }
        if (pos - offset != req.contentLength) throw IOException("short body")
        val received: Long
        var finishNow = false
        synchronized(up) {
            received = up.add(offset, pos)
            inc.received = received
            if (received == inc.size && !up.finished) { up.finished = true; finishNow = true }
        }
        val now = System.currentTimeMillis()
        if (finishNow) finish(up) else if (now - up.lastPublish > 300) { up.lastPublish = now; Hub.publish() }
        json(out, 200, JSONObject().put("received", received).put("done", finishNow || inc.done))
    }

    private fun finish(up: Upload) {
        runCatching { up.channel.force(false); up.channel.close(); up.pfd.close() }
        ctx.contentResolver.update(up.incoming.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        up.incoming.done = true
        up.incoming.finishedAt = System.currentTimeMillis()
        uploads.remove(up.incoming.id)
        Hub.publish()
        Dbg.log("received ${up.incoming.name} (${up.incoming.size} bytes)")
    }

    private fun sanitize(n: String): String {
        val s = n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim()
        return s.ifEmpty { "file" }.take(200)
    }
}
