package dev.lanbeam

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A file the Android user chose to offer to the paired browser. */
data class Offer(val id: String, val uri: Uri, val name: String, val size: Long, val mime: String)

/** A file being received from the paired browser. */
class Incoming(
    val id: String,
    val name: String,
    val size: Long,
    val uri: Uri,
    val startedAt: Long = System.currentTimeMillis(),
) {
    @Volatile var received: Long = 0
    @Volatile var done: Boolean = false
    @Volatile var finishedAt: Long = 0
}

data class IncomingView(val id: String, val name: String, val size: Long, val received: Long, val done: Boolean, val bytesPerSec: Long)

data class UiState(
    val running: Boolean = false,
    val baseUrl: String? = null,
    val pairUrl: String? = null,
    val sessions: Int = 0,
    val offers: List<Offer> = emptyList(),
    val incoming: List<IncomingView> = emptyList(),
    val error: String? = null,
    /** "direct" (this phone's hotspot) or "wifi" (an existing Wi-Fi network). */
    val mode: String = "direct",
    /** Wi-Fi QR payload for joining this phone's hotspot (direct mode only). */
    val wifiQr: String? = null,
    val hotspotName: String? = null,
    /** Human-readable link description, e.g. "5 GHz · 866 Mbps link". */
    val link: String? = null,
    /** Advice for getting more speed, if any. */
    val advice: String? = null,
    /** Devices on our network and how far each has got (joined, opened page, paired). */
    val peers: List<Peer> = emptyList(),
    /** 6-digit one-time code for devices that cannot scan the page QR. */
    val pairCode: String? = null,
    /** Code pairing paused after too many wrong codes from the network. */
    val codeLocked: Boolean = false,
    /** SHA-256 of this Android's certificate, to compare with what the browser shows. */
    val certFp: String? = null,
)

/** Process-wide state shared by the UI, the service and the server. */
object Hub {
    val state = MutableStateFlow(UiState())

    val offers = CopyOnWriteArrayList<Offer>()
    val incoming = CopyOnWriteArrayList<Incoming>()
    /** Paired browser sessions -> expiry (ms). Enforced here, not only by the cookie's Max-Age. */
    private val sessions = ConcurrentHashMap<String, Long>()
    private const val SESSION_TTL_MS = 12 * 3600 * 1000L

    private fun newSession(): String = randomId(32).also { sessions[it] = System.currentTimeMillis() + SESSION_TTL_MS }

    /** True if [sid] is a live session; expired ones are dropped. */
    fun isPaired(sid: String?): Boolean {
        if (sid == null) return false
        val exp = sessions[sid] ?: return false
        if (exp < System.currentTimeMillis()) { sessions.remove(sid); return false }
        return true
    }

    fun endSession(sid: String) { sessions.remove(sid); publish() }

    /** One-time pairing tokens -> expiry time (ms). */
    private val tokens = ConcurrentHashMap<String, Long>()
    private val rng = SecureRandom()
    private const val TOKEN_TTL_MS = 10 * 60 * 1000L

    @Volatile var baseUrl: String? = null

    /** Set only in debug builds so tests from the Mac can pair without scanning the QR code. */
    @Volatile var debugLogPairUrl = false

