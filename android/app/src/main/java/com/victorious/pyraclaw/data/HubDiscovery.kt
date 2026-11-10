package com.victorious.pyraclaw.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class FoundHub(val name: String, val url: String, val via: String)

/**
 * Finds hubs two ways, in parallel, for ~6 s:
 * 1. mDNS/DNS-SD via NsdManager: `_pyra-hub._tcp` (advertised by hub/install.sh v0.4+) and any
 *    `_http._tcp` service whose name contains "pyra".
 * 2. Probing well-known addresses with `GET /health` (USB tether gateway, pyra.local, raspberrypi.local).
 */
class HubDiscovery(private val context: Context, client: OkHttpClient) {
    private val probeClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun scan(windowMs: Long = 6_000): Flow<FoundHub> = channelFlow {
        launch {
            coroutineScope {
                CANDIDATES.map { url -> async { if (probe(url)) send(FoundHub(hostOf(url), url, "address probe")) } }.awaitAll()
            }
        }
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@channelFlow
        val resolveLock = Mutex()
        val listeners = SERVICE_TYPES.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    val pyraType = info.serviceType.contains("pyra-hub")
                    if (!pyraType && !info.serviceName.contains("pyra", ignoreCase = true)) return
                    launch {
                        // Older Android versions allow only one resolve at a time.
                        val resolved = resolveLock.withLock { resolve(nsd, info) } ?: return@launch
                        @Suppress("DEPRECATION") // getHostAddresses() is API 34+
                        val host = resolved.host?.hostAddress ?: return@launch
                        val h = if (host.contains(':')) "[$host]" else host
                        send(FoundHub(resolved.serviceName, "http://$h:${resolved.port}/", "mDNS"))
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        delay(windowMs)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION") // resolveService is fine for one-shot resolves on all supported APIs
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(3_000) {
            val done = CompletableDeferred<NsdServiceInfo?>()
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.complete(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { done.complete(serviceInfo) }
            })
            done.await()
        }

    private fun probe(url: String): Boolean = runCatching {
        probeClient.newCall(Request.Builder().url(url + "health").build()).execute().use { r ->
            r.isSuccessful && (r.body?.string()?.contains("\"ok\"") == true)
        }
    }.getOrDefault(false)

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    companion object {
        val SERVICE_TYPES = listOf("_pyra-hub._tcp.", "_http._tcp.")
        val CANDIDATES = listOf(
            "http://192.168.42.1:8787/", // Android USB-tether gateway default (see MANUAL)
            "http://pyra.local:8787/",
            "http://pyra-hub.local:8787/",
            "http://raspberrypi.local:8787/",
        )
    }
}
