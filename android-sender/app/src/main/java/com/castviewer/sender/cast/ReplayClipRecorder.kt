package com.castviewer.sender.cast

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Annex-B H.264 parser and circular clip-window slicer (unit-tested on JVM).
 */
internal object ReplayClipLogic {

    private val ANNEX_B_PREFIX = byteArrayOf(0, 0, 0, 1)

    data class AnnexBParsed(
        val sps: ByteArray?,
        val pps: ByteArray?,
        val containsIdr: Boolean,
        val containsVcl: Boolean
    )

    data class VideoFrameEntry(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val monotonicNs: Long,
        val isKeyFrame: Boolean,
        val containsVcl: Boolean,
        val sps: ByteArray?,
        val pps: ByteArray?
    )

    data class SelectedReplayVideo(
        val frames: List<VideoFrameEntry>,
        val sps: ByteArray,
        val pps: ByteArray,
        val width: Int,
        val height: Int,
        val durationMs: Long
    )

    fun parseAnnexB(data: ByteArray): AnnexBParsed {
        if (data.size < 4) {
            return AnnexBParsed(sps = null, pps = null, containsIdr = false, containsVcl = false)
        }

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var containsIdr = false
        var containsVcl = false

        var pos = 0
        while (pos < data.size) {
            val sc = findStartCode(data, pos)
            if (sc < 0) break
            val scLen = if (sc + 3 < data.size && data[sc + 2] == 0.toByte() && data[sc + 3] == 1.toByte()) 4 else 3
            val nalStart = sc + scLen
            if (nalStart >= data.size) break

            val nextSc = findStartCode(data, nalStart)
            val nalEnd = if (nextSc >= 0) nextSc else data.size
            val nalLen = nalEnd - nalStart

            if (nalLen > 0) {
                val nalType = data[nalStart].toInt() and 0x1F
                when (nalType) {
                    7 -> sps = wrapWithStartCode(data, nalStart, nalLen)
                    8 -> pps = wrapWithStartCode(data, nalStart, nalLen)
                    5 -> {
                        containsIdr = true
                        containsVcl = true
                    }
                    1 -> containsVcl = true
                }
            }
            pos = nalEnd
        }

        return AnnexBParsed(
            sps = sps,
            pps = pps,
            containsIdr = containsIdr,
            containsVcl = containsVcl
        )
    }

    fun selectReplaySlice(
        frames: List<VideoFrameEntry>,
        targetWindowMs: Long = 30_000L
    ): SelectedReplayVideo? {
        if (frames.isEmpty()) return null
        val lastFrame = frames.last()
        val targetW = lastFrame.width
        val targetH = lastFrame.height
        if (targetW <= 0 || targetH <= 0) return null

        // Isolate contiguous tail matching latest resolution so MediaMuxer format remains consistent.
        var tailStart = frames.lastIndex
        while (tailStart > 0) {
            val prev = frames[tailStart - 1]
            if (prev.width != targetW || prev.height != targetH) break
            tailStart--
        }

        val tail = frames.subList(tailStart, frames.size)
        if (tail.isEmpty()) return null

        val cutoffNs = lastFrame.monotonicNs - targetWindowMs * 1_000_000L
        val maxSpanNs = (targetWindowMs + 8_000L) * 1_000_000L

        var bestBeforeCutoffIdx = -1
        var earliestKeyIdx = -1

        var rollingSps: ByteArray? = null
        var rollingPps: ByteArray? = null
        var bestBeforeSps: ByteArray? = null
        var bestBeforePps: ByteArray? = null
        var earliestSps: ByteArray? = null
        var earliestPps: ByteArray? = null

        for (i in tail.indices) {
            val f = tail[i]
            if (f.sps != null) rollingSps = f.sps
            if (f.pps != null) rollingPps = f.pps

            if (f.isKeyFrame && f.containsVcl && rollingSps != null && rollingPps != null) {
                if (earliestKeyIdx < 0) {
                    earliestKeyIdx = i
                    earliestSps = rollingSps
                    earliestPps = rollingPps
                }
                val span = lastFrame.monotonicNs - f.monotonicNs
                if (f.monotonicNs <= cutoffNs && span <= maxSpanNs) {
                    bestBeforeCutoffIdx = i
                    bestBeforeSps = rollingSps
                    bestBeforePps = rollingPps
                }
            }
        }

        val startIdx: Int
        val startSps: ByteArray?
        val startPps: ByteArray?
        if (bestBeforeCutoffIdx >= 0) {
            startIdx = bestBeforeCutoffIdx
            startSps = bestBeforeSps
            startPps = bestBeforePps
        } else {
            startIdx = earliestKeyIdx
            startSps = earliestSps
            startPps = earliestPps
        }

        if (startIdx < 0 || startSps == null || startPps == null) return null

        val selected = tail.subList(startIdx, tail.size).filter { it.containsVcl }
        if (selected.isEmpty()) return null

        val durationMs = ((selected.last().monotonicNs - selected.first().monotonicNs) / 1_000_000L)
            .coerceAtLeast(1L)

        return SelectedReplayVideo(
            frames = selected,
            sps = startSps,
            pps = startPps,
            width = targetW,
            height = targetH,
            durationMs = durationMs
        )
    }

