package com.firecast.local.airplay

import android.content.Context
import android.util.Log
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Pure JVM MAC / stable AirPlay device-ID helpers (zero Android runtime dependencies).
 */
object AirPlayHardwareIdentity {
    const val ANDROID_REDACTED_MAC = "02:00:00:00:00:00"

    fun parseMacHex(hex: String?): ByteArray? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.replace(":", "").replace("-", "").trim()
        if (cleaned.length != 12) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            val byteVal = cleaned.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = byteVal.toByte()
        }
        return if (isUsableMac(out)) out else null
    }

    fun formatMacHex(bytes: ByteArray): String {
        require(bytes.size == 6) { "Expected 6 bytes for MAC address" }
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun isUsableMac(mac: ByteArray?): Boolean {
        if (mac == null || mac.size != 6) return false
        if (mac.all { it.toInt() == 0 }) return false
        return formatMacHex(mac) != ANDROID_REDACTED_MAC
    }
}

/**
 * JNI wrapper over the native AirPlay 2 / RAOP engine (`libcastviewer_airplay.so`).
 *
 * Exposes lifecycle control, dynamic display/codec hints, TXT record retrieval for
 * Android NSD advertisement, and stable 6-byte hardware identity persistence.
 */
object AirPlayNativeBridge {
    private const val TAG = "AirPlayNativeBridge"
    private const val PREFS_NAME = "castviewer_airplay_prefs"
    private const val KEY_HW_ADDR = "hw_addr_hex"

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("castviewer_airplay")
            libraryLoaded = true
        } catch (t: Throwable) {
            loadError = t
            runCatching { Log.e(TAG, "Failed to load native library castviewer_airplay", t) }
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun getLoadError(): Throwable? = loadError

    interface NativeCallback {
        fun onVideoData(data: ByteArray, ptsUs: Long, isH265: Boolean)
        fun onVideoSize(isH265: Boolean, srcWidth: Int, srcHeight: Int, width: Int, height: Int)
        fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
        fun onAudioData(data: ByteArray, ct: Int, ptsUs: Long)
        fun onAudioFlush()
        fun onClientVolume(volumeDb: Float)
        fun onClientVolumeQuery(): Float
        fun onConnectionInit()
        fun onConnectionDestroy()
        fun onConnectionReset(reason: Int)
        fun onVideoPause()
        fun onVideoResume()
        fun onDisplayPin(pin: String)
    }

    external fun nativeInit(
        callback: NativeCallback,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean,
        requirePin: Boolean
    ): Long

    external fun nativeStart(handle: Long, requestedPort: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeSetDisplaySize(handle: Long, width: Int, height: Int, fps: Int)
    external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    external fun nativeDisconnectActiveClient(handle: Long)
    external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetRaopServiceName(handle: Long): String?

    /**
     * Returns a persistent 6-byte hardware ID for AirPlay `deviceid` and `pi` generation.
     *
     * Android 6+ often restricts [NetworkInterface.getHardwareAddress] to `02:00:00:00:00:00`,
     * so when unavailable we generate a stable locally-administered unicast MAC once and store
     * it in private [android.content.SharedPreferences].
     */
    fun getOrCreateStableHardwareAddress(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHex = prefs.getString(KEY_HW_ADDR, null)
        parseMacHex(savedHex)?.let { return it }

        val detected = findUsableInterfaceMac()
        val chosen = detected ?: generateLocallyAdministeredMac()
        prefs.edit().putString(KEY_HW_ADDR, formatMacHex(chosen)).apply()
        return chosen
    }

    internal fun parseMacHex(hex: String?): ByteArray? = AirPlayHardwareIdentity.parseMacHex(hex)

    internal fun formatMacHex(bytes: ByteArray): String = AirPlayHardwareIdentity.formatMacHex(bytes)

    internal fun isUsableMac(mac: ByteArray?): Boolean = AirPlayHardwareIdentity.isUsableMac(mac)

    private fun findUsableInterfaceMac(): ByteArray? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: ByteArray? = null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val mac = iface.hardwareAddress
                if (!isUsableMac(mac)) continue
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    return mac
                }
                if (fallback == null) fallback = mac
            }
            fallback
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to inspect network interface MAC", t)
            null
        }
    }

    private fun generateLocallyAdministeredMac(): ByteArray {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        // Clear multicast bit (bit 0) and set locally-administered bit (bit 1).
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes
    }
}
