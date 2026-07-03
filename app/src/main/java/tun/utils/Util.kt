package tun.utils

import android.content.Context
import android.net.ConnectivityManager

object Util {

    @JvmStatic
    external fun jni_getprop(name: String): String

    @JvmStatic
    fun getDefaultDNS(context: Context): List<String> {
        val out = ArrayList<String>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return out
        val network = cm.activeNetwork ?: return out
        val lp = cm.getLinkProperties(network) ?: return out
        for (dns in lp.dnsServers) {
            dns.hostAddress?.let { out.add(it) }
        }
        return out
    }
}