    private fun findStartCode(data: ByteArray, fromIndex: Int): Int {
        var i = fromIndex
        val limit = data.size - 3
        while (i <= limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    return i
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun wrapWithStartCode(data: ByteArray, offset: Int, len: Int): ByteArray {
        val out = ByteArray(ANNEX_B_PREFIX.size + len)
        System.arraycopy(ANNEX_B_PREFIX, 0, out, 0, ANNEX_B_PREFIX.size)
        System.arraycopy(data, offset, out, ANNEX_B_PREFIX.size, len)
        return out
    }

    /**
     * Builds a 1-frame [SelectedReplayVideo] from the latest cached keyframe + SPS/PPS headers
     * so 1-tap screenshots work instantly even if the 30s rolling buffer is disabled.
     */
    fun selectLatestKeyframeForScreenshot(
        latestKeyframe: VideoFrameEntry?,
        fallbackSps: ByteArray?,
        fallbackPps: ByteArray?
    ): SelectedReplayVideo? {
        val frame = latestKeyframe ?: return null
        if (!frame.isKeyFrame || frame.data.isEmpty() || frame.width <= 0 || frame.height <= 0) {
            return null
        }
        val sps = frame.sps ?: fallbackSps ?: return null
        val pps = frame.pps ?: fallbackPps ?: return null
        return SelectedReplayVideo(
            frames = listOf(frame),
            sps = sps,
            pps = pps,
            width = frame.width,
            height = frame.height,
            durationMs = 0L
        )
    }
}

/**
 * Zero-video-reencode 30-second rolling replay buffer.
 * Copies already-encoded hardware H.264 packets via [TeeReplayVideoEncoder] directly into RAM,
 * and muxes into Gallery (`Movies/CastViewer`) on demand on a background thread.
 */
object ReplayClipRecorder {

    private const val TAG = "ReplayClipRecorder"
    private const val TARGET_WINDOW_MS = 30_000L
    private const val BUFFER_RETENTION_MS = 35_500L
    private const val MAX_VIDEO_BYTES_NORMAL = 28L * 1024L * 1024L
    private const val MAX_VIDEO_BYTES_LOW_RAM = 14L * 1024L * 1024L
    private const val MAX_AUDIO_BYTES_NORMAL = 6L * 1024L * 1024L
    private const val MAX_AUDIO_BYTES_LOW_RAM = 5L * 1024L * 1024L

    private data class AudioChunkEntry(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val monotonicNs: Long
    )

    private data class EncodedAacSample(
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    private data class EncodedAacTrack(
        val format: MediaFormat,
        val samples: List<EncodedAacSample>
    )

    @Volatile
    private var captureEnabled: Boolean = true

    @Volatile
    private var lowRam: Boolean = false

    @Volatile
    private var streamingActive: Boolean = false

    @Volatile
    private var savingClip: Boolean = false

    @Volatile
    private var savingShot: Boolean = false

    private val forceKeyframeRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class PendingVideoPacket(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val isKeyFrameFlag: Boolean,
        val timestampNs: Long,
        val epoch: Long
    )

    private val videoLock = Any()
    private val audioLock = Any()
    private val videoBuffer = ArrayDeque<ReplayClipLogic.VideoFrameEntry>(512)
    private var videoBytesTotal: Long = 0L
    private val audioBuffer = ArrayDeque<AudioChunkEntry>(512)
    private var audioBytesTotal: Long = 0L

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var lastVideoMonotonicNs: Long = 0L
    private var lastAudioMonotonicNs: Long = 0L
    private var latestKeyframe: ReplayClipLogic.VideoFrameEntry? = null

    private val sessionEpoch = AtomicLong(1L)
    private val pendingVideoQueue = ArrayBlockingQueue<PendingVideoPacket>(192)
    private val ingestThread = Thread({
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {}
        while (true) {
            try {
                val pkt = pendingVideoQueue.take()
                ingestVideoPacket(pkt)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Replay ingest error: ${t.message}")
            }
        }
    }, "CastViewer-ReplayIngest").apply {
        priority = Thread.MIN_PRIORITY + 2
        isDaemon = true
        start()
    }

    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CastViewer-ClipSaver").apply {
            priority = Thread.MIN_PRIORITY + 1
            isDaemon = true
        }
    }

    fun isStreamingActive(): Boolean = streamingActive

    fun isCaptureActive(): Boolean = captureEnabled && streamingActive

    fun isAudioCaptureActive(): Boolean = captureEnabled && streamingActive

    internal fun getAudioBufferStatsForTest(): Pair<Int, Long> =
        synchronized(audioLock) { audioBuffer.size to audioBytesTotal }

    fun isCurrentlySaving(): Boolean = savingClip

    fun isCurrentlySavingShot(): Boolean = savingShot

    fun requestNextKeyframe() {
        forceKeyframeRequested.set(true)
    }

    fun consumeForceKeyframeRequest(): Boolean = forceKeyframeRequested.getAndSet(false)

    fun configure(enabled: Boolean, lowRamMode: Boolean) {
        captureEnabled = enabled
        lowRam = lowRamMode
        if (!enabled) {
            pendingVideoQueue.clear()
            synchronized(videoLock) {
                videoBuffer.clear()
                videoBytesTotal = 0L
            }
            synchronized(audioLock) {
                audioBuffer.clear()
                audioBytesTotal = 0L
            }
            return
        }
        if (lowRamMode) {
            val nowNs = System.nanoTime()
            synchronized(videoLock) {
                pruneVideoLocked(nowNs)
            }
            synchronized(audioLock) {
                pruneAudioLocked(nowNs)
            }
        }
    }

    fun setSessionActive(active: Boolean) {
        streamingActive = active
        if (!active) {
            clearBuffers()
        }
    }

    fun clearBuffers() {
        sessionEpoch.incrementAndGet()
        pendingVideoQueue.clear()
        synchronized(videoLock) {
            videoBuffer.clear()
            videoBytesTotal = 0L
            cachedSps = null
            cachedPps = null
            cachedWidth = 0
            cachedHeight = 0
            lastVideoMonotonicNs = 0L
            latestKeyframe = null
        }
        synchronized(audioLock) {
            audioBuffer.clear()
            audioBytesTotal = 0L
            lastAudioMonotonicNs = 0L
        }
    }

    /**
     * Non-blocking O(1) enqueue so hardware WebRTC VideoEncoder callback never parses Annex-B or blocks on locks.
     */
    fun appendH264Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        isKeyFrameFlag: Boolean,
        timestampNs: Long = System.nanoTime()
    ) {
        if (!streamingActive || data.size < 4 || width <= 0 || height <= 0) return
        if (!captureEnabled && !isKeyFrameFlag) return

        val packet = PendingVideoPacket(
            data = data,
            width = width,
            height = height,
            isKeyFrameFlag = isKeyFrameFlag,
            timestampNs = timestampNs,
            epoch = sessionEpoch.get()
        )
        // Touch lazy ingestThread reference so daemon is active before first frame arrives.
        ingestThread.isAlive
        if (!pendingVideoQueue.offer(packet)) {
            pendingVideoQueue.poll()
            pendingVideoQueue.offer(packet)
        }
    }

