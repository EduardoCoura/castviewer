package com.firecast.local

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Resolves the viewer's city/region and IANA timezone automatically over HTTPS IP geolocation
 * (zero runtime location permissions required on Fire TV) and formats a second-accurate clock.
 */
object NetworkClockResolver {

    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 3500
    private const val MIN_VALID_EPOCH_MS = 1_700_000_000_000L
    private const val DRIFT_TOLERANCE_MS = 15_000L

    data class ClockResolution(
        val timeZone: TimeZone,
        val locationLabel: String?,
        val clockOffsetMs: Long,
        val source: String,
        val fromNetwork: Boolean
    )

    internal data class NetworkFetchPayload(
        val body: String,
        val headerDateMs: Long
    )

    fun resolveBlocking(localNowMs: Long = System.currentTimeMillis()): ClockResolution {
        fetchAndParse(
            url = "https://ipwho.is/",
            localNowMs = localNowMs,
            parser = ::parseIpWhoIs
        )?.let { return it }

        fetchAndParse(
            url = "https://ipapi.co/json/",
            localNowMs = localNowMs,
            parser = ::parseIpApiCo
        )?.let { return it }

        fetchAndParse(
            url = "https://worldtimeapi.org/api/ip",
            localNowMs = localNowMs,
            parser = ::parseWorldTimeApi
        )?.let { return it }

        val defaultTz = TimeZone.getDefault()
        return ClockResolution(
            timeZone = defaultTz,
            locationLabel = buildFallbackLocationLabel(defaultTz, localNowMs),
            clockOffsetMs = 0L,
            source = "system-default",
            fromNetwork = false
        )
    }

