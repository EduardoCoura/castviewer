package com.firecast.local

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Kotlin timing curves, radial/comet angle math, color blending, and continuous
 * Play/Pause polygon morph vertex solver shared by Fire TV animations and unit tests.
 */
object KineticMath {

    fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    fun lerp(start: Float, end: Float, t: Float): Float =
        start + (end - start) * clamp01(t)

    fun windowProgress(t: Float, start: Float, end: Float): Float {
        if (end <= start) return if (t >= end) 1f else 0f
        return clamp01((t - start) / (end - start))
    }

    /** Ultra-smooth tvOS quintic deceleration with zero end snap. */
    fun easeOutQuint(t: Float): Float {
        val c = clamp01(t)
        return 1f - (1f - c).pow(5)
    }

    /** Symmetric quintic S-curve for cross-dissolves and iris portal opening. */
    fun easeInOutQuint(t: Float): Float {
        val c = clamp01(t)
        return if (c < 0.5f) {
            16f * c * c * c * c * c
        } else {
            1f - (-2f * c + 2f).pow(5) / 2f
        }
    }

    /** Fast ignition curve for laser beams and sonar shockwaves. */
    fun easeOutExpo(t: Float): Float {
        val c = clamp01(t)
        return if (c >= 1f) 1f else 1f - 2f.pow(-10f * c)
    }

    /** Gentle organic settle (~1.8% overshoot, no cartoon bounce). */
    fun easeOutBackSubtle(t: Float): Float {
        val c = clamp01(t)
        val c1 = 0.72f
        val c3 = c1 + 1f
        val shifted = c - 1f
        return (1f + c3 * shifted * shifted * shifted + c1 * shifted * shifted).coerceAtLeast(0f)
    }

    /** Smooth ARGB channel interpolation without object allocation. */
    fun lerpColorArgb(startArgb: Int, endArgb: Int, t: Float): Int {
        val f = clamp01(t)
        val a1 = (startArgb ushr 24) and 0xFF
        val r1 = (startArgb ushr 16) and 0xFF
        val g1 = (startArgb ushr 8) and 0xFF
        val b1 = startArgb and 0xFF

        val a2 = (endArgb ushr 24) and 0xFF
        val r2 = (endArgb ushr 16) and 0xFF
        val g2 = (endArgb ushr 8) and 0xFF
        val b2 = endArgb and 0xFF

        val a = (a1 + (a2 - a1) * f).roundToInt().coerceIn(0, 255)
        val r = (r1 + (r2 - r1) * f).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * f).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Caps frame deltas cleanly so heavy cold-start EGL warmups never drop opening frames. */
    fun advanceCappedClockMs(previousElapsedMs: Long, rawDeltaMs: Long, maxStepMs: Long = 34L): Long {
        val safeDelta = rawDeltaMs.coerceIn(0L, maxStepMs)
        return previousElapsedMs + safeDelta
    }

    /**
     * Computes the SweepGradient matrix rotation angle so its bright comet head peak (at position 0.88 = 316.8 deg)
     * lands directly at [headDeg], preventing any 0/360 degree wrap seam inside the visible arc.
     */
    fun cometShaderRotationDeg(headDeg: Float): Float = headDeg - 316.8f

    /**
     * Writes 16 floats (left quad: indices 0..7 as x0,y0..x3,y3; right quad: indices 8..15 as x0,y0..x3,y3)
     * describing a seamless continuous morph from Play triangle (pauseAmount = 0f) to Pause bars (pauseAmount = 1f).
     */
    fun computePlayPauseMorphQuads(
        cx: Float,
        cy: Float,
        halfSize: Float,
        pauseAmount: Float,
        outCoords: FloatArray
    ) {
        val p = clamp01(pauseAmount)
        val s = halfSize
        val midY = 0.535f // collinear point on (-0.66, -0.92) -> (+0.92, 0) at x = 0

        // Left half: left part of triangle -> left Pause bar
        outCoords[0] = cx + lerp(-0.66f, -0.78f, p) * s
        outCoords[1] = cy + lerp(-0.92f, -0.88f, p) * s
        outCoords[2] = cx + lerp(0.02f, -0.22f, p) * s
        outCoords[3] = cy + lerp(-midY, -0.88f, p) * s
        outCoords[4] = cx + lerp(0.02f, -0.22f, p) * s
        outCoords[5] = cy + lerp(midY, 0.88f, p) * s
        outCoords[6] = cx + lerp(-0.66f, -0.78f, p) * s
        outCoords[7] = cy + lerp(0.92f, 0.88f, p) * s

        // Right half: right apex of triangle -> right Pause bar
        outCoords[8] = cx + lerp(-0.01f, 0.22f, p) * s
        outCoords[9] = cy + lerp(-midY, -0.88f, p) * s
        outCoords[10] = cx + lerp(0.92f, 0.78f, p) * s
        outCoords[11] = cy + lerp(0f, -0.88f, p) * s
        outCoords[12] = cx + lerp(0.92f, 0.78f, p) * s
        outCoords[13] = cy + lerp(0f, 0.88f, p) * s
        outCoords[14] = cx + lerp(-0.01f, 0.22f, p) * s
        outCoords[15] = cy + lerp(midY, 0.88f, p) * s
    }

    fun breatheWave(elapsedMs: Long, cycleHz: Float = 0.55f): Float =
        0.5f + 0.5f * sin(elapsedMs * 0.001f * 2f * PI.toFloat() * cycleHz)

    fun orbitCos(angleDeg: Float): Float = cos(Math.toRadians(angleDeg.toDouble())).toFloat()

    fun orbitSin(angleDeg: Float): Float = sin(Math.toRadians(angleDeg.toDouble())).toFloat()
}
