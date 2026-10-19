package com.remotephone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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

    /** Addresses on networks the owner has not switched off in Settings. */
    fun allowed(context: Context): List<Address> = list(context).filter { Prefs.interfaceAllowed(context, it.kind.name) }

    /**
     * Whether a connection that arrived on local address [local] may proceed. Both servers listen on
     * every interface; connections on a switched-off network kind are closed at once. Addresses that
     * belong to no known network (loopback, an interface that appeared a moment ago) are let through.
     */
    fun isAllowedLocal(context: Context, local: InetAddress?): Boolean {
        if (local == null || local.isLoopbackAddress || local.isAnyLocalAddress) return true
        val disabled = Prefs.disabledInterfaces(context)
        if (disabled.isEmpty()) return true
        val ip = (unmapped(local)).hostAddress?.substringBefore('%') ?: return true
        val kind = collect(context, ipv4Only = false)[ip]?.kind ?: return true
        return kind.name !in disabled
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) as plain IPv4. */
    private fun unmapped(a: InetAddress): InetAddress {
        if (a is Inet6Address) {
            val b = a.address
            if (b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) {
                return InetAddress.getByAddress(b.copyOfRange(12, 16))
            }
        }
        return a
    }

    private fun usable(a: InetAddress, ipv4Only: Boolean) =
        !a.isLoopbackAddress && !a.isLinkLocalAddress && (a is Inet4Address || (!ipv4Only && a is Inet6Address))

    fun list(context: Context): List<Address> = collect(context, ipv4Only = true).values.sortedBy { it.kind.ordinal }

    private fun collect(context: Context, ipv4Only: Boolean): Map<String, Address> {
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
                    if (usable(a, ipv4Only)) {
                        val ip = a.hostAddress?.substringBefore('%') ?: continue
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
                    if (usable(a, ipv4Only)) {
                        val ip = a.hostAddress?.substringBefore('%') ?: continue
                        found.putIfAbsent(ip, Address(ip, kind))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    private val VIRTUAL_PREFIXES = listOf("tun", "ppp", "wg", "ipsec", "rmnet", "ccmni", "dummy", "p2p")
}
