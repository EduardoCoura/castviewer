package com.firecast.local

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.webrtc.EncodedImage
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provides a direct SurfaceView Surface and lifecycle callbacks for zero-copy hardware MediaCodec rendering.
 */
interface DirectSurfaceProvider {
    fun getDirectSurface(): Surface?
    fun awaitDirectSurface(timeoutMs: Long): Surface?
    fun onDirectVideoSizeChanged(width: Int, height: Int)
    fun onDirectFirstFrameRendered()
    fun isStreamPaused(): Boolean
    fun registerSurfaceConsumer(onAvailable: (Surface) -> Unit, onDestroyed: () -> Unit)
    fun unregisterSurfaceConsumer()
}

/**
 * Hardware-accelerated WebRTC VideoDecoderFactory that routes H.264 / VP8 / VP9 directly from
 * Android MediaCodec to a dedicated SurfaceView Surface, completely bypassing SurfaceTextureHelper's
 * single-slot OES texture lock, dpframework MDP conversion, and OpenGL EglRenderer overhead.
 */
class DirectSurfaceVideoDecoderFactory(
    private val fallbackFactory: VideoDecoderFactory,
    private val surfaceProvider: DirectSurfaceProvider,
    private val onDirectModeChanged: (Boolean) -> Unit
) : VideoDecoderFactory {

    companion object {
        private const val TAG = "DirectSurfaceDecFactory"

        private val MIME_BY_NAME = mapOf(
            "H264" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "VP8" to MediaFormat.MIMETYPE_VIDEO_VP8,
            "VP9" to MediaFormat.MIMETYPE_VIDEO_VP9
        )

        fun findHardwareDecoderName(mimeType: String): String? {
            return try {
                val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                infos.firstOrNull { info ->
                    if (info.isEncoder) return@firstOrNull false
                    val name = info.name
                    val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        name.startsWith("OMX.google.", ignoreCase = true) ||
                            name.startsWith("c2.android.", ignoreCase = true)
                    }
                    !isSoftware && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                }?.name
            } catch (e: Exception) {
                Log.w(TAG, "Error querying MediaCodecList for $mimeType: ${e.message}")
                null
            }
        }
    }

    override fun createDecoder(codecInfo: VideoCodecInfo?): VideoDecoder? {
        if (codecInfo == null) return null
        val codecKey = codecInfo.name.uppercase()
        val mimeType = MIME_BY_NAME[codecKey]
        val hwName = mimeType?.let { findHardwareDecoderName(it) }
        if (mimeType != null && hwName != null) {
            Log.i(TAG, "createDecoder: using DirectSurfaceVideoDecoder ($hwName) for ${codecInfo.name} params=${codecInfo.params}")
            onDirectModeChanged(true)
            return DirectSurfaceVideoDecoder(hwName, mimeType, surfaceProvider)
        }
        Log.i(TAG, "createDecoder: falling back to DefaultVideoDecoderFactory for ${codecInfo.name}")
        onDirectModeChanged(false)
        return fallbackFactory.createDecoder(codecInfo)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val list = mutableListOf<VideoCodecInfo>()
        if (findHardwareDecoderName(MediaFormat.MIMETYPE_VIDEO_AVC) != null) {
            for (profile in listOf("42001f", "42002a", "42e01f", "42e02a")) {
                for (pkt in listOf("1", "0")) {
                    list.add(
                        VideoCodecInfo(
                            "H264",
                            mapOf(
                                "level-asymmetry-allowed" to "1",
                                "packetization-mode" to pkt,
                                "profile-level-id" to profile
                            )
                        )
                    )
                }
            }
        }
        if (findHardwareDecoderName(MediaFormat.MIMETYPE_VIDEO_VP8) != null) {
            list.add(VideoCodecInfo("VP8", emptyMap()))
        }
        if (findHardwareDecoderName(MediaFormat.MIMETYPE_VIDEO_VP9) != null) {
            list.add(VideoCodecInfo("VP9", emptyMap()))
        }
        for (fallback in fallbackFactory.supportedCodecs) {
            val dup = list.any { existing ->
                existing.name.equals(fallback.name, ignoreCase = true) &&
                    existing.params["profile-level-id"]?.lowercase() == fallback.params["profile-level-id"]?.lowercase() &&
                    (existing.params["packetization-mode"] ?: "1") == (fallback.params["packetization-mode"] ?: "1")
            }
            if (!dup) {
                list.add(fallback)
            }
        }
        return list.toTypedArray()
    }
}

