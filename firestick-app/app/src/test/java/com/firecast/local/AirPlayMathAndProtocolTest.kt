package com.firecast.local

import com.firecast.local.airplay.AirPlayAspectMath
import com.firecast.local.airplay.AirPlayAudioConfig
import com.firecast.local.airplay.AirPlayHardwareIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayMathAndProtocolTest {

    @Test
    fun `computeFitBounds handles landscape portrait and tablet ratios on 1080p TV`() {
        // 16:9 full TV match
        val tv = AirPlayAspectMath.computeFitBounds(1920, 1080, 1920, 1080)
        assertEquals(1920, tv.width)
        assertEquals(1080, tv.height)

        // iPhone portrait (1170 x 2532 -> 499 x 1080)
        val iphonePortrait = AirPlayAspectMath.computeFitBounds(1920, 1080, 1170, 2532)
        assertEquals(1080, iphonePortrait.height)
        assertEquals(499, iphonePortrait.width)

        // iPad 4:3 (2048 x 1536 -> 1440 x 1080)
        val ipad43 = AirPlayAspectMath.computeFitBounds(1920, 1080, 2048, 1536)
        assertEquals(1440, ipad43.width)
        assertEquals(1080, ipad43.height)

        // Ultrawide (2560 x 1080 -> 1920 x 810)
        val ultrawide = AirPlayAspectMath.computeFitBounds(1920, 1080, 2560, 1080)
        assertEquals(1920, ultrawide.width)
        assertEquals(810, ultrawide.height)

        // Safe fallback on degenerate dimensions
        val safe = AirPlayAspectMath.computeFitBounds(1920, 1080, 0, -5)
        assertEquals(1920, safe.width)
        assertEquals(1080, safe.height)
    }

    @Test
    fun `isKeyframeAnnexB detects H264 and H265 keyframes cleanly`() {
        // H.264 4-byte Annex-B SPS (0x67, type 7) + IDR (0x65, type 5)
        assertTrue(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00), isH265 = false))
        assertTrue(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 1, 0x65, 0x88.toByte()), isH265 = false))
        // H.264 non-IDR P-slice (0x41, type 1)
        assertFalse(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte()), isH265 = false))

        // H.265 VPS (type 32 -> (0x40 >> 1) & 0x3F = 32) and IDR_W_RADL (type 19 -> (0x26 >> 1) & 0x3F = 19)
        assertTrue(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 0, 1, 0x40, 0x01), isH265 = true))
        assertTrue(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 1, 0x26, 0x01), isH265 = true))
        // H.265 TRAIL_R (type 1 -> 0x02)
        assertFalse(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 0, 1, 0x02, 0x01, 0x00), isH265 = true))

        assertFalse(AirPlayAspectMath.isKeyframeAnnexB(byteArrayOf(0, 0, 0, 1), isH265 = false))
    }

    @Test
    fun `audio gain curve and AAC CSD0 match AirPlay RAOP specification`() {
        assertEquals(0.0f, AirPlayAudioConfig.dbToLinearGain(-144.0f), 0.0001f)
        assertEquals(0.0f, AirPlayAudioConfig.dbToLinearGain(Float.NaN), 0.0001f)
        assertEquals(1.0f, AirPlayAudioConfig.dbToLinearGain(0.0f), 0.0001f)
        assertEquals(0.1f, AirPlayAudioConfig.dbToLinearGain(-20.0f), 0.0001f)
        assertEquals(0.5012f, AirPlayAudioConfig.dbToLinearGain(-6.0f), 0.001f)

        assertEquals(-144.0f, AirPlayAudioConfig.linearGainToDb(0.0f), 0.01f)
        assertEquals(0.0f, AirPlayAudioConfig.linearGainToDb(1.0f), 0.01f)
        assertEquals(-20.0f, AirPlayAudioConfig.linearGainToDb(0.1f), 0.01f)

        assertArrayEquals(byteArrayOf(0x12, 0x10), AirPlayAudioConfig.buildAudioCsd0(AirPlayAudioConfig.CT_AAC_LC, 1024))
        assertArrayEquals(byteArrayOf(0x12, 0x14), AirPlayAudioConfig.buildAudioCsd0(AirPlayAudioConfig.CT_AAC_LC, 960))
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00),
            AirPlayAudioConfig.buildAudioCsd0(AirPlayAudioConfig.CT_AAC_ELD, 480)
        )
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x40, 0x00),
            AirPlayAudioConfig.buildAudioCsd0(AirPlayAudioConfig.CT_AAC_ELD, 512)
        )
        assertNull(AirPlayAudioConfig.buildAudioCsd0(AirPlayAudioConfig.CT_PCM, 352))
    }

    @Test
    fun `stable hardware MAC parsing rejects Android redacted and zero addresses`() {
        assertNull(AirPlayHardwareIdentity.parseMacHex("02:00:00:00:00:00"))
        assertNull(AirPlayHardwareIdentity.parseMacHex("00:00:00:00:00:00"))
        assertNull(AirPlayHardwareIdentity.parseMacHex("invalid-mac"))

        val parsed = AirPlayHardwareIdentity.parseMacHex("58-55-ca-1a-e2-88")
        assertNotNull(parsed)
        assertEquals("58:55:CA:1A:E2:88", AirPlayHardwareIdentity.formatMacHex(parsed!!))
        assertTrue(AirPlayHardwareIdentity.isUsableMac(parsed))
        assertFalse(AirPlayHardwareIdentity.isUsableMac(byteArrayOf(2, 0, 0, 0, 0, 0)))
    }
}
