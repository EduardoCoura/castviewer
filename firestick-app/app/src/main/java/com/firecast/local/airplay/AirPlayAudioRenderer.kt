package com.firecast.local.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure JVM audio math & codec header helpers for AirPlay RAOP streams.
 */
object AirPlayAudioConfig {
    const val CT_PCM = 1
    const val CT_ALAC = 2
    const val CT_AAC_LC = 4
    const val CT_AAC_ELD = 8

    const val DEFAULT_SAMPLE_RATE = 44100
    const val DEFAULT_CHANNEL_COUNT = 2
    private const val AAC_PROFILE_LC = 2
    private const val AAC_PROFILE_ELD = 39

    /**
     * Converts Apple RAOP logarithmic gain (`[-30.0, 0.0]`, `-144.0` = mute) to linear `[0.0, 1.0]`.
     */
    fun dbToLinearGain(volumeDb: Float): Float {
        if (volumeDb.isNaN() || volumeDb <= -144.0f) return 0.0f
        if (volumeDb >= 0.0f) return 1.0f
        return 10.0f.pow(volumeDb / 20.0f).coerceIn(0.0f, 1.0f)
    }

    /**
     * Converts linear `[0.0, 1.0]` gain back into AirPlay dB scale for volume queries.
     */
    fun linearGainToDb(gain: Float): Float {
        if (gain.isNaN() || gain <= 0.0f) return -144.0f
        if (gain >= 1.0f) return 0.0f
        return (20.0f * log10(gain.coerceIn(0.0001f, 1.0f))).coerceAtLeast(-144.0f)
    }

    /**
     * Builds the ISO 14496-3 AudioSpecificConfig (`csd-0`) expected by Android [MediaCodec]
     * for AirPlay AAC-LC and AAC-ELD streams at 44100 Hz stereo.
     */
    fun buildAudioCsd0(codingType: Int, samplesPerFrame: Int): ByteArray? = when (codingType) {
        CT_AAC_LC -> {
            // AudioObjectType=2 (5b), SamplingFreqIndex=4/44.1kHz (4b), ChannelConfig=2 (4b), frameLenFlag (1b)
            if (samplesPerFrame == 960) byteArrayOf(0x12, 0x14) else byteArrayOf(0x12, 0x10)
        }
        CT_AAC_ELD -> {
            // AudioObjectType=31+8=39, 44.1kHz stereo ELD config (960/480 vs 1024/512)
            if (samplesPerFrame == 512) {
                byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x40, 0x00)
            } else {
                byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            }
        }
        else -> null
    }

    fun aacProfileForCodingType(codingType: Int): Int? = when (codingType) {
        CT_AAC_LC -> AAC_PROFILE_LC
        CT_AAC_ELD -> AAC_PROFILE_ELD
        else -> null
    }
}

/**
 * Plays AirPlay audio streams (`PCM` / native-decoded `ALAC`, `AAC-LC`, and `AAC-ELD`) onto
 * Android [AudioTrack] configured for Fire TV / HDMI `USAGE_MEDIA` routing.
 */
class AirPlayAudioRenderer {

    companion object {
        private const val TAG = "AirPlayAudioRenderer"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private val lock = Any()
    private val codecBufferInfo = MediaCodec.BufferInfo()

    private var audioTrack: AudioTrack? = null
    private var mediaCodec: MediaCodec? = null
    private var configuredCodingType: Int = 0
    private var configuredSpf: Int = 0
    private var sampleRate: Int = AirPlayAudioConfig.DEFAULT_SAMPLE_RATE
    private var channelCount: Int = AirPlayAudioConfig.DEFAULT_CHANNEL_COUNT

    private var volumeDb: Float = 0.0f
    private var isMuted: Boolean = false

    fun configureFormat(codingType: Int, samplesPerFrame: Int) {
        synchronized(lock) {
            val normalizedType = if (codingType == AirPlayAudioConfig.CT_ALAC) {
                // Native JNI already decodes ALAC -> interleaved S16LE PCM before delivering bytes.
                AirPlayAudioConfig.CT_PCM
            } else {
                codingType
            }

            if (configuredCodingType != normalizedType || configuredSpf != samplesPerFrame) {
                releaseCodecLocked()
                configuredCodingType = normalizedType
                configuredSpf = samplesPerFrame
                sampleRate = AirPlayAudioConfig.DEFAULT_SAMPLE_RATE
                channelCount = AirPlayAudioConfig.DEFAULT_CHANNEL_COUNT
            }

            ensureAudioTrackLocked(sampleRate, channelCount)
            if (normalizedType == AirPlayAudioConfig.CT_AAC_LC || normalizedType == AirPlayAudioConfig.CT_AAC_ELD) {
                ensureAacDecoderLocked(normalizedType, samplesPerFrame)
            }
        }
    }

    fun renderAudioData(data: ByteArray, codingType: Int, ptsUs: Long) {
        if (data.isEmpty()) return
        synchronized(lock) {
            val normalizedType = if (codingType == AirPlayAudioConfig.CT_ALAC) {
                AirPlayAudioConfig.CT_PCM
            } else {
                codingType
            }

            if (configuredCodingType != normalizedType || audioTrack == null) {
                configureFormat(
                    normalizedType,
                    if (configuredSpf > 0) configuredSpf else defaultSpfFor(normalizedType)
                )
            }

            if (normalizedType == AirPlayAudioConfig.CT_PCM) {
                writePcmLocked(data)
            } else if (normalizedType == AirPlayAudioConfig.CT_AAC_LC || normalizedType == AirPlayAudioConfig.CT_AAC_ELD) {
                decodeAacAndWriteLocked(data, ptsUs)
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            try {
                audioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                    track.play()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack flush warning: ${t.message}")
            }

            try {
                mediaCodec?.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "Audio MediaCodec flush warning: ${t.message}")
            }
        }
    }

    fun setVolumeDb(db: Float) {
        synchronized(lock) {
            volumeDb = db
            applyEffectiveGainLocked()
        }
    }

    fun getVolumeDb(): Float = synchronized(lock) { volumeDb }

    fun setMuted(muted: Boolean) {
        synchronized(lock) {
            isMuted = muted
            applyEffectiveGainLocked()
        }
    }

    fun isMuted(): Boolean = synchronized(lock) { isMuted }

    fun release() {
        synchronized(lock) {
            releaseCodecLocked()
            releaseAudioTrackLocked()
            configuredCodingType = 0
            configuredSpf = 0
        }
    }

    private fun defaultSpfFor(codingType: Int): Int = when (codingType) {
        AirPlayAudioConfig.CT_AAC_ELD -> 480
        AirPlayAudioConfig.CT_AAC_LC -> 1024
        else -> 352
    }

    private fun ensureAudioTrackLocked(rate: Int, channels: Int) {
        val existing = audioTrack
        if (existing != null && sampleRate == rate && channelCount == channels) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                existing.play()
            }
            return
        }

