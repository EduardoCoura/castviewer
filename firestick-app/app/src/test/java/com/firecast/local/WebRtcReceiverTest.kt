package com.firecast.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcReceiverTest {

    @Test
    fun testOptimizeSdpPrefersH264ConstrainedBaselineAndSetsBitrate() {
        val originalSdp = """
            v=0
            o=- 123456 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 100
            c=IN IP4 0.0.0.0
            a=rtpmap:96 VP8/90000
            a=rtpmap:97 VP9/90000
            a=rtpmap:98 H264/90000
            a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
            a=rtpmap:100 H264/90000
            a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640028
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=rtpmap:111 opus/48000/2
        """.trimIndent()

        val optimized = WebRtcReceiver.optimizeSdp(originalSdp)

        // 1. Verify m=video prioritizes H.264 Constrained Baseline (98) at the very front
        val mVideoLine = optimized.lines().first { it.startsWith("m=video") }
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 98 96 97 100", mVideoLine.trim())

        // 2. Verify 25 Mbps bandwidth cap and 60 FPS framerate attribute are set
        assertTrue(optimized.contains("b=AS:25000"))
        assertTrue(optimized.contains("b=TIAS:25000000"))
        assertTrue(optimized.contains("a=framerate:60"))

        // 3. Verify H.264 Level 3.1/4.0 (42e01f / 640028) is upgraded to Level 4.2 (42e02a / 64002a) for 1080p60
        assertTrue(optimized.contains("profile-level-id=42e02a"))
        assertTrue(optimized.contains("profile-level-id=64002a"))

        // 4. Verify Google bitrate parameters (4.5 Mbps min, 10 Mbps start, 20 Mbps max)
        assertTrue(optimized.contains("x-google-min-bitrate=4500;x-google-start-bitrate=10000;x-google-max-bitrate=20000"))

        // 4. Verify 20ms Opus glitch-free audio packetization
        assertTrue(optimized.contains("a=ptime:20"))
    }

    @Test
    fun testOptimizeSdpWith42001fBaselineProfile() {
        val originalSdp = """
            v=0
            o=- 123456 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=video 9 UDP/TLS/RTP/SAVPF 100 102
            c=IN IP4 0.0.0.0
            a=rtpmap:100 VP8/90000
            a=rtpmap:102 H264/90000
            a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=rtpmap:111 opus/48000/2
        """.trimIndent()

        val optimized = WebRtcReceiver.optimizeSdp(originalSdp)

        // Payload 102 (42001f -> upgraded to 42002a) should be moved to front
        val mVideoLine = optimized.lines().first { it.startsWith("m=video") }
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 100", mVideoLine.trim())
        assertTrue(optimized.contains("profile-level-id=42002a"))
        assertTrue(optimized.contains("a=framerate:60"))
        assertTrue(optimized.contains("b=AS:25000"))
        assertTrue(optimized.contains("b=TIAS:25000000"))
    }

    @Test
    fun testOptimizeSdpPrioritizesHw4200OverSw42e0() {
        val originalSdp = """
            v=0
            o=- 123456 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=video 9 UDP/TLS/RTP/SAVPF 96 98 100 102
            c=IN IP4 0.0.0.0
            a=rtpmap:96 VP8/90000
            a=rtpmap:98 H264/90000
            a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f
            a=rtpmap:100 H264/90000
            a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=0;profile-level-id=42001f
            a=rtpmap:102 H264/90000
            a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=rtpmap:111 opus/48000/2
        """.trimIndent()

        val optimized = WebRtcReceiver.optimizeSdp(originalSdp)
        val mVideoLine = optimized.lines().first { it.startsWith("m=video") }
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 100 98 96", mVideoLine.trim())
        assertTrue(optimized.contains("profile-level-id=42002a"))
    }
}
