package com.castviewer.sender.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.castviewer.sender.model.CastPreferencesRepository
import com.castviewer.sender.model.CastStateStore
import com.castviewer.sender.model.DeviceSource
import com.castviewer.sender.model.FireStickDevice
import com.castviewer.sender.model.QrUriParser
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FireStickDiscovery(
    context: Context,
    private val prefsRepo: CastPreferencesRepository
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val isDiscovering = AtomicBoolean(false)

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)
    private val probeExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        val manager = nsdManager ?: return
        if (isDiscovering.get()) return

        probeSavedDevicesAsync()
        acquireMulticastLock()
        CastStateStore.setMdnsScanning(true)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed: code=$errorCode")
                isDiscovering.set(false)
                CastStateStore.setMdnsScanning(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS stop failed: code=$errorCode")
                isDiscovering.set(false)
                CastStateStore.setMdnsScanning(false)
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started for $serviceType")
                isDiscovering.set(true)
                CastStateStore.setMdnsScanning(true)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped for $serviceType")
                isDiscovering.set(false)
                CastStateStore.setMdnsScanning(false)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType.orEmpty()
                if (!type.contains("_firecast", ignoreCase = true)) return
                resolveQueue.offer(serviceInfo)
                drainResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                CastStateStore.removeMdnsDeviceByNameOrId(serviceInfo.serviceName)
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw ${e.message}")
            isDiscovering.set(false)
            CastStateStore.setMdnsScanning(false)
        }
    }

    fun refresh() {
        stop()
        start()
    }

    fun stop() {
        resolveQueue.clear()
        isResolving.set(false)
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null && isDiscovering.getAndSet(false)) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        CastStateStore.setMdnsScanning(false)
        releaseMulticastLock()
    }

    fun shutdown() {
        stop()
        probeExecutor.shutdownNow()
    }

    private fun probeSavedDevicesAsync() {
        val saved = prefsRepo.loadSavedDevices()
        if (saved.isEmpty()) return
        probeExecutor.execute {
            for (dev in saved) {
                val reachable = try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(dev.ip, dev.port), 450)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
                CastStateStore.updateSavedDeviceOnlineStatus(dev.id, reachable)
            }
        }
    }

    private fun drainResolveQueue() {
        val manager = nsdManager ?: return
        if (!isResolving.compareAndSet(false, true)) return

        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }

        @Suppress("DEPRECATION")
        try {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: code=$errorCode")
                    isResolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        handleResolvedService(serviceInfo)
                    } finally {
                        isResolving.set(false)
                        drainResolveQueue()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService threw ${e.message}")
            isResolving.set(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResolvedService(info: NsdServiceInfo) {
        val ipv4: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?: info.host?.takeIf { it is Inet4Address }?.hostAddress
        } else {
            val host = info.host
            if (host is Inet4Address && !host.isLoopbackAddress) host.hostAddress else host?.hostAddress
        }

        val ip = ipv4?.trim().orEmpty()
        if (!QrUriParser.isValidIpv4(ip)) return

        val port = info.port.takeIf { it in 1..65535 } ?: QrUriParser.DEFAULT_PORT
        val txtName = info.attributes?.get("name")?.let { bytes ->
            String(bytes, Charsets.UTF_8).trim().ifBlank { null }
        }
        val friendlyName = txtName
            ?: info.serviceName?.trim()?.ifBlank { null }
            ?: "Fire Stick ($ip)"

        val device = FireStickDevice(
            id = "$ip:$port",
            name = friendlyName,
            ip = ip,
            port = port,
            source = DeviceSource.MDNS,
            isOnline = true
        )
        CastStateStore.upsertMdnsDevice(device)
        prefsRepo.upsertSavedDevice(device)
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("CastViewerMdnsLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock unavailable: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "FireStickDiscovery"
        private const val SERVICE_TYPE = "_firecast._tcp."
    }
}
