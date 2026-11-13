package com.castviewer.sender

import com.castviewer.sender.cast.ReplayClipLogic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayClipRecorderTest {

    @Test
    fun parseAnnexB_extractsSpsPpsAndIdrAcrossStartCodeLengths() {
        // 4-byte SPS (type 7) + 3-byte PPS (type 8) + 4-byte IDR (type 5)
        val packet = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21
        )

        val parsed = ReplayClipLogic.parseAnnexB(packet)
        assertTrue(parsed.containsIdr)
        assertTrue(parsed.containsVcl)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E),
            parsed.sps
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte()),
            parsed.pps
        )
    }

    @Test
    fun parseAnnexB_identifiesNonIdrSliceWithoutCsd() {
        val deltaPacket = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x12)
        val parsed = ReplayClipLogic.parseAnnexB(deltaPacket)
        assertFalse(parsed.containsIdr)
        assertTrue(parsed.containsVcl)
        assertNull(parsed.sps)
        assertNull(parsed.pps)
    }

    @Test
    fun selectReplaySlice_picksLatestKeyframeBefore30sWindowAndIsolatesResolution() {
        val sps720 = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val pps720 = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val sps1080 = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x28)
        val pps1080 = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        val frames = listOf(
            // Older resolution block (must be excluded so MediaMuxer sees consistent dimensions)
            ReplayClipLogic.VideoFrameEntry(
                data = byteArrayOf(0, 0, 0, 1, 0x65, 1),
                width = 1920,
                height = 1080,
                monotonicNs = 1_000_000_000L,
                isKeyFrame = true,
                containsVcl = true,
                sps = sps1080,
                pps = pps1080
            ),
            // New 720p stream keyframes at 5s, 10s, 36s, and P-frame at 41s
            ReplayClipLogic.VideoFrameEntry(
                data = byteArrayOf(0, 0, 0, 1, 0x65, 2),
                width = 1280,
                height = 720,
                monotonicNs = 5_000_000_000L,
                isKeyFrame = true,
                containsVcl = true,
                sps = sps720,
                pps = pps720
            ),
            ReplayClipLogic.VideoFrameEntry(
                data = byteArrayOf(0, 0, 0, 1, 0x65, 3),
                width = 1280,
                height = 720,
                monotonicNs = 10_000_000_000L,
                isKeyFrame = true,
                containsVcl = true,
                sps = sps720,
                pps = pps720
            ),
            ReplayClipLogic.VideoFrameEntry(
                data = byteArrayOf(0, 0, 0, 1, 0x65, 4),
                width = 1280,
                height = 720,
                monotonicNs = 36_000_000_000L,
                isKeyFrame = true,
                containsVcl = true,
                sps = sps720,
                pps = pps720
            ),
            ReplayClipLogic.VideoFrameEntry(
                data = byteArrayOf(0, 0, 0, 1, 0x41, 5),
                width = 1280,
                height = 720,
                monotonicNs = 41_000_000_000L,
                isKeyFrame = false,
                containsVcl = true,
                sps = null,
                pps = null
            )
        )

        val selected = ReplayClipLogic.selectReplaySlice(frames, targetWindowMs = 30_000L)
        assertNotNull(selected)
        val clip = selected!!
        assertEquals(1280, clip.width)
        assertEquals(720, clip.height)
        assertEquals(3, clip.frames.size)
        assertEquals(10_000_000_000L, clip.frames.first().monotonicNs)
        assertEquals(31_000L, clip.durationMs)
        assertArrayEquals(sps720, clip.sps)
    }

    @Test
    fun selectLatestKeyframeForScreenshot_usesFallbackCsdWhenKeyframeOmitsInlineSpsPps() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val keyframe = ReplayClipLogic.VideoFrameEntry(
            data = byteArrayOf(0, 0, 0, 1, 0x65, 9, 8, 7),
            width = 1280,
            height = 720,
            monotonicNs = 5_000_000_000L,
            isKeyFrame = true,
            containsVcl = true,
            sps = null,
            pps = null
        )

        val slice = ReplayClipLogic.selectLatestKeyframeForScreenshot(
            latestKeyframe = keyframe,
            fallbackSps = sps,
            fallbackPps = pps
        )
        assertNotNull(slice)
        assertEquals(1, slice!!.frames.size)
        assertArrayEquals(sps, slice.sps)
        assertArrayEquals(pps, slice.pps)
        assertEquals(1280, slice.width)
        assertEquals(720, slice.height)
    }

    @Test
    fun selectLatestKeyframeForScreenshot_rejectsNonKeyframeOrMissingCsd() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val pFrame = ReplayClipLogic.VideoFrameEntry(
            data = byteArrayOf(0, 0, 0, 1, 0x41, 1),
            width = 1280,
            height = 720,
            monotonicNs = 2_000_000_000L,
            isKeyFrame = false,
            containsVcl = true,
            sps = sps,
            pps = null
        )

        assertNull(ReplayClipLogic.selectLatestKeyframeForScreenshot(pFrame, sps, null))
        assertNull(ReplayClipLogic.selectLatestKeyframeForScreenshot(null, sps, null))
    }
}
