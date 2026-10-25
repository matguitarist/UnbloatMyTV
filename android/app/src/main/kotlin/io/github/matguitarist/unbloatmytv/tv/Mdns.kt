package io.github.matguitarist.unbloatmytv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Wireless debugging announces itself on the network (mDNS): the port to connect to, and while
 * "Pair device with pairing code" is open, the port to pair on. Both change every time.
 */
object Mdns {
    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"

    data class Service(val type: String, val host: String, val port: Int)

    /** Everything of these types announced within [durationMs], or the first one when [first] is set. */
    suspend fun discover(ctx: Context, types: List<String>, durationMs: Long, first: Boolean = false): List<Service> =
        // Off the main thread: taking the multicast lock is a call into the system that can take seconds.
        withContext(Dispatchers.IO) { discoverHere(ctx, types, durationMs, first) }

    private suspend fun discoverHere(ctx: Context, types: List<String>, durationMs: Long, first: Boolean): List<Service> = coroutineScope {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return@coroutineScope emptyList()
        // Some phones drop multicast packets unless an app holds this lock.
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("unbloatmytv-mdns")
        runCatching { lock?.setReferenceCounted(false); lock?.acquire() }
        val found = ConcurrentHashMap<String, Service>()
        val resolving = Mutex() // older Androids resolve one service at a time
        val work = Job(coroutineContext[Job])
        val listeners = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    launch(work) { resolve(nsd, info, resolving)?.let { found["$type|${it.host}"] = it.copy(type = type) } }
                }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        try {
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end && !(first && found.isNotEmpty())) delay(250)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            work.cancel()
            runCatching { lock?.release() }
        }
        found.values.sortedBy { it.host }
    }

    /** The port Wireless debugging listens on at [host] right now, if it says so within [timeoutMs]. */
    suspend fun connectPort(ctx: Context, host: String, timeoutMs: Long = 4_000): Int? {
        val step = 1_000L
        var waited = 0L
        while (waited < timeoutMs) {
            discover(ctx, listOf(CONNECT), step, first = true).firstOrNull { it.host == host }?.let { return it.port }
            waited += step
        }
        return null
    }

    @Suppress("DEPRECATION") // resolveService and host still work; the newer calls need Android 14
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo, resolving: Mutex): Service? = resolving.withLock {
        val resolved = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(si: NsdServiceInfo) { if (cont.isActive) cont.resume(si) }
                })
            }
        } ?: return@withLock null
        val host = (resolved.host as? Inet4Address)?.hostAddress ?: return@withLock null
        Service(info.serviceType, host, resolved.port)
    }
}
