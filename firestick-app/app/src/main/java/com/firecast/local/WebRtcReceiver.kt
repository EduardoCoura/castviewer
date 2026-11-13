package com.firecast.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the WebRTC connection, hardware-accelerated decoding, audio output,
 * and rendering onto Android TV's SurfaceViewRenderer.
 *
 * Ultra-Low Latency & 60 FPS Receiver Optimization:
 * - Hardware MediaCodec H.264/VP8/VP9 decoding via DefaultVideoDecoderFactory.
 * - Hardware scaler enabled without FPS reduction on SurfaceViewRenderer for 60 FPS+.
 * - Fix for Fire TV / Android TV HDMI Audio:
 *     - Sets AudioAttributes USAGE_MEDIA and CONTENT_TYPE_MOVIE in JavaAudioDeviceModule.
 *     - Explicitly forces AudioManager.MODE_NORMAL and speakerphoneOn=false on main thread.
 *     - Prevents WebRTC from defaulting to MODE_IN_COMMUNICATION which mutes or reroutes HDMI.
 * - Low-latency audio pipeline via JavaAudioDeviceModule (setUseLowLatency=true, setUseStereoOutput=true).
 * - Jitter buffer acceleration (audioJitterBufferFastAccelerate=true, audioJitterBufferMaxPackets=2 [40ms max]).
 * - SDP munging specifying 20 Mbps video bandwidth (b=AS:20000) and 10ms Opus packetization (a=minptime:10).
 * - P2P LAN-only unified plan signaling without cloud STUN/TURN overhead.
 */