    private fun parseAnnexBFastOrFull(
        data: ByteArray,
        isKeyFrameFlag: Boolean,
        hasCachedParams: Boolean
    ): ReplayClipLogic.AnnexBParsed {
        if (!isKeyFrameFlag && hasCachedParams && data.size >= 5) {
            val nalType = when {
                data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1 ->
                    data[4].toInt() and 0x1F
                data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1 ->
                    data[3].toInt() and 0x1F
                else -> -1
            }
            if (nalType in 1..4) {
                return ReplayClipLogic.AnnexBParsed(
                    sps = null,
                    pps = null,
                    containsIdr = false,
                    containsVcl = true
                )
            }
        }
        return ReplayClipLogic.parseAnnexB(data)
    }

    private fun ingestVideoPacket(packet: PendingVideoPacket) {
        if (!streamingActive || packet.epoch != sessionEpoch.get()) return
        val data = packet.data
        val width = packet.width
        val height = packet.height
        val hasCached = width == cachedWidth && height == cachedHeight && cachedSps != null && cachedPps != null
        val parsed = parseAnnexBFastOrFull(data, packet.isKeyFrameFlag, hasCached)
        if (!parsed.containsVcl && parsed.sps == null && parsed.pps == null) return

        synchronized(videoLock) {
            if (!streamingActive || packet.epoch != sessionEpoch.get()) return

            if (width != cachedWidth || height != cachedHeight) {
                cachedSps = parsed.sps
                cachedPps = parsed.pps
                cachedWidth = width
                cachedHeight = height
                latestKeyframe = null
            } else {
                if (parsed.sps != null) cachedSps = parsed.sps
                if (parsed.pps != null) cachedPps = parsed.pps
            }

            val isKey = packet.isKeyFrameFlag || parsed.containsIdr
            val shouldKeepRolling = captureEnabled
            if (!shouldKeepRolling && !isKey) return

            val monoNs = nextVideoMonotonicNsLocked(packet.timestampNs)
            val entry = ReplayClipLogic.VideoFrameEntry(
                data = data,
                width = width,
                height = height,
                monotonicNs = monoNs,
                isKeyFrame = isKey,
                containsVcl = parsed.containsVcl,
                sps = cachedSps,
                pps = cachedPps
            )
            if (isKey && parsed.containsVcl) {
                latestKeyframe = entry
            }
            if (shouldKeepRolling) {
                videoBuffer.addLast(entry)
                videoBytesTotal += data.size.toLong()
                pruneVideoLocked(monoNs)
            }
        }
    }

