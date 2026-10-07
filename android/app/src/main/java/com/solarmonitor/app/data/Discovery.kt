package com.solarmonitor.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/** Finds the monitor on the local network via mDNS (it advertises _solarmon._tcp). */
object Discovery {
    @Suppress("DEPRECATION")
    suspend fun find(context: Context, timeoutMs: Long = 8000): String? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("solar-discovery").apply { setReferenceCounted(false); acquire() }
        val result = CompletableDeferred<String?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val addr = info.host
                        if (addr is Inet4Address) result.complete(addr.hostAddress)
                    }
                })
            }
        }
        return try {
            nsd.discoverServices("_solarmon._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
        }
    }
}
