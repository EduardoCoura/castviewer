package com.castviewer.sender.model

import java.net.URI

object QrUriParser {
    const val DEFAULT_PORT = 8765

    data class ParsedTarget(
        val ip: String,
        val port: Int
    )

    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isValidIpv4(ip: String): Boolean {
        val m = IPV4_REGEX.matchEntire(ip.trim()) ?: return false
        return (1..4).all { idx ->
            val octet = m.groupValues[idx].toIntOrNull() ?: return@all false
            octet in 0..255
        }
    }

    /**
     * Parses Fire Stick QR codes (`ws://192.168.1.50:8765?t=12345`),
     * raw host:port (`192.168.1.50:8765`), or bare IPv4 (`192.168.1.50`).
     */
    fun parseFireStickTarget(rawInput: String?): ParsedTarget? {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)
        ) {
            return try {
                val uri = URI(trimmed)
                val host = uri.host?.trim().orEmpty()
                if (!isValidIpv4(host)) return null
                val port = if (uri.port in 1..65535) uri.port else DEFAULT_PORT
                ParsedTarget(host, port)
            } catch (_: Exception) {
                null
            }
        }

        val cleaned = trimmed.substringBefore('?').substringBefore('/').trim()
        val colonIdx = cleaned.indexOf(':')
        if (colonIdx > 0) {
            val host = cleaned.substring(0, colonIdx).trim()
            val portStr = cleaned.substring(colonIdx + 1).trim()
            val port = portStr.toIntOrNull()
            if (!isValidIpv4(host) || port == null || port !in 1..65535) return null
            return ParsedTarget(host, port)
        }

        return if (isValidIpv4(cleaned)) ParsedTarget(cleaned, DEFAULT_PORT) else null
    }
}