    private fun fetchAndParse(
        url: String,
        localNowMs: Long,
        parser: (String, Long, Long) -> ClockResolution?
    ): ClockResolution? {
        val payload = httpGet(url) ?: return null
        return try {
            parser(payload.body, payload.headerDateMs, localNowMs)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlString: String): NetworkFetchPayload? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CastViewer-FireTV/1.0")
            }
            if (conn.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val headerDate = conn.date.takeIf { it >= MIN_VALID_EPOCH_MS } ?: 0L
            NetworkFetchPayload(body = body, headerDateMs = headerDate)
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseIpWhoIs(
        rawJson: String,
        headerDateMs: Long,
        localNowMs: Long
    ): ClockResolution? {
        val obj = JSONObject(rawJson)
        if (!obj.optBoolean("success", true)) return null
        val tzObj = obj.optJSONObject("timezone")
        val tzId = tzObj?.optString("id", "").orEmpty().trim()
        val city = obj.optString("city", "")
        val regionCode = obj.optString("region_code", "")
        val countryCode = obj.optString("country_code", "")
        return buildResolutionFromFields(
            tzId = tzId,
            city = city,
            regionCode = regionCode,
            countryCode = countryCode,
            serverEpochMs = headerDateMs.takeIf { it > 0L },
            localNowMs = localNowMs,
            source = "ipwho.is"
        )
    }

    private fun parseIpApiCo(
        rawJson: String,
        headerDateMs: Long,
        localNowMs: Long
    ): ClockResolution? {
        val obj = JSONObject(rawJson)
        if (obj.optBoolean("error", false)) return null
        return buildResolutionFromFields(
            tzId = obj.optString("timezone", ""),
            city = obj.optString("city", ""),
            regionCode = obj.optString("region_code", ""),
            countryCode = obj.optString("country_code", ""),
            serverEpochMs = headerDateMs.takeIf { it > 0L },
            localNowMs = localNowMs,
            source = "ipapi.co"
        )
    }

    private fun parseWorldTimeApi(
        rawJson: String,
        headerDateMs: Long,
        localNowMs: Long
    ): ClockResolution? {
        val obj = JSONObject(rawJson)
        val unixSec = obj.optLong("unixtime", 0L)
        val jsonEpochMs = if (unixSec > 0L) unixSec * 1000L else headerDateMs
        return buildResolutionFromFields(
            tzId = obj.optString("timezone", ""),
            city = null,
            regionCode = null,
            countryCode = null,
            serverEpochMs = jsonEpochMs.takeIf { it > 0L },
            localNowMs = localNowMs,
            source = "worldtimeapi.org"
        )
    }

    internal fun buildResolutionFromFields(
        tzId: String?,
        city: String?,
        regionCode: String?,
        countryCode: String?,
        serverEpochMs: Long?,
        localNowMs: Long,
        source: String
    ): ClockResolution? {
        val tz = resolveValidTimeZone(tzId) ?: return null
        val offset = computeClockOffsetMs(serverEpochMs, localNowMs)
        val effectiveNow = localNowMs + offset
        val compactPlace = formatCompactLocation(city, regionCode, countryCode, tz.id)
        val tzAbbr = formatShortTzAbbr(tz, effectiveNow)
        val label = listOfNotNull(compactPlace, tzAbbr)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
            .ifBlank { null }

        return ClockResolution(
            timeZone = tz,
            locationLabel = label,
            clockOffsetMs = offset,
            source = source,
            fromNetwork = true
        )
    }

    internal fun buildFallbackLocationLabel(timeZone: TimeZone, epochMs: Long): String? {
        val placeFromTz = extractCityFromTimeZoneId(timeZone.id)
        val abbr = formatShortTzAbbr(timeZone, epochMs)
        return listOfNotNull(placeFromTz, abbr)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
            .ifBlank { null }
    }

    internal fun resolveValidTimeZone(rawTzId: String?): TimeZone? {
        val clean = cleanString(rawTzId) ?: return null
        val tz = TimeZone.getTimeZone(clean)
        val isExplicitGmtOrUtc = clean.equals("GMT", ignoreCase = true) ||
            clean.equals("UTC", ignoreCase = true) ||
            clean.startsWith("Etc/GMT", ignoreCase = true)
        if (!isExplicitGmtOrUtc && tz.id.equals("GMT", ignoreCase = true)) {
            return null
        }
        return tz
    }

    internal fun formatCompactLocation(
        city: String?,
        regionCode: String?,
        countryCode: String?,
        tzId: String?
    ): String? {
        val cleanCity = cleanString(city)
        val cleanRegion = cleanString(regionCode)?.takeIf {
            it.length in 1..4 && it.any(Char::isLetter)
        }
        val cleanCountry = cleanString(countryCode)?.takeIf { it.length in 2..3 }

        if (cleanCity != null) {
            val suffix = cleanRegion ?: cleanCountry
            return if (suffix != null && !suffix.equals(cleanCity, ignoreCase = true)) {
                "$cleanCity, $suffix"
            } else {
                cleanCity
            }
        }
        val tzCity = extractCityFromTimeZoneId(tzId)
        if (tzCity != null) {
            return if (cleanCountry != null) "$tzCity, $cleanCountry" else tzCity
        }
        return cleanCountry
    }

    internal fun computeClockOffsetMs(serverEpochMs: Long?, localNowMs: Long): Long {
        if (serverEpochMs == null || serverEpochMs < MIN_VALID_EPOCH_MS) return 0L
        val diff = serverEpochMs - localNowMs
        return if (abs(diff) > DRIFT_TOLERANCE_MS) diff else 0L
    }

    internal fun formatClockWithSeconds(epochMs: Long, timeZone: TimeZone): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(Date(epochMs))
    }

    internal fun computeNextSecondDelayMs(adjustedEpochMs: Long): Long {
        val remainder = ((adjustedEpochMs % 1000L) + 1000L) % 1000L
        return (1000L - remainder).coerceIn(50L, 1000L)
    }

    private fun extractCityFromTimeZoneId(tzId: String?): String? {
        val clean = cleanString(tzId) ?: return null
        if (!clean.contains('/')) return null
        val leaf = clean.substringAfterLast('/').replace('_', ' ').trim()
        return leaf.takeIf { it.isNotBlank() && !it.startsWith("GMT", ignoreCase = true) }
    }

    private fun formatShortTzAbbr(timeZone: TimeZone, epochMs: Long): String? {
        val inDst = timeZone.inDaylightTime(Date(epochMs))
        val abbr = timeZone.getDisplayName(inDst, TimeZone.SHORT, Locale.US)?.trim()
        return abbr?.takeIf { it.isNotBlank() }
    }

    private fun cleanString(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isBlank()) return null
        if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("undefined", ignoreCase = true)) {
            return null
        }
        return trimmed
    }
}
