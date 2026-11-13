package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Unlocks hardware-accelerated MediaCodec/Codec2 H.264 encoding (e.g. c2.exynos.*, c2.qti.*,
 * c2.mtk.*, OMX.MTK.*, c2.unisoc.*) that stock stream-webrtc HardwareVideoEncoderFactory
 * rejects due to its legacy OMX.qcom./OMX.Exynos. allowlist.
 * Preserves Zero-Copy SurfaceTexture encoding and wraps with VideoEncoderFallback when available.
 */
class CastUniversalVideoEncoderFactory(
    private val eglContext: EglBase.Context
) : VideoEncoderFactory {

    private val codecWrapperFactory = LowLatencyMediaCodecWrapperFactory()
    private val defaultFactory = DefaultVideoEncoderFactory(eglContext, true, false)
    private val softwareFactory = SoftwareVideoEncoderFactory()

    private val discoveredHwAvc: DiscoveredAvcCodec? = findHardwareAvcCodec()
    private val defaultHasHwAvc: Boolean = hasDefaultHardwareAvc()

    @Volatile
    var activeCodecSummary: String = when {
        defaultHasHwAvc -> "H.264 HW"
        discoveredHwAvc != null -> "H.264 HW"
        else -> "VP8"
    }
        private set

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (info.name.equals("H264", ignoreCase = true) && discoveredHwAvc != null) {
            val egl14 = eglContext as? EglBase14.Context
            if (egl14 != null) {
                return try {
                    val bitrateAdjuster = if (
                        discoveredHwAvc.codecName.startsWith("OMX.Exynos.", ignoreCase = true)
                    ) {
                        FramerateBitrateAdjuster()
                    } else {
                        DynamicBitrateAdjuster()
                    }
                    val hwEncoder = HardwareVideoEncoder(
                        codecWrapperFactory,
                        discoveredHwAvc.codecName,
                        VideoCodecMimeType.H264,
                        discoveredHwAvc.surfaceColorFormat,
                        discoveredHwAvc.yuvColorFormat,
                        info.params,
                        KEYFRAME_INTERVAL_SEC,
                        FORCED_KEYFRAME_INTERVAL_MS,
                        bitrateAdjuster,
                        egl14
                    )
                    val teeEncoder = TeeReplayVideoEncoder(hwEncoder)
                    activeCodecSummary = "H.264 HW (${shortCodecTag(discoveredHwAvc.codecName)})"
                    val swFallback = softwareFactory.createEncoder(VideoCodecInfo("VP8", emptyMap()))
                    if (swFallback != null) {
                        VideoEncoderFallback(swFallback, teeEncoder)
                    } else {
                        teeEncoder
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware H.264 wrapper fallback: ${e.message}")
                    defaultFactory.createEncoder(info)
                }
            }
        }

        val enc = defaultFactory.createEncoder(info)
        if (enc != null) {
            activeCodecSummary = if (enc.isHardwareEncoder) {
                "${info.name.uppercase()} HW"
            } else {
                "${info.name.uppercase()} SW"
            }
            if (info.name.equals("H264", ignoreCase = true) && enc !is TeeReplayVideoEncoder) {
                return TeeReplayVideoEncoder(enc)
            }
        }
        return enc
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val list = LinkedHashSet<VideoCodecInfo>()
        if (!defaultHasHwAvc && discoveredHwAvc != null) {
            list.add(
                VideoCodecInfo(
                    "H264",
                    MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H264, false)
                )
            )
        }
        defaultFactory.supportedCodecs.forEach { list.add(it) }
        return list.toTypedArray()
    }

    private fun hasDefaultHardwareAvc(): Boolean {
        return try {
            HardwareVideoEncoderFactory(eglContext, true, false)
                .supportedCodecs
                .any { it.name.equals("H264", ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    private fun findHardwareAvcCodec(): DiscoveredAvcCodec? {
        return try {
            val regularCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            // Prefer Codec2 hardware encoders first, then vendor OMX hardware encoders
            val candidates = regularCodecs.filter { info ->
                info.isEncoder &&
                    MediaCodecUtils.codecSupportsType(info, VideoCodecMimeType.H264) &&
                    !MediaCodecUtils.isSoftwareOnly(info)
            }.sortedBy { info ->
                when {
                    info.name.startsWith("c2.", ignoreCase = true) -> 0
                    info.name.startsWith("OMX.", ignoreCase = true) -> 1
                    else -> 2
                }
            }

            for (info in candidates) {
                val caps = try {
                    info.getCapabilitiesForType(VideoCodecMimeType.H264.mimeType())
                } catch (_: Exception) {
                    null
                } ?: continue

                val yuvColor = MediaCodecUtils.selectColorFormat(
                    MediaCodecUtils.ENCODER_COLOR_FORMATS,
                    caps
                ) ?: continue

                val surfaceColor = MediaCodecUtils.selectColorFormat(
                    MediaCodecUtils.TEXTURE_COLOR_FORMATS,
                    caps
                )

                // Zero-copy Surface texture input requires a valid TEXTURE_COLOR_FORMATS match
                if (surfaceColor != null) {
                    return DiscoveredAvcCodec(
                        codecName = info.name,
                        surfaceColorFormat = surfaceColor,
                        yuvColorFormat = yuvColor
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun shortCodecTag(rawName: String): String {
        val parts = rawName.split('.')
        return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else rawName.take(14)
    }

    private data class DiscoveredAvcCodec(
        val codecName: String,
        val surfaceColorFormat: Int,
        val yuvColorFormat: Int
    )

    companion object {
        private const val TAG = "CastUniversalEncoder"
        private const val KEYFRAME_INTERVAL_SEC = 5
        private const val FORCED_KEYFRAME_INTERVAL_MS = 5000
    }

    /**
     * Configures MediaCodec hardware H.264 encoders with real-time scheduling, peak 60+ FPS operating-rate
     * hints (preventing DVFS underclocking on older Exynos/MediaTek/Snapdragon SoCs), and API 30+ low-latency
     * output while gracefully falling back to plain format keys if a vendor driver rejects optional hints.
     */
    private class LowLatencyMediaCodecWrapperFactory(
        private val delegateFactory: MediaCodecWrapperFactory = MediaCodecWrapperFactoryImpl()
    ) : MediaCodecWrapperFactory {
        override fun createByCodecName(name: String): MediaCodecWrapper {
            val delegate = delegateFactory.createByCodecName(name)
            return object : MediaCodecWrapper by delegate {
                override fun configure(
                    format: MediaFormat,
                    surface: Surface?,
                    crypto: MediaCrypto?,
                    flags: Int
                ) {
                    // Snapshot clean baseline BEFORE injecting vendor hints so fallback never loses or corrupts keys
                    val backupFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaFormat(format)
                    } else {
                        cloneSafeBaselineFormat(format)
                    }

                    try {
                        val requestedFps = readIntOrFloatAsInt(format, MediaFormat.KEY_FRAME_RATE, 60)
                        val operatingRate = requestedFps.coerceIn(60, 120)
                        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                        format.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            format.setInteger(MediaFormat.KEY_LATENCY, 1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        delegate.configure(format, surface, crypto, flags)
                    } catch (e: Exception) {
                        Log.w(TAG, "Low-latency encoder hints rejected by $name (${e.message}), falling back")
                        delegate.configure(backupFormat, surface, crypto, flags)
                    }
                }
            }
        }

        private fun cloneSafeBaselineFormat(src: MediaFormat): MediaFormat {
            val mime = src.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val w = readIntOrFloatAsInt(src, MediaFormat.KEY_WIDTH, 720)
            val h = readIntOrFloatAsInt(src, MediaFormat.KEY_HEIGHT, 1280)
            return MediaFormat.createVideoFormat(mime, w, h).also { dst ->
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_COLOR_FORMAT)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_BIT_RATE)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_FRAME_RATE)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_I_FRAME_INTERVAL)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_BITRATE_MODE)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_PROFILE)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_LEVEL)
                copyNumericKeyIfPresent(src, dst, MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER)
            }
        }

        private fun readIntOrFloatAsInt(format: MediaFormat, key: String, fallback: Int): Int {
            if (!format.containsKey(key)) return fallback
            return try {
                format.getInteger(key)
            } catch (_: ClassCastException) {
                try {
                    format.getFloat(key).toInt()
                } catch (_: Exception) {
                    fallback
                }
            } catch (_: Exception) {
                fallback
            }
        }

        private fun copyNumericKeyIfPresent(src: MediaFormat, dst: MediaFormat, key: String) {
            if (!src.containsKey(key)) return
            try {
                dst.setInteger(key, src.getInteger(key))
            } catch (_: ClassCastException) {
                try {
                    dst.setFloat(key, src.getFloat(key))
                } catch (_: ClassCastException) {
                    try {
                        dst.setLong(key, src.getLong(key))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