    fun randomId(bytes: Int = 16): String {
        val b = ByteArray(bytes); rng.nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Creates a fresh one-time token and returns the URL to put in the QR code. */
    fun newPairUrl(): String? {
        val base = baseUrl ?: return null
        val now = System.currentTimeMillis()
        tokens.clear() // a new QR cancels every older one, used or not
        val t = randomId(16)
        tokens[t] = now + TOKEN_TTL_MS
        val url = "$base/#t=$t"
        state.value = state.value.copy(pairUrl = url)
        if (!state.value.codeLocked && (code == null || System.currentTimeMillis() > codeExp)) newCode()
        Dbg.log("debug pair url: $url")
        return url
    }

    // ---- pairing by code, for laptops and other devices without a camera

    @Volatile private var code: String? = null
    @Volatile private var codeExp = 0L
    private var wrongTotal = 0
    private val wrongByIp = ConcurrentHashMap<String, LongArray>() // ip -> [count, windowStart]
    private const val WRONG_PER_IP = 5          // then that device waits a minute
    private const val WRONG_WINDOW_MS = 60_000L
    private const val WRONG_TOTAL = 10          // then the code is replaced, so guessing never adds up
    private const val GLOBAL_WRONG_MAX = 20     // wrong codes from all addresses together ...
    private const val GLOBAL_WINDOW_MS = 10 * 60_000L // ... within 10 minutes: code pairing pauses
    private val wrongRecent = java.util.ArrayDeque<Long>()

    /** "Allow codes again" on the Android, after code pairing paused itself. */
    @Synchronized
    fun unlockCodes() {
        wrongRecent.clear(); wrongByIp.clear()
        state.value = state.value.copy(codeLocked = false)
        newCode()
    }

    /** A fresh one-time 6-digit code (shown next to the page QR). */
    private fun newCode() {
        code = "%06d".format(rng.nextInt(1_000_000))
        codeExp = System.currentTimeMillis() + TOKEN_TTL_MS
        wrongTotal = 0
        state.value = state.value.copy(pairCode = code)
        Dbg.log("debug pair code: $code")
    }

    sealed interface CodeResult {
        data class Ok(val sid: String) : CodeResult
        data class Wrong(val message: String) : CodeResult
    }

    /** Checks a typed code. One use, 10 minutes, and limited guesses per device and overall. */
    @Synchronized
    fun redeemCode(typed: String, ip: String): CodeResult {
        val now = System.currentTimeMillis()
        // Overall cap across all addresses (one machine can use many IPs): after too many wrong codes,
        // code pairing stays off until someone taps "Allow codes again" on the Android.
        if (state.value.codeLocked) return CodeResult.Wrong("Code pairing is paused on the Android after too many wrong codes. Ask its owner to allow it again.")
        wrongRecent.removeIf { now - it > GLOBAL_WINDOW_MS }
        val w = wrongByIp.getOrPut(ip) { longArrayOf(0, now) }
        if (now - w[1] > WRONG_WINDOW_MS) { w[0] = 0; w[1] = now }
        if (w[0] >= WRONG_PER_IP) {
            val secs = ((WRONG_WINDOW_MS - (now - w[1])) / 1000).coerceAtLeast(1)
            return CodeResult.Wrong("Too many wrong codes. Try again in $secs s.")
        }
        val c = code
        if (c == null || now > codeExp) { newCode(); return CodeResult.Wrong("That code has expired. Use the new one shown on the Android.") }
        val digits = typed.filter { it.isDigit() }
        if (!java.security.MessageDigest.isEqual(digits.toByteArray(), c.toByteArray())) {
            w[0]++; wrongTotal++; wrongRecent += now
            if (wrongRecent.size >= GLOBAL_WRONG_MAX) {
                code = null
                state.value = state.value.copy(pairCode = null, codeLocked = true)
                android.util.Log.w("LanBeam", "code pairing paused after ${wrongRecent.size} wrong codes")
                return CodeResult.Wrong("Code pairing is paused on the Android after too many wrong codes.")
            }
            if (wrongTotal >= WRONG_TOTAL) { newCode(); return CodeResult.Wrong("Wrong code. The Android now shows a new one.") }
            return CodeResult.Wrong("Wrong code. Check the 6 digits on the Android.")
        }
        val sid = newSession()
        wrongByIp.remove(ip)
        newCode() // used: never valid again
        publish()
        return CodeResult.Ok(sid)
    }

    /** Redeems a pairing token for a session id. The token can be used once. */
    fun redeem(token: String): String? {
        val exp = tokens.remove(token) ?: return null
        if (exp < System.currentTimeMillis()) return null
        val sid = newSession()
        // Show a new QR so the used one is never displayed again.
        newPairUrl()
        publish()
        return sid
    }

    fun revokeAll() { sessions.clear(); peers.replaceAll { _, p -> if (p.stage == PeerStage.PAIRED) p.copy(stage = PeerStage.OPENED) else p }; publish() }

    private val peers = ConcurrentHashMap<String, Peer>()

    /** Called by the server on every request: the browser at [ip] opened the page, and maybe is paired. */
    fun peerRequest(ip: String, kind: String?, paired: Boolean) {
        val old = peers[ip]
        // Once this device has paired, a later unpaired request from the same address (a second
        // browser, a private tab) must not downgrade it. Only a real unpair does that.
        val stage = if (paired || old?.stage == PeerStage.PAIRED) PeerStage.PAIRED else PeerStage.OPENED
        val now = System.currentTimeMillis()
        val p = Peer(ip, kind ?: old?.kind ?: "Device", stage, now, lastRequest = now, pageOpen = true)
        peers[ip] = p
        if (old == null || old.stage != p.stage || old.kind != p.kind || !old.pageOpen) publish()
    }

    /** This browser unpaired itself ("Unpair this browser" on the page). */
    fun peerUnpaired(ip: String) {
        peers.computeIfPresent(ip) { _, p -> if (p.stage == PeerStage.PAIRED) p.copy(stage = PeerStage.OPENED) else p }
        publish()
    }

    fun hasPeers() = peers.isNotEmpty()

    fun peerList(): List<Peer> = peers.values.toList()

    /** Result of re-checking one known phone: still on our Wi-Fi or not. Two misses in a row remove it. */
    fun peerChecked(ip: String, present: Boolean) {
        val old = peers[ip] ?: return
        val now = System.currentTimeMillis()
        val pageOpen = now - old.lastRequest < PAGE_IDLE_MS
        if (!present && old.misses >= 1) { peers.remove(ip); publish(); return }
        val p = old.copy(seen = if (present) now else old.seen, misses = if (present) 0 else old.misses + 1, pageOpen = pageOpen)
        peers[ip] = p
        if (p.pageOpen != old.pageOpen) publish()
    }

    /** The page polls every 2 s while open; quiet for this long means it was closed or put in the background. */
    const val PAGE_IDLE_MS = 8_000L

    /** Called by the hotspot scan with every address that answered (ip -> kind guess). */
    fun peersSeen(found: Map<String, String>) {
        val now = System.currentTimeMillis()
        var changed = false
        for ((ip, kind) in found) {
            val old = peers[ip]
            if (old == null) { peers[ip] = Peer(ip, kind, PeerStage.JOINED, now); changed = true }
            else peers[ip] = old.copy(seen = now, kind = if (old.kind == "Device" || old.kind == "Phone") kind else old.kind)
        }

        if (changed) publish()
    }

    fun reset() {
        tokens.clear(); sessions.clear(); peers.clear(); baseUrl = null; code = null; wrongByIp.clear()
        state.value = UiState(mode = state.value.mode, offers = offers.toList(), incoming = views())
    }

    private fun views(): List<IncomingView> = incoming.map {
        val end = if (it.done) it.finishedAt else System.currentTimeMillis()
        val secs = ((end - it.startedAt).coerceAtLeast(1)) / 1000.0
        IncomingView(it.id, it.name, it.size, it.received, it.done, (it.received / secs).toLong())
    }

    fun publish() {
        state.value = state.value.copy(
            sessions = sessions.size,
            offers = offers.toList(),
            incoming = views().reversed(),
            peers = peers.values.sortedBy { it.ip },
        )
    }
}
