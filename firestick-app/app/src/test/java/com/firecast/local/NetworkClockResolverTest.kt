package com.firecast.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NetworkClockResolverTest {

    @Test
    fun formatClockWithSeconds_usesResolvedTimeZoneAndFormatsHHmmss() {
        // 2026-11-13T04:10:46Z -> 01:10:46 in America/Sao_Paulo (-03:00), 23:10:46 (prev day) in America/New_York (-05:00)
        val utcEpochMs = 1_794_543_046_000L
        val saoPauloTz = TimeZone.getTimeZone("America/Sao_Paulo")
        val newYorkTz = TimeZone.getTimeZone("America/New_York")

        assertEquals("01:10:46", NetworkClockResolver.formatClockWithSeconds(utcEpochMs, saoPauloTz))
        assertEquals("23:10:46", NetworkClockResolver.formatClockWithSeconds(utcEpochMs, newYorkTz))
    }

    @Test
    fun resolveValidTimeZone_rejectsGarbageAndAcceptsValidZones() {
        assertNull(NetworkClockResolver.resolveValidTimeZone(null))
        assertNull(NetworkClockResolver.resolveValidTimeZone("   "))
        assertNull(NetworkClockResolver.resolveValidTimeZone("Invalid/Not_A_Zone"))

        assertNotNull(NetworkClockResolver.resolveValidTimeZone("America/Sao_Paulo"))
        assertNotNull(NetworkClockResolver.resolveValidTimeZone("UTC"))
    }

    @Test
    fun formatCompactLocation_formatsCityAndRegionOrCountryCleanly() {
        assertEquals(
            "São Paulo, SP",
            NetworkClockResolver.formatCompactLocation("São Paulo", "SP", "BR", "America/Sao_Paulo")
        )
        // Numeric region codes (e.g. FIPS "11") should fall back to country code cleanly
        assertEquals(
            "Lisboa, PT",
            NetworkClockResolver.formatCompactLocation("Lisboa", "11", "PT", "Europe/Lisbon")
        )
        // Missing city extracts clean leaf from IANA zone
        assertEquals(
            "Manaus, BR",
            NetworkClockResolver.formatCompactLocation(null, null, "BR", "America/Manaus")
        )
    }

    @Test
    fun computeClockOffsetMs_ignoresSmallNetworkJitterAndCorrectsRealClockDrift() {
        val localNow = 1_794_543_046_000L
        assertEquals(0L, NetworkClockResolver.computeClockOffsetMs(localNow + 900L, localNow))
        assertEquals(0L, NetworkClockResolver.computeClockOffsetMs(localNow - 4_000L, localNow))
        assertEquals(0L, NetworkClockResolver.computeClockOffsetMs(0L, localNow))

        // 2-hour drift on wrong device clock must be corrected
        val twoHoursMs = 2 * 60 * 60 * 1000L
        assertEquals(twoHoursMs, NetworkClockResolver.computeClockOffsetMs(localNow + twoHoursMs, localNow))
    }

    @Test
    fun computeNextSecondDelayMs_alignsToNextSecondBoundary() {
        assertEquals(1000L, NetworkClockResolver.computeNextSecondDelayMs(1_794_543_046_000L))
        assertEquals(750L, NetworkClockResolver.computeNextSecondDelayMs(1_794_543_046_250L))
        assertEquals(50L, NetworkClockResolver.computeNextSecondDelayMs(1_794_543_046_990L))
    }

    @Test
    fun buildResolutionFromFields_buildsCompleteNetworkClockResolution() {
        val res = NetworkClockResolver.buildResolutionFromFields(
            tzId = "America/Sao_Paulo",
            city = "Campinas",
            regionCode = "SP",
            countryCode = "BR",
            serverEpochMs = 1_794_543_046_500L,
            localNowMs = 1_794_543_046_000L,
            source = "ipwho.is"
        )

        assertNotNull(res)
        assertEquals("America/Sao_Paulo", res!!.timeZone.id)
        assertEquals(0L, res.clockOffsetMs)
        assertNotNull(res.locationLabel)
        assertTrue(res.locationLabel!!.startsWith("Campinas, SP · "))
    }
}
