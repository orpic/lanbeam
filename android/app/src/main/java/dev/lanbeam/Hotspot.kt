package dev.lanbeam

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.util.SparseIntArray
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Credentials and band of the phone's own hotspot. */
data class HotspotInfo(val ssid: String, val passphrase: String, val requestedBand: String)

/**
 * Starts a local-only hotspot: a private Wi-Fi network with no internet, created by this phone.
 * On Android 16+ it asks for the 5 GHz band (fastest); if the system refuses, it falls back to
 * the default hotspot. The system picks a random SSID and password each time.
 */
class Hotspot(private val ctx: Context) {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Suppress("MissingPermission")
    fun start(timeoutSec: Long = 15): HotspotInfo {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        var result: HotspotInfo? = null
        var failure: Int? = null

        fun attempt(prefer5: Boolean, named: Boolean = false): Boolean {
            val latch = CountDownLatch(1)
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    // Reading the hotspot's name and password changed across Android versions.
                    val (ssid, pass) = when {
                        Build.VERSION.SDK_INT >= 33 -> r.softApConfiguration.let { (it.wifiSsid?.toString() ?: "") to (it.passphrase ?: "") }
                        Build.VERSION.SDK_INT >= 30 -> @Suppress("DEPRECATION") r.softApConfiguration.let { (it.ssid ?: "") to (it.passphrase ?: "") }
                        else -> @Suppress("DEPRECATION") r.wifiConfiguration.let { (it?.SSID ?: "") to (it?.preSharedKey ?: "") }
                    }
                    result = HotspotInfo(ssid.trim('"'), pass.trim('"'), if (prefer5) "5 GHz" else "auto")
                    latch.countDown()
                }
                override fun onFailed(reason: Int) { failure = reason; latch.countDown() }
                override fun onStopped() { Log.i("LanBeam", "hotspot stopped by system") }
            }
            val exec = Executors.newSingleThreadExecutor()
            if (prefer5 && Build.VERSION.SDK_INT >= 36) {
                val b = SoftApConfiguration.Builder()
                    .setChannels(SparseIntArray().apply { put(SoftApConfiguration.BAND_5GHZ, 0) })
                if (named && !brand(b)) return false
                wm.startLocalOnlyHotspotWithConfiguration(b.build(), exec, cb)
            } else {
                wm.startLocalOnlyHotspot(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            latch.await(timeoutSec, TimeUnit.SECONDS)
            return result != null
        }

        // First try our own name (LanBeam_1234). The name/password setters are public only from
        // Android 16.1 (API 36.1); on 36.0 they exist but are hidden, so this is best-effort.
        if (Build.VERSION.SDK_INT >= 36 && attempt(prefer5 = true, named = true)) return result!!
        failure = null
        if (!attempt(prefer5 = true)) {
            Log.w("LanBeam", "5 GHz hotspot refused (reason=$failure), trying default band")
            failure = null
            if (!attempt(prefer5 = false)) throw IllegalStateException(failureText(failure))
        }
        return result!!
    }

    fun stop() { runCatching { reservation?.close() }; reservation = null }

    /** Sets SSID "LanBeam_####" and a fresh random WPA2 password. Returns false if this Android won't let us. */
    @androidx.annotation.RequiresApi(36)
    private fun brand(b: SoftApConfiguration.Builder): Boolean = try {
        val rnd = java.security.SecureRandom()
        val ssid = "LanBeam_" + (1000 + rnd.nextInt(9000))
        val abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val pass = (1..12).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
        val wifiSsid = android.net.wifi.WifiSsid::class.java.getMethod("fromBytes", ByteArray::class.java)
            .invoke(null, ssid.toByteArray(Charsets.UTF_8))
        b.javaClass.getMethod("setWifiSsid", android.net.wifi.WifiSsid::class.java).invoke(b, wifiSsid)
        b.javaClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
            .invoke(b, pass, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        Dbg.log("asked for hotspot name $ssid (Android may ignore it before 16.1)")
        true
    } catch (e: Throwable) {
        Log.w("LanBeam", "custom hotspot name not allowed here: ${e.cause ?: e}")
        false
    }

    private fun failureText(reason: Int?) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspot is not allowed on this phone."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Turn off the regular Personal hotspot first."
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No free Wi-Fi channel for the hotspot."
        null -> "The hotspot did not start in time."
        else -> "Hotspot failed (code $reason)."
    }

    companion object {
        /** The password inside a WIFI:...;P:<pass>;; QR payload (unescaped), or null. */
        fun passFromQr(qr: String?): String? = qr?.let { Regex("""P:((?:\\.|[^;])*);""").find(it)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1") }

        /** Standard Wi-Fi QR payload; the iPhone and Android cameras join it directly. */
        fun wifiQr(ssid: String, pass: String): String {
            fun esc(s: String) = s.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
            return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
        }

        /** Waits for the hotspot interface to get an IPv4 address that was not there before. */
        fun awaitApAddress(before: Set<Inet4Address>, timeoutMs: Long = 8000): LanAddress? {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                Net.allLanAddresses().firstOrNull { it.address !in before }?.let { return it }
                Thread.sleep(200)
            }
            return null
        }
    }
}
