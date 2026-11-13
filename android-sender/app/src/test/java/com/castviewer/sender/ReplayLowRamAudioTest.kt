package com.castviewer.sender

import com.castviewer.sender.cast.ReplayClipRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayLowRamAudioTest {

    @Before
    fun setUp() {
        ReplayClipRecorder.setSessionActive(false)
        ReplayClipRecorder.configure(enabled = false, lowRamMode = false)
        ReplayClipRecorder.clearBuffers()
    }

    @After
    fun tearDown() {
        ReplayClipRecorder.setSessionActive(false)
        ReplayClipRecorder.configure(enabled = false, lowRamMode = false)
        ReplayClipRecorder.clearBuffers()
    }

    @Test
    fun lowRamMode_retainsAudioCaptureAndCapsAtFiveMegabytes() {
        ReplayClipRecorder.configure(enabled = true, lowRamMode = true)
        ReplayClipRecorder.setSessionActive(true)

        assertTrue(
            "Low-RAM mode should still capture replay audio",
            ReplayClipRecorder.isAudioCaptureActive()
        )

        val chunkSize = 256 * 1024 // 256 KB per chunk
        val pcm = ByteArray(chunkSize) { 0x1A }
        var tsNs = 1_000_000_000L
        repeat(28) { // 7 MB total -> must prune down <= 5 MB
            tsNs += 250_000_000L
            ReplayClipRecorder.appendAudioPcm(pcm, chunkSize, 48_000, 2, tsNs)
        }

        val (count, totalBytes) = ReplayClipRecorder.getAudioBufferStatsForTest()
        val maxLowRamAudioBytes = 5L * 1024L * 1024L
        assertTrue("Expected retained audio chunks in low-RAM mode, got $count", count > 2)
        assertTrue(
            "Expected low-RAM audio buffer <= 5 MB ($maxLowRamAudioBytes), was $totalBytes",
            totalBytes in 1L..maxLowRamAudioBytes
        )
    }

    @Test
    fun disablingReplay_clearsAudioBufferAndStopsCapture() {
        ReplayClipRecorder.configure(enabled = true, lowRamMode = true)
        ReplayClipRecorder.setSessionActive(true)
        val pcm = ByteArray(8192) { 0x2B }
        ReplayClipRecorder.appendAudioPcm(pcm, pcm.size, 48_000, 2, 2_000_000_000L)
        assertTrue(ReplayClipRecorder.getAudioBufferStatsForTest().second > 0L)

        ReplayClipRecorder.configure(enabled = false, lowRamMode = true)
        assertFalse(ReplayClipRecorder.isAudioCaptureActive())
        assertEquals(0, ReplayClipRecorder.getAudioBufferStatsForTest().first)
        assertEquals(0L, ReplayClipRecorder.getAudioBufferStatsForTest().second)
    }
}
