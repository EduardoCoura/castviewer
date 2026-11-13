package com.castviewer.sender.cast

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.castviewer.sender.model.AudioCaptureMode
import com.castviewer.sender.model.ImageMode
import com.castviewer.sender.model.PerformancePrefs
import com.castviewer.sender.model.TelemetryStats
import com.castviewer.sender.model.VideoPreset
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CastUniversalVideoEncoderFactory
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcSenderManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalOfferReady(sdp: String, width: Int, height: Int, audioEnabled: Boolean)
        fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onIceConnected()
        fun onIceDisconnectedOrFailed(reason: String)
        fun onProjectionRevokedBySystem()
        fun onTelemetryUpdated(stats: TelemetryStats)
        fun onWarning(message: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            } catch (_: Throwable) {}
            runnable.run()
        }, "CastWebRtc-Exec").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var universalEncoderFactory: CastUniversalVideoEncoderFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioPlaybackCapturer: AudioPlaybackCapturer? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    private var peerConnection: PeerConnection? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private var currentPrefs = PerformancePrefs()
    private var currentWidth = 1280
    private var currentHeight = 720
    private var isPaused = false

    private var prevBytesSent = 0L
    private var prevStatsTsMs = 0L
    private var statsTaskRunning = false

    fun start(
        mediaProjectionResultData: Intent,
        prefs: PerformancePrefs,
        initialWidth: Int,
        initialHeight: Int
    ) {
        currentPrefs = prefs
        currentWidth = initialWidth
        currentHeight = initialHeight

        executor.execute {
            if (disposed.get()) return@execute
            try {
                initializeFactoryAndCapture(mediaProjectionResultData, prefs, initialWidth, initialHeight)
                createPeerConnectionAndOffer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebRTC sender", e)
                listener.onError("Falha ao iniciar espelhamento: ${e.message ?: "erro interno"}")
            }
        }
    }

    fun onRemoteAnswer(sdp: String) {
        executor.execute {
            val pc = peerConnection ?: return@execute
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() {
                    executor.execute {
                        remoteDescriptionSet = true
                        pendingRemoteIce.forEach { pc.addIceCandidate(it) }
                        pendingRemoteIce.clear()
                    }
                }

                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    listener.onError("Falha ao aplicar resposta do Fire Stick: $error")
                }
            }, answer)
        }
    }

    fun onRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        executor.execute {
            val pc = peerConnection ?: return@execute
            val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            if (remoteDescriptionSet) {
                pc.addIceCandidate(ice)
            } else {
                pendingRemoteIce.add(ice)
            }
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        audioPlaybackCapturer?.setPaused(paused)
        executor.execute {
            if (!disposed.get()) {
                val captureFps = if (currentPrefs.preset.fps >= 60 && !currentPrefs.lowSpecMode) 120 else currentPrefs.preset.fps
                val targetFps = if (paused) 1 else captureFps
                videoSource?.adaptOutputFormat(currentWidth, currentHeight, targetFps)
            }
        }
    }

    fun setAudioMode(mode: AudioCaptureMode) {
        currentPrefs = currentPrefs.copy(audioMode = mode)
        audioPlaybackCapturer?.setMode(mode)
    }

    fun applyImageMode(mode: ImageMode) {
        currentPrefs = currentPrefs.copy(imageMode = mode)
        executor.execute {
            videoSource?.setIsScreencast(currentPrefs.preset.fps < 60 && mode == ImageMode.DETAIL)
            applyRtpEncodingParameters()
        }
    }

    fun applyVideoProfile(preset: VideoPreset, captureWidth: Int, captureHeight: Int) {
        currentPrefs = currentPrefs.copy(preset = preset)
        updateVideoCaptureSize(captureWidth, captureHeight, preset.fps)
    }

    fun updateVideoCaptureSize(width: Int, height: Int, fps: Int) {
        currentWidth = width
        currentHeight = height
        executor.execute {
            try {
                val adaptFps = if (fps >= 60 && !currentPrefs.lowSpecMode) 120 else fps
                videoSource?.setIsScreencast(fps < 60 && currentPrefs.imageMode == ImageMode.DETAIL)
                screenCapturer?.changeCaptureFormat(width, height, fps)
                videoSource?.adaptOutputFormat(width, height, adaptFps)
                applyRtpEncodingParameters()
            } catch (e: Exception) {
                Log.w(TAG, "updateVideoCaptureSize warning: ${e.message}")
            }
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        statsTaskRunning = false
        executor.execute {
            try {
                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null
            } catch (_: Exception) {
            }

            try {
                screenCapturer?.stopCapture()
            } catch (_: Exception) {
            }
            try {
                screenCapturer?.dispose()
                screenCapturer = null
            } catch (_: Exception) {
            }

            try {
                videoTrack?.dispose()
                videoTrack = null
                videoSource?.dispose()
                videoSource = null
            } catch (_: Exception) {
            }

            try {
                audioTrack?.dispose()
                audioTrack = null
                audioSource?.dispose()
                audioSource = null
            } catch (_: Exception) {
            }

            try {
                audioPlaybackCapturer?.release()
                audioPlaybackCapturer = null
                audioDeviceModule?.release()
                audioDeviceModule = null
            } catch (_: Exception) {
            }

            try {
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
            } catch (_: Exception) {
            }

            try {
                factory?.dispose()
                factory = null
            } catch (_: Exception) {
            }

            try {
                eglBase?.release()
                eglBase = null
            } catch (_: Exception) {
            }
        }
        executor.shutdown()
    }

    private fun initializeFactoryAndCapture(
        mediaProjectionResultData: Intent,
        prefs: PerformancePrefs,
        width: Int,
        height: Int
    ) {
        val fieldTrials = buildString {
            append("WebRTC-FlexFEC-03/Disabled/")
            append("WebRTC-Pacer-DrainQueue/Enabled/")
            append("WebRTC-Pacer-IgnoreTransportOverhead/Enabled/")
            if (prefs.latencyMode == com.castviewer.sender.model.LatencyMode.ULTRA) {
                append("WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:0/")
            } else {
                append("WebRTC-ForceSendPlayoutDelay/min_ms:30,max_ms:60/")
            }
        }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setFieldTrials(fieldTrials)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        PeerConnectionFactory.initializeFieldTrials(fieldTrials)

        val egl = EglBase.create()
        eglBase = egl

        val capturerCallback = AudioPlaybackCapturer(appContext, prefs.audioMode).apply {
            onInternalCaptureUnavailable = { reason -> listener.onWarning(reason) }
        }
        audioPlaybackCapturer = capturerCallback

        val hasRecordAudioPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val wantAudio = prefs.audioMode != AudioCaptureMode.OFF && hasRecordAudioPerm
        if (prefs.audioMode != AudioCaptureMode.OFF && !hasRecordAudioPerm) {
            listener.onWarning("Permissão de áudio não concedida; transmitindo apenas vídeo.")
        }

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setInputSampleRate(48000)
            .setUseStereoInput(true)
            .setUseStereoOutput(false)
            .setUseLowLatency(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordDataCallback(capturerCallback)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val encoderFactory = if (prefs.compatMode) {
            universalEncoderFactory = null
            SoftwareVideoEncoderFactory()
        } else {
            val universal = CastUniversalVideoEncoderFactory(egl.eglBaseContext)
            universalEncoderFactory = universal
            universal
        }
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        val pcFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        factory = pcFactory

        val screen = ScreenCapturerAndroid(
            mediaProjectionResultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!disposed.get()) {
                        listener.onProjectionRevokedBySystem()
                    }
                }
            }
        )
        screenCapturer = screen

        val sth = SurfaceTextureHelper.create("CastScreenThread", egl.eglBaseContext, true)
        surfaceTextureHelper = sth
        sth.handler.post {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Throwable) {}
        }

        // Disable WebRTC's legacy presentation-slides throttle on 60 FPS streams so games & video sustain full FPS
        val useScreencastMode = prefs.preset.fps < 60 && prefs.imageMode == ImageMode.DETAIL
        val vSource = pcFactory.createVideoSource(useScreencastMode)
        vSource.setIsScreencast(useScreencastMode)
        videoSource = vSource

        screen.initialize(sth, appContext, vSource.capturerObserver)
        screen.startCapture(width, height, prefs.preset.fps)
        val adaptFps = if (prefs.preset.fps >= 60 && !prefs.lowSpecMode) 120 else prefs.preset.fps
        vSource.adaptOutputFormat(width, height, adaptFps)

        // Attach live MediaProjection object so AudioPlaybackCapture can start capturing system sound
        capturerCallback.attachProjection(screen.mediaProjection)

        val vTrack = pcFactory.createVideoTrack("cast-video-0", vSource)
        vTrack.setEnabled(true)
        videoTrack = vTrack

        if (wantAudio) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            }
            val aSource = pcFactory.createAudioSource(audioConstraints)
            audioSource = aSource
            val aTrack = pcFactory.createAudioTrack("cast-audio-0", aSource)
            aTrack.setEnabled(true)
            audioTrack = aTrack
        }
    }

    private fun createPeerConnectionAndOffer() {
        val pcFactory = factory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            disableIPv6OnWifi = true
            enableDscp = true
            enableCpuOveruseDetection = currentPrefs.lowSpecMode
            suspendBelowMinBitrate = false
            screencastMinBitrate = computeTargetBitrateBps() / 2
        }

        val pc = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        listener.onIceConnected()
                        startTelemetryPolling()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        listener.onIceDisconnectedOrFailed("Conexão WebRTC falhou na rede local.")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        listener.onIceDisconnectedOrFailed("Conexão interrompida com o Fire Stick.")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {}
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    listener.onLocalIceCandidate(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: run {
            listener.onError("Falha ao criar PeerConnection.")
            return
        }

        peerConnection = pc
        val streamIds = listOf("castviewer-stream")
        videoSender = videoTrack?.let { pc.addTrack(it, streamIds) }
        audioTrack?.let { pc.addTrack(it, streamIds) }

        applyRtpEncodingParameters()

        val offerConstraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val rawSdp = desc?.description ?: return
                val targetBitrate = computeTargetBitrateBps()
                val optimizedSdp = SdpOptimizer.optimizeOfferSdp(
                    sdp = rawSdp,
                    targetBitrateBps = targetBitrate,
                    targetFps = currentPrefs.preset.fps,
                    preferH264 = !currentPrefs.compatMode
                )
                val optimizedDesc = SessionDescription(desc.type, optimizedSdp)
                executor.execute {
                    val activePc = peerConnection ?: return@execute
                    activePc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            listener.onLocalOfferReady(
                                sdp = optimizedSdp,
                                width = currentWidth,
                                height = currentHeight,
                                audioEnabled = audioTrack != null
                            )
                        }

                        override fun onCreateFailure(err: String?) {}
                        override fun onSetFailure(err: String?) {
                            listener.onError("Falha no SDP local: $err")
                        }
                    }, optimizedDesc)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                listener.onError("Falha ao gerar offer SDP: $err")
            }

            override fun onSetFailure(err: String?) {}
        }, offerConstraints)
    }

    private fun computeTargetBitrateBps(): Int {
        var bitrate = currentPrefs.preset.bitrateBps
        if (currentPrefs.imageMode == ImageMode.DETAIL) {
            bitrate = (bitrate * 1.30f).toInt()
        }
        if (currentPrefs.lowSpecMode) {
            bitrate = (bitrate * 0.78f).toInt().coerceAtLeast(2_200_000)
        }
        return bitrate.coerceIn(2_000_000, 25_000_000)
    }

    private fun applyRtpEncodingParameters() {
        val sender = videoSender ?: return
        try {
            val params = sender.parameters ?: return
            val targetBitrate = computeTargetBitrateBps()
            val minBitrate = (targetBitrate * 0.40f).toInt().coerceAtLeast(1_200_000)

            params.degradationPreference = if (currentPrefs.imageMode == ImageMode.MOTION || currentPrefs.preset.fps >= 60) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }

            params.encodings?.forEach { enc ->
                enc.maxBitrateBps = targetBitrate
                enc.minBitrateBps = minBitrate
                enc.maxFramerate = if (currentPrefs.preset.fps >= 60 && !currentPrefs.lowSpecMode) null else currentPrefs.preset.fps
                enc.numTemporalLayers = if (currentPrefs.preset.fps >= 60) 1 else null
                enc.scaleResolutionDownBy = 1.0
                enc.bitratePriority = 4.0
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "applyRtpEncodingParameters warning: ${e.message}")
        }
    }

    private fun startTelemetryPolling() {
        if (statsTaskRunning || disposed.get()) return
        statsTaskRunning = true
        scheduleNextTelemetryTick()
    }

    private fun scheduleNextTelemetryTick() {
        if (!statsTaskRunning || disposed.get()) return
        val intervalMs = if (currentPrefs.lowRamMode) 2500L else 1000L
        executor.schedule({
            if (!statsTaskRunning || disposed.get()) return@schedule
            val pc = peerConnection ?: return@schedule
            pc.getStats { report ->
                if (disposed.get()) return@getStats
                val statsMap = report.statsMap
                var rttMs: Int? = null
                var fps: Int? = null
                var width: Int? = null
                var height: Int? = null
                var bytesSent = 0L
                var codecName = if (currentPrefs.compatMode) "VP8 SW" else "H.264"

                val codecs = ConcurrentHashMap<String, String>()
                statsMap.values.forEach { stat ->
                    if (stat.type == "codec") {
                        val mime = stat.members["mimeType"]?.toString() ?: ""
                        if (mime.startsWith("video/", ignoreCase = true)) {
                            codecs[stat.id] = mime.substringAfter('/').uppercase()
                        }
                    }
                }

                statsMap.values.forEach { stat ->
                    when (stat.type) {
                        "outbound-rtp" -> {
                            val kind = stat.members["kind"]?.toString()
                                ?: stat.members["mediaType"]?.toString()
                            if (kind == "video") {
                                (stat.members["framesPerSecond"] as? Number)?.let { fps = it.toInt() }
                                (stat.members["frameWidth"] as? Number)?.let { width = it.toInt() }
                                (stat.members["frameHeight"] as? Number)?.let { height = it.toInt() }
                                (stat.members["bytesSent"] as? Number)?.let { bytesSent = it.toLong() }
                                val codecId = stat.members["codecId"]?.toString()
                                if (codecId != null && codecs[codecId] != null) {
                                    codecName = codecs[codecId]!!
                                }
                            }
                        }
                        "candidate-pair" -> {
                            val state = stat.members["state"]?.toString()
                            val nominated = stat.members["nominated"] as? Boolean ?: false
                            if (state == "succeeded" || nominated) {
                                (stat.members["currentRoundTripTime"] as? Number)?.let { rttSec ->
                                    rttMs = (rttSec.toDouble() * 1000.0).toInt()
                                }
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                var bitrateMbps: Float? = null
                if (prevStatsTsMs > 0 && bytesSent >= prevBytesSent) {
                    val deltaMs = (now - prevStatsTsMs).coerceAtLeast(250L)
                    val deltaBits = (bytesSent - prevBytesSent) * 8.0
                    bitrateMbps = (deltaBits / (deltaMs / 1000.0) / 1_000_000.0).toFloat()
                }
                prevBytesSent = bytesSent
                prevStatsTsMs = now

                val resLabel = if ((width ?: 0) > 0 && (height ?: 0) > 0) {
                    "${width}x${height}"
                } else {
                    "${currentWidth}x${currentHeight}"
                }

                val activeLabel = universalEncoderFactory?.activeCodecSummary ?: codecName

                mainHandler.post {
                    listener.onTelemetryUpdated(
                        TelemetryStats(
                            rttMs = rttMs,
                            fps = fps,
                            bitrateMbps = bitrateMbps,
                            resolution = resLabel,
                            codecLabel = activeLabel
                        )
                    )
                }
                scheduleNextTelemetryTick()
            }
        }, intervalMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "WebRtcSenderManager"
    }
}
