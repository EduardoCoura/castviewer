package com.firecast.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KineticMathTest {

    @Test
    fun `easing curves preserve endpoints and monotonic arrival`() {
        assertEquals(0f, KineticMath.easeOutQuint(0f), 1e-5f)
        assertEquals(1f, KineticMath.easeOutQuint(1f), 1e-5f)
        assertTrue(KineticMath.easeOutQuint(0.5f) > 0.9f)

        assertEquals(0f, KineticMath.easeInOutQuint(0f), 1e-5f)
        assertEquals(0.5f, KineticMath.easeInOutQuint(0.5f), 1e-5f)
        assertEquals(1f, KineticMath.easeInOutQuint(1f), 1e-5f)

        assertEquals(0f, KineticMath.easeOutExpo(0f), 1e-5f)
        assertEquals(1f, KineticMath.easeOutExpo(1f), 1e-5f)
        assertEquals(1f, KineticMath.easeOutBackSubtle(1f), 1e-5f)
        assertTrue(KineticMath.easeOutBackSubtle(0.75f) >= 0.99f)
    }

    @Test
    fun `advanceCappedClockMs protects opening frames from cold-launch stalls`() {
        val afterSpike = KineticMath.advanceCappedClockMs(previousElapsedMs = 0L, rawDeltaMs = 340L, maxStepMs = 24L)
        assertEquals(24L, afterSpike)

        val normalFrame = KineticMath.advanceCappedClockMs(previousElapsedMs = 24L, rawDeltaMs = 16L, maxStepMs = 34L)
        assertEquals(40L, normalFrame)

        val negativeGuard = KineticMath.advanceCappedClockMs(previousElapsedMs = 40L, rawDeltaMs = -10L)
        assertEquals(40L, negativeGuard)
    }

    @Test
    fun `lerpColorArgb blends channels linearly without jumps`() {
        val orange = 0xFFFF6B1A.toInt()
        val emerald = 0xFF22C55E.toInt()
        assertEquals(orange, KineticMath.lerpColorArgb(orange, emerald, 0f))
        assertEquals(emerald, KineticMath.lerpColorArgb(orange, emerald, 1f))

        val mid = KineticMath.lerpColorArgb(orange, emerald, 0.5f)
        val midR = (mid ushr 16) and 0xFF
        val midG = (mid ushr 8) and 0xFF
        assertTrue(midR in 135..155)
        assertTrue(midG in 140..160)
    }

    @Test
    fun `cometShaderRotationDeg places peak cleanly at headDeg`() {
        val headDeg = 90f
        val rot = KineticMath.cometShaderRotationDeg(headDeg)
        val gradientAngleAtHead = (headDeg - rot)
        assertEquals(316.8f, gradientAngleAtHead, 1e-3f)
    }

    @Test
    fun `play pause morph quads form clean Play triangle at 0 and Pause bars at 1`() {
        val coords = FloatArray(16)

        // Play state (p = 0): right-hand quad outer vertices converge to single apex (+0.92*s, 0)
        KineticMath.computePlayPauseMorphQuads(cx = 100f, cy = 200f, halfSize = 10f, pauseAmount = 0f, outCoords = coords)
        assertEquals(109.2f, coords[10], 1e-3f)
        assertEquals(200f, coords[11], 1e-3f)
        assertEquals(109.2f, coords[12], 1e-3f)
        assertEquals(200f, coords[13], 1e-3f)

        // Pause state (p = 1): separated symmetric vertical bars
        KineticMath.computePlayPauseMorphQuads(cx = 100f, cy = 200f, halfSize = 10f, pauseAmount = 1f, outCoords = coords)
        assertTrue(coords[2] < coords[8]) // distinct center gap between bars
        assertEquals(coords[1], coords[3], 1e-3f) // flat horizontal top of left bar
        assertEquals(coords[9], coords[11], 1e-3f) // flat horizontal top of right bar

        // Midpoint state (p = 0.5): continuous geometry between endpoints
        val mid = FloatArray(16)
        KineticMath.computePlayPauseMorphQuads(cx = 100f, cy = 200f, halfSize = 10f, pauseAmount = 0.5f, outCoords = mid)
        assertTrue(mid[8] > coords[8] - 3f && mid[8] < 102.2f)
    }
}
