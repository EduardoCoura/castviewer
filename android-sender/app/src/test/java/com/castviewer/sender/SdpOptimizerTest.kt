package com.castviewer.sender.cast

import org.junit.Assert.assertTrue
import org.junit.Test

class SdpOptimizerTest {

    @Test
    fun prioritizesConstrainedBaselineH264InjectsBitrateAndOpusPtime() {
        val sampleSdp = """
            v=0
            o=- 12345 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            m=video 9 UDP/TLS/RTP/SAVPF 96 98 100
            c=IN IP4 0.0.0.0
            a=rtpmap:96 VP8/90000
            a=rtpmap:98 H264/90000
            a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f
            a=rtpmap:100 rtx/90000
            a=fmtp:100 apt=98
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            c=IN IP4 0.0.0.0
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;useinbandfec=1
        """.trimIndent()

        val result = SdpOptimizer.optimizeOfferSdp(
            sdp = sampleSdp,
            targetBitrateBps = 10_000_000,
            targetFps = 60,
            preferH264 = true
        )

        assertTrue(result.contains("m=video 9 UDP/TLS/RTP/SAVPF 98 96 100"))
        assertTrue(result.contains("b=AS:10000"))
        assertTrue(result.contains("a=framerate:60"))
        assertTrue(result.contains("x-google-max-bitrate=10000"))
        assertTrue(result.contains("a=ptime:20"))
        assertTrue(result.contains("stereo=1"))
        assertTrue(result.contains("usedtx=0"))
        // RTX fmtp must not get x-google-max-bitrate injected
        assertTrue(result.contains("a=fmtp:100 apt=98\r\n"))
    }
}
