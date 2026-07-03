package com.lonelytragedy.r1999trackerapp

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    fun wifiIp(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("dummy")) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}