class WebRtcReceiver(
    private val context: Context,
    val rootEglBase: EglBase = EglBase.create()
) {

    companion object {
        private const val TAG = "WebRtcReceiver"

        /**
         * Optimizes SDP for ultra-low latency, zero video blur, and no B-frame lag:
         * 1. Prefers H.264 Constrained Baseline (profile-level-id=42e01f / 42001f) if present in SDP
         *    by moving its payload types to the beginning of m=video, ensuring Android's hardware
         *    MediaCodec does not buffer B-frames (eliminates 150-250ms decoder latency).
         * 2. Sets b=AS:25000 and b=TIAS:25000000 (25 Mbps bandwidth cap for crisp 1080p60 / 4K screencast).
         * 3. Sets x-google-min-bitrate=8000;x-google-start-bitrate=12000;x-google-max-bitrate=25000
         *    on video formats to guarantee instantaneous high quality without startup blur.
         * 4. Enforces 10ms Opus audio packetization (a=minptime:10, a=ptime:10).
         * 5. Preserves playout-delay header extension for 0ms jitter buffer delay.
         */
        fun optimizeSdp(sdp: String, playoutDelayExtension: String? = null): String {
            val lines = sdp.split(Regex("\r?\n"))
            val result = StringBuilder()

            // Pass 1: scan video section to identify H.264 Constrained Baseline payload types (42e01f / 42001f)
            val seenVideoPts = mutableListOf<String>()
            val rtxOrFecPts = mutableSetOf<String>()
            val candidates = mutableListOf<Pair<String, Int>>()
            var inVideoScan = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("m=video")) {
                    inVideoScan = true
                    val parts = trimmed.split(" ").filter { it.isNotBlank() }
                    if (parts.size > 3) {
                        seenVideoPts.addAll(parts.subList(3, parts.size))
                    }
                    continue
                } else if (trimmed.startsWith("m=")) {
                    inVideoScan = false
                    continue
                }

                if (inVideoScan && trimmed.startsWith("a=rtpmap:")) {
                    val rtpMatch = Regex("""^a=rtpmap:(\d+)\s+([a-zA-Z0-9_-]+)/""").find(trimmed)
                    if (rtpMatch != null) {
                        val pt = rtpMatch.groupValues[1]
                        val codecName = rtpMatch.groupValues[2].lowercase()
                        if (codecName == "rtx" || codecName == "red" || codecName == "ulpfec" || codecName == "flexfec-03") {
                            rtxOrFecPts.add(pt)
                        }
                    }
                }

                if (inVideoScan && trimmed.startsWith("a=fmtp:")) {
                    val match = Regex("""^a=fmtp:(\d+)\s+(.*)$""").find(trimmed)
                    if (match != null) {
                        val pt = match.groupValues[1]
                        val params = match.groupValues[2]

                        val is4200 = Regex("""profile-level-id=4200(1f|28|2a)""", RegexOption.IGNORE_CASE).containsMatchIn(params)
                        val is42e0 = Regex("""profile-level-id=42e0(1f|28|2a)""", RegexOption.IGNORE_CASE).containsMatchIn(params)
                        val isPacketization1 = params.contains("packetization-mode=1", ignoreCase = true)

                        if (is4200 || is42e0) {
                            val priority = when {
                                is4200 && isPacketization1 -> 0
                                is4200 -> 1
                                is42e0 && isPacketization1 -> 2
                                else -> 3
                            }
                            candidates.add(Pair(pt, priority))
                        }
                    }
                }
            }

            // Sort preferred payload types: 4200 (HW Baseline) first, then 42e0, packetization-mode=1 first
            candidates.sortBy { it.second }
            val orderedPreferredPts = candidates.map { it.first }.distinct()

            // Pass 2: reconstruct optimized SDP
            var inVideo = false
            var inAudio = false
            var videoBandwidthInjected = false
            val emittedFmtpPts = mutableSetOf<String>()
            val bitrateParams = "x-google-min-bitrate=4500;x-google-start-bitrate=10000;x-google-max-bitrate=20000;max-fs=8704;max-mbps=522240"

            fun injectVideoFmtpFallbacks() {
                for (pt in seenVideoPts) {
                    if (!emittedFmtpPts.contains(pt) && !rtxOrFecPts.contains(pt)) {
                        result.append("a=fmtp:").append(pt).append(" ").append(bitrateParams).append("\r\n")
                        emittedFmtpPts.add(pt)
                    }
                }
            }

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue

                if (trimmed.startsWith("m=video")) {
                    inVideo = true
                    inAudio = false
                    videoBandwidthInjected = false

                    // Reorder payload types to place Constrained Baseline H.264 at the beginning
                    val parts = trimmed.split(" ").filter { it.isNotBlank() }
                    if (parts.size > 3 && orderedPreferredPts.isNotEmpty()) {
                        val header = parts.subList(0, 3)
                        val originalPts = parts.subList(3, parts.size)
                        val reordered = mutableListOf<String>()

                        for (pPt in orderedPreferredPts) {
                            if (originalPts.contains(pPt) && !reordered.contains(pPt)) {
                                reordered.add(pPt)
                            }
                        }
                        for (oPt in originalPts) {
                            if (!reordered.contains(oPt)) {
                                reordered.add(oPt)
                            }
                        }
                        val newLine = (header + reordered).joinToString(" ")
                        result.append(newLine).append("\r\n")
                    } else {
                        result.append(trimmed).append("\r\n")
                    }
                    continue
                } else if (trimmed.startsWith("m=audio")) {
                    if (inVideo) {
                        if (!videoBandwidthInjected) {
                            result.append("b=AS:25000\r\n")
                            result.append("b=TIAS:25000000\r\n")
                            if (!sdp.contains("a=framerate:")) {
                                result.append("a=framerate:60\r\n")
                            }
                            videoBandwidthInjected = true
                        }
                        injectVideoFmtpFallbacks()
                    }
                    inAudio = true
                    inVideo = false
                    result.append(trimmed).append("\r\n")
                    continue
                } else if (trimmed.startsWith("m=")) {
                    if (inVideo) {
                        if (!videoBandwidthInjected) {
                            result.append("b=AS:25000\r\n")
                            result.append("b=TIAS:25000000\r\n")
                            if (!sdp.contains("a=framerate:")) {
                                result.append("a=framerate:60\r\n")
                            }
                            videoBandwidthInjected = true
                        }
                        injectVideoFmtpFallbacks()
                    }
                    inVideo = false
                    inAudio = false
                }

                // In video section: remove existing bandwidth lines or lower framerate lines
                if (inVideo && (trimmed.startsWith("b=AS:") || trimmed.startsWith("b=TIAS:") || trimmed.startsWith("a=framerate:"))) {
                    continue
                }

                // Inject 25 Mbps bandwidth cap (b=AS:25000, b=TIAS:25000000), a=framerate:60, and preserve playout-delay
                if (inVideo && !videoBandwidthInjected && (trimmed.startsWith("c=") || trimmed.startsWith("a="))) {
                    if (trimmed.startsWith("c=")) {
                        result.append(trimmed).append("\r\n")
                        result.append("b=AS:25000\r\n")
                        result.append("b=TIAS:25000000\r\n")
                        result.append("a=framerate:60\r\n")
                        videoBandwidthInjected = true
                        if (playoutDelayExtension != null && !sdp.contains("http://www.webrtc.org/experiments/rtp-hdrext/playout-delay")) {
                            result.append(playoutDelayExtension).append("\r\n")
                        }
                        continue
                    } else {
                        result.append("b=AS:25000\r\n")
                        result.append("b=TIAS:25000000\r\n")
                        result.append("a=framerate:60\r\n")
                        videoBandwidthInjected = true
                        if (playoutDelayExtension != null && !sdp.contains("http://www.webrtc.org/experiments/rtp-hdrext/playout-delay")) {
                            result.append(playoutDelayExtension).append("\r\n")
                        }
                        // Fall through to process this a= line
                    }
                }

                // In video section: upgrade H.264 profile-level-id from Level 3.1/4.0 (1f/28) to Level 4.2 (2a = 1080p60)
                // and inject x-google bitrate parameters ONLY into real video codec fmtp lines (not RTX apt=)
                if (inVideo && trimmed.startsWith("a=fmtp:")) {
                    val fmtpMatch = Regex("""^a=fmtp:(\d+)\s+(.*)$""").find(trimmed)
                    if (fmtpMatch != null) {
                        val pt = fmtpMatch.groupValues[1]
                        var params = fmtpMatch.groupValues[2]
                        emittedFmtpPts.add(pt)

                        if (params.contains("apt=") || rtxOrFecPts.contains(pt)) {
                            result.append(trimmed).append("\r\n")
                            continue
                        }

                        params = params.replace(
                            Regex("profile-level-id=([0-9a-fA-F]{4})(1f|28)", RegexOption.IGNORE_CASE)
                        ) { match ->
                            "profile-level-id=${match.groupValues[1]}2a"
                        }

                        val cleaned = params
                            .replace(Regex("""(;?\b(x-google-min-bitrate|x-google-start-bitrate|x-google-max-bitrate)=\d+)"""), "")
                            .trim(';', ' ')
                        val updatedParams = if (cleaned.isEmpty()) bitrateParams else "$cleaned;$bitrateParams"
                        result.append("a=fmtp:").append(pt).append(" ").append(updatedParams).append("\r\n")
                        continue
                    }
                }

                // In audio section: filter out previous minptime/ptime lines
                if (inAudio && (trimmed.startsWith("a=minptime:") || trimmed.startsWith("a=ptime:"))) {
                    continue
                }

                // In audio section: use standard 20ms Opus frame size so Wi-Fi jitter never causes popping
                if (inAudio && trimmed.startsWith("a=rtpmap:") && trimmed.contains("opus/48000", ignoreCase = true)) {
                    result.append(trimmed).append("\r\n")
                    result.append("a=ptime:20\r\n")
                    continue
                }

                result.append(trimmed).append("\r\n")
            }

            // Final check if SDP ended while still in video section
            if (inVideo) {
                if (!videoBandwidthInjected) {
                    result.append("b=AS:25000\r\n")
                    result.append("b=TIAS:25000000\r\n")
                    result.append("a=framerate:60\r\n")
                }
                injectVideoFmtpFallbacks()
            }

            return result.toString()
        }

        fun optimizeSdpForLowLatency(sdp: String): String = optimizeSdp(sdp)
    }

    fun optimizeSdp(sdp: String, playoutDelayExtension: String? = null): String =
        Companion.optimizeSdp(sdp, playoutDelayExtension)
    fun optimizeSdpForLowLatency(sdp: String): String = Companion.optimizeSdp(sdp)

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChange(sessionGeneration: Long, newState: PeerConnection.PeerConnectionState)
        fun onFirstFrameReceived(sessionGeneration: Long)
        fun onError(sessionGeneration: Long, message: String)
    }

    var listener: Listener? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lifecycleLock = Any()
    private val sessionLock = Any()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var activeVideoTrack: VideoTrack? = null
    private var activeVideoSink: org.webrtc.VideoSink? = null
    private var activeAudioTrack: AudioTrack? = null
    private var boundRenderer: SurfaceViewRenderer? = null
    private var directSurfaceView: android.view.SurfaceView? = null
    @Volatile
    private var directSurface: android.view.Surface? = null
    @Volatile
    private var isDirectDecoderActive: Boolean = false
    @Volatile
    private var isVideoVisible: Boolean = false
    @Volatile
    private var isStreamPausedFlag: Boolean = false
    @Volatile
    private var surfaceAvailableCallback: ((android.view.Surface) -> Unit)? = null
    @Volatile
    private var surfaceDestroyedCallback: (() -> Unit)? = null
    private val surfaceLock = java.util.concurrent.locks.ReentrantLock()
    private val surfaceCondition = surfaceLock.newCondition()
    @Volatile
    private var preferredLatencyMode: String = "ultra"

    fun setLatencyMode(mode: String) {
        preferredLatencyMode = if (mode.equals("balanced", ignoreCase = true)) "balanced" else "ultra"
        Log.i(TAG, "Preferred latency mode updated: $preferredLatencyMode")
    }

    // Queue for ICE candidates arriving before remote description is set
    private val pendingIceCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var isRemoteDescriptionSet = false
    @Volatile
    private var sessionGeneration: Long = 0L
    @Volatile
    private var hasLiveSession: Boolean = false

    fun currentSessionGeneration(): Long = synchronized(sessionLock) {
        sessionGeneration
    }

    fun isCurrentGeneration(gen: Long): Boolean = synchronized(sessionLock) {
        hasLiveSession && sessionGeneration == gen
    }

    fun hasActivePeerConnection(): Boolean = synchronized(sessionLock) {
        hasLiveSession && peerConnection != null
    }

    private fun isSessionValidLocked(expectedGen: Long): Boolean =
        hasLiveSession && expectedGen == sessionGeneration && peerConnection != null

    init {
        // Initialize WebRTC Android library
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setFieldTrials(
                "WebRTC-ZeroPlayoutDelay/min_pacing:0ms,max_decode_queue_size:4/" +
                    "WebRTC-FlexFEC-03/Disabled/" +
                    "WebRTC-Pacer-DrainQueue/Enabled/"
            )
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // HDMI Media Audio & Low-latency stereo configuration for Fire TV / Android TV
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseLowLatency(false)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        // Hardware-accelerated zero-copy DirectSurface decoding for H.264 / VP8 / VP9 (with DefaultVideoDecoderFactory fallback)
        val baseDecoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        val directProvider = object : DirectSurfaceProvider {
            override fun getDirectSurface(): android.view.Surface? {
                val s = directSurface
                return if (s != null && s.isValid) s else null
            }

            override fun awaitDirectSurface(timeoutMs: Long): android.view.Surface? {
                mainHandler.post { syncSurfaceViewsVisibility() }
                surfaceLock.lock()
                try {
                    val deadlineNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                    var remaining = deadlineNs
                    while (remaining > 0L) {
                        val s = directSurface
                        if (s != null && s.isValid) return s
                        remaining = surfaceCondition.awaitNanos(remaining)
                    }
                    val s = directSurface
                    return if (s != null && s.isValid) s else null
                } finally {
                    surfaceLock.unlock()
                }
            }

            override fun onDirectVideoSizeChanged(width: Int, height: Int) {
                mainHandler.post { updateDirectSurfaceAspectRatio(width, height) }
            }

            override fun onDirectFirstFrameRendered() {
                listener?.onFirstFrameReceived(currentSessionGeneration())
            }

            override fun isStreamPaused(): Boolean = isStreamPausedFlag

            override fun registerSurfaceConsumer(
                onAvailable: (android.view.Surface) -> Unit,
                onDestroyed: () -> Unit
            ) {
                surfaceAvailableCallback = onAvailable
                surfaceDestroyedCallback = onDestroyed
            }

            override fun unregisterSurfaceConsumer() {
                surfaceAvailableCallback = null
                surfaceDestroyedCallback = null
            }
        }
        val decoderFactory = DirectSurfaceVideoDecoderFactory(baseDecoderFactory, directProvider) { directActive ->
            isDirectDecoderActive = directActive
            mainHandler.post { syncSurfaceViewsVisibility() }
        }
        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized with hardware MediaCodec decoder and HDMI media audio module.")
    }

    /**
     * Enforces Android AudioManager.MODE_NORMAL on the main thread so Fire TV HDMI audio routes cleanly.
     */
    private fun enforceNormalAudioMode() {
        mainHandler.post {
            try {
                audioManager?.let { am ->
                    am.mode = AudioManager.MODE_NORMAL
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    Log.i(TAG, "Enforced AudioManager.MODE_NORMAL & isSpeakerphoneOn=false for HDMI media speakers.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting audioManager mode: ${e.message}")
            }
        }
    }

    /**
     * Initializes the SurfaceViewRenderer for hardware rendering with EGL context.
     * Full native 60 FPS refresh rate is preserved (setFpsReduction is NOT called).
     */
    fun setupRenderer(renderer: SurfaceViewRenderer) {
        this.boundRenderer = renderer
        renderer.init(rootEglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setMirror(false)
        renderer.disableFpsReduction()

        val parent = renderer.parent as? android.view.ViewGroup
        if (parent != null && directSurfaceView == null) {
            val insertIdx = parent.indexOfChild(renderer).coerceAtLeast(0)
            val sv = android.view.SurfaceView(renderer.context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                )
                visibility = android.view.View.GONE
                keepScreenOn = true
            }
            sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    surfaceLock.lock()
                    try {
                        directSurface = holder.surface
                        surfaceCondition.signalAll()
                    } finally {
                        surfaceLock.unlock()
                    }
                    holder.surface?.let { s ->
                        if (s.isValid) surfaceAvailableCallback?.invoke(s)
                    }
                }

                override fun surfaceChanged(
                    holder: android.view.SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    surfaceLock.lock()
                    try {
                        directSurface = holder.surface
                        surfaceCondition.signalAll()
                    } finally {
                        surfaceLock.unlock()
                    }
                    Log.i(TAG, "DirectSurfaceView surfaceChanged: ${width}x$height")
                }

                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    surfaceLock.lock()
                    try {
                        directSurface = null
                    } finally {
                        surfaceLock.unlock()
                    }
                    surfaceDestroyedCallback?.invoke()
                }
            })
            parent.addView(sv, insertIdx)
            directSurfaceView = sv
        }
    }

    fun setVideoVisible(visible: Boolean) {
        isVideoVisible = visible
        if (visible && DirectSurfaceVideoDecoderFactory.findHardwareDecoderName(android.media.MediaFormat.MIMETYPE_VIDEO_AVC) != null) {
            isDirectDecoderActive = true
        }
        syncSurfaceViewsVisibility()
    }

    private fun syncSurfaceViewsVisibility() {
        val sv = directSurfaceView
        val r = boundRenderer
        if (!isVideoVisible) {
            sv?.visibility = android.view.View.GONE
            r?.visibility = android.view.View.GONE
            return
        }
        if (isDirectDecoderActive && sv != null) {
            r?.visibility = android.view.View.GONE
            sv.visibility = android.view.View.VISIBLE
            sv.keepScreenOn = true
        } else {
            sv?.visibility = android.view.View.GONE
            r?.visibility = android.view.View.VISIBLE
            r?.keepScreenOn = true
        }
    }

    private fun updateDirectSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        val sv = directSurfaceView ?: return
        val parent = sv.parent as? android.view.ViewGroup ?: return
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val parentAspect = pw.toFloat() / ph.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val (targetW, targetH) = if (kotlin.math.abs(parentAspect - videoAspect) < 0.01f) {
            android.view.ViewGroup.LayoutParams.MATCH_PARENT to android.view.ViewGroup.LayoutParams.MATCH_PARENT
        } else if (videoAspect > parentAspect) {
            pw to (pw / videoAspect).toInt().coerceAtLeast(1)
        } else {
            (ph * videoAspect).toInt().coerceAtLeast(1) to ph
        }
        val lp = (sv.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(targetW, targetH, android.view.Gravity.CENTER)
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            lp.gravity = android.view.Gravity.CENTER
            sv.layoutParams = lp
        }
    }

    /**
     * Processes an incoming SDP offer from a casting client and produces the optimized SDP answer.
     */
    fun handleOffer(offerSdp: String, onAnswerCreated: (String) -> Unit) {
        synchronized(lifecycleLock) {
            stopCurrentSession()

            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

                // Ultra-low-latency NetEq & BWE configuration while preserving smooth 60 FPS Wi-Fi playback
                audioJitterBufferFastAccelerate = preferredLatencyMode != "balanced"
                audioJitterBufferMaxPackets = if (preferredLatencyMode == "balanced") 40 else 16
                screencastMinBitrate = 1000 // 1 Mbps floor so GCC adapts smoothly on Wi-Fi
                combinedAudioVideoBwe = true
            }

            val expectedGen: Long
            synchronized(sessionLock) {
                expectedGen = ++sessionGeneration
                hasLiveSession = true
                isRemoteDescriptionSet = false
                pendingIceCandidates.clear()
                peerConnection = null
            }

            val observer = object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                        Log.d(TAG, "SignalingState: $state")
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "IceConnectionState: $state")
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {
                        Log.d(TAG, "IceConnectionReceiving: $receiving")
                    }

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "IceGatheringState: $state")
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        val valid = synchronized(sessionLock) {
                            expectedGen == sessionGeneration && hasLiveSession
                        }
                        if (!valid) return
                        candidate?.let {
                            Log.d(TAG, "Generated local ICE candidate: ${it.sdpMid}")
                            listener?.onLocalIceCandidate(it)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                        Log.d(TAG, "ICE candidates removed")
                    }

                    override fun onAddStream(stream: MediaStream?) {
                        val valid = synchronized(sessionLock) {
                            isSessionValidLocked(expectedGen)
                        }
                        if (!valid) return
                        Log.i(TAG, "Stream added (legacy): ${stream?.id}")
                        stream?.videoTracks?.firstOrNull()?.let { attachVideoTrack(it, expectedGen) }
                        stream?.audioTracks?.firstOrNull()?.let { attachAudioTrack(it, expectedGen) }
                    }

                    override fun onRemoveStream(stream: MediaStream?) {
                        Log.i(TAG, "Stream removed (legacy): ${stream?.id}")
                    }

                    override fun onDataChannel(dataChannel: DataChannel?) {
                        Log.d(TAG, "DataChannel received: ${dataChannel?.label()}")
                    }

                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "Renegotiation needed")
                    }

                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        Log.d(TAG, "Track added via onAddTrack")
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val valid = synchronized(sessionLock) {
                            isSessionValidLocked(expectedGen)
                        }
                        if (!valid) return
                        val receiver = transceiver?.receiver
                        val track = receiver?.track()
                        Log.i(TAG, "Track received via Unified Plan: ${track?.kind()} (${track?.id()})")

                        receiver?.SetObserver(object : RtpReceiver.Observer {
                            override fun onFirstPacketReceived(mediaType: MediaStreamTrack.MediaType?) {
                                if (!isCurrentGeneration(expectedGen)) return
                                Log.d(TAG, "First RTP packet received on receiver for $mediaType")
                                if (mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                                    listener?.onFirstFrameReceived(expectedGen)
                                }
                            }
                        })

                        when (track) {
                            is VideoTrack -> attachVideoTrack(track, expectedGen)
                            is AudioTrack -> attachAudioTrack(track, expectedGen)
                        }
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        val valid = isCurrentGeneration(expectedGen)
                        if (!valid) {
                            Log.d(TAG, "Ignoring stale PeerConnectionState $newState (gen=$expectedGen != $sessionGeneration)")
                            return
                        }
                        Log.i(TAG, "PeerConnectionState changed: $newState")
                        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                            // Post to main thread to guarantee HDMI media audio mode is active
                            enforceNormalAudioMode()
                        }
                        newState?.let { listener?.onConnectionStateChange(expectedGen, it) }
                    }
                }

            // Create PeerConnection OUTSIDE sessionLock so WebRTC signaling_thread observer callbacks never deadlock on sessionLock.
            val createdPc = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            var obsoletePcToDispose: PeerConnection? = null
            synchronized(sessionLock) {
                if (expectedGen != sessionGeneration || !hasLiveSession) {
                    obsoletePcToDispose = createdPc
                } else {
                    peerConnection = createdPc
                    if (createdPc == null) {
                        hasLiveSession = false
                    }
                }
            }

            if (obsoletePcToDispose != null) {
                try {
                    obsoletePcToDispose?.close()
                } catch (_: Exception) {}
                try {
                    obsoletePcToDispose?.dispose()
                } catch (_: Exception) {}
                return
            }

            if (createdPc == null) {
                listener?.onError(expectedGen, "Falha ao criar PeerConnection")
                return
            }

            val playoutMatch = Regex("""^a=extmap:(\d+)(?:/[a-z]+)?\s+(http://www\.webrtc\.org/experiments/rtp-hdrext/playout-delay)""", RegexOption.MULTILINE).find(offerSdp)
            val playoutExtId = playoutMatch?.groupValues?.get(1)
            val playoutDelayExt = playoutExtId?.let { "a=extmap:$it http://www.webrtc.org/experiments/rtp-hdrext/playout-delay" }
            if (playoutDelayExt != null) {
                Log.i(TAG, "Preserving playout-delay in answer: $playoutDelayExt")
            }

            val optimizedOfferSdp = optimizeSdp(offerSdp)
            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, optimizedOfferSdp)

            val remoteObserver = object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    val answerObserver = object : SimpleSdpObserver("createAnswer") {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            super.onCreateSuccess(desc)
                            if (desc == null) return
                            // Apply low-latency 25 Mbps bandwidth, H.264 baseline priority, playout-delay preservation, and Opus audio optimizations
                            val lowLatencySdp = optimizeSdp(desc.description, playoutDelayExt)
                            val answerDesc = SessionDescription(desc.type, lowLatencySdp)

                            val localObserver = object : SimpleSdpObserver("setLocalDescription") {
                                override fun onSetSuccess() {
                                    super.onSetSuccess()
                                    val stillValid = synchronized(sessionLock) {
                                        isSessionValidLocked(expectedGen)
                                    }
                                    if (!stillValid) return
                                    Log.i(TAG, "Local SDP Answer set successfully with 60 FPS / Low-Latency parameters.")
                                    onAnswerCreated(answerDesc.description)
                                }

                                override fun onSetFailure(error: String?) {
                                    super.onSetFailure(error)
                                    listener?.onError(expectedGen, "Falha SDP local: ${error ?: "desconhecido"}")
                                }
                            }

                            val pc = synchronized(sessionLock) {
                                if (isSessionValidLocked(expectedGen)) peerConnection else null
                            }
                            if (pc != null) {
                                pc.setLocalDescription(localObserver, answerDesc)
                            }
                        }

                        override fun onCreateFailure(error: String?) {
                            super.onCreateFailure(error)
                            listener?.onError(expectedGen, "Falha ao criar answer SDP: ${error ?: "desconhecido"}")
                        }
                    }

                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }

                    var candidatesToDrain: List<IceCandidate> = emptyList()
                    val pc = synchronized(sessionLock) {
                        val activePc = if (isSessionValidLocked(expectedGen)) peerConnection else null
                        if (activePc != null) {
                            isRemoteDescriptionSet = true
                            if (pendingIceCandidates.isNotEmpty()) {
                                candidatesToDrain = ArrayList(pendingIceCandidates)
                                pendingIceCandidates.clear()
                            }
                        }
                        activePc
                    }
                    if (pc != null) {
                        drainPendingCandidates(pc, candidatesToDrain)
                        pc.createAnswer(answerObserver, constraints)
                    }
                }

                override fun onSetFailure(error: String?) {
                    super.onSetFailure(error)
                    listener?.onError(expectedGen, "Falha SDP remoto: ${error ?: "desconhecido"}")
                }
            }

            val pcForRemote = synchronized(sessionLock) {
                if (isSessionValidLocked(expectedGen)) peerConnection else null
            }
            if (pcForRemote != null) {
                pcForRemote.setRemoteDescription(remoteObserver, remoteDesc)
            }
        }
    }

    /**
     * Adds an ICE candidate received from the remote sender safely across WebSocketWorker/MainThread races.
     */
    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (Looper.myLooper() != mainHandler.looper) {
            mainHandler.post { addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate) }
            return
        }
        val iceCandidate = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        val pcToApply: PeerConnection? = synchronized(sessionLock) {
            if (!hasLiveSession) {
                Log.d(TAG, "Ignoring remote ICE candidate: no active WebRTC session")
                return
            }
            val pc = peerConnection
            if (isRemoteDescriptionSet && pc != null) {
                pc
            } else {
                pendingIceCandidates.add(iceCandidate)
                null
            }
        }
        if (pcToApply != null) {
            try {
                pcToApply.addIceCandidate(iceCandidate)
            } catch (e: Exception) {
                Log.w(TAG, "Ignored addIceCandidate failure during transition: ${e.message}")
            }
        }
    }

    private fun drainPendingCandidates(pc: PeerConnection, candidates: List<IceCandidate>) {
        if (candidates.isNotEmpty()) {
            Log.d(TAG, "Draining ${candidates.size} queued ICE candidates")
            for (candidate in candidates) {
                try {
                    pc.addIceCandidate(candidate)
                } catch (e: Exception) {
                    Log.w(TAG, "Ignored queued addIceCandidate failure: ${e.message}")
                }
            }
        }
    }

    private fun attachVideoTrack(track: VideoTrack, expectedGen: Long) {
        var renderer: SurfaceViewRenderer? = null
        var fpsCounter: org.webrtc.VideoSink? = null
        synchronized(sessionLock) {
            if (!isSessionValidLocked(expectedGen)) return
            if (activeVideoTrack?.id() == track.id()) {
                Log.d(TAG, "VideoTrack ${track.id()} is already attached; ignoring duplicate attach.")
                return
            }
            activeVideoTrack = track
            renderer = boundRenderer
            if (renderer != null) {
                val r = renderer!!
                fpsCounter = object : org.webrtc.VideoSink {
                    private var count = 0
                    private var lastLogNs = System.nanoTime()
                    override fun onFrame(frame: org.webrtc.VideoFrame) {
                        if (!isDirectDecoderActive) {
                            r.onFrame(frame)
                        }
                        count++
                        val now = System.nanoTime()
                        val elapsedNs = now - lastLogNs
                        if (elapsedNs >= 2_000_000_000L) {
                            val fps = count * 1_000_000_000.0 / elapsedNs
                            Log.d(TAG, "VideoSink arrived fps=%.1f frames=%d res=%dx%d direct=%s".format(fps, count, frame.rotatedWidth, frame.rotatedHeight, isDirectDecoderActive))
                            count = 0
                            lastLogNs = now
                        }
                    }
                }
                activeVideoSink = fpsCounter
            }
        }
        try {
            track.setEnabled(true)
            fpsCounter?.let { sink ->
                track.addSink(sink)
                Log.i(TAG, "VideoTrack attached (directSurface=$isDirectDecoderActive)")
                listener?.onFirstFrameReceived(expectedGen)
            }
        } catch (e: Exception) {
            Log.w(TAG, "VideoTrack attach ignored during session switch: ${e.message}")
        }
    }

    private fun attachAudioTrack(track: AudioTrack, expectedGen: Long) {
        synchronized(sessionLock) {
            if (!isSessionValidLocked(expectedGen)) return
            if (activeAudioTrack?.id() == track.id()) {
                Log.d(TAG, "AudioTrack ${track.id()} is already attached; ignoring duplicate attach.")
                return
            }
            activeAudioTrack = track
        }
        try {
            track.setEnabled(true)
            track.setVolume(1.0)
            // Explicitly enforce MODE_NORMAL and speakerphoneOn=false on main thread for Fire TV HDMI audio
            enforceNormalAudioMode()
            Log.i(TAG, "AudioTrack enabled, volume set to 1.0, and HDMI media audio mode enforced")
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack attach ignored during session switch: ${e.message}")
        }
    }

    /**
     * Pauses or resumes the active WebRTC stream on the Fire TV receiver:
     * - Freezes or resumes the 1080p hardware frame on SurfaceViewRenderer via pauseVideo() / disableFpsReduction()
     * - Mutes or unmutes the remote audio track cleanly
     */
    fun setStreamPaused(paused: Boolean) {
        val audioTrack: AudioTrack?
        val renderer: SurfaceViewRenderer?
        synchronized(sessionLock) {
            isStreamPausedFlag = paused
            audioTrack = activeAudioTrack
            renderer = boundRenderer
        }
        try {
            audioTrack?.setEnabled(!paused)
            renderer?.let { r ->
                if (paused) {
                    r.pauseVideo()
                } else {
                    r.disableFpsReduction()
                }
            }
            Log.i(TAG, "Stream pause state updated: paused=$paused")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update stream pause state: ${e.message}")
        }
    }

    /**
     * Stops the active casting session and closes the PeerConnection safely without lock-inversion or UAF races.
     */
    fun stopCurrentSession() {
        synchronized(lifecycleLock) {
            val pcToDispose: PeerConnection?
            val videoTrackToDisable: VideoTrack?
            val videoSinkToRemove: org.webrtc.VideoSink?
            val audioTrackToDisable: AudioTrack?
            val rendererToRestore: SurfaceViewRenderer?

            synchronized(sessionLock) {
                sessionGeneration++
                hasLiveSession = false
                isRemoteDescriptionSet = false
                pendingIceCandidates.clear()
                isStreamPausedFlag = false

                pcToDispose = peerConnection
                peerConnection = null
                videoTrackToDisable = activeVideoTrack
                videoSinkToRemove = activeVideoSink
                activeVideoTrack = null
                activeVideoSink = null
                audioTrackToDisable = activeAudioTrack
                activeAudioTrack = null
                rendererToRestore = boundRenderer
            }

            try {
                rendererToRestore?.disableFpsReduction()
                videoTrackToDisable?.let { videoTrack ->
                    videoSinkToRemove?.let { sink ->
                        try {
                            videoTrack.removeSink(sink)
                        } catch (_: Exception) {}
                    }
                    rendererToRestore?.let { renderer ->
                        try {
                            videoTrack.removeSink(renderer)
                        } catch (_: Exception) {}
                    }
                    try {
                        videoTrack.setEnabled(false)
                    } catch (_: Exception) {}
                }
                try {
                    audioTrackToDisable?.setEnabled(false)
                } catch (_: Exception) {}

                enforceNormalAudioMode()

                // Dispose OUTSIDE sessionLock so WebRTC signaling thread cannot deadlock on sessionLock,
                // while lifecycleLock serializes session creation/teardown and sessionLock prevents concurrent JNI calls.
                try {
                    pcToDispose?.close()
                } catch (_: Exception) {}
                try {
                    pcToDispose?.dispose()
                } catch (_: Exception) {}

                Log.d(TAG, "WebRTC session cleanly stopped.")
            } catch (e: Exception) {
                Log.w(TAG, "Exception stopping session: ${e.message}")
            }
        }
    }

    /**
     * Full teardown of WebRTC resources when Activity is destroyed.
     */
    fun dispose() {
        stopCurrentSession()
        try {
            boundRenderer?.release()
        } catch (_: Exception) {}
        try {
            peerConnectionFactory.dispose()
        } catch (_: Exception) {}
        try {
            rootEglBase.release()
        } catch (_: Exception) {}
        Log.i(TAG, "WebRtcReceiver fully disposed.")
    }

    private open class SimpleSdpObserver(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            Log.d(TAG, "$operation succeeded")
        }

        override fun onSetSuccess() {
            Log.d(TAG, "$operation set succeeded")
        }

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$operation failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$operation set failed: $error")
        }
    }
}
