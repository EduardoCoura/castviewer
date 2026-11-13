package com.castviewer.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareProfilerTest {

    @Test
    fun flagshipPhoneWithHwEncoderGets1080p60() {
        val rec = HardwareProfiler.classifyHardware(
            deviceModel = "Samsung Galaxy S23",
            socDescriptor = "qcom sm8550",
            cpuCores = 8,
            is64Bit = true,
            ramGb = 8.0f,
            hasHwH264Encoder = true,
            hwEncoderName = "c2.qti.avc.encoder"
        )
        assertEquals(HardwareTier.HIGH, rec.hardwareInfo.tier)
        assertEquals(VideoPreset.PRESET_1080P60, rec.recommendedPreset)
        assertFalse(rec.lowSpecMode)
        assertFalse(rec.lowRamMode)
        assertFalse(rec.compatMode)
    }

    @Test
    fun lowRamMidPhoneGets720p60WithLowRamFlag() {
        val rec = HardwareProfiler.classifyHardware(
            deviceModel = "Moto G",
            socDescriptor = "sm6225",
            cpuCores = 8,
            is64Bit = true,
            ramGb = 4.0f,
            hasHwH264Encoder = true,
            hwEncoderName = "c2.qti.avc.encoder"
        )
        assertEquals(HardwareTier.BALANCED, rec.hardwareInfo.tier)
        assertEquals(VideoPreset.PRESET_720P60, rec.recommendedPreset)
        assertTrue(rec.lowRamMode)
        assertFalse(rec.lowSpecMode)
    }

    @Test
    fun weakEntrySocGets720p30AndLowSpecMode() {
        val rec = HardwareProfiler.classifyHardware(
            deviceModel = "Entry Phone",
            socDescriptor = "mt6765 Helio P35",
            cpuCores = 4,
            is64Bit = false,
            ramGb = 2.0f,
            hasHwH264Encoder = true,
            hwEncoderName = "OMX.MTK.VIDEO.ENCODER.AVC"
        )
        assertEquals(HardwareTier.WEAK, rec.hardwareInfo.tier)
        assertEquals(VideoPreset.PRESET_720P30, rec.recommendedPreset)
        assertTrue(rec.lowSpecMode)
        assertTrue(rec.lowRamMode)
    }

    @Test
    fun missingHardwareAvcTriggersCompatAndWeakMode() {
        val rec = HardwareProfiler.classifyHardware(
            deviceModel = "Custom ROM",
            socDescriptor = "generic",
            cpuCores = 8,
            is64Bit = true,
            ramGb = 6.0f,
            hasHwH264Encoder = false,
            hwEncoderName = "Software Fallback"
        )
        assertEquals(HardwareTier.WEAK, rec.hardwareInfo.tier)
        assertEquals(VideoPreset.PRESET_720P30, rec.recommendedPreset)
        assertTrue(rec.lowSpecMode)
        assertTrue(rec.compatMode)
    }

    @Test
    fun galaxyA30sExynos7904HdScreenGetsBalanced720p60() {
        val rec = HardwareProfiler.classifyHardware(
            deviceModel = "Samsung SM-A307GT",
            socDescriptor = "exynos7904 universal7885",
            cpuCores = 8,
            is64Bit = true,
            ramGb = 4.0f,
            hasHwH264Encoder = true,
            hwEncoderName = "OMX.Exynos.avc.enc",
            physicalShortEdgePx = 720,
            powerSaveActive = false
        )
        assertEquals(HardwareTier.BALANCED, rec.hardwareInfo.tier)
        assertEquals(VideoPreset.PRESET_720P60, rec.recommendedPreset)
        assertTrue(rec.lowRamMode)
        assertFalse(rec.lowSpecMode)
    }
}