    fun appendAudioPcm(
        pcmBuffer: ByteArray,
        bytesLength: Int,
        sampleRate: Int,
        channels: Int,
        timestampNs: Long = System.nanoTime()
    ) {
        if (!isAudioCaptureActive() || bytesLength <= 0 || bytesLength > pcmBuffer.size) return
        val copy = ByteArray(bytesLength)
        System.arraycopy(pcmBuffer, 0, copy, 0, bytesLength)

        synchronized(audioLock) {
            if (!isAudioCaptureActive()) return
            val monoNs = nextAudioMonotonicNsLocked(timestampNs)
            audioBuffer.addLast(
                AudioChunkEntry(
                    pcm = copy,
                    sampleRate = sampleRate,
                    channels = channels,
                    monotonicNs = monoNs
                )
            )
            audioBytesTotal += bytesLength.toLong()
            pruneAudioLocked(monoNs)
        }
    }

    fun saveLastClipAsync(
        context: Context,
        onStateChanged: (saving: Boolean, message: String?) -> Unit,
        onFinished: (success: Boolean, message: String) -> Unit
    ) {
        if (!captureEnabled) {
            onFinished(false, "Ative 'Clip Replay 30s' em Ajustes para usar este recurso.")
            return
        }
        if (savingClip) {
            onStateChanged(true, "Salvando clip anterior...")
            return
        }

        val appContext = context.applicationContext
        val videoSnapshot: List<ReplayClipLogic.VideoFrameEntry>
        val audioSnapshot: List<AudioChunkEntry>
        synchronized(videoLock) {
            if (videoBuffer.isEmpty()) {
                onFinished(false, "Sem quadros H.264 em buffer ainda (use codec H.264 e aguarde alguns segundos).")
                return
            }
            videoSnapshot = videoBuffer.toList()
        }
        audioSnapshot = synchronized(audioLock) {
            audioBuffer.toList()
        }

        val selected = ReplayClipLogic.selectReplaySlice(videoSnapshot, TARGET_WINDOW_MS)
        if (selected == null || selected.frames.size < 8) {
            onFinished(false, "Buffer insuficiente ainda — aguarde ~5s de transmissão para gerar o clip.")
            return
        }

        savingClip = true
        onStateChanged(true, "Exportando últimos ${(selected.durationMs / 1000L).coerceAtLeast(1L)}s...")

        saveExecutor.execute {
            var tempFile: File? = null
            try {
                val clipStartNs = selected.frames.first().monotonicNs
                val clipEndNs = selected.frames.last().monotonicNs

                val matchingAudio = audioSnapshot.filter { chunk ->
                    chunk.monotonicNs in (clipStartNs - 40_000_000L)..(clipEndNs + 40_000_000L)
                }

                val aacTrack = if (matchingAudio.isNotEmpty()) {
                    encodeAudioToAacSafely(matchingAudio, clipStartNs)
                } else {
                    null
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "CastViewer_Clip_${timestamp}.mp4"
                tempFile = File(appContext.cacheDir, fileName)
                if (tempFile.exists()) tempFile.delete()

                muxToMp4(tempFile, selected, clipStartNs, aacTrack)

                val savedLocation = exportMp4ToGallery(appContext, tempFile, fileName)
                val durationSec = ((selected.durationMs + 500L) / 1000L).coerceAtLeast(1L)
                onFinished(true, "Clip salvo (${durationSec}s): $savedLocation")
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao salvar clip: ${e.message}", e)
                onFinished(false, "Não foi possível salvar clip: ${e.message ?: "erro de muxer"}")
            } finally {
                tempFile?.delete()
                savingClip = false
            }
        }
    }

    private fun nextVideoMonotonicNsLocked(candidateNs: Long): Long {
        val next = if (candidateNs > lastVideoMonotonicNs) candidateNs else lastVideoMonotonicNs + 1_000L
        lastVideoMonotonicNs = next
        return next
    }

    private fun nextAudioMonotonicNsLocked(candidateNs: Long): Long {
        val next = if (candidateNs > lastAudioMonotonicNs) candidateNs else lastAudioMonotonicNs + 1_000L
        lastAudioMonotonicNs = next
        return next
    }

    private fun pruneVideoLocked(latestNs: Long) {
        val minKeepNs = latestNs - BUFFER_RETENTION_MS * 1_000_000L
        val maxVideoBytes = if (lowRam) MAX_VIDEO_BYTES_LOW_RAM else MAX_VIDEO_BYTES_NORMAL

        while (videoBuffer.size > 2 &&
            (videoBuffer.first().monotonicNs < minKeepNs || videoBytesTotal > maxVideoBytes)
        ) {
            val removed = videoBuffer.removeFirst()
            videoBytesTotal = (videoBytesTotal - removed.data.size.toLong()).coerceAtLeast(0L)
        }
    }

    private fun pruneAudioLocked(latestNs: Long) {
        val minKeepNs = latestNs - BUFFER_RETENTION_MS * 1_000_000L
        val maxAudioBytes = if (lowRam) MAX_AUDIO_BYTES_LOW_RAM else MAX_AUDIO_BYTES_NORMAL
        while (audioBuffer.size > 2 &&
            (audioBuffer.first().monotonicNs < minKeepNs || audioBytesTotal > maxAudioBytes)
        ) {
            val removed = audioBuffer.removeFirst()
            audioBytesTotal = (audioBytesTotal - removed.pcm.size.toLong()).coerceAtLeast(0L)
        }
    }

    private fun muxToMp4(
        outputFile: File,
        selected: ReplayClipLogic.SelectedReplayVideo,
        clipStartNs: Long,
        aacTrack: EncodedAacTrack?
    ) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                selected.width,
                selected.height
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(selected.sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(selected.pps))
            }

