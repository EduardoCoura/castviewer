package com.castviewer.sender.model

object ScreenCaptureMath {

    data class CaptureDimensions(
        val width: Int,
        val height: Int
    )

    /**
     * Scales physical display dimensions cleanly to fit the preset's long/short budget
     * while preserving current Portrait/Landscape orientation and enforcing mod-16 alignment
     * for maximum Qualcomm/MediaTek/Exynos/Unisoc MediaCodec H.264 compatibility.
     */
    fun computeMod16CaptureSize(
        displayWidthPx: Int,
        displayHeightPx: Int,
        preset: VideoPreset
    ): CaptureDimensions {
        val safeW = displayWidthPx.coerceAtLeast(320)
        val safeH = displayHeightPx.coerceAtLeast(320)
        val isPortrait = safeH > safeW

        val maxW = if (isPortrait) preset.maxShortEdge else preset.maxLongEdge
        val maxH = if (isPortrait) preset.maxLongEdge else preset.maxShortEdge

        val scale = minOf(
            maxW.toFloat() / safeW.toFloat(),
            maxH.toFloat() / safeH.toFloat(),
            1.0f
        )

        val rawW = (safeW * scale).toInt()
        val rawH = (safeH * scale).toInt()

        val alignedW = ((rawW / 16) * 16).coerceAtLeast(256)
        val alignedH = ((rawH / 16) * 16).coerceAtLeast(256)
        return CaptureDimensions(alignedW, alignedH)
    }
}
