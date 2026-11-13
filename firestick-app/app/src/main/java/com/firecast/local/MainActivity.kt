package com.firecast.local

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.firecast.local.airplay.AirPlayAspectMath
import com.firecast.local.airplay.AirPlayReceiverController
import com.firecast.local.databinding.ActivityMainBinding
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main Activity for CastViewer Fire TV / Android TV Receiver.
 * Displays an authentic Apple TV / AirPlay minimalist 10-foot waiting screen with local Wi-Fi IP and QR code,
 * plays 60 FPS Minimalist Kinetic Precision animations (Intro, Connect, Source Switch, Play/Pause, Disconnect),
 * and seamlessly transitions to hardware-accelerated fullscreen 60 FPS WebRTC playback
 * with Fire OS HDMI media audio routing and Fire TV remote Play/Pause control.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val cinematicIntroView: CinematicIntroView
        get() = binding.splashOverlay

    private enum class StreamTransport {
        NONE,
        WEBRTC,
        AIRPLAY
    }

    private lateinit var nsdAdvertiser: NsdAdvertiser
    private lateinit var signalingServer: SignalingServer
    private lateinit var webRtcReceiver: WebRtcReceiver
    private lateinit var airPlayReceiverController: AirPlayReceiverController
    private var publishedAirPlayName: String = ""
    private var lastAirPlayVideoWidth: Int = 1920
    private var lastAirPlayVideoHeight: Int = 1080

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockExecutor = Executors.newSingleThreadExecutor()
    private val clockLookupInFlight = AtomicBoolean(false)
    @Volatile
    private var resolvedTimeZone: TimeZone = TimeZone.getDefault()
    @Volatile
    private var resolvedLocationLabel: String? =
        NetworkClockResolver.buildFallbackLocationLabel(TimeZone.getDefault(), System.currentTimeMillis())
    @Volatile
    private var resolvedClockOffsetMs: Long = 0L
    @Volatile
    private var lastNetworkClockResolveMs: Long = 0L
    @Volatile
    private var hasNetworkResolvedClock: Boolean = false

    private var activeStreamTransport = StreamTransport.NONE
    private var isStreamingActive = false
    private var isTakeoverInProgress = false
    private var isStreamPaused = false
    private var activeSenderName: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val takeoverOfferTimeoutRunnable = Runnable {
        if (isTakeoverInProgress && !isStreamingActive) {
            disconnectAndReturnToWaitingScreen("Tempo esgotado na troca de dispositivo")
        }
    }

    private var isIntroPlaying = false
    private val tvEaseOutInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private var statusDotAnimator: ObjectAnimator? = null
    private var hasShownInitialQr = false

    // Smooth HUD auto-hiding animation (glides up cleanly after 3.5s)
    private val hideHudRunnable = Runnable {
        val density = resources.displayMetrics.density
        binding.streamingHudLayout.animate().cancel()
        binding.streamingHudLayout.animate()
            .alpha(0f)
            .translationY(-10f * density)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(320L)
            .setInterpolator(tvEaseOutInterpolator)
            .withLayer()
            .withEndAction {
                binding.streamingHudLayout.isVisible = false
                binding.streamingHudLayout.translationY = 0f
                binding.streamingHudLayout.scaleX = 1f
                binding.streamingHudLayout.scaleY = 1f
            }
            .start()
    }

    // Second-accurate corner clock runnable (active ONLY on waiting screen)
    private val waitingClockRunnable = object : Runnable {
        override fun run() {
            if (isStreamingActive || !binding.waitingContainer.isVisible || isDestroyed || isFinishing) {
                return
            }
            val adjustedNow = System.currentTimeMillis() + resolvedClockOffsetMs
            binding.tvCornerClock.text = NetworkClockResolver.formatClockWithSeconds(adjustedNow, resolvedTimeZone)
            val locLabel = resolvedLocationLabel
            if (!locLabel.isNullOrBlank()) {
                binding.tvClockLocation.text = locLabel
                binding.tvClockLocation.isVisible = true
            } else {
                binding.tvClockLocation.isVisible = false
            }
            val nextDelay = NetworkClockResolver.computeNextSecondDelayMs(adjustedNow)
            mainHandler.postDelayed(this, nextDelay)
        }
    }

    private fun startWaitingClock() {
        mainHandler.removeCallbacks(waitingClockRunnable)
        if (!isStreamingActive && binding.waitingContainer.isVisible) {
            mainHandler.post(waitingClockRunnable)
        }
    }

    private fun stopWaitingClock() {
        mainHandler.removeCallbacks(waitingClockRunnable)
    }

    // ---- Streaming clock overlay (enabled + corner chosen by the sender app: phone / PC) ----
    private var streamClockEnabled = false
    private var streamClockCorner = "br"
    private var lastStreamClockText = ""

    private val streamClockRunnable = object : Runnable {
        override fun run() {
            if (!isStreamingActive || !streamClockEnabled || isDestroyed || isFinishing) {
                binding.tvStreamClock.isVisible = false
                return
            }
            val adjustedNow = System.currentTimeMillis() + resolvedClockOffsetMs
            val text = NetworkClockResolver.formatClockWithSeconds(adjustedNow, resolvedTimeZone)
            if (text != lastStreamClockText) {
                lastStreamClockText = text
                binding.tvStreamClock.text = text
            }
            mainHandler.postDelayed(this, NetworkClockResolver.computeNextSecondDelayMs(adjustedNow))
        }
    }

    private fun cornerToGravity(corner: String): Int = when (corner.lowercase(Locale.ROOT)) {
        "tl" -> Gravity.TOP or Gravity.START
        "tr" -> Gravity.TOP or Gravity.END
        "bl" -> Gravity.BOTTOM or Gravity.START
        else -> Gravity.BOTTOM or Gravity.END
    }

    private fun applyStreamClockState() {
        mainHandler.removeCallbacks(streamClockRunnable)
        val show = isStreamingActive && streamClockEnabled
        if (!show) {
            if (binding.tvStreamClock.isVisible) {
                binding.tvStreamClock.animate().cancel()
                binding.tvStreamClock.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withLayer()
                    .withEndAction {
                        binding.tvStreamClock.isVisible = false
                        binding.tvStreamClock.alpha = 1f
                    }
                    .start()
            }
            return
        }
        val wasVisible = binding.tvStreamClock.isVisible
        val lp = binding.tvStreamClock.layoutParams as android.widget.FrameLayout.LayoutParams
        val newGravity = cornerToGravity(streamClockCorner)
        val cornerChanged = lp.gravity != newGravity
        if (cornerChanged) {
            lp.gravity = newGravity
            binding.tvStreamClock.layoutParams = lp
        }
        lastStreamClockText = ""
        binding.tvStreamClock.animate().cancel()
        if (!wasVisible) {
            binding.tvStreamClock.alpha = 0f
            binding.tvStreamClock.isVisible = true
            binding.tvStreamClock.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(tvEaseOutInterpolator)
                .withLayer()
                .start()
        } else if (cornerChanged) {
            binding.tvStreamClock.alpha = 0.2f
            binding.tvStreamClock.animate()
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(tvEaseOutInterpolator)
                .withLayer()
                .start()
        }
        resolveNetworkClockIfNeeded()
        mainHandler.post(streamClockRunnable)
    }

    private fun resolveNetworkClockIfNeeded(force: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val staleWindowMs = if (hasNetworkResolvedClock) 15 * 60_000L else 45_000L
        if (!force && lastNetworkClockResolveMs > 0L && (nowElapsed - lastNetworkClockResolveMs) < staleWindowMs) {
            return
        }
        if (!clockLookupInFlight.compareAndSet(false, true)) return
        clockExecutor.execute {
            try {
                val res = NetworkClockResolver.resolveBlocking()
                mainHandler.post {
                    if (isDestroyed || isFinishing) return@post
                    resolvedTimeZone = res.timeZone
                    resolvedLocationLabel = res.locationLabel
                    resolvedClockOffsetMs = res.clockOffsetMs
                    hasNetworkResolvedClock = res.fromNetwork
                    lastNetworkClockResolveMs = SystemClock.elapsedRealtime()
                    if (!isStreamingActive && binding.waitingContainer.isVisible) {
                        startWaitingClock()
                    }
                }
            } catch (_: Exception) {
            } finally {
                clockLookupInFlight.set(false)
            }
        }
    }

    // Rotating QR code runnable (every 5 seconds with subtle 180ms alpha pulse)
    private val qrRotationRunnable = object : Runnable {
        override fun run() {
            rotateQrCode()
            mainHandler.postDelayed(this, 5000L)
        }
    }

    private fun startQrRotation() {
        mainHandler.removeCallbacks(qrRotationRunnable)
        mainHandler.post(qrRotationRunnable)
    }

    private fun stopQrRotation() {
        mainHandler.removeCallbacks(qrRotationRunnable)
    }

    private fun rotateQrCode() {
        val ip = NetworkUtils.getLocalIpv4Address() ?: return
        val timeSlot = System.currentTimeMillis() / 5000L
        val qrContent = "ws://$ip:${SignalingServer.DEFAULT_PORT}?t=$timeSlot"
        if (clockExecutor.isShutdown) return
        // Generate the bitmap off the main thread so the TV UI never stutters
        clockExecutor.execute {
            val qrBitmap = NetworkUtils.generateQrCodeBitmap(qrContent, sizePx = 480) ?: return@execute
            mainHandler.post {
                if (isDestroyed || isFinishing || isStreamingActive) return@post
                if (!hasShownInitialQr) {
                    hasShownInitialQr = true
                    binding.ivQrCode.setImageBitmap(qrBitmap)
                } else {
                    binding.ivQrCode.animate().cancel()
                    binding.ivQrCode.alpha = 0.78f
                    binding.ivQrCode.scaleX = 0.985f
                    binding.ivQrCode.scaleY = 0.985f
                    binding.ivQrCode.setImageBitmap(qrBitmap)
                    binding.ivQrCode.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(tvEaseOutInterpolator)
                        .withLayer()
                        .start()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Route volume hardware keys to media stream and ensure HDMI speaker mode
        volumeControlStream = AudioManager.STREAM_MUSIC
        ensureNormalAudioMode()

        enableImmersiveMode()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Root container draws full @drawable/bg_tv_gradient; strip redundant Window fill to cut overdraw in half
        window.setBackgroundDrawable(null)

        // Keep TV screen awake at all times while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize core services
        nsdAdvertiser = NsdAdvertiser(this)
        signalingServer = SignalingServer(SignalingServer.DEFAULT_PORT)
        webRtcReceiver = WebRtcReceiver(this)

        webRtcReceiver.setupRenderer(binding.surfaceViewRenderer)
        airPlayReceiverController = AirPlayReceiverController(
            context = this,
            surfaceView = binding.airplaySurfaceView,
            listener = airPlayListener
        )
        binding.rootContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if ((w != oldW || h != oldH) && activeStreamTransport == StreamTransport.AIRPLAY) {
                updateAirPlaySurfaceBounds(lastAirPlayVideoWidth, lastAirPlayVideoHeight)
            }
        }

        setupListeners()
        setupBackNavigation()
        startServices()

        // Signature Cold-Launch Opening Animation
        if (savedInstanceState == null) {
            playIntroAnimation()
        } else {
            skipIntroAnimation()
        }
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_MUSIC
        ensureNormalAudioMode()
        if (isStreamingActive) {
            stopStatusDotPulse()
            requestStreamAudioFocus()
        } else {
            startQrRotation()
            startWaitingClock()
            if (!isIntroPlaying) {
                startStatusDotPulse()
            }
            resolveNetworkClockIfNeeded()
        }
        enableImmersiveMode()
        refreshNetworkInfo()
    }

    override fun onPause() {
        super.onPause()
        stopQrRotation()
        stopWaitingClock()
        stopStatusDotPulse()
        if (isStreamingActive) {
            abandonStreamAudioFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopQrRotation()
        stopWaitingClock()
        stopStatusDotPulse()
        cancelIntroAnimation()
        cinematicIntroView.cancelAllAnimations()
        abandonStreamAudioFocus()
        clockExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            signalingServer.stop(500)
        } catch (_: Exception) {}
        nsdAdvertiser.stop()
        airPlayReceiverController.stop()
        webRtcReceiver.dispose()
    }

    private fun startStatusDotPulse() {
        if (isStreamingActive) return
        if (statusDotAnimator == null) {
            statusDotAnimator = ObjectAnimator.ofPropertyValuesHolder(
                binding.dotStatus,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.24f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.24f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.52f)
            ).apply {
                duration = 1400L
                // Three gentle welcome breaths, then rest calmly at 0% idle GPU overhead
                repeatCount = 5
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        statusDotAnimator?.cancel()
        statusDotAnimator?.start()
    }

    private fun stopStatusDotPulse() {
        statusDotAnimator?.cancel()
        binding.dotStatus.scaleX = 1f
        binding.dotStatus.scaleY = 1f
        binding.dotStatus.alpha = 1f
    }

    /**
     * Stages waiting UI children at subtle resting offsets so the radial iris reveals a coordinated
     * Apple TV staggered choreography rather than a delayed second transition.
     */
    private fun stageWaitingChildrenHidden() {
        val d = resources.displayMetrics.density
        binding.waitingContainer.animate().cancel()
        binding.leftEditorialZone.animate().cancel()
        binding.rightQrZone.animate().cancel()
        binding.tvQuickGuide.animate().cancel()
        binding.clockCornerLayout.animate().cancel()

        binding.waitingContainer.alpha = 1f
        binding.waitingContainer.scaleX = 1f
        binding.waitingContainer.scaleY = 1f
        binding.waitingContainer.isVisible = true

        binding.leftEditorialZone.alpha = 0f
        binding.leftEditorialZone.translationX = -20f * d
        binding.leftEditorialZone.translationY = 8f * d

        binding.rightQrZone.alpha = 0f
        binding.rightQrZone.scaleX = 0.93f
        binding.rightQrZone.scaleY = 0.93f
        binding.rightQrZone.translationY = 14f * d

        binding.tvQuickGuide.alpha = 0f
        binding.tvQuickGuide.translationY = 8f * d
        binding.clockCornerLayout.alpha = 0f
        binding.clockCornerLayout.translationY = 8f * d
    }

    private fun revealWaitingChildrenStaggered() {
        binding.waitingContainer.isVisible = true
        binding.waitingContainer.alpha = 1f

        // Non-overlapping text/badge groups animate directly in Skia without offscreen FBO allocation
        binding.leftEditorialZone.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(440L)
            .setInterpolator(tvEaseOutInterpolator)
            .start()

        binding.rightQrZone.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setStartDelay(70L)
            .setDuration(480L)
            .setInterpolator(tvEaseOutInterpolator)
            .withLayer()
            .start()

        binding.tvQuickGuide.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(120L)
            .setDuration(360L)
            .setInterpolator(tvEaseOutInterpolator)
            .start()

        binding.clockCornerLayout.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(140L)
            .setDuration(360L)
            .setInterpolator(tvEaseOutInterpolator)
            .start()
    }

    private fun restoreWaitingChildrenInstant() {
        binding.waitingContainer.animate().cancel()
        binding.leftEditorialZone.animate().cancel()
        binding.rightQrZone.animate().cancel()
        binding.tvQuickGuide.animate().cancel()
        binding.clockCornerLayout.animate().cancel()

        binding.waitingContainer.scaleX = 1f
        binding.waitingContainer.scaleY = 1f
        binding.waitingContainer.alpha = 1f

        binding.leftEditorialZone.alpha = 1f
        binding.leftEditorialZone.translationX = 0f
        binding.leftEditorialZone.translationY = 0f

        binding.rightQrZone.alpha = 1f
        binding.rightQrZone.scaleX = 1f
        binding.rightQrZone.scaleY = 1f
        binding.rightQrZone.translationY = 0f

        binding.tvQuickGuide.alpha = 1f
        binding.tvQuickGuide.translationY = 0f
        binding.clockCornerLayout.alpha = 1f
        binding.clockCornerLayout.translationY = 0f
    }

    /**
     * Animation 1: Executes the 60 FPS Minimalist Kinetic Precision cold-launch intro
     * with overlapped staggered waiting-screen choreography as the radial iris opens.
     */
    private fun playIntroAnimation() {
        isIntroPlaying = true
        stageWaitingChildrenHidden()

        cinematicIntroView.playIntro(
            onRevealStart = {
                if (isIntroPlaying && !isStreamingActive) {
                    revealWaitingChildrenStaggered()
                }
            },
            onComplete = {
                isIntroPlaying = false
                if (!isStreamingActive) {
                    startStatusDotPulse()
                }
            }
        )
    }

    /**
     * Bypasses the intro animation (e.g. on configuration changes or warm re-creation).
     */
    private fun skipIntroAnimation() {
        isIntroPlaying = false
        cinematicIntroView.cancelIntro()
        binding.waitingContainer.isVisible = true
        restoreWaitingChildrenInstant()
    }

    /**
     * Immediately cancels running cold-launch intro animations so incoming casting is never blocked.
     */
    private fun cancelIntroAnimation() {
        if (!isIntroPlaying) return
        isIntroPlaying = false

        cinematicIntroView.cancelIntro()
        restoreWaitingChildrenInstant()
    }

    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    /**
     * Enforces Android AudioManager.MODE_NORMAL on the main thread so Fire TV HDMI audio routes cleanly.
     */
    private fun ensureNormalAudioMode() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure MODE_NORMAL audio mode: ${e.message}")
        }
    }

    /**
     * Requests AudioManager.AUDIOFOCUS_GAIN with AudioAttributes USAGE_MEDIA and CONTENT_TYPE_MOVIE
     * when starting a stream to guarantee Fire TV HDMI audio playback.
     */
    private fun requestStreamAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            ensureNormalAudioMode()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Maintain media stream playback */ }
                    .build()
                audioFocusRequest = focusRequest
                val result = audioManager.requestAudioFocus(focusRequest)
                Log.i(TAG, "Requested AUDIOFOCUS_GAIN (API 26+): result=$result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                Log.i(TAG, "Requested AUDIOFOCUS_GAIN (legacy): result=$result")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
        }
    }

    /**
     * Abandons audio focus cleanly when stream terminates.
     */
    private fun abandonStreamAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        }
    }

    private fun setupBackNavigation() {
        // Fire TV remote Back button handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isStreamingActive) {
                    disconnectAndReturnToWaitingScreen("Transmissão encerrada pelo controle remoto")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isStreamingActive) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event?.repeatCount == 0) {
                        applyStreamPauseState(!isStreamPaused, notifyRemote = true)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event?.repeatCount == 0 && !isStreamPaused) {
                        applyStreamPauseState(true, notifyRemote = true)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (event?.repeatCount == 0 && isStreamPaused) {
                        applyStreamPauseState(false, notifyRemote = true)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> {
                    showStreamingHud(activeSenderName ?: "Dispositivo Local")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val airPlayListener = object : AirPlayReceiverController.Listener {
        override fun onAirPlayReady(serviceName: String, port: Int) {
            Log.i(TAG, "AirPlay receiver ready: name='$serviceName' port=$port")
        }

        override fun onAirPlayUnavailable(reason: String) {
            Log.w(TAG, "AirPlay unavailable on this runtime: $reason")
        }

        override fun onAirPlaySessionStarted() {
            if (isDestroyed || isFinishing) return
            if (activeStreamTransport == StreamTransport.AIRPLAY && isStreamingActive) return

            if (activeStreamTransport == StreamTransport.WEBRTC ||
                isTakeoverInProgress ||
                signalingServer.isSessionActive() ||
                webRtcReceiver.hasActivePeerConnection()
            ) {
                isTakeoverInProgress = false
                mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
                webRtcReceiver.stopCurrentSession()
                signalingServer.disconnectActiveSession("Nova transmissão AirPlay iniciada")
            }

            cancelIntroAnimation()
            ensureNormalAudioMode()
            requestStreamAudioFocus()
            isStreamPaused = false
            val airPlayTitle = getString(R.string.airplay_hud_title)
            activeSenderName = airPlayTitle
            switchToStreamingMode(airPlayTitle, StreamTransport.AIRPLAY)
        }

        override fun onAirPlayFirstFrameRendered() {
            if (isDestroyed || isFinishing) return
            if (activeStreamTransport != StreamTransport.AIRPLAY) {
                onAirPlaySessionStarted()
            } else {
                ensureNormalAudioMode()
                requestStreamAudioFocus()
                binding.airplaySurfaceView.isVisible = true
            }
        }

        override fun onAirPlayVideoDimensionsChanged(width: Int, height: Int) {
            lastAirPlayVideoWidth = width
            lastAirPlayVideoHeight = height
            if (activeStreamTransport == StreamTransport.AIRPLAY) {
                updateAirPlaySurfaceBounds(width, height)
            }
        }

        override fun onAirPlaySessionEnded() {
            if (isDestroyed || isFinishing) return
            if (activeStreamTransport == StreamTransport.AIRPLAY) {
                disconnectAndReturnToWaitingScreen("Espelhamento AirPlay encerrado")
            }
        }

        override fun onAirPlayPinRequired(pin: String) {
            if (isDestroyed || isFinishing) return
            val pinMsg = getString(R.string.airplay_pin_message, pin)
            Toast.makeText(this@MainActivity, pinMsg, Toast.LENGTH_LONG).show()
            binding.tvStatusRow.text = pinMsg
        }
    }

    private fun updateAirPlaySurfaceBounds(videoWidth: Int, videoHeight: Int) {
        val containerW = binding.rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerH = binding.rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val bounds = AirPlayAspectMath.computeFitBounds(containerW, containerH, videoWidth, videoHeight)
        val lp = (binding.airplaySurfaceView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(bounds.width, bounds.height, Gravity.CENTER)
        if (lp.width != bounds.width || lp.height != bounds.height || lp.gravity != Gravity.CENTER) {
            lp.width = bounds.width
            lp.height = bounds.height
            lp.gravity = Gravity.CENTER
            binding.airplaySurfaceView.layoutParams = lp
        }
    }

    /**
     * Applies Play/Pause state on the Fire TV receiver (freezing/resuming WebRTC video & muting audio
     * on WebRTC or AirPlay), optionally notifies WebRTC sender, and plays the kinetic badge animation.
     */
    private fun applyStreamPauseState(paused: Boolean, notifyRemote: Boolean) {
        isStreamPaused = paused
        if (activeStreamTransport == StreamTransport.AIRPLAY) {
            airPlayReceiverController.setMuted(paused)
        } else {
            webRtcReceiver.setStreamPaused(paused)
            if (notifyRemote) {
                signalingServer.sendRemotePauseState(paused)
            }
        }
        cinematicIntroView.playPauseStateAnimation(paused)
        showStreamingHud(activeSenderName ?: "Dispositivo Local")
    }

    private fun setupListeners() {
        // WebRTC event listener
        webRtcReceiver.listener = object : WebRtcReceiver.Listener {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signalingServer.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            override fun onConnectionStateChange(sessionGeneration: Long, newState: PeerConnection.PeerConnectionState) {
                mainHandler.post {
                    if (!webRtcReceiver.isCurrentGeneration(sessionGeneration)) return@post
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            isTakeoverInProgress = false
                            mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
                            cancelIntroAnimation()
                            ensureNormalAudioMode()
                            requestStreamAudioFocus()
                            showStreamingUi()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            if (activeStreamTransport != StreamTransport.AIRPLAY &&
                                (isStreamingActive || isTakeoverInProgress || webRtcReceiver.hasActivePeerConnection())
                            ) {
                                disconnectAndReturnToWaitingScreen("Conexão WebRTC finalizada")
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onFirstFrameReceived(sessionGeneration: Long) {
                mainHandler.post {
                    if (!webRtcReceiver.isCurrentGeneration(sessionGeneration)) return@post
                    isTakeoverInProgress = false
                    mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
                    cancelIntroAnimation()
                    ensureNormalAudioMode()
                    requestStreamAudioFocus()
                    showStreamingUi()
                }
            }

            override fun onError(sessionGeneration: Long, message: String) {
                mainHandler.post {
                    if (!webRtcReceiver.isCurrentGeneration(sessionGeneration)) return@post
                    if (activeStreamTransport == StreamTransport.AIRPLAY) return@post
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    disconnectAndReturnToWaitingScreen("Erro WebRTC: $message")
                }
            }
        }

        // Signaling server event listener
        signalingServer.listener = object : SignalingServer.Listener {
            override fun onOfferReceived(sdp: String, senderName: String) {
                mainHandler.post {
                    if (activeStreamTransport == StreamTransport.AIRPLAY ||
                        airPlayReceiverController.isSessionActive()
                    ) {
                        airPlayReceiverController.disconnectActiveSession()
                        binding.airplaySurfaceView.isVisible = false
                    }
                    activeStreamTransport = StreamTransport.WEBRTC
                    isTakeoverInProgress = false
                    mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
                    cancelIntroAnimation()
                    ensureNormalAudioMode()
                    requestStreamAudioFocus()
                    isStreamPaused = false
                    activeSenderName = senderName
                    binding.tvStatusRow.text = "Conectando com $senderName…"
                    switchToStreamingMode(senderName, StreamTransport.WEBRTC)
                    webRtcReceiver.handleOffer(sdp) { answerSdp ->
                        signalingServer.sendAnswer(answerSdp)
                    }
                }
            }

            override fun onIceCandidateReceived(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
                mainHandler.post {
                    webRtcReceiver.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
            }

            override fun onSourceSwitchReceived(sourceName: String, isWindow: Boolean) {
                runOnUiThread {
                    if (isStreamingActive) {
                        isStreamPaused = false
                        webRtcReceiver.setStreamPaused(false)
                        cinematicIntroView.playSourceSwitchAnimation(sourceName, isWindow)
                        val sender = activeSenderName ?: "PC"
                        showStreamingHud("$sender ($sourceName)")
                    }
                }
            }

            override fun onStreamPauseReceived(paused: Boolean) {
                runOnUiThread {
                    if (isStreamingActive) {
                        applyStreamPauseState(paused, notifyRemote = false)
                    }
                }
            }

            override fun onImageModeChanged(mode: String) {
                runOnUiThread {
                    if (isStreamingActive) {
                        binding.surfaceViewRenderer.disableFpsReduction()
                        if (!isStreamPaused) {
                            cinematicIntroView.playImageModeAnimation(mode)
                        }
                        val sender = activeSenderName ?: "PC"
                        val isDetail = mode.equals("detail", ignoreCase = true)
                        val modeLabel = if (isDetail) "Ultra Nitidez" else "Fluidez 60 FPS"
                        showStreamingHud("$sender • $modeLabel")
                    }
                }
            }

            override fun onVideoProfileChanged(preset: String, label: String, name: String) {
                runOnUiThread {
                    if (isStreamingActive) {
                        if (!isStreamPaused) {
                            binding.surfaceViewRenderer.disableFpsReduction()
                            cinematicIntroView.playVideoProfileAnimation(label, name)
                        }
                        val sender = activeSenderName ?: "PC"
                        showStreamingHud("$sender • Vídeo ${label.ifBlank { name }}")
                    }
                }
            }

            override fun onLatencyModeChanged(mode: String) {
                webRtcReceiver.setLatencyMode(mode)
                runOnUiThread {
                    if (isStreamingActive) {
                        if (!isStreamPaused) {
                            cinematicIntroView.playLatencyModeAnimation(mode)
                        }
                        val sender = activeSenderName ?: "PC"
                        val isBalanced = mode.equals("balanced", ignoreCase = true)
                        val latLabel = if (isBalanced) "Latência Wi-Fi ~60 ms" else "Latência Ultra 0 ms"
                        showStreamingHud("$sender • $latLabel")
                    }
                }
            }

            override fun onClockConfigChanged(enabled: Boolean, corner: String) {
                mainHandler.post {
                    streamClockEnabled = enabled
                    streamClockCorner = corner
                    applyStreamClockState()
                }
            }

            override fun onByeReceived() {
                mainHandler.post {
                    if (activeStreamTransport != StreamTransport.AIRPLAY) {
                        disconnectAndReturnToWaitingScreen("Cliente desconectou")
                    }
                }
            }

            override fun onSessionDisconnected(reason: String) {
                mainHandler.post {
                    if (activeStreamTransport != StreamTransport.AIRPLAY) {
                        disconnectAndReturnToWaitingScreen(reason)
                    }
                }
            }

            override fun onRejectedBusyAttempt(attemptedSender: String, activeSender: String) {
                mainHandler.post {
                    if (isStreamingActive) {
                        showStreamingHud("$activeSender ativo • $attemptedSender bloqueado")
                    }
                }
            }

            override fun onTakeoverRequested(challengerName: String, activeSender: String) {
                mainHandler.post {
                    if (isStreamingActive) {
                        showStreamingHud("$challengerName pediu para assumir a TV (15s)...")
                    }
                }
            }

            override fun onTakeoverDenied(challengerName: String, activeSender: String) {
                mainHandler.post {
                    if (isStreamingActive) {
                        showStreamingHud("$activeSender manteve a transmissão • $challengerName recusado")
                    }
                }
            }

            override fun onSessionTakeover(newSenderName: String) {
                mainHandler.post {
                    resetReceiverForIncomingTakeover(newSenderName)
                }
            }

            override fun onServerStarted(port: Int) {
                mainHandler.post {
                    refreshNetworkInfo()
                }
            }

            override fun onServerError(error: Exception) {
                mainHandler.post {
                    binding.tvStatusRow.text = "Erro no servidor WebSocket: ${error.message}"
                }
            }
        }

        nsdAdvertiser.onStatusChanged = { statusText ->
            mainHandler.post {
                Log.d(TAG, "mDNS Service status: $statusText")
            }
        }
    }

    private fun startServices() {
        try {
            signalingServer.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao iniciar servidor de sinalização: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refreshNetworkInfo()
    }

    private fun refreshNetworkInfo() {
        val friendlyName = NetworkUtils.getDeviceFriendlyName(this)
        val ip = NetworkUtils.getLocalIpv4Address()

        binding.tvDeviceName.text = friendlyName

        if (ip != null) {
            binding.tvStatusRow.text = getString(R.string.tv_badge_online)
            binding.dotStatus.isVisible = true
            binding.tvWsAddress.text = ""

            // Register mDNS (_firecast._tcp.) and Apple AirPlay (_airplay._tcp. & _raop._tcp.)
            nsdAdvertiser.start(SignalingServer.DEFAULT_PORT, friendlyName)
            if (!airPlayReceiverController.isRunning() || publishedAirPlayName != friendlyName) {
                val dm = resources.displayMetrics
                val airplayW = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(1280, 1920)
                val airplayH = minOf(dm.widthPixels, dm.heightPixels).coerceIn(720, 1080)
                @Suppress("DEPRECATION")
                val refreshRate = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.refreshRate
                } else {
                    windowManager.defaultDisplay?.refreshRate
                })?.toInt()?.coerceIn(30, 60) ?: 60
                publishedAirPlayName = friendlyName
                airPlayReceiverController.start(friendlyName, airplayW, airplayH, refreshRate)
            }

            if (!isStreamingActive && !isTakeoverInProgress) {
                startQrRotation()
                startWaitingClock()
                resolveNetworkClockIfNeeded()
            }
        } else {
            stopQrRotation()
            nsdAdvertiser.stop()
            if (airPlayReceiverController.isRunning()) {
                airPlayReceiverController.stop()
                publishedAirPlayName = ""
            }
            binding.tvStatusRow.text = getString(R.string.network_disconnected)
            binding.dotStatus.isVisible = false
            binding.tvWsAddress.text = ""
            if (!isStreamingActive && !isTakeoverInProgress) {
                startWaitingClock()
            }
        }
    }

    private fun showStreamingUi() {
        val transport = if (activeStreamTransport == StreamTransport.NONE) {
            StreamTransport.WEBRTC
        } else {
            activeStreamTransport
        }
        switchToStreamingMode(activeSenderName ?: "Dispositivo Local", transport)
    }

    private fun switchToStreamingMode(
        senderName: String,
        transport: StreamTransport = StreamTransport.WEBRTC
    ) {
        cancelIntroAnimation()
        val wasAlreadyStreaming = isStreamingActive && activeStreamTransport == transport
        isStreamingActive = true
        activeStreamTransport = transport

        stopQrRotation()
        stopWaitingClock()
        stopStatusDotPulse()

        ensureNormalAudioMode()
        requestStreamAudioFocus()

        if (!wasAlreadyStreaming && binding.waitingContainer.isVisible) {
            binding.waitingContainer.animate().cancel()
            binding.waitingContainer.animate()
                .alpha(0f)
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(180L)
                .setInterpolator(tvEaseOutInterpolator)
                .withLayer()
                .withEndAction {
                    if (isStreamingActive) {
                        binding.waitingContainer.isVisible = false
                        restoreWaitingChildrenInstant()
                    }
                }
                .start()
        } else if (!wasAlreadyStreaming) {
            binding.waitingContainer.isVisible = false
        }

        if (transport == StreamTransport.AIRPLAY) {
            webRtcReceiver.setVideoVisible(false)
            updateAirPlaySurfaceBounds(lastAirPlayVideoWidth, lastAirPlayVideoHeight)
            binding.airplaySurfaceView.isVisible = true
        } else {
            binding.airplaySurfaceView.isVisible = false
            webRtcReceiver.setVideoVisible(true)
            if (!isStreamPaused) {
                binding.surfaceViewRenderer.disableFpsReduction()
            }
        }
        enableImmersiveMode()

        if (!wasAlreadyStreaming) {
            isStreamPaused = false
            if (transport == StreamTransport.AIRPLAY) {
                airPlayReceiverController.setMuted(false)
            } else {
                webRtcReceiver.setStreamPaused(false)
            }
            cinematicIntroView.playCastConnectAnimation(senderName)
        }

        showStreamingHud(senderName)
        applyStreamClockState()
    }

    private fun showStreamingHud(label: String) {
        mainHandler.removeCallbacks(hideHudRunnable)
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        val hudInstructions = when {
            lang.startsWith("es") -> "Play/Pause para pausar • Atrás para salir"
            lang.startsWith("en") -> "Play/Pause to pause • Back to exit"
            else -> "Play/Pause para pausar • Voltar para sair"
        }
        binding.tvHudText.text = "CastViewer • $label • $hudInstructions"
        val d = resources.displayMetrics.density
        binding.streamingHudLayout.animate().cancel()
        if (!binding.streamingHudLayout.isVisible || binding.streamingHudLayout.alpha < 0.9f) {
            binding.streamingHudLayout.alpha = 0f
            binding.streamingHudLayout.translationY = -12f * d
            binding.streamingHudLayout.scaleX = 0.96f
            binding.streamingHudLayout.scaleY = 0.96f
        }
        binding.streamingHudLayout.isVisible = true
        binding.streamingHudLayout.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(tvEaseOutInterpolator)
            .withLayer()
            .start()

        // Auto-fade after 3.5 seconds
        mainHandler.postDelayed(hideHudRunnable, 3500L)
    }

    private fun switchToWaitingMode(
        cancelOverlayAnimations: Boolean = true,
        staggeredReveal: Boolean = false
    ) {
        streamClockEnabled = false
        mainHandler.removeCallbacks(streamClockRunnable)
        binding.tvStreamClock.animate().cancel()
        binding.tvStreamClock.isVisible = false
        if (cancelOverlayAnimations) {
            cinematicIntroView.cancelAllAnimations()
        }
        binding.streamingHudLayout.animate().cancel()
        binding.streamingHudLayout.isVisible = false
        webRtcReceiver.setVideoVisible(false)
        binding.airplaySurfaceView.isVisible = false

        if (staggeredReveal) {
            stageWaitingChildrenHidden()
        } else {
            binding.waitingContainer.isVisible = true
            restoreWaitingChildrenInstant()
        }

        refreshNetworkInfo()
        startQrRotation()
        startWaitingClock()
        startStatusDotPulse()
        resolveNetworkClockIfNeeded()
    }

    private fun disconnectAndReturnToWaitingScreen(reason: String) {
        cancelIntroAnimation()
        mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
        val hasRecoverableState = isStreamingActive ||
            isTakeoverInProgress ||
            activeStreamTransport != StreamTransport.NONE ||
            !binding.waitingContainer.isVisible ||
            signalingServer.isSessionActive() ||
            airPlayReceiverController.isSessionActive()
        if (!hasRecoverableState) return

        isTakeoverInProgress = false
        isStreamingActive = false
        isStreamPaused = false
        activeStreamTransport = StreamTransport.NONE
        activeSenderName = null
        mainHandler.removeCallbacks(hideHudRunnable)

        abandonStreamAudioFocus()
        ensureNormalAudioMode()
        webRtcReceiver.stopCurrentSession()
        signalingServer.disconnectActiveSession(reason)
        if (airPlayReceiverController.isSessionActive()) {
            airPlayReceiverController.disconnectActiveSession()
        }
        binding.airplaySurfaceView.isVisible = false

        switchToWaitingMode(cancelOverlayAnimations = false, staggeredReveal = true)
        cinematicIntroView.playCastDisconnectAnimation(
            reason = reason,
            onRevealStart = {
                if (!isStreamingActive) {
                    revealWaitingChildrenStaggered()
                }
            }
        )
    }

    private fun resetReceiverForIncomingTakeover(newSenderName: String) {
        cancelIntroAnimation()
        cinematicIntroView.cancelAllAnimations()
        if (activeStreamTransport == StreamTransport.AIRPLAY || airPlayReceiverController.isSessionActive()) {
            airPlayReceiverController.disconnectActiveSession()
            binding.airplaySurfaceView.isVisible = false
        }
        activeStreamTransport = StreamTransport.WEBRTC
        isTakeoverInProgress = true
        isStreamingActive = false
        isStreamPaused = false
        activeSenderName = newSenderName
        mainHandler.removeCallbacks(takeoverOfferTimeoutRunnable)
        mainHandler.postDelayed(takeoverOfferTimeoutRunnable, 14_000L)
        mainHandler.removeCallbacks(hideHudRunnable)
        stopQrRotation()
        stopWaitingClock()
        stopStatusDotPulse()
        streamClockEnabled = false
        mainHandler.removeCallbacks(streamClockRunnable)
        binding.tvStreamClock.isVisible = false

        abandonStreamAudioFocus()
        ensureNormalAudioMode()
        webRtcReceiver.stopCurrentSession()
        // Keep SurfaceView warm and visible across sender handoff so MediaTek DirectSurface
        // does not tear down and recreate its hardware SurfaceHolder mid-transition.
        webRtcReceiver.setVideoVisible(true)

        binding.waitingContainer.isVisible = false
        enableImmersiveMode()
        showStreamingHud("Assumindo sessão: $newSenderName...")
    }
}
