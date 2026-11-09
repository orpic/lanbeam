package dev.lanbeam

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A local Wi-Fi (or hotspot) IPv4 address and its subnet prefix length. */
data class LanAddress(val iface: String, val address: Inet4Address, val prefix: Int)

object Net {
    /** Interface name prefixes that are Wi-Fi client or hotspot interfaces. Cellular (rmnet, ccmni) is never used. */
    private val wifiLike = listOf("wlan", "ap", "swlan", "softap")

    /**
     * Finds the IPv4 address of the Wi-Fi or hotspot interface. The server binds only to this
     * address, so it can never be reached over mobile data or the internet.
     */
    fun findLanAddress(): LanAddress? =
        // Prefer the hotspot interface when it exists (no-router case), then the normal Wi-Fi client.
        allLanAddresses().sortedBy { if (it.iface.startsWith("wlan0")) 1 else 0 }.firstOrNull()

    /** The Wi-Fi client interface (wlan0), if connected. */
    fun wifiClientAddress(): LanAddress? = allLanAddresses().firstOrNull { it.iface == "wlan0" }

    fun allLanAddresses(): List<LanAddress> {
        val candidates = mutableListOf<LanAddress>()
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.isUp || ni.isLoopback) continue
            if (wifiLike.none { ni.name.startsWith(it) }) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) candidates += LanAddress(ni.name, a, ia.networkPrefixLength.toInt())
            }
        }
        return candidates
    }

    fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        if (a !is Inet4Address || b !is Inet4Address) return false
        val x = a.address; val y = b.address
        var bits = prefix
        for (i in 0 until 4) {
            val m = when {
                bits >= 8 -> 0xFF
                bits <= 0 -> 0
                else -> (0xFF shl (8 - bits)) and 0xFF
            }
            if ((x[i].toInt() and m) != (y[i].toInt() and m)) return false
            bits -= 8
        }
        return true
    }
}
