package com.castviewer.sender.cast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.castviewer.sender.model.AudioCaptureMode
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Replaces WebRTC's AudioRecord PCM payload with Android 10+ AudioPlaybackCapture audio
 * whenever [AudioCaptureMode.INTERNAL] is active, muting to pure zeros while paused or offline
 * so room microphone audio never leaks to the TV.
 */
class AudioPlaybackCapturer(
    private val context: Context,
    initialMode: AudioCaptureMode
) : AudioRecordDataCallback {

    private val modeRef = AtomicReference(initialMode)
    private val pausedRef = AtomicBoolean(false)
    private val projectionRef = AtomicReference<MediaProjection?>(null)
    private val released = AtomicBoolean(false)

    private val lock = Any()
    private var ringBuffer = ByteArray(0)
    private var ringReadPos = 0
    private var ringWritePos = 0
    private var ringAvailable = 0

    private var activeSampleRate = 0
    private var activeChannelCount = 0
    private var playbackRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    private val readerRunning = AtomicBoolean(false)
    private var zeroScratch = ByteArray(0)
    private var ringPrimed = false

    var onInternalCaptureUnavailable: ((String) -> Unit)? = null
    private var unavailableNotified = false

    fun attachProjection(projection: MediaProjection?) {
        projectionRef.set(projection)
    }

    fun setMode(mode: AudioCaptureMode) {
        val prev = modeRef.getAndSet(mode)
        if (prev != mode) {
            clearRing()
            if (mode != AudioCaptureMode.INTERNAL) {
                stopPlaybackCapture()
            }
        }
    }

    fun setPaused(paused: Boolean) {
        pausedRef.set(paused)
        if (paused) {
            clearRing()
        }
    }

    fun release() {
        released.set(true)
        projectionRef.set(null)
        stopPlaybackCapture()
    }

    override fun onAudioDataRecorded(
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        byteBuffer: ByteBuffer
    ) {
        val targetBytes = byteBuffer.capacity()
        if (targetBytes <= 0) return

        val mode = modeRef.get()
        if (released.get() || pausedRef.get() || mode == AudioCaptureMode.OFF) {
            writeZeros(byteBuffer, targetBytes)
            return
        }

        if (mode == AudioCaptureMode.MIC) {
            if (ReplayClipRecorder.isAudioCaptureActive()) {
                val view = byteBuffer.duplicate()
                view.clear()
                val pcmCopy = ByteArray(view.remaining())
                view.get(pcmCopy)
                ReplayClipRecorder.appendAudioPcm(pcmCopy, pcmCopy.size, sampleRate, channelCount)
            }
            return
        }

        // AudioCaptureMode.INTERNAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!unavailableNotified) {
                unavailableNotified = true
                onInternalCaptureUnavailable?.invoke("Áudio interno exige Android 10 ou superior; usando mudo.")
            }
            writeZeros(byteBuffer, targetBytes)
            return
        }

        val projection = projectionRef.get()
        if (projection == null) {
            writeZeros(byteBuffer, targetBytes)
            return
        }

        ensurePlaybackCaptureStarted(projection, sampleRate, channelCount, targetBytes)
        drainIntoWebRtcBuffer(byteBuffer, targetBytes)
    }

    private fun ensurePlaybackCaptureStarted(
        projection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
        chunkBytes: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (readerRunning.get() && activeSampleRate == sampleRate && activeChannelCount == channelCount) {
            return
        }

        synchronized(lock) {
            if (readerRunning.get() && activeSampleRate == sampleRate && activeChannelCount == channelCount) {
                return
            }
            stopPlaybackCaptureLocked()

            val hasRecordPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasRecordPerm) {
                if (!unavailableNotified) {
                    unavailableNotified = true
                    onInternalCaptureUnavailable?.invoke("Permissão de áudio negada; áudio interno silenciado.")
                }
                return
            }

            try {
                val channelMask = if (channelCount >= 2) {
                    AudioFormat.CHANNEL_IN_STEREO
                } else {
                    AudioFormat.CHANNEL_IN_MONO
                }

                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()

                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(chunkBytes * 4)

                @SuppressLint("MissingPermission")
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 6))
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    if (!unavailableNotified) {
                        unavailableNotified = true
                        onInternalCaptureUnavailable?.invoke("Este aparelho não liberou captura de áudio interno.")
                    }
                    return
                }

                // Cap ring buffer at 40ms (4x 10ms frames) so audio latency stays locked near zero
                val ringCap = (chunkBytes * 4).coerceAtLeast(4096)
                ringBuffer = ByteArray(ringCap)
                ringReadPos = 0
                ringWritePos = 0
                ringAvailable = 0
                ringPrimed = false

                activeSampleRate = sampleRate
                activeChannelCount = channelCount
                playbackRecord = record
                readerRunning.set(true)
                record.startRecording()

                val thread = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val readChunk = ByteArray(chunkBytes)
                    while (readerRunning.get() && !released.get()) {
                        val read = try {
                            record.read(readChunk, 0, readChunk.size, AudioRecord.READ_BLOCKING)
                        } catch (_: Exception) {
                            -1
                        }
                        if (read > 0) {
                            if (ReplayClipRecorder.isAudioCaptureActive()) {
                                ReplayClipRecorder.appendAudioPcm(readChunk, read, sampleRate, channelCount)
                            }
                            pushRing(readChunk, read)
                        } else if (read == 0) {
                            try {
                                Thread.sleep(2)
                            } catch (_: InterruptedException) {
                                break
                            }
                        } else {
                            break
                        }
                    }
                }, "CastPlaybackAudio")
                thread.isDaemon = true
                readerThread = thread
                thread.start()
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlaybackCapture init failed: ${e.message}")
                stopPlaybackCaptureLocked()
                if (!unavailableNotified) {
                    unavailableNotified = true
                    onInternalCaptureUnavailable?.invoke("Áudio interno indisponível neste dispositivo.")
                }
            }
        }
    }

    private fun pushRing(src: ByteArray, len: Int) {
        synchronized(lock) {
            val cap = ringBuffer.size
            if (cap == 0) return
            val clamped = len.coerceAtMost(cap)

            // Drop oldest frames when exceeding capacity so latency never accumulates
            val overflow = (ringAvailable + clamped) - cap
            if (overflow > 0) {
                ringReadPos = (ringReadPos + overflow) % cap
                ringAvailable -= overflow
            }

            val firstCopy = minOf(clamped, cap - ringWritePos)
            System.arraycopy(src, 0, ringBuffer, ringWritePos, firstCopy)
            val remaining = clamped - firstCopy
            if (remaining > 0) {
                System.arraycopy(src, firstCopy, ringBuffer, 0, remaining)
            }
            ringWritePos = (ringWritePos + clamped) % cap
            ringAvailable += clamped
        }
    }

    private fun drainIntoWebRtcBuffer(dest: ByteBuffer, requested: Int) {
        if (zeroScratch.size < requested) {
            zeroScratch = ByteArray(requested)
        }
        val out = zeroScratch
        java.util.Arrays.fill(out, 0, requested, 0.toByte())

        synchronized(lock) {
            val cap = ringBuffer.size
            val primeThreshold = requested * 2
            if (!ringPrimed) {
                if (ringAvailable >= primeThreshold) {
                    ringPrimed = true
                }
            }
            if (ringPrimed && cap > 0 && ringAvailable > 0) {
                val toCopy = minOf(requested, ringAvailable)
                val firstPart = minOf(toCopy, cap - ringReadPos)
                System.arraycopy(ringBuffer, ringReadPos, out, 0, firstPart)
                val remaining = toCopy - firstPart
                if (remaining > 0) {
                    System.arraycopy(ringBuffer, 0, out, firstPart, remaining)
                }
                ringReadPos = (ringReadPos + toCopy) % cap
                ringAvailable -= toCopy
                if (ringAvailable == 0) {
                    ringPrimed = false
                }
            }
        }

        val view = dest.duplicate()
        view.clear()
        view.put(out, 0, requested)
    }

    private fun writeZeros(dest: ByteBuffer, requested: Int) {
        if (zeroScratch.size < requested) {
            zeroScratch = ByteArray(requested)
        }
        java.util.Arrays.fill(zeroScratch, 0, requested, 0.toByte())
        val view = dest.duplicate()
        view.clear()
        view.put(zeroScratch, 0, requested)
    }

    private fun clearRing() {
        synchronized(lock) {
            ringReadPos = 0
            ringWritePos = 0
            ringAvailable = 0
            ringPrimed = false
        }
    }

    private fun stopPlaybackCapture() {
        synchronized(lock) {
            stopPlaybackCaptureLocked()
        }
    }

    private fun stopPlaybackCaptureLocked() {
        readerRunning.set(false)
        val record = playbackRecord
        playbackRecord = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        readerThread?.interrupt()
        readerThread = null
        ringReadPos = 0
        ringWritePos = 0
        ringAvailable = 0
        activeSampleRate = 0
        activeChannelCount = 0
    }

    companion object {
        private const val TAG = "AudioPlaybackCapturer"
    }
}
