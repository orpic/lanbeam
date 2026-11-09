package dev.lanbeam

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build

/** Reads the current Wi-Fi link and turns it into plain advice for getting more speed. */
object LinkInfo {
    data class Client(val text: String, val advice: String?, val freqMhz: Int, val linkMbps: Int)

    fun wifiInfo(ctx: Context): WifiInfo? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return caps.transportInfo as? WifiInfo
        }
        return null
    }

    fun band(freq: Int) = when {
        freq <= 0 -> "unknown band"
        freq < 3000 -> "2.4 GHz"
        freq < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    fun client(ctx: Context): Client {
        val wi = wifiInfo(ctx) ?: return Client("Wi-Fi", null, 0, 0)
        val f = wi.frequency
        val link = maxOf(wi.txLinkSpeedMbps, wi.rxLinkSpeedMbps, wi.linkSpeed)
        val ceiling = link * 0.7 / 8 // realistic MB/s for one hop
        val text = "Same Wi-Fi · ${band(f)} · $link Mbps link (≈${"%.0f".format(ceiling / 2)}–${"%.0f".format(ceiling)} MB/s)"
        val advice = when {
            f in 1..2999 -> "This phone is on 2.4 GHz, the slow band. For 3–5× more speed use Direct mode, or connect both phones to the router's 5 GHz network."
            link in 1..399 -> "Weak Wi-Fi link. Move closer to the router, or use Direct mode."
            else -> "Through a router, data crosses the air twice. Direct mode is usually about 2× faster."
        }
        return Client(text, advice, f, link)
    }

    /** What the start screen shows about this phone's Wi-Fi. The name is hidden by Android without location access. */
    data class Here(val name: String?, val band: String?, val open: Boolean, val slow: Boolean)

    fun here(ctx: Context): Here? {
        val wi = wifiInfo(ctx) ?: return null
        val name = wi.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != WifiManagerUnknown }
        val open = Build.VERSION.SDK_INT >= 31 && wi.currentSecurityType.let {
            it == WifiInfo.SECURITY_TYPE_OPEN || it == WifiInfo.SECURITY_TYPE_OWE
        }
        return Here(name, if (wi.frequency > 0) band(wi.frequency) else null, open, wi.frequency in 1..2999)
    }
    private const val WifiManagerUnknown = "<unknown ssid>"

    /** The hotspot's real band is only visible to system apps; report what we asked for. */
    fun apBand(@Suppress("UNUSED_PARAMETER") iface: String) =
        if (Build.VERSION.SDK_INT >= 36) "5 GHz requested" else "band chosen by the system"

    fun directAdvice(ctx: Context): String? {
        val wi = wifiInfo(ctx) ?: return null
        return if (wi.frequency in 1..2999)
            "This phone is also connected to a 2.4 GHz Wi-Fi. The hotspot may be forced onto that slow band. For full speed, disconnect from that Wi-Fi while sharing."
        else null
    }
}
