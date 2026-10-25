package io.github.matguitarist.unbloatmytv.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Finds devices on the phone's Wi-Fi that accept ADB connections on port 5555. Same rules as the desktop apps. */
object Scanner {
    /** The phone's private IPv4 addresses on Wi-Fi or Ethernet; empty when it is only on mobile data. */
    fun localAddresses(context: Context): List<Inet4Address> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { it.address as? Inet4Address }
            .filter { it.isSiteLocalAddress }
            .distinctBy { it.hostAddress }
    }

    /** Every address of the /24 around each of the phone's addresses, 64 at a time. */
    suspend fun scan(context: Context, onProgress: (Float) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val own = localAddresses(context).map { it.hostAddress!! }.toSet()
        val targets = localAddresses(context).take(4).flatMap { a ->
            val b = a.address
            (1..254).map { "${b[0].toUByte()}.${b[1].toUByte()}.${b[2].toUByte()}.$it" }
        }.distinct().filter { it !in own }
        if (targets.isEmpty()) return@withContext emptyList()

        val done = AtomicInteger()
        val gate = Semaphore(64)
        coroutineScope {
            targets.map { ip ->
                async {
                    gate.withPermit {
                        val open = isPortOpen(ip, Tv.PORT, 700)
                        onProgress(done.incrementAndGet().toFloat() / targets.size)
                        if (open) ip else null
                    }
                }
            }.awaitAll().filterNotNull()
        }.sortedBy { ip -> ip.split('.').fold(0L) { acc, part -> acc * 256 + part.toLong() } }
    }

    fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }
}
