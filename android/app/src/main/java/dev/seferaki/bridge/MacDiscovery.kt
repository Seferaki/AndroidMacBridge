package dev.seferaki.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Find a paired Mac again; advertised addresses are accepted only after pinned TLS and device ID validation. */
@Synchronized
internal fun rediscoverMac(context: Context, client: BridgeClient, isActive: () -> Boolean): Boolean {
    val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val candidates = LinkedBlockingQueue<Pair<String, Int>>()
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!info.serviceType.trimEnd('.').equals("_smbbridge._tcp", true)) return
            @Suppress("DEPRECATION")
            runCatching { manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, error: Int) = Unit
                override fun onServiceResolved(service: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    service.host?.hostAddress?.let { candidates.offer(it to service.port) }
                }
            }) }
        }
    }
    return try {
        manager.discoverServices("_smbbridge._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        val deadline = SystemClock.elapsedRealtime() + 8000
        var found = false
        while (isActive() && SystemClock.elapsedRealtime() < deadline && !found) {
            val candidate = candidates.poll(500, TimeUnit.MILLISECONDS) ?: continue
            found = runCatching { client.tryDiscoveredAddress(candidate.first, candidate.second) }.getOrDefault(false)
        }
        found
    } finally { runCatching { manager.stopServiceDiscovery(listener) } }
}
