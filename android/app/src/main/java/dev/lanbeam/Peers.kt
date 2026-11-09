package dev.lanbeam

import android.util.Log
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** How far a device on our network has got: joined the Wi-Fi, opened the page, or paired. */
enum class PeerStage(val label: String) { JOINED("Joined Wi-Fi"), OPENED("Opened the page"), PAIRED("Paired") }

data class Peer(
    val ip: String,
    val kind: String,
    val stage: PeerStage,
    val seen: Long,
    val lastRequest: Long = 0,
    val pageOpen: Boolean = false,
    val misses: Int = 0,
)

/**
 * Finds devices that joined this phone's hotspot. Android gives normal apps no list of hotspot
 * clients, so this tries a TCP connection to every address on the hotspot's small subnet: a device
 * that is there answers (accept or refuse) within milliseconds, an empty address stays silent.
 * Port 62078 is open only on iPhones and iPads (Apple's sync service), which tells us the kind.
 */
class PeerScanner(private val lan: LanAddress, private val sweep: Boolean) {
    @Volatile private var running = true
    private val pool = Executors.newFixedThreadPool(128)

    fun start() = thread(name = "lanbeam-peers", isDaemon = true) {
        // Waiting: sweep the whole hotspot back to back until a phone shows up.
        // Connected: only re-check the known phones (one connection attempt each), and only when their page is quiet.
        // When the last one leaves, go back to sweeping.
        while (running) {
            if (!Hub.hasPeers() && !sweep) {
                Thread.sleep(1000) // Same Wi-Fi: never sweep someone else's network; phones appear when they open the page
            } else if (!Hub.hasPeers()) {
                val t0 = System.currentTimeMillis()
                runCatching { scanOnce() }.onFailure { Log.w("LanBeam", "peer scan", it) }
                val took = System.currentTimeMillis() - t0
                if (took < 200) Thread.sleep(200 - took) // never spin if the network errors out instantly
                if (Hub.hasPeers()) Dbg.log("peer found, last sweep $took ms; now watching it")
            } else {
                Thread.sleep(3000)
                val now = System.currentTimeMillis()
                for (p in Hub.peerList()) {
                    if (now - p.lastRequest < 3000) { Hub.peerChecked(p.ip, true); continue } // its page just talked to us
                    val ip = InetAddress.getByName(p.ip) as Inet4Address
                    val here = probe(ip) != null
                    Hub.peerChecked(p.ip, here)
                    if (!Hub.hasPeers()) Dbg.log("${p.kind} ${p.ip} left the Wi-Fi; sweeping again")
                }
            }
        }
    }

    fun stop() { running = false; pool.shutdownNow() }

    private fun scanOnce() {
        val prefix = lan.prefix.coerceAtLeast(24) // hotspots use /24 or smaller; never sweep more than 254 hosts
        val base = lan.address.address.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val net = base and mask
        val hosts = (1 until (1L shl (32 - prefix)) - 1).map { net + it }.filter { it != base }
        val tasks = hosts.map { h ->
            Callable {
                val ip = InetAddress.getByAddress(byteArrayOf((h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte())) as Inet4Address
                probe(ip)?.let { ip.hostAddress!! to it }
            }
        }
        val found = pool.invokeAll(tasks, 8, TimeUnit.SECONDS).mapNotNull { f -> runCatching { f.get() }.getOrNull() }.toMap()
        Hub.peersSeen(found)
    }

    /** Returns "iPhone" if Apple's sync port answers, "Phone" if the device refuses it, or null if nobody is there. */
    private fun probe(ip: Inet4Address): String? = try {
        Socket().use { s ->
            s.bind(InetSocketAddress(lan.address, 0))
            s.connect(InetSocketAddress(ip, 62078), 800)
        }
        "iPhone"
    } catch (e: ConnectException) {
        if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", true) == true) "Phone" else null
    } catch (_: Exception) { null }

    companion object {
        /** Device kind from the browser's User-Agent. */
        fun kindFromAgent(ua: String?): String? = when {
            ua == null -> null
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Android" in ua -> "Android phone"
            "Macintosh" in ua -> "Mac"
            "Windows" in ua -> "PC"
            else -> null
        }
    }
}
