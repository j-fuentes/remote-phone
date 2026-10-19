package com.remotephone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The phone's reachable IPv4 addresses, labelled by network, local network first.
 *
 * Read from ConnectivityManager, which only lists networks that are actually connected, so a
 * disconnected VPN disappears (a bare NetworkInterface scan can keep showing a stale tun
 * interface, and returns it before the WiFi one). Interfaces that are not Android "networks",
 * such as the hotspot, are added from NetworkInterface, VPN/cellular names excluded.
 */
object NetworkAddresses {

    enum class Kind(val label: String) {
        WIFI("WiFi"), ETHERNET("Ethernet"), HOTSPOT("Hotspot"), OTHER("Network"), VPN("VPN"), MOBILE("Mobile data")
    }

    data class Address(val ip: String, val kind: Kind)

    fun list(context: Context): List<Address> {
        val found = LinkedHashMap<String, Address>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val link = cm.getLinkProperties(network) ?: continue
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Kind.VPN
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.MOBILE
                    else -> Kind.OTHER
                }
                for (la in link.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        found.putIfAbsent(ip, Address(ip, kind))
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name ?: continue
                if (VIRTUAL_PREFIXES.any { name.startsWith(it) }) continue
                val kind = when {
                    name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> Kind.HOTSPOT
                    name.startsWith("wlan") -> Kind.WIFI
                    name.startsWith("eth") -> Kind.ETHERNET
                    else -> continue
                }
                for (a in iface.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        found.putIfAbsent(ip, Address(ip, kind))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.values.sortedBy { it.kind.ordinal }
    }

    private val VIRTUAL_PREFIXES = listOf("tun", "ppp", "wg", "ipsec", "rmnet", "ccmni", "dummy", "p2p")
}
