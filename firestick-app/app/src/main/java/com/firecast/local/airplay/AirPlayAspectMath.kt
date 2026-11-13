package com.firecast.local.airplay

import kotlin.math.roundToInt

/**
 * Pure JVM geometry & Annex-B bitstream helpers for AirPlay screen mirroring.
 */
object AirPlayAspectMath {

    data class TargetBounds(val width: Int, val height: Int)

    /**
     * Computes scaled dimensions inside [containerWidth] x [containerHeight] that fit an incoming
     * AirPlay video frame ([videoWidth] x [videoHeight]) cleanly centered without stretching.
     *
     * Handles both iPhone portrait (`9:19.5`) and landscape (`16:9` / `4:3`) orientations.
     */
    fun computeFitBounds(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): TargetBounds {
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return TargetBounds(containerWidth.coerceAtLeast(1), containerHeight.coerceAtLeast(1))
        }

        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        return if (videoAspect > containerAspect) {
            val scaledH = (containerWidth.toFloat() / videoAspect).roundToInt().coerceIn(1, containerHeight)
            TargetBounds(containerWidth, scaledH)
        } else {
            val scaledW = (containerHeight.toFloat() * videoAspect).roundToInt().coerceIn(1, containerWidth)
            TargetBounds(scaledW, containerHeight)
        }
    }

    /**
     * Scans Annex-B start codes (`00 00 00 01` or `00 00 01`) to detect IDR keyframes or parameter
     * sets (SPS/PPS for H.264; VPS/SPS/PPS/IDR/CRA for H.265/HEVC).
     */
    fun isKeyframeAnnexB(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 4) {
            val scLen = when {
                data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1 -> 3
                i <= data.size - 5 && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> 4
                else -> 0
            }
            if (scLen > 0) {
                val nalHeaderIndex = i + scLen
                if (nalHeaderIndex < data.size) {
                    val nalByte = data[nalHeaderIndex].toInt() and 0xFF
                    if (isH265) {
                        val nalType = (nalByte ushr 1) and 0x3F
                        if (nalType in 19..21 || nalType in 32..34) {
                            return true
                        }
                    } else {
                        val nalType = nalByte and 0x1F
                        if (nalType == 5 || nalType == 7 || nalType == 8) {
                            return true
                        }
                    }
                }
                i += scLen + 1
            } else {
                i++
            }
        }
        return false
    }
}
