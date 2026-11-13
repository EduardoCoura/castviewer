package com.castviewer.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureMathTest {

    @Test
    fun portraitPhoneMapsCorrectlyAndAlignsMod16() {
        val dims = ScreenCaptureMath.computeMod16CaptureSize(
            displayWidthPx = 1080,
            displayHeightPx = 2400,
            preset = VideoPreset.PRESET_1080P60
        )
        assertTrue(dims.height > dims.width)
        assertEquals(0, dims.width % 16)
        assertEquals(0, dims.height % 16)
        assertTrue(dims.height <= 1920)
        assertTrue(dims.width <= 1080)
    }

    @Test
    fun landscapePhoneFitsWithin1080pLandscapeBudget() {
        val dims = ScreenCaptureMath.computeMod16CaptureSize(
            displayWidthPx = 2400,
            displayHeightPx = 1080,
            preset = VideoPreset.PRESET_1080P60
        )
        assertTrue(dims.width > dims.height)
        assertEquals(0, dims.width % 16)
        assertEquals(0, dims.height % 16)
        assertTrue(dims.width <= 1920)
        assertTrue(dims.height <= 1080)
    }

    @Test
    fun downscalesTo720pPresetAndRemainsMod16() {
        val dims = ScreenCaptureMath.computeMod16CaptureSize(
            displayWidthPx = 1440,
            displayHeightPx = 3120,
            preset = VideoPreset.PRESET_720P30
        )
        assertEquals(0, dims.width % 16)
        assertEquals(0, dims.height % 16)
        assertTrue(dims.height <= 1280)
        assertTrue(dims.width <= 720)
    }

    @Test
    fun turbo540p60DownscalesToCompactMod16Budget() {
        val dims = ScreenCaptureMath.computeMod16CaptureSize(
            displayWidthPx = 720,
            displayHeightPx = 1560,
            preset = VideoPreset.PRESET_540P60
        )
        assertEquals(0, dims.width % 16)
        assertEquals(0, dims.height % 16)
        assertTrue(dims.height <= 960)
        assertTrue(dims.width <= 544)
    }
}
