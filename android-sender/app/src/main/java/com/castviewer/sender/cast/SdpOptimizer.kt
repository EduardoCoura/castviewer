package com.castviewer.sender.cast

object SdpOptimizer {

    /**
     * Reorders video codecs to prefer H.264 Baseline/Constrained Baseline (zero B-frame delay),
     * injects generous LAN bitrate caps (matching 60 FPS clarity without startup blur),
     * and enforces 20ms stereo Opus for clean game/media audio.
     */
    fun optimizeOfferSdp(
        sdp: String,
        targetBitrateBps: Int,
        targetFps: Int,
        preferH264: Boolean = true
    ): String {
        val bitrateKbps = (targetBitrateBps / 1000).coerceIn(2000, 25000)
        val minBitrateKbps = (bitrateKbps * 0.45f).toInt().coerceAtLeast(1500)
        val startBitrateKbps = (bitrateKbps * 0.65f).toInt().coerceAtLeast(minBitrateKbps)
        val bitrateParams =
            "x-google-min-bitrate=$minBitrateKbps;x-google-start-bitrate=$startBitrateKbps;x-google-max-bitrate=$bitrateKbps"

        val lines = sdp.split(Regex("\r?\n"))
        val candidates = mutableListOf<Pair<String, Int>>()
        val rtxOrFecPts = mutableSetOf<String>()
        var inVideoScan = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("m=video")) {
                inVideoScan = true
                continue
            } else if (trimmed.startsWith("m=")) {
                inVideoScan = false
                continue
            }

            if (inVideoScan && trimmed.startsWith("a=rtpmap:")) {
                val rtp = Regex("""^a=rtpmap:(\d+)\s+([a-zA-Z0-9_-]+)/""").find(trimmed)
                if (rtp != null) {
                    val pt = rtp.groupValues[1]
                    val name = rtp.groupValues[2].lowercase()
                    if (name == "rtx" || name == "red" || name == "ulpfec" || name == "flexfec-03") {
                        rtxOrFecPts.add(pt)
                    }
                }
            }

            if (preferH264 && inVideoScan && trimmed.startsWith("a=fmtp:")) {
                val match = Regex("""^a=fmtp:(\d+)\s+(.*)$""").find(trimmed) ?: continue
                val pt = match.groupValues[1]
                val params = match.groupValues[2].lowercase()
                val pkt1 = params.contains("packetization-mode=1")
                val score = when {
                    params.contains("profile-level-id=4200") -> if (pkt1) 0 else 1
                    params.contains("profile-level-id=42e0") -> if (pkt1) 2 else 3
                    params.contains("profile-level-id=4d00") -> if (pkt1) 4 else 5
                    params.contains("profile-level-id=6400") -> if (pkt1) 6 else 7
                    else -> null
                }
                if (score != null) {
                    candidates.add(pt to score)
                }
            }
        }

        val orderedH264Pts = candidates.sortedBy { it.second }.map { it.first }.distinct()

        val out = StringBuilder()
        var inVideo = false
        var inAudio = false
        var bwInjected = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            if (line.startsWith("m=video")) {
                inVideo = true
                inAudio = false
                bwInjected = false
                val parts = line.split(" ").filter { it.isNotBlank() }
                if (preferH264 && parts.size > 3 && orderedH264Pts.isNotEmpty()) {
                    val header = parts.subList(0, 3)
                    val pts = parts.subList(3, parts.size)
                    val reordered = mutableListOf<String>()
                    orderedH264Pts.forEach { if (pts.contains(it) && !reordered.contains(it)) reordered.add(it) }
                    pts.forEach { if (!reordered.contains(it)) reordered.add(it) }
                    out.append((header + reordered).joinToString(" ")).append("\r\n")
                } else {
                    out.append(line).append("\r\n")
                }
                continue
            } else if (line.startsWith("m=audio")) {
                if (inVideo && !bwInjected) {
                    out.append("b=AS:").append(bitrateKbps).append("\r\n")
                    out.append("b=TIAS:").append(targetBitrateBps).append("\r\n")
                    out.append("a=framerate:").append(targetFps).append("\r\n")
                    bwInjected = true
                }
                inAudio = true
                inVideo = false
                out.append(line).append("\r\n")
                continue
            } else if (line.startsWith("m=")) {
                inVideo = false
                inAudio = false
            }

            if (inVideo && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:") || line.startsWith("a=framerate:"))) {
                continue
            }

            if (inVideo && !bwInjected && (line.startsWith("c=") || line.startsWith("a="))) {
                if (line.startsWith("c=")) {
                    out.append(line).append("\r\n")
                    out.append("b=AS:").append(bitrateKbps).append("\r\n")
                    out.append("b=TIAS:").append(targetBitrateBps).append("\r\n")
                    out.append("a=framerate:").append(targetFps).append("\r\n")
                    bwInjected = true
                    continue
                } else {
                    out.append("b=AS:").append(bitrateKbps).append("\r\n")
                    out.append("b=TIAS:").append(targetBitrateBps).append("\r\n")
                    out.append("a=framerate:").append(targetFps).append("\r\n")
                    bwInjected = true
                }
            }

            if (inVideo && line.startsWith("a=fmtp:")) {
                val match = Regex("""^a=fmtp:(\d+)\s+(.*)$""").find(line)
                if (match != null) {
                    val pt = match.groupValues[1]
                    val params = match.groupValues[2]
                    if (params.contains("apt=") || rtxOrFecPts.contains(pt)) {
                        out.append(line).append("\r\n")
                        continue
                    }
                    val cleaned = params
                        .replace(Regex("""(;?\b(x-google-min-bitrate|x-google-start-bitrate|x-google-max-bitrate)=\d+)"""), "")
                        .trim(';', ' ')
                    val merged = if (cleaned.isEmpty()) bitrateParams else "$cleaned;$bitrateParams"
                    out.append("a=fmtp:").append(pt).append(" ").append(merged).append("\r\n")
                    continue
                }
            }

            if (inAudio && (line.startsWith("a=minptime:") || line.startsWith("a=ptime:"))) {
                continue
            }

            if (inAudio && line.startsWith("a=rtpmap:") && line.contains("opus/48000", ignoreCase = true)) {
                out.append(line).append("\r\n")
                out.append("a=minptime:10\r\n")
                out.append("a=ptime:20\r\n")
                continue
            }

            if (inAudio && line.startsWith("a=fmtp:") && !line.contains("usedtx=", ignoreCase = true)) {
                out.append(line).append(";stereo=1;sprop-stereo=1;maxaveragebitrate=128000;usedtx=0\r\n")
                continue
            }

            out.append(line).append("\r\n")
        }

        if (inVideo && !bwInjected) {
            out.append("b=AS:").append(bitrateKbps).append("\r\n")
            out.append("b=TIAS:").append(targetBitrateBps).append("\r\n")
            out.append("a=framerate:").append(targetFps).append("\r\n")
        }

        return out.toString()
    }
}