            val videoTrackIdx = muxer.addTrack(videoFormat)
            val audioTrackIdx = if (aacTrack != null && aacTrack.samples.isNotEmpty()) {
                muxer.addTrack(aacTrack.format)
            } else {
                -1
            }

            muxer.start()
            muxerStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            var lastVideoPtsUs = -1L
            for (frame in selected.frames) {
                val rawPtsUs = ((frame.monotonicNs - clipStartNs) / 1_000L).coerceAtLeast(0L)
                val ptsUs = if (rawPtsUs <= lastVideoPtsUs) lastVideoPtsUs + 1_000L else rawPtsUs
                lastVideoPtsUs = ptsUs

                bufferInfo.offset = 0
                bufferInfo.size = frame.data.size
                bufferInfo.presentationTimeUs = ptsUs
                bufferInfo.flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

                muxer.writeSampleData(videoTrackIdx, ByteBuffer.wrap(frame.data), bufferInfo)
            }

            if (audioTrackIdx >= 0 && aacTrack != null) {
                var lastAudioPtsUs = -1L
                for (sample in aacTrack.samples) {
                    val ptsUs = if (sample.ptsUs <= lastAudioPtsUs) lastAudioPtsUs + 100L else sample.ptsUs
                    lastAudioPtsUs = ptsUs
                    bufferInfo.offset = 0
                    bufferInfo.size = sample.data.size
                    bufferInfo.presentationTimeUs = ptsUs
                    bufferInfo.flags = sample.flags
                    muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(sample.data), bufferInfo)
                }
            }
        } finally {
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun encodeAudioToAacSafely(
        chunks: List<AudioChunkEntry>,
        clipStartNs: Long
    ): EncodedAacTrack? {
        val sampleRate = chunks.first().sampleRate.takeIf { it > 0 } ?: 48000
        val channels = chunks.first().channels.coerceIn(1, 2)
        val filtered = chunks.filter { it.sampleRate == sampleRate && it.channels == channels }
        if (filtered.isEmpty()) return null

        var encoder: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            var outputFormat: MediaFormat? = null
            val samples = ArrayList<EncodedAacSample>(filtered.size + 32)
            var inputDone = false
            var outputDone = false
            var eosDrainMisses = 0
            var chunkIndex = 0
            var chunkOffset = 0
            var bytesPerFrame = channels * 2

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        if (chunkIndex >= filtered.size) {
                            encoder.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val inBuf = encoder.getInputBuffer(inIdx)
                            val chunk = filtered[chunkIndex]
                            val chunkBasePtsUs = ((chunk.monotonicNs - clipStartNs) / 1_000L)
                                .coerceAtLeast(0L) +
                                (chunkOffset.toLong() * 1_000_000L) / (sampleRate.toLong() * bytesPerFrame)

                            val available = chunk.pcm.size - chunkOffset
                            val capacity = inBuf?.remaining() ?: available
                            val toCopy = minOf(available, capacity)
                            inBuf?.clear()
                            inBuf?.put(chunk.pcm, chunkOffset, toCopy)
                            encoder.queueInputBuffer(inIdx, 0, toCopy, chunkBasePtsUs, 0)

                            chunkOffset += toCopy
                            if (chunkOffset >= chunk.pcm.size) {
                                chunkIndex++
                                chunkOffset = 0
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = encoder.outputFormat
                    }
                    outIdx >= 0 -> {
                        eosDrainMisses = 0
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && info.size > 0 && !isConfig) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val copy = ByteArray(info.size)
                            outBuf.get(copy)
                            samples.add(
                                EncodedAacSample(
                                    data = copy,
                                    ptsUs = info.presentationTimeUs.coerceAtLeast(0L),
                                    flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                                )
                            )
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            eosDrainMisses++
                            // Give hardware/software AAC encoder bounded time to flush tail frames before giving up on EOS
                            if (eosDrainMisses >= 40) {
                                break
                            }
                        }
                    }
                }
            }

            if (outputFormat != null && samples.isNotEmpty()) {
                EncodedAacTrack(outputFormat, samples)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio AAC mux skipped, saving video-only clip: ${e.message}")
            null
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun exportMp4ToGallery(context: Context, tempFile: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CastViewer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Falha ao criar entrada no MediaStore")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 32 * 1024)
                    }
                } ?: throw IllegalStateException("OutputStream nulo no MediaStore")

                val publish = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                return "Movies/CastViewer/$fileName"
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
        } else {
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?.let { File(it, "CastViewer") }
                ?: File(context.filesDir, "CastViewer")
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, fileName)
            tempFile.inputStream().use { input ->
                outFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("video/mp4"), null)
            return outFile.name
        }
    }

    fun saveKeyframeScreenshotAsync(
        context: Context,
        onProgress: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (!streamingActive) {
            onFinished(false, "Inicie o espelhamento para capturar um screenshot.")
            return
        }
        if (savingShot) {
            onFinished(false, "Já salvando o screenshot anterior...")
            return
        }

        val initialSnapshot = snapshotLatestKeyframeSlice()
        if (initialSnapshot == null) {
            requestNextKeyframe()
        }

        savingShot = true
        val appCtx = context.applicationContext
        onProgress(
            if (initialSnapshot != null) "Extraindo frame-chave da transmissão..."
            else "Solicitando próximo frame-chave I-frame..."
        )

        saveExecutor.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "CastViewer_Shot_$stamp.png"
            val tempMp4 = File(appCtx.cacheDir, "cast_shot_$stamp.mp4")
            var bitmap: android.graphics.Bitmap? = null
            val retriever = android.media.MediaMetadataRetriever()

            try {
                var snapshot = initialSnapshot
                if (snapshot == null) {
                    val deadline = System.currentTimeMillis() + 1800L
                    while (snapshot == null && System.currentTimeMillis() < deadline && streamingActive) {
                        Thread.sleep(100L)
                        snapshot = snapshotLatestKeyframeSlice()
                    }
                }
                if (snapshot == null) {
                    throw IllegalStateException("Nenhum frame-chave H.264 recebido (desative Modo Compatibilidade).")
                }

                muxSingleKeyframeMp4(tempMp4, snapshot)
                retriever.setDataSource(tempMp4.absolutePath)
                bitmap = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                    ?: throw IllegalStateException("Decodificador não extraiu o frame H.264")

                val savedDesc = exportBitmapPngToGallery(appCtx, bitmap, fileName)
                onFinished(true, "Screenshot salvo em $savedDesc")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save keyframe screenshot: ${e.message}", e)
                onFinished(false, "Falha ao capturar screenshot: ${e.message ?: "erro interno"}")
            } finally {
                savingShot = false
                try {
                    bitmap?.recycle()
                } catch (_: Exception) {
                }
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
                try {
                    if (tempMp4.exists()) tempMp4.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun snapshotLatestKeyframeSlice(): ReplayClipLogic.SelectedReplayVideo? {
        synchronized(videoLock) {
            val fallbackSlice = videoBuffer.lastOrNull { it.isKeyFrame && it.containsVcl }
            val kf = latestKeyframe ?: fallbackSlice
            val sps = kf?.sps ?: cachedSps
            val pps = kf?.pps ?: cachedPps
            return ReplayClipLogic.selectLatestKeyframeForScreenshot(kf, sps, pps)
        }
    }

    private fun muxSingleKeyframeMp4(outputFile: File, slice: ReplayClipLogic.SelectedReplayVideo) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                slice.width,
                slice.height
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(slice.sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(slice.pps))
            }
            val trackIdx = muxer.addTrack(videoFormat)
            muxer.start()
            muxerStarted = true

            val frame = slice.frames.first()
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = frame.data.size
                presentationTimeUs = 0L
                flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            muxer.writeSampleData(trackIdx, ByteBuffer.wrap(frame.data), info)
        } finally {
            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun exportBitmapPngToGallery(
        context: Context,
        bitmap: android.graphics.Bitmap,
        fileName: String
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CastViewer")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Falha ao criar imagem no MediaStore")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException("Falha ao codificar PNG")
                    }
                } ?: throw IllegalStateException("OutputStream nulo no MediaStore")

                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                return "Pictures/CastViewer/$fileName"
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
        } else {
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?.let { File(it, "CastViewer") }
                ?: File(context.filesDir, "CastViewer")
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, fileName)
            outFile.outputStream().use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Falha ao codificar PNG")
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("image/png"), null)
            return outFile.name
        }
    }
}
