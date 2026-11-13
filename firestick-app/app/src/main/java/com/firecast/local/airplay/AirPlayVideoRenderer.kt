package com.firecast.local.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Direct-to-Surface hardware [MediaCodec] video decoder for AirPlay H.264 (AVC) and H.265 (HEVC).
 *
 * `raop_rtp_mirror.c` delivers Annex-B NAL units with prepended SPS/PPS/VPS before IDR keyframes,
 * which we submit directly to the hardware decoder on a dedicated high-priority thread so mirror
 * TCP socket reads never stall on decoder queue contention.
 */
class AirPlayVideoRenderer(
    private val onVideoDimensionsChanged: (width: Int, height: Int) -> Unit,
    private val onFirstFrameRendered: () -> Unit
) {

    companion object {
        private const val TAG = "AirPlayVideoRenderer"
        private const val MAX_QUEUED_FRAMES = 45
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        private val SOFTWARE_DECODER_PREFIXES = listOf(
            "omx.google.",
            "c2.android.",
            "omx.qcom.video.decoder.hevcswvdec",
            "omx.sec.hevc.sw.dec"
        )

        fun isSoftwareDecoderName(name: String): Boolean {
            val lower = name.lowercase()
            return SOFTWARE_DECODER_PREFIXES.any { lower.startsWith(it) || lower.contains(it) }
        }

        fun hasHardwareHevcDecoder(): Boolean = selectDecoderName(MediaFormat.MIMETYPE_VIDEO_HEVC) != null

        internal fun selectDecoderName(mimeType: String): String? {
            return try {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                var fallbackHardware: String? = null
                for (info in infos) {
                    if (info.isEncoder) continue
                    if (info.name.lowercase().endsWith(".secure")) continue
                    if (!info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) continue
                    if (isSoftwareDecoderName(info.name)) continue
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (info.isHardwareAccelerated) return info.name
                    } else {
                        return info.name
                    }
                    if (fallbackHardware == null) fallbackHardware = info.name
                }
                fallbackHardware
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to enumerate MediaCodec decoders for $mimeType", t)
                null
            }
        }
    }

    private data class EncodedFrame(
        val data: ByteArray,
        val ptsUs: Long,
        val isH265: Boolean
    )

    private val queueLock = Object()
    private val frameQueue = ArrayDeque<EncodedFrame>(MAX_QUEUED_FRAMES)

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var surfaceChanged = false

    @Volatile
    private var resetRequested = false

    @Volatile
    private var hintWidth = 1920

    @Volatile
    private var hintHeight = 1080

    private var lastKeyframe: EncodedFrame? = null
    private var firstFrameNotified = false

    // Worker-thread-owned codec state
    private var codec: MediaCodec? = null
    private var activeIsH265: Boolean? = null
    private var waitingForKeyframe = true
    private val bufferInfo = MediaCodec.BufferInfo()
    private var reportedWidth = 0
    private var reportedHeight = 0

    fun setSurface(surface: Surface?) {
        synchronized(queueLock) {
            if (currentSurface === surface) return
            currentSurface = surface
            surfaceChanged = true
            if (surface == null) {
                resetRequested = true
            }
            queueLock.notifyAll()
        }
    }

    fun updateStreamSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        hintWidth = width
        hintHeight = height
        if (width != reportedWidth || height != reportedHeight) {
            reportedWidth = width
            reportedHeight = height
            onVideoDimensionsChanged(width, height)
        }
    }

    fun submitFrame(data: ByteArray, ptsUs: Long, isH265: Boolean) {
        if (data.isEmpty()) return
        val frame = EncodedFrame(data, ptsUs, isH265)
        val isKeyframe = AirPlayAspectMath.isKeyframeAnnexB(data, isH265)

        synchronized(queueLock) {
            if (!running) {
                startWorkerLocked()
            }
            if (isKeyframe) {
                lastKeyframe = frame
            }

            if (frameQueue.size >= MAX_QUEUED_FRAMES) {
                // Keep latency low on congested receivers by shedding non-key frames up to latest IDR.
                var dropped = false
                val iter = frameQueue.iterator()
                while (iter.hasNext()) {
                    val queued = iter.next()
                    if (!AirPlayAspectMath.isKeyframeAnnexB(queued.data, queued.isH265)) {
                        iter.remove()
                        dropped = true
                        break
                    }
                }
                if (!dropped) {
                    frameQueue.pollFirst()
                }
            }

            frameQueue.addLast(frame)
            queueLock.notifyAll()
        }
    }

    fun pausePlayback() {
        // Decoder remains initialized cleanly so resume on IDR is instant.
    }

    fun resumePlayback() {
        synchronized(queueLock) {
            queueLock.notifyAll()
        }
    }

    fun resetSession() {
        synchronized(queueLock) {
            frameQueue.clear()
            lastKeyframe = null
            firstFrameNotified = false
            resetRequested = true
            reportedWidth = 0
            reportedHeight = 0
            queueLock.notifyAll()
        }
    }

    fun release() {
        val threadToJoin: Thread?
        synchronized(queueLock) {
            running = false
            frameQueue.clear()
            lastKeyframe = null
            currentSurface = null
            resetRequested = true
            queueLock.notifyAll()
            threadToJoin = workerThread
            workerThread = null
        }

        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun startWorkerLocked() {
        if (running) return
        running = true
        workerThread = thread(
            start = true,
            isDaemon = true,
            name = "AirPlayVideoDecoder",
            priority = Process.THREAD_PRIORITY_DISPLAY.coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
        ) {
            runDecoderLoop()
        }
    }

    private fun runDecoderLoop() {
        while (true) {
            var nextFrame: EncodedFrame? = null
            var surface: Surface?
            var needsSurfaceUpdate: Boolean
            var needsReset: Boolean

            synchronized(queueLock) {
                while (running && frameQueue.isEmpty() && !resetRequested && (!surfaceChanged || currentSurface == null)) {
                    queueLock.wait(250)
                }
                if (!running) {
                    releaseCodecOnWorker()
                    return
                }

                surface = currentSurface
                needsSurfaceUpdate = surfaceChanged
                surfaceChanged = false
                needsReset = resetRequested
                resetRequested = false

                if (!needsReset && surface != null && surface!!.isValid) {
                    if (frameQueue.isEmpty() && codec == null && lastKeyframe != null) {
                        // Surface became ready after the first IDR already arrived: replay it now.
                        nextFrame = lastKeyframe
                    } else {
                        nextFrame = frameQueue.pollFirst()
                    }
                }
            }

            if (needsReset) {
                releaseCodecOnWorker()
                waitingForKeyframe = true
            }

            val validSurface = surface?.takeIf { it.isValid }
            if (validSurface == null) {
                continue
            }

            if (needsSurfaceUpdate && codec != null) {
                try {
                    codec?.setOutputSurface(validSurface)
                } catch (t: Throwable) {
                    Log.w(TAG, "setOutputSurface failed, recreating MediaCodec: ${t.message}")
                    releaseCodecOnWorker()
                    waitingForKeyframe = true
                }
            }

            val frame = nextFrame ?: continue

            if (codec == null || activeIsH265 != frame.isH265) {
                val initFrame = if (AirPlayAspectMath.isKeyframeAnnexB(frame.data, frame.isH265)) {
                    frame
                } else {
                    lastKeyframe?.takeIf { it.isH265 == frame.isH265 }
                }

                if (initFrame == null) {
                    continue
                }

                if (!initCodecOnWorker(validSurface, frame.isH265)) {
                    continue
                }
                if (initFrame !== frame) {
                    feedFrameOnWorker(initFrame)
                }
            }

            if (waitingForKeyframe && !AirPlayAspectMath.isKeyframeAnnexB(frame.data, frame.isH265)) {
                val key = lastKeyframe?.takeIf { it.isH265 == frame.isH265 } ?: continue
                feedFrameOnWorker(key)
            }

            feedFrameOnWorker(frame)
            drainOutputsOnWorker()
        }
    }

    private fun initCodecOnWorker(surface: Surface, isH265: Boolean): Boolean {
        releaseCodecOnWorker()
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoderName = selectDecoderName(mime)

        val candidates = listOf(true, false)
        for (lowLatency in candidates) {
            var created: MediaCodec? = null
            try {
                created = if (decoderName != null) {
                    MediaCodec.createByCodecName(decoderName)
                } else {
                    MediaCodec.createDecoderByType(mime)
                }

                val format = MediaFormat.createVideoFormat(
                    mime,
                    hintWidth.coerceAtLeast(1280),
                    hintHeight.coerceAtLeast(720)
                ).apply {
                    val nameLower = (decoderName ?: "").lowercase()
                    val isMtkOrIntel = nameLower.startsWith("omx.mtk") || nameLower.startsWith("omx.intel")
                    if (!isMtkOrIntel && !nameLower.contains("sw")) {
                        // Adaptive playback avoids restarts on portrait <-> landscape rotations where supported.
                        setInteger(MediaFormat.KEY_MAX_WIDTH, 3840)
                        setInteger(MediaFormat.KEY_MAX_HEIGHT, 3840)
                    }
                    if (lowLatency) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                        }
                    }
                }

                created.configure(format, surface, null, 0)
                created.start()
                codec = created
                activeIsH265 = isH265
                waitingForKeyframe = false
                Log.i(TAG, "Configured AirPlay MediaCodec name=${created.name} mime=$mime lowLatency=$lowLatency")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Codec init attempt (lowLatency=$lowLatency, name=$decoderName) failed: ${t.message}")
                try {
                    created?.release()
                } catch (_: Throwable) {
                }
            }
        }
        return false
    }

    private fun feedFrameOnWorker(frame: EncodedFrame) {
        val activeCodec = codec ?: return
        try {
            val index = activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                val inputBuf = activeCodec.getInputBuffer(index)
                if (inputBuf != null) {
                    inputBuf.clear()
                    if (frame.data.size > inputBuf.remaining()) {
                        Log.w(TAG, "Dropping oversized NALU packet (${frame.data.size} > ${inputBuf.remaining()})")
                        activeCodec.queueInputBuffer(index, 0, 0, frame.ptsUs.coerceAtLeast(0L), 0)
                        return
                    }
                    inputBuf.put(frame.data)
                    activeCodec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs.coerceAtLeast(0L), 0)
                    waitingForKeyframe = false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MediaCodec queueInputBuffer failed: ${t.message}")
            releaseCodecOnWorker()
            waitingForKeyframe = true
        }
    }

    private fun drainOutputsOnWorker() {
        val activeCodec = codec ?: return
        try {
            while (true) {
                val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
                when {
                    outIndex >= 0 -> {
                        val render = bufferInfo.size > 0
                        activeCodec.releaseOutputBuffer(outIndex, render)
                        if (render && !firstFrameNotified) {
                            firstFrameNotified = true
                            onFirstFrameRendered()
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = activeCodec.outputFormat
                        val (w, h) = extractVisibleSize(outFmt)
                        if (w > 0 && h > 0 && (w != reportedWidth || h != reportedHeight)) {
                            reportedWidth = w
                            reportedHeight = h
                            onVideoDimensionsChanged(w, h)
                        }
                    }
                    else -> break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MediaCodec dequeueOutputBuffer failed: ${t.message}")
            releaseCodecOnWorker()
            waitingForKeyframe = true
        }
    }

    private fun extractVisibleSize(format: MediaFormat): Pair<Int, Int> {
        val hasCrop = format.containsKey("crop-left") &&
            format.containsKey("crop-right") &&
            format.containsKey("crop-top") &&
            format.containsKey("crop-bottom")
        if (hasCrop) {
            val w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            val h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            if (w > 0 && h > 0) return w to h
        }
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        return w to h
    }

    private fun releaseCodecOnWorker() {
        val activeCodec = codec ?: return
        codec = null
        activeIsH265 = null
        try {
            activeCodec.stop()
        } catch (_: Throwable) {
        }
        try {
            activeCodec.release()
        } catch (_: Throwable) {
        }
    }
}
