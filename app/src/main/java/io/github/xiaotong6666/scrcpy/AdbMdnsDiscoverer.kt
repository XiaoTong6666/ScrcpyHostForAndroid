package io.github.xiaotong6666.scrcpy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class AdbMdnsService(
    val serviceType: String,
    val serviceName: String,
    val host: String,
    val port: Int,
)

object AdbMdnsDiscoverer {
    private const val TAG = "AdbMdnsDiscoverer"
    private const val TLS_CONNECT = "_adb-tls-connect._tcp"
    private const val TLS_PAIRING = "_adb-tls-pairing._tcp"

    suspend fun discoverConnectService(
        context: Context,
        timeoutMs: Long = 12_000L,
    ): BridgeCallResult<AdbMdnsService> = discover(context, TLS_CONNECT, timeoutMs)

    suspend fun discoverPairingService(
        context: Context,
        timeoutMs: Long = 12_000L,
    ): BridgeCallResult<AdbMdnsService> = discover(context, TLS_PAIRING, timeoutMs)

    private suspend fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
    ): BridgeCallResult<AdbMdnsService> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return@withContext BridgeCallResult(
                isSuccess = false,
                message = "mDNS discovery requires Android 4.1+",
            )
        }

        val nsdManager = context.getSystemService(NsdManager::class.java)
            ?: return@withContext BridgeCallResult(
                isSuccess = false,
                message = "NsdManager unavailable",
            )
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("scrcpyandroid2-mdns")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        try {
            val resultRef = AtomicReference<AdbMdnsService?>()
            val done = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.i(TAG, "discovery started type=$regType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "start discovery failed type=$serviceType error=$errorCode")
                    done.set(true)
                    latch.countDown()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "stop discovery failed type=$serviceType error=$errorCode")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "discovery stopped type=$serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (done.get()) {
                        return
                    }
                    Log.i(TAG, "service found name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed name=${serviceInfo.serviceName} error=$errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (done.get()) {
                                return
                            }
                            val hostAddress = serviceInfo.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return
                            val port = serviceInfo.port
                            if (port !in 1..65535) {
                                return
                            }

                            val resolved = AdbMdnsService(
                                serviceType = serviceType,
                                serviceName = serviceInfo.serviceName.orEmpty(),
                                host = hostAddress,
                                port = port,
                            )
                            if (resultRef.compareAndSet(null, resolved)) {
                                done.set(true)
                                latch.countDown()
                            }
                        }
                    }

                    runCatching {
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(serviceInfo, resolveListener)
                    }.onFailure { error ->
                        Log.w(TAG, "resolveService failed name=${serviceInfo.serviceName}", error)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "service lost name=${serviceInfo.serviceName}")
                }
            }

            @Suppress("DEPRECATION")
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }

            val result = resultRef.get()
            if (result != null) {
                BridgeCallResult(
                    isSuccess = true,
                    value = result,
                    message = "${result.host}:${result.port}",
                )
            } else {
                BridgeCallResult(
                    isSuccess = false,
                    message = "No $serviceType service found within ${timeoutMs}ms",
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "discover failed type=$serviceType", error)
            BridgeCallResult(
                isSuccess = false,
                message = error.message ?: "mDNS discovery failed",
            )
        } finally {
            runCatching { multicastLock?.release() }
        }
    }
}
