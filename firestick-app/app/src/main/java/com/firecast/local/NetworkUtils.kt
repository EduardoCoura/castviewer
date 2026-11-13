package com.firecast.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.EnumMap

/**
 * Utility functions for network IP resolution, Fire TV device naming,
 * and high-contrast QR code rendering for 10-foot TV displays.
 */
object NetworkUtils {

    /**
     * Resolves the active local IPv4 address (e.g. 192.168.x.x or 10.x.x.x).
     * Prioritizes Wi-Fi (wlan) and Ethernet (eth) interfaces commonly found on Fire TV.
     */
    fun getLocalIpv4Address(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: Active Wi-Fi interfaces (wlan0, wlan1...)
            val wifiIp = findIpForInterface(interfaces) { name ->
                name.contains("wlan", ignoreCase = true)
            }
            if (!wifiIp.isNullOrBlank()) return wifiIp

            // Priority 2: Active Ethernet interfaces (eth0, eth1... Fire TV Cube or USB LAN adapter)
            val ethIp = findIpForInterface(interfaces) { name ->
                name.contains("eth", ignoreCase = true)
            }
            if (!ethIp.isNullOrBlank()) return ethIp

            // Priority 3: Any non-loopback site-local IPv4
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isPointToPoint) continue
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Return null if network inspection fails
        }
        return null
    }

    private fun findIpForInterface(
        interfaces: List<NetworkInterface>,
        filter: (String) -> Boolean
    ): String? {
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            if (!filter(intf.name)) continue
            val addresses = Collections.list(intf.inetAddresses)
            for (addr in addresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank()) {
                        return host
                    }
                }
            }
        }
        return null
    }

    /**
     * Retrieves the friendly name for the Fire OS device (e.g. "Fire TV Sala"),
     * falling back to Amazon hardware model identifiers.
     */
    fun getDeviceFriendlyName(context: Context): String {
        val configuredName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }

        if (!configuredName.isNullOrBlank()) {
            return configuredName
        }

        val model = Build.MODEL ?: "Fire TV"
        return when {
            model.startsWith("AFT", ignoreCase = true) -> mapAmazonModel(model)
            model.contains("Fire", ignoreCase = true) -> model
            else -> "Fire TV ($model)"
        }
    }

    /**
     * Maps known Amazon Fire TV hardware model codes to human friendly names.
     */
    private fun mapAmazonModel(model: String): String {
        return when (model.uppercase()) {
            "AFTMM" -> "Fire TV Stick 4K"
            "AFTR" -> "Fire TV Stick (3rd Gen)"
            "AFTSS" -> "Fire TV Stick Lite"
            "AFTKA" -> "Fire TV Stick 4K Max"
            "AFTEU011" -> "Fire TV Stick 4K Max (2nd Gen)"
            "AFTEU014" -> "Fire TV Stick 4K (2nd Gen)"
            "AFTA" -> "Fire TV Cube (1st Gen)"
            "AFTR" -> "Fire TV Cube (2nd Gen)"
            "AFTEAM" -> "Fire TV Cube (3rd Gen)"
            else -> "Fire TV Stick ($model)"
        }
    }

    /**
     * Generates a crisp QR code Bitmap from the given string payload.
     * Uses ZXing core QRCodeWriter with error correction level M and clean margin.
     */
    fun generateQrCodeBitmap(
        content: String,
        sizePx: Int = 400,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 1)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }

            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