        releaseAudioTrackLocked()
        sampleRate = rate
        channelCount = channels

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(channelMask)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 2, 16384))
            .build()

        audioTrack = track
        applyEffectiveGainLocked()
        track.play()
        Log.i(TAG, "Started AirPlay AudioTrack rate=${rate}Hz ch=$channels buf=${max(minBuf * 2, 16384)}")
    }

    private fun ensureAacDecoderLocked(codingType: Int, samplesPerFrame: Int) {
        if (mediaCodec != null) return
        val csd0 = AirPlayAudioConfig.buildAudioCsd0(codingType, samplesPerFrame) ?: return
        val profile = AirPlayAudioConfig.aacProfileForCodingType(codingType) ?: return

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()
            mediaCodec = codec
            Log.i(TAG, "Started AirPlay AAC MediaCodec ct=$codingType profile=$profile spf=$samplesPerFrame")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize AAC decoder (ct=$codingType, spf=$samplesPerFrame)", t)
            releaseCodecLocked()
        }
    }

    private fun decodeAacAndWriteLocked(data: ByteArray, ptsUs: Long) {
        val codec = mediaCodec ?: run {
            ensureAacDecoderLocked(
                configuredCodingType,
                if (configuredSpf > 0) configuredSpf else defaultSpfFor(configuredCodingType)
            )
            mediaCodec ?: return
        }

        try {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    val count = data.size.coerceAtMost(inputBuf.remaining())
                    inputBuf.put(data, 0, count)
                    codec.queueInputBuffer(inputIndex, 0, count, ptsUs.coerceAtLeast(0L), 0)
                }
            }

            while (true) {
                val outIndex = codec.dequeueOutputBuffer(codecBufferInfo, 0L)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && codecBufferInfo.size > 0) {
                            outBuf.position(codecBufferInfo.offset)
                            outBuf.limit(codecBufferInfo.offset + codecBufferInfo.size)
                            val pcm = ByteArray(codecBufferInfo.size)
                            outBuf.get(pcm)
                            writePcmLocked(pcm)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val newRate = if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else {
                            sampleRate
                        }
                        val newChannels = if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else {
                            channelCount
                        }
                        ensureAudioTrackLocked(newRate, newChannels)
                    }
                    else -> break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error decoding AirPlay AAC packet: ${t.message}", t)
            releaseCodecLocked()
        }
    }

    private fun writePcmLocked(pcm: ByteArray) {
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                Log.w(TAG, "AudioTrack.write returned $written")
                break
            }
            offset += written
        }
    }

    private fun applyEffectiveGainLocked() {
        val track = audioTrack ?: return
        val gain = if (isMuted) 0.0f else AirPlayAudioConfig.dbToLinearGain(volumeDb)
        try {
            track.setVolume(gain)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to set AudioTrack volume: ${t.message}")
        }
    }

    private fun releaseCodecLocked() {
        val codec = mediaCodec ?: return
        mediaCodec = null
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    private fun releaseAudioTrackLocked() {
        val track = audioTrack ?: return
        audioTrack = null
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            track.flush()
            track.stop()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }
}
