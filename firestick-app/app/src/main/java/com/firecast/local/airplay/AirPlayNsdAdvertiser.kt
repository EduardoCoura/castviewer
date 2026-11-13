package com.firecast.local.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Publishes `_airplay._tcp.` and `_raop._tcp.` mDNS services on the local LAN so iPhone, iPad,
 * and macOS Control Center automatically discover this Fire TV / Android TV receiver.
 */
class AirPlayNsdAdvertiser(context: Context) {

    companion object {
        private const val TAG = "AirPlayNsdAdvertiser"
        private const val TYPE_AIRPLAY = "_airplay._tcp."
        private const val TYPE_RAOP = "_raop._tcp."
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val lock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airplayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var isStarted = false

    fun start(
        serviceName: String,
        raopServiceName: String,
        port: Int,
        airplayTxt: Map<String, String>,
        raopTxt: Map<String, String>
    ) {
        if (port <= 0) {
            Log.w(TAG, "Ignoring NSD start with invalid port=$port")
            return
        }
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable on this device")
            return
        }

        synchronized(lock) {
            stopLocked()
            acquireMulticastLockLocked()
            isStarted = true

            registerServiceLocked(
                mgr = mgr,
                type = TYPE_AIRPLAY,
                name = serviceName,
                port = port,
                txt = airplayTxt,
                assignListener = { airplayListener = it },
                clearListener = { listener -> if (airplayListener === listener) airplayListener = null }
            )

            registerServiceLocked(
                mgr = mgr,
                type = TYPE_RAOP,
                name = raopServiceName,
                port = port,
                txt = raopTxt,
                assignListener = { raopListener = it },
                clearListener = { listener -> if (raopListener === listener) raopListener = null }
            )
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        isStarted = false
        val mgr = nsdManager
        if (mgr != null) {
            unregisterSafely(mgr, airplayListener)
            unregisterSafely(mgr, raopListener)
        }
        airplayListener = null
        raopListener = null
        releaseMulticastLockLocked()
    }

    private fun unregisterSafely(
        mgr: NsdManager,
        listener: NsdManager.RegistrationListener?
    ) {
        if (listener == null) return
        try {
            mgr.unregisterService(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Ignoring NsdManager unregister exception: ${t.message}")
        }
    }

    private fun registerServiceLocked(
        mgr: NsdManager,
        type: String,
        name: String,
        port: Int,
        txt: Map<String, String>,
        assignListener: (NsdManager.RegistrationListener) -> Unit,
        clearListener: (NsdManager.RegistrationListener) -> Unit
    ) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            for ((k, v) in txt) {
                try {
                    setAttribute(k, v)
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping TXT attribute $k on $type: ${t.message}")
                }
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Published mDNS service ${serviceInfo.serviceType} name='${serviceInfo.serviceName}' port=$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Failed to register ${serviceInfo.serviceType} (${serviceInfo.serviceName}) error=$errorCode")
                synchronized(lock) {
                    clearListener(this)
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Unpublished mDNS service ${serviceInfo.serviceType} (${serviceInfo.serviceName})")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregister failed for ${serviceInfo.serviceType} error=$errorCode")
            }
        }

        assignListener(listener)
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception registering $type ($name)", t)
            clearListener(listener)
        }
    }

    private fun acquireMulticastLockLocked() {
        try {
            val existing = multicastLock
            if (existing == null) {
                val created = wifiManager?.createMulticastLock("CastViewerAirPlayMulticast")
                created?.setReferenceCounted(false)
                multicastLock = created
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire Wi-Fi MulticastLock: ${t.message}")
        }
    }

    private fun releaseMulticastLockLocked() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing Wi-Fi MulticastLock: ${t.message}")
        }
    }
}