/**
 * Direct-to-Surface hardware VideoDecoder eliminating SurfaceTexture & GLES swaps for rock-solid 60 FPS.
 */
class DirectSurfaceVideoDecoder(
    private val codecName: String,
    private val mimeType: String,
    private val surfaceProvider: DirectSurfaceProvider
) : VideoDecoder {

    companion object {
        private const val TAG = "DirectSurfaceDecoder"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 25_000L
        private const val SURFACE_WAIT_MS = 500L

        private val SHARED_TINY_I420: JavaI420Buffer by lazy {
            JavaI420Buffer.allocate(16, 16)
        }
    }

    private data class FrameMeta(
        val decodeStartElapsedMs: Long,
        val captureTimeNs: Long
    )

    private class StubVideoFrameBuffer(
        private val frameWidth: Int,
        private val frameHeight: Int
    ) : VideoFrame.Buffer {
        private val refCount = AtomicInteger(1)

        override fun getWidth(): Int = frameWidth.coerceAtLeast(2)
        override fun getHeight(): Int = frameHeight.coerceAtLeast(2)

        override fun toI420(): VideoFrame.I420Buffer {
            SHARED_TINY_I420.retain()
            return SHARED_TINY_I420
        }

        override fun retain() {
            refCount.incrementAndGet()
        }

        override fun release() {
            refCount.decrementAndGet()
        }

        override fun cropAndScale(
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            scaleWidth: Int,
            scaleHeight: Int
        ): VideoFrame.Buffer = StubVideoFrameBuffer(scaleWidth, scaleHeight)
    }

    private val codecLock = Any()
    @Volatile
    private var codec: MediaCodec? = null
    @Volatile
    private var callback: VideoDecoder.Callback? = null
    @Volatile
    private var running = false
    @Volatile
    private var keyFrameRequired = true
    @Volatile
    private var width = 0
    @Volatile
    private var height = 0
    @Volatile
    private var firstRenderedNotified = false
    @Volatile
    private var boundToRealSurface = false

    private val dummySurfaceLock = Any()
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private var outputThread: Thread? = null
    private val pendingMetaByPtsUs = java.util.concurrent.ConcurrentHashMap<Long, FrameMeta>()

    override fun getImplementationName(): String = codecName

    override fun initDecode(settings: VideoDecoder.Settings?, callback: VideoDecoder.Callback?): VideoCodecStatus {
        if (settings == null || callback == null) return VideoCodecStatus.ERR_PARAMETER
        this.callback = callback
        return initDecodeInternal(settings.width.coerceAtLeast(16), settings.height.coerceAtLeast(16))
    }

    private fun ensureDummySurface(): Surface = synchronized(dummySurfaceLock) {
        val existing = dummySurface
        if (existing != null && existing.isValid) return existing
        val st = SurfaceTexture(0)
        st.setDefaultBufferSize(16, 16)
        val surf = Surface(st)
        dummySurfaceTexture = st
        dummySurface = surf
        return surf
    }

    private fun releaseDummySurface() = synchronized(dummySurfaceLock) {
        try {
            dummySurface?.release()
        } catch (_: Exception) {}
        try {
            dummySurfaceTexture?.release()
        } catch (_: Exception) {}
        dummySurface = null
        dummySurfaceTexture = null
    }

    private fun initDecodeInternal(targetWidth: Int, targetHeight: Int): VideoCodecStatus {
        releaseCodecOnly()

        var targetSurface = surfaceProvider.getDirectSurface() ?: surfaceProvider.awaitDirectSurface(SURFACE_WAIT_MS)
        val usingReal = targetSurface != null && targetSurface.isValid
        if (!usingReal) {
            targetSurface = ensureDummySurface()
            Log.w(TAG, "Real Surface not ready within ${SURFACE_WAIT_MS}ms; starting on offscreen dummy Surface until ready")
        }

        synchronized(codecLock) {
            width = targetWidth
            height = targetHeight
            keyFrameRequired = true
            firstRenderedNotified = false
            pendingMetaByPtsUs.clear()

            try {
                val mediaCodec = MediaCodec.createByCodecName(codecName)
                val maxInputSize = maxOf(targetWidth * targetHeight, 1024 * 1024)
                val baselineFormat = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                val tunedFormat = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setInteger(MediaFormat.KEY_LATENCY, 0)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } else {
                        setInteger("low-latency", 1)
                    }
                    // Vendor low-latency hints for Fire OS 6/7/8 SoCs (MediaTek/Amlogic/Realtek/Qualcomm)
                    setInteger("vdec-lowlatency", 1)
                    setInteger("vendor.low-latency.enable", 1)
                    setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                    setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                }
                try {
                    mediaCodec.configure(tunedFormat, targetSurface, null, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Low-latency decoder hints rejected by $codecName (${e.message}), falling back")
                    runCatching { mediaCodec.reset() }
                    mediaCodec.configure(baselineFormat, targetSurface, null, 0)
                }
                mediaCodec.start()

                codec = mediaCodec
                boundToRealSurface = usingReal
                running = true

                surfaceProvider.registerSurfaceConsumer(
                    onAvailable = { realSurface ->
                        switchOutputSurface(realSurface)
                    },
                    onDestroyed = {
                        switchOutputSurface(ensureDummySurface(), isReal = false)
                    }
                )

                outputThread = Thread({ drainOutputLoop() }, "DirectSurfaceDecoder.out").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                Log.i(TAG, "initDecodeInternal ready: codec=$codecName ${targetWidth}x$targetHeight realSurface=$usingReal")
                return VideoCodecStatus.OK
            } catch (e: Exception) {
                Log.e(TAG, "initDecodeInternal failed for $codecName (${targetWidth}x$targetHeight): ${e.message}", e)
                releaseCodecOnly()
                return VideoCodecStatus.FALLBACK_SOFTWARE
            }
        }
    }

    private fun switchOutputSurface(surface: Surface, isReal: Boolean = true) {
        synchronized(codecLock) {
            val activeCodec = codec ?: return
            if (!running || !surface.isValid) return
            try {
                activeCodec.setOutputSurface(surface)
                boundToRealSurface = isReal
                Log.i(TAG, "setOutputSurface swapped (isReal=$isReal)")
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface failed (isReal=$isReal): ${e.message}")
            }
        }
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        if (frame == null) return VideoCodecStatus.ERR_PARAMETER
        val activeCodec = codec
        if (activeCodec == null || callback == null || !running) {
            return VideoCodecStatus.UNINITIALIZED
        }
        val src = frame.buffer ?: return VideoCodecStatus.ERR_PARAMETER
        if (src.remaining() == 0) {
            return VideoCodecStatus.ERR_PARAMETER
        }

        if (frame.encodedWidth > 0 && frame.encodedHeight > 0 &&
            (frame.encodedWidth != width || frame.encodedHeight != height)
        ) {
            Log.i(TAG, "Resolution changed ${width}x$height -> ${frame.encodedWidth}x${frame.encodedHeight}; reinitializing")
            val status = initDecodeInternal(frame.encodedWidth, frame.encodedHeight)
            if (status != VideoCodecStatus.OK) return status
        }

        if (keyFrameRequired) {
            if (frame.frameType != EncodedImage.FrameType.VideoFrameKey) {
                return VideoCodecStatus.NO_OUTPUT
            }
        }

        val currentCodec = codec ?: return VideoCodecStatus.UNINITIALIZED
        var inputIndex = -1
        for (attempt in 0 until 4) {
            if (!running) return VideoCodecStatus.UNINITIALIZED
            inputIndex = try {
                currentCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            } catch (e: Exception) {
                Log.e(TAG, "dequeueInputBuffer failed: ${e.message}")
                return VideoCodecStatus.ERROR
            }
            if (inputIndex >= 0) break
        }
        if (inputIndex < 0) {
            Log.w(TAG, "decode() - no input buffer available")
            return VideoCodecStatus.NO_OUTPUT
        }

        return try {
            val inputBuffer: ByteBuffer = currentCodec.getInputBuffer(inputIndex) ?: return VideoCodecStatus.ERROR
            val payload = src.duplicate()
            val size = payload.remaining()
            if (inputBuffer.capacity() < size) {
                currentCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                return VideoCodecStatus.ERROR
            }
            inputBuffer.clear()
            inputBuffer.put(payload)

            val ptsUs = TimeUnit.NANOSECONDS.toMicros(frame.captureTimeNs)
            val flags = if (frame.frameType == EncodedImage.FrameType.VideoFrameKey) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            if (pendingMetaByPtsUs.size > 180) {
                pendingMetaByPtsUs.clear()
            }
            pendingMetaByPtsUs[ptsUs] = FrameMeta(SystemClock.elapsedRealtime(), frame.captureTimeNs)
            currentCodec.queueInputBuffer(inputIndex, 0, size, ptsUs, flags)
            keyFrameRequired = false
            VideoCodecStatus.OK
        } catch (e: Exception) {
            Log.e(TAG, "queueInputBuffer failed: ${e.message}", e)
            VideoCodecStatus.ERROR
        }
    }

    private fun drainOutputLoop() {
        val info = MediaCodec.BufferInfo()
        var decodedSinceLog = 0
        var renderedSinceLog = 0
        var decodeMsSum = 0L
        var lastStatsLogNs = System.nanoTime()

        while (running) {
            val activeCodec = codec ?: break
            try {
                val outIndex = activeCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = activeCodec.outputFormat
                    val outW = if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left")) {
                        fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                    } else {
                        fmt.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    val outH = if (fmt.containsKey("crop-bottom") && fmt.containsKey("crop-top")) {
                        fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                    } else {
                        fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (outW > 0 && outH > 0) {
                        width = outW
                        height = outH
                        surfaceProvider.onDirectVideoSizeChanged(outW, outH)
                        Log.i(TAG, "Output format changed to ${outW}x$outH")
                    }
                } else if (outIndex >= 0) {
                    val meta = pendingMetaByPtsUs.remove(info.presentationTimeUs)
                    val decodeTimeMs = meta?.let {
                        (SystemClock.elapsedRealtime() - it.decodeStartElapsedMs).toInt().coerceAtLeast(0)
                    }
                    if (!boundToRealSurface) {
                        val realSurf = surfaceProvider.getDirectSurface()
                        if (realSurf != null && realSurf.isValid) {
                            switchOutputSurface(realSurf, isReal = true)
                        }
                    }

                    val shouldRender = !surfaceProvider.isStreamPaused() && boundToRealSurface
                    activeCodec.releaseOutputBuffer(outIndex, shouldRender)

                    decodedSinceLog++
                    if (shouldRender) renderedSinceLog++
                    decodeMsSum += (decodeTimeMs ?: 0)

                    if (shouldRender && !firstRenderedNotified) {
                        firstRenderedNotified = true
                        surfaceProvider.onDirectFirstFrameRendered()
                    }

                    val cb = callback
                    if (cb != null) {
                        val stub = StubVideoFrameBuffer(width, height)
                        val captureNs = meta?.captureTimeNs ?: TimeUnit.MICROSECONDS.toNanos(info.presentationTimeUs)
                        val vf = VideoFrame(stub, 0, captureNs)
                        cb.onDecodedFrame(vf, decodeTimeMs, null)
                        vf.release()
                    }

                    val nowNs = System.nanoTime()
                    val elapsedNs = nowNs - lastStatsLogNs
                    if (elapsedNs >= 2_000_000_000L) {
                        val sec = elapsedNs / 1_000_000_000.0
                        val decFps = decodedSinceLog / sec
                        val renFps = renderedSinceLog / sec
                        val avgDecMs = if (decodedSinceLog > 0) decodeMsSum.toDouble() / decodedSinceLog else 0.0
                        Log.i(
                            TAG,
                            "DirectSurface stats: decodedFps=%.1f renderedFps=%.1f res=%dx%d avgDecodeMs=%.2f paused=%s realSurface=%s"
                                .format(decFps, renFps, width, height, avgDecMs, surfaceProvider.isStreamPaused(), boundToRealSurface)
                        )
                        decodedSinceLog = 0
                        renderedSinceLog = 0
                        decodeMsSum = 0L
                        lastStatsLogNs = nowNs
                    }
                }
            } catch (e: IllegalStateException) {
                if (running) {
                    Log.w(TAG, "IllegalState in drainOutputLoop: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Error in drainOutputLoop: ${e.message}")
                }
            }
        }
    }

    private fun releaseCodecOnly() {
        running = false
        surfaceProvider.unregisterSurfaceConsumer()
        val threadToJoin = outputThread
        outputThread = null
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(1000)
            } catch (_: InterruptedException) {}
        }
        synchronized(codecLock) {
            val c = codec
            codec = null
            boundToRealSurface = false
            if (c != null) {
                try {
                    c.stop()
                } catch (_: Exception) {}
                try {
                    c.release()
                } catch (_: Exception) {}
            }
            pendingMetaByPtsUs.clear()
        }
    }

    override fun release(): VideoCodecStatus {
        releaseCodecOnly()
        releaseDummySurface()
        callback = null
        return VideoCodecStatus.OK
    }
}
