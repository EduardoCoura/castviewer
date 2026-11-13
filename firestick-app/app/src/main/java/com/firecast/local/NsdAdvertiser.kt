package com.firecast.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Advertises the FireCast receiver service on the local area network using
 * Android Network Service Discovery (mDNS / DNS-SD).
 *
 * Implements PROTOCOL.md:
 * - Service Type: _firecast._tcp.
 * - Port: 8765
 * - TXT records:
 *     name: Friendly name
 *     version: "1"
 */
class NsdAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "NsdAdvertiser"
        const val SERVICE_TYPE = "_firecast._tcp"
        const val PROTOCOL_VERSION = "1"
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    var onStatusChanged: ((String) -> Unit)? = null

    /**
     * Starts advertising the service with the given port and friendly device name.
     */
    @Synchronized
    fun start(port: Int, friendlyName: String) {
        if (isRegistered) {
            Log.d(TAG, "NSD service already registered. Stopping previous instance first.")
            stop()
        }

        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = friendlyName
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT records per PROTOCOL.md
            setAttribute("name", friendlyName)
            setAttribute("version", PROTOCOL_VERSION)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isRegistered = true
                Log.i(TAG, "Service registered successfully: ${registeredService.serviceName} on port ${registeredService.port}")
                onStatusChanged?.invoke("mDNS ativo: ${registeredService.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.e(TAG, "Service registration failed with error code: $errorCode")
                onStatusChanged?.invoke("Falha ao registrar mDNS (erro: $errorCode)")
                releaseMulticastLock()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                onStatusChanged?.invoke("mDNS desativado")
                releaseMulticastLock()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed with error code: $errorCode")
                isRegistered = false
                releaseMulticastLock()
            }
        }

        registrationListener = listener

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.d(TAG, "Registered NSD service request sent: $friendlyName :$port")
        } catch (e: Exception) {
            Log.e(TAG, "Error calling nsdManager.registerService", e)
            releaseMulticastLock()
        }
    }

    /**
     * Cleanly unregisters the mDNS service and releases the Wi-Fi multicast lock.
     */
    @Synchronized
    fun stop() {
        val listener = registrationListener
        if (listener != null && isRegistered) {
            try {
                nsdManager?.unregisterService(listener)
                Log.d(TAG, "Requested unregister of NSD service")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister NSD service cleanly: ${e.message}")
            }
        }
        registrationListener = null
        isRegistered = false
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("FireCastMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
                Log.d(TAG, "Acquired Wi-Fi MulticastLock for Fire OS mDNS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Released Wi-Fi MulticastLock")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        } finally {
            multicastLock = null
        }
    }
}
