package com.firecast.local

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cinema-Grade 60 FPS Hardware-Accelerated Kinetic Overlay View for CastViewer Fire TV.
 *
 * Engineered for MT8695 / PowerVR GE8300 & Fire OS TV displays (zero per-frame allocations,
 * zero Canvas.clipPath overhead, pre-built Matrix-transformed GPU Radial/Sweep/Linear shaders,
 * VSync-capped frame clock so cold-launch EGL warmup never skips intro frames):
 *  1. Cold Launch Intro: volumetric obsidian stage, optical laser ignition filament, glowing logo
 *     rise with tapered comet orbit lock, and feathered RadialGradient aperture revealing the
 *     waiting canvas underneath in overlap.
 *  2. Connect Portal: silky dark veil fade-in, dual counter-rotating optical orbitals blending
 *     Fire-Orange -> Emerald at lock with concentric sonar shockwaves, and smooth radial iris
 *     opening onto the fullscreen 60 FPS WebRTC video.
 *  3. Top-Center Dynamic Glass Toast: compact unobtrusive Apple TV-style floating banner with
 *     top glass specular sheen, bottom optical gleam, and animated vector badges for Source Switch,
 *     Image Mode, Video Quality, and Latency updates.
 *  4. Continuous Play / Pause Morph & Living Orbit: true polygon vertex morphing between || and ▶
 *     plus continuous 60 FPS VSync ambient breathing while paused.
 *  5. Disconnect Veil: smooth closing reticle settling cleanly back into the staggered waiting UI.
 */
class CinematicIntroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class AnimationMode {
        NONE,
        COLD_INTRO,
        CAST_CONNECT,
        SOURCE_SWITCH,
        PAUSE_STATE,
        CAST_DISCONNECT
    }

    companion object {
        private const val INTRO_DURATION_MS = 1420L
        private const val CONNECT_DURATION_MS = 1200L
        private const val SOURCE_SWITCH_DURATION_MS = 960L
        private const val PAUSE_TRANSITION_DURATION_MS = 640L
        private const val DISCONNECT_DURATION_MS = 920L

        private const val COLOR_OBSIDIAN_RGB = 0x08090C
        private val COLOR_OBSIDIAN = Color.parseColor("#08090C")
        private val COLOR_FIRE_ORANGE = Color.parseColor("#FF6B1A")
        private val COLOR_FIRE_AMBER = Color.parseColor("#FF9A4D")
        private val COLOR_EMERALD = Color.parseColor("#22C55E")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#F5F5F7")
    }

    private var currentMode = AnimationMode.NONE
    private var elapsedAnimMs = 0L
    private var lastFrameUptimeMs = 0L
    private var isRunning = false
    private var revealTriggered = false
    private var onRevealStartCallback: (() -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var activeAudioTrack: AudioTrack? = null

    private var portalSenderName: String = "PC"
    private var switchSourceName: String = "Tela"
    private var switchIsWindow: Boolean = false
    private var switchBadgeOverride: String? = null
    private var pauseTargetState: Boolean = false
    private var disconnectReason: String = ""

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val strokeThinDp = 1.25f * density
    private val stroke175Dp = 1.75f * density

    private val officialLogoBitmap: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.ic_castviewer_logo_official)
    } catch (_: Exception) {
        null
    }

    // Reusable geometry & coordinate buffers (zero GC pressure inside onDraw)
    private val shaderMatrix = Matrix()
    private val logoDstRect = RectF()
    private val arcRect = RectF()
    private val innerArcRect = RectF()
    private val cardRect = RectF()
    private val iconRect = RectF()
    private val secondaryWindowRect = RectF()
    private val morphLeftPath = Path()
    private val morphRightPath = Path()
    private val morphCoords = FloatArray(16)

    // Pre-built GPU unit shaders positioned dynamically via Matrix
    private val fireAuraShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x4EFF6B1A, 0x26FF8A3D, 0x0CFF6B1A, 0x00000000),
        floatArrayOf(0f, 0.30f, 0.68f, 1f),
        Shader.TileMode.CLAMP
    )
    private val emeraldAuraShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x4622C55E, 0x2234D399, 0x0B22C55E, 0x00000000),
        floatArrayOf(0f, 0.30f, 0.68f, 1f),
        Shader.TileMode.CLAMP
    )
    private val photonOrangeShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0xEEFFB070.toInt(), 0x55FF6B1A, 0x00FF6B1A),
        floatArrayOf(0f, 0.24f, 0.56f, 1f),
        Shader.TileMode.CLAMP
    )
    private val photonEmeraldShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0xEE7BF0A6.toInt(), 0x5522C55E, 0x0022C55E),
        floatArrayOf(0f, 0.24f, 0.56f, 1f),
        Shader.TileMode.CLAMP
    )
    private val horizonBeamShader = LinearGradient(
        -1f, 0f, 1f, 0f,
        intArrayOf(0x00FF6B1A, 0xAAFF6B1A.toInt(), 0xFFFFD8B8.toInt(), 0xAAFF6B1A.toInt(), 0x00FF6B1A),
        floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1f),
        Shader.TileMode.CLAMP
    )
    private val glassSheenShader = LinearGradient(
        -1f, 0f, 1f, 0f,
        intArrayOf(0x00FFFFFF, 0x26FFFFFF, 0x48FFFFFF, 0x26FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f),
        Shader.TileMode.CLAMP
    )
    private val orangeCometShader = SweepGradient(
        0f, 0f,
        intArrayOf(0x00FF6B1A, 0x28FF6B1A, 0xCCFF6B1A.toInt(), 0xFFFFB273.toInt(), 0x00FF6B1A),
        floatArrayOf(0f, 0.26f, 0.64f, 0.88f, 0.995f)
    )
    private val emeraldCometShader = SweepGradient(
        0f, 0f,
        intArrayOf(0x0022C55E, 0x2822C55E, 0xCC22C55E.toInt(), 0xFF86EFAC.toInt(), 0x0022C55E),
        floatArrayOf(0f, 0.26f, 0.64f, 0.88f, 0.995f)
    )

    // Dedicated Paints
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val photonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        shader = horizonBeamShader
    }
    private val sheenStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1f * density
        shader = glassSheenShader
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = stroke175Dp
    }
    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeThinDp
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = stroke175Dp
    }
    private val morphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.barlow_bold_italic)
            ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.barlow_bold_italic)
            ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.barlow_condensed)
            ?: Typeface.DEFAULT
    }

    val isCastPortalRunning: Boolean
        get() = isRunning && currentMode == AnimationMode.CAST_CONNECT

    init {
        visibility = GONE
    }

    // --- Runtime Localization Helpers (pt default/primary, es, en) ---

    private fun currentLang(): String {
        val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return when {
            lang.startsWith("es") -> "es"
            lang.startsWith("en") -> "en"
            else -> "pt"
        }
    }

    private fun localizedIntroSubtitle(): String = when (currentLang()) {
        "es" -> "TRANSMISIÓN EN TIEMPO REAL • 60 FPS"
        "en" -> "REAL-TIME STREAMING • 60 FPS"
        else -> "TRANSMISSÃO EM TEMPO REAL • 60 FPS"
    }

    private fun localizedConnectBadge(): String = when (currentLang()) {
        "es" -> "CONEXIÓN DIRECTA • 1080P 60FPS"
        "en" -> "DIRECT LINK • 1080P 60FPS"
        else -> "CONEXÃO DIRETA • 1080P 60FPS"
    }

    private fun localizedConnectTitle(sender: String): String = when (currentLang()) {
        "es" -> "Conectado a $sender"
        "en" -> "Connected to $sender"
        else -> "Conectado a $sender"
    }

    private fun localizedSwitchBadge(isWindow: Boolean): String = switchBadgeOverride ?: when (currentLang()) {
        "es" -> if (isWindow) "CAMBIANDO VENTANA" else "CAMBIANDO PANTALLA"
        "en" -> if (isWindow) "SWITCHING WINDOW" else "SWITCHING SCREEN"
        else -> if (isWindow) "ALTERNANDO JANELA" else "ALTERNANDO TELA"
    }

    private fun localizedImageModeBadge(): String = when (currentLang()) {
        "es" -> "MODO DE IMAGEN"
        "en" -> "IMAGE MODE"
        else -> "MODO DE IMAGEM"
    }

    private fun localizedImageModeTitle(mode: String): String {
        val isDetail = mode.equals("detail", ignoreCase = true)
        return when (currentLang()) {
            "es" -> if (isDetail) "Ultra Nitidez • 22 Mbps" else "Fluidez Máxima • 60 FPS"
            "en" -> if (isDetail) "Ultra Sharpness • 22 Mbps" else "Max Fluidity • 60 FPS"
            else -> if (isDetail) "Ultra Nitidez • 22 Mbps" else "Fluidez Máxima • 60 FPS"
        }
    }

    private fun localizedPausedPersistentPill(): String = when (currentLang()) {
        "es" -> "TRANSMISIÓN PAUSADA • Pulsa Play/Pause para reanudar"
        "en" -> "STREAM PAUSED • Press Play/Pause to resume"
        else -> "TRANSMISSÃO PAUSADA • Aperte Play/Pause para retomar"
    }

    private fun localizedPauseBadge(isPaused: Boolean): String = when (currentLang()) {
        "es" -> if (isPaused) "TRANSMISIÓN EN PAUSA" else "EN TIEMPO REAL • 60 FPS"
        "en" -> if (isPaused) "STREAM PAUSED" else "LIVE • 60 FPS"
        else -> if (isPaused) "TRANSMISSÃO PAUSADA" else "EM TEMPO REAL • 60 FPS"
    }

    private fun localizedPauseTitle(isPaused: Boolean): String = when (currentLang()) {
        "es" -> if (isPaused) "Imagen Congelada" else "Transmisión Reanudada"
        "en" -> if (isPaused) "Frame Frozen" else "Stream Resumed"
        else -> if (isPaused) "Imagem Pausada" else "Transmissão Retomada"
    }

    private fun localizedPausedSubtext(): String = when (currentLang()) {
        "es" -> "Video y audio en espera • Control remoto / PC"
        "en" -> "Video & audio on hold • Remote / PC"
        else -> "Vídeo e áudio em espera • Controle / PC"
    }

    private fun localizedVideoProfileBadge(): String = when (currentLang()) {
        "es" -> "CALIDAD DE VÍDEO"
        "en" -> "VIDEO QUALITY"
        else -> "QUALIDADE DE VÍDEO"
    }

    private fun localizedLatencyModeBadge(): String = when (currentLang()) {
        "es" -> "CONTROL DE LATENCIA"
        "en" -> "LATENCY CONTROL"
        else -> "CONTROLE DE LATÊNCIA"
    }

    private fun localizedLatencyModeTitle(mode: String): String {
        val isBalanced = mode.equals("balanced", ignoreCase = true)
        return when (currentLang()) {
            "es" -> if (isBalanced) "Equilibrada Wi-Fi • ~60 ms" else "Ultra Baja • 0 ms"
            "en" -> if (isBalanced) "Balanced Wi-Fi • ~60 ms" else "Ultra Low • 0 ms"
            else -> if (isBalanced) "Equilibrada Wi-Fi • ~60 ms" else "Ultra Baixa • 0 ms"
        }
    }

    private fun localizedDisconnectBadge(): String = when (currentLang()) {
        "es" -> "SESIÓN FINALIZADA"
        "en" -> "SESSION ENDED"
        else -> "SESSÃO FINALIZADA"
    }

    private fun localizedDisconnectTitle(): String = when (currentLang()) {
        "es" -> "Transmisión Finalizada"
        "en" -> "Stream Ended"
        else -> "Transmissão Encerrada"
    }

    // --- Public Animation Triggers ---

    /**
     * Animation 1: Cold-Launch Intro with overlapped waiting-screen reveal callback.
     */
    fun playIntro(
        onRevealStart: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        startMode(
            mode = AnimationMode.COLD_INTRO,
            onRevealStart = onRevealStart,
            onComplete = onComplete
        )
    }

    fun startIntro(onComplete: () -> Unit = {}) {
        playIntro(onComplete = onComplete)
    }

    /**
     * Animation 2: Connect Portal / Start Receiving with smooth orange->emerald lock & radial reveal.
     */
    fun playCastConnectAnimation(senderName: String, onComplete: () -> Unit = {}) {
        portalSenderName = senderName.ifBlank { "PC" }
        startMode(mode = AnimationMode.CAST_CONNECT, onComplete = onComplete)
    }

    /**
     * Animation 3: Sleek Top-Center Dynamic Glass Pill for Screen/Window Switch & live adjustments.
     */
    fun playSourceSwitchAnimation(
        sourceName: String,
        isWindow: Boolean,
        onComplete: () -> Unit = {}
    ) {
        switchBadgeOverride = null
        switchSourceName = sourceName.ifBlank {
            when (currentLang()) {
                "es" -> if (isWindow) "Ventana" else "Pantalla"
                "en" -> if (isWindow) "Window" else "Screen"
                else -> if (isWindow) "Janela" else "Tela"
            }
        }
        switchIsWindow = isWindow
        startMode(mode = AnimationMode.SOURCE_SWITCH, onComplete = onComplete)
    }

    private fun playKineticSettingAnimation(
        badge: String,
        title: String,
        isAlternateAccent: Boolean,
        onComplete: () -> Unit = {}
    ) {
        switchBadgeOverride = badge
        switchSourceName = title
        switchIsWindow = isAlternateAccent
        startMode(mode = AnimationMode.SOURCE_SWITCH, onComplete = onComplete)
    }

    fun playImageModeAnimation(mode: String, onComplete: () -> Unit = {}) {
        val isDetail = mode.equals("detail", ignoreCase = true)
        playKineticSettingAnimation(
            badge = localizedImageModeBadge(),
            title = localizedImageModeTitle(mode),
            isAlternateAccent = isDetail,
            onComplete = onComplete
        )
    }

    fun playVideoProfileAnimation(label: String, name: String, onComplete: () -> Unit = {}) {
        val cleanLabel = label.trim()
        val cleanName = name.trim()
        val title = when {
            cleanLabel.isEmpty() -> cleanName.ifEmpty { "1080p60" }
            cleanName.isEmpty() || cleanName.equals(cleanLabel, ignoreCase = true) -> cleanLabel
            cleanName.contains(cleanLabel, ignoreCase = true) -> cleanName
            else -> "$cleanLabel • $cleanName"
        }
        playKineticSettingAnimation(
            badge = localizedVideoProfileBadge(),
            title = title,
            isAlternateAccent = cleanLabel.contains("1080", ignoreCase = true),
            onComplete = onComplete
        )
    }

    fun playLatencyModeAnimation(mode: String, onComplete: () -> Unit = {}) {
        val isBalanced = mode.equals("balanced", ignoreCase = true)
        playKineticSettingAnimation(
            badge = localizedLatencyModeBadge(),
            title = localizedLatencyModeTitle(mode),
            isAlternateAccent = isBalanced,
            onComplete = onComplete
        )
    }

    /**
     * Animation 4: Play / Pause continuous vertex morph & living breathing state.
     */
    fun playPauseStateAnimation(isPaused: Boolean, onComplete: () -> Unit = {}) {
        pauseTargetState = isPaused
        startMode(mode = AnimationMode.PAUSE_STATE, onComplete = onComplete)
    }

    /**
     * Animation 5: Smooth Disconnect Portal returning gracefully to waiting screen.
     */
    fun playCastDisconnectAnimation(
        reason: String = "",
        onRevealStart: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        disconnectReason = reason
        startMode(
            mode = AnimationMode.CAST_DISCONNECT,
            onRevealStart = onRevealStart,
            onComplete = onComplete
        )
    }

    fun cancelIntro() {
        if (currentMode == AnimationMode.COLD_INTRO) {
            cancelAllAnimations()
        }
    }

    fun cancelAllAnimations() {
        isRunning = false
        currentMode = AnimationMode.NONE
        revealTriggered = false
        onRevealStartCallback = null
        onCompleteCallback = null
        stopAudioTrack()
        visibility = GONE
    }

    private fun startMode(
        mode: AnimationMode,
        onRevealStart: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        stopAudioTrack()
        currentMode = mode
        elapsedAnimMs = 0L
        lastFrameUptimeMs = 0L
        revealTriggered = false
        onRevealStartCallback = onRevealStart
        onCompleteCallback = onComplete
        isRunning = true
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    private fun stopAudioTrack() {
        try {
            activeAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {
        } finally {
            activeAudioTrack = null
        }
    }

    private fun triggerRevealIfNeeded() {
        if (!revealTriggered) {
            revealTriggered = true
            val cb = onRevealStartCallback
            onRevealStartCallback = null
            cb?.invoke()
        }
    }

    // --- 60 FPS VSync Canvas Pipeline ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isRunning) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            postInvalidateOnAnimation()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (lastFrameUptimeMs == 0L) {
            lastFrameUptimeMs = now
        } else {
            val rawDelta = now - lastFrameUptimeMs
            lastFrameUptimeMs = now
            // Cap step for first 80ms so cold-start EGL/signaling warmup never jumps past opening frames
            val maxStep = if (elapsedAnimMs < 80L) 24L else 48L
            elapsedAnimMs = KineticMath.advanceCappedClockMs(elapsedAnimMs, rawDelta, maxStep)
        }

        when (currentMode) {
            AnimationMode.COLD_INTRO -> drawColdIntro(canvas, w, h)
            AnimationMode.CAST_CONNECT -> drawCastConnect(canvas, w, h)
            AnimationMode.SOURCE_SWITCH -> drawSourceSwitch(canvas, w, h)
            AnimationMode.PAUSE_STATE -> drawPauseState(canvas, w, h)
            AnimationMode.CAST_DISCONNECT -> drawCastDisconnect(canvas, w, h)
            AnimationMode.NONE -> {}
        }
    }

    private fun drawVolumetricAura(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        alphaMultiplier: Float,
        emerald: Boolean = false
    ) {
        val alphaInt = (KineticMath.clamp01(alphaMultiplier) * 255f).toInt().coerceIn(0, 255)
        if (alphaInt <= 2 || radius <= 4f) return
        val shader = if (emerald) emeraldAuraShader else fireAuraShader
        shaderMatrix.reset()
        shaderMatrix.setScale(radius, radius)
        shaderMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(shaderMatrix)
        auraPaint.shader = shader
        auraPaint.alpha = alphaInt
        canvas.drawCircle(cx, cy, radius, auraPaint)
    }

    private fun drawOpticalPhoton(
        canvas: Canvas,
        x: Float,
        y: Float,
        alphaMultiplier: Float,
        emerald: Boolean = false,
        radiusDp: Float = 10f
    ) {
        val alphaInt = (KineticMath.clamp01(alphaMultiplier) * 255f).toInt().coerceIn(0, 255)
        if (alphaInt <= 2) return
        val glowR = radiusDp * density
        val shader = if (emerald) photonEmeraldShader else photonOrangeShader
        shaderMatrix.reset()
        shaderMatrix.setScale(glowR, glowR)
        shaderMatrix.postTranslate(x, y)
        shader.setLocalMatrix(shaderMatrix)
        photonGlowPaint.shader = shader
        photonGlowPaint.alpha = alphaInt
        canvas.drawCircle(x, y, glowR, photonGlowPaint)
    }

    private fun drawFeatheredIrisBackdrop(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        irisProgress: Float,
        rimGlowColor: Int = COLOR_FIRE_ORANGE
    ) {
        val p = KineticMath.clamp01(irisProgress)
        if (p <= 0.001f) {
            surfacePaint.color = COLOR_OBSIDIAN
            canvas.drawRect(0f, 0f, w, h, surfacePaint)
            return
        }

        val fadeOut = 1f - KineticMath.easeInOutQuint(p)
        val bgAlpha = (fadeOut * 255f).toInt().coerceIn(0, 255)
        if (bgAlpha > 2) {
            surfacePaint.color = Color.argb(bgAlpha, 8, 9, 12)
            canvas.drawRect(0f, 0f, w, h, surfacePaint)
        }

        // Crisp expanding portal wave riding ahead of the reveal without full-screen fragment shader fill
        val expand = KineticMath.easeOutExpo(p)
        val portalRadius = (0.10f + 0.75f * expand) * kotlin.math.hypot(w, h) * 0.65f
        val rimFade = sin(p * PI.toFloat()) * (1f - p * 0.30f)
        val rimAlpha = (rimFade * 175f).toInt().coerceIn(0, 255)
        if (rimAlpha > 2) {
            ringStrokePaint.strokeWidth = stroke175Dp
            ringStrokePaint.color = Color.argb(
                rimAlpha,
                Color.red(rimGlowColor),
                Color.green(rimGlowColor),
                Color.blue(rimGlowColor)
            )
            canvas.drawCircle(cx, cy, portalRadius, ringStrokePaint)
        }
    }

    private fun drawCometArc(
        canvas: Canvas,
        bounds: RectF,
        startDeg: Float,
        sweepDeg: Float,
        alphaMultiplier: Float,
        emeraldMix: Float = 0f,
        strokePx: Float = stroke175Dp
    ) {
        val a = KineticMath.clamp01(alphaMultiplier)
        if (a <= 0.01f || kotlin.math.abs(sweepDeg) < 0.5f) return

        val headDeg = startDeg + sweepDeg
        val mix = KineticMath.clamp01(emeraldMix)

        if (mix < 0.99f) {
            val orangeAlpha = (a * (1f - mix) * 240f).toInt().coerceIn(0, 255)
            shaderMatrix.reset()
            shaderMatrix.setRotate(KineticMath.cometShaderRotationDeg(headDeg))
            orangeCometShader.setLocalMatrix(shaderMatrix)
            cometPaint.shader = orangeCometShader
            cometPaint.strokeWidth = strokePx
            cometPaint.alpha = orangeAlpha
            canvas.drawArc(bounds, startDeg, sweepDeg, false, cometPaint)
        }
        if (mix > 0.01f) {
            val emeraldAlpha = (a * mix * 240f).toInt().coerceIn(0, 255)
            shaderMatrix.reset()
            shaderMatrix.setRotate(KineticMath.cometShaderRotationDeg(headDeg))
            emeraldCometShader.setLocalMatrix(shaderMatrix)
            cometPaint.shader = emeraldCometShader
            cometPaint.strokeWidth = strokePx
            cometPaint.alpha = emeraldAlpha
            canvas.drawArc(bounds, startDeg, sweepDeg, false, cometPaint)
        }
    }

    private fun drawHorizonFilament(
        canvas: Canvas,
        cx: Float,
        y: Float,
        halfSpan: Float,
        alphaMultiplier: Float,
        thicknessPx: Float = stroke175Dp
    ) {
        val a = (KineticMath.clamp01(alphaMultiplier) * 255f).toInt().coerceIn(0, 255)
        if (a <= 2 || halfSpan <= 2f) return
        shaderMatrix.reset()
        shaderMatrix.setScale(halfSpan, 1f)
        shaderMatrix.postTranslate(cx, y)
        horizonBeamShader.setLocalMatrix(shaderMatrix)
        horizonPaint.alpha = a
        horizonPaint.strokeWidth = thicknessPx
        canvas.drawLine(cx - halfSpan, y, cx + halfSpan, y, horizonPaint)
    }

    private fun drawGlassTopSheen(
        canvas: Canvas,
        rect: RectF,
        insetX: Float,
        alphaMultiplier: Float
    ) {
        val halfSpan = (rect.width() * 0.5f - insetX).coerceAtLeast(8f)
        val cx = rect.centerX()
        val y = rect.top + 0.75f * density
        val a = (KineticMath.clamp01(alphaMultiplier) * 235f).toInt().coerceIn(0, 255)
        if (a <= 2) return
        shaderMatrix.reset()
        shaderMatrix.setScale(halfSpan, 1f)
        shaderMatrix.postTranslate(cx, y)
        glassSheenShader.setLocalMatrix(shaderMatrix)
        sheenStrokePaint.alpha = a
        canvas.drawLine(cx - halfSpan, y, cx + halfSpan, y, sheenStrokePaint)
    }

    private fun drawOfficialLogo(canvas: Canvas, cx: Float, cy: Float, sizePx: Float, alpha: Float) {
        val bmp = officialLogoBitmap ?: return
        val half = sizePx * 0.5f
        logoPaint.alpha = (KineticMath.clamp01(alpha) * 255f).toInt().coerceIn(0, 255)
        logoDstRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(bmp, null, logoDstRect, logoPaint)
    }

    /**
     * Animation 1: Cold-Launch Intro (1420ms cinema sequence + overlapped waiting reveal at 56%).
     */
    private fun drawColdIntro(canvas: Canvas, w: Float, h: Float) {
        val t = (elapsedAnimMs.toFloat() / INTRO_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val cx = w * 0.5f
        val cy = h * 0.44f

        if (t >= 0.54f) {
            triggerRevealIfNeeded()
        }

        val irisP = KineticMath.windowProgress(t, 0.62f, 1.0f)
        val emblemExitFade = 1f - KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.56f, 0.84f))

        // Feathered obsidian radial curtain opening onto the already-staging waiting UI
        drawFeatheredIrisBackdrop(canvas, w, h, cx, cy, irisP, COLOR_FIRE_ORANGE)

        // Breathing volumetric ambient ember (bounded radius for 60 FPS PowerVR fill rate)
        val auraIn = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.04f, 0.42f))
        val auraOut = 1f - KineticMath.windowProgress(t, 0.58f, 0.90f)
        drawVolumetricAura(canvas, cx, cy, min(w, h) * 0.25f, auraIn * auraOut)

        // Stage 1: Tapered optical horizon beam expanding horizontally then condensing into orb
        if (t < 0.46f) {
            val lp = KineticMath.windowProgress(t, 0f, 0.46f)
            val expand = KineticMath.easeOutExpo(KineticMath.windowProgress(lp, 0f, 0.55f))
            val retract = KineticMath.easeInOutQuint(KineticMath.windowProgress(lp, 0.50f, 1.0f))
            val halfSpan = w * 0.34f * expand * (1f - retract * 0.72f)
            val beamAlpha = KineticMath.easeOutQuint(KineticMath.windowProgress(lp, 0f, 0.18f)) *
                (1f - KineticMath.windowProgress(lp, 0.55f, 1.0f))
            drawHorizonFilament(canvas, cx, cy, halfSpan, beamAlpha * 0.95f, 2.1f * density)
        }

        // Stage 2: Squircle Emblem + Tapered Comet Orbital Ring
        val logoIn = KineticMath.windowProgress(t, 0.08f, 0.50f)
        if (logoIn > 0f && emblemExitFade > 0.01f) {
            val riseEase = KineticMath.easeOutBackSubtle(logoIn)
            val offsetY = (1f - KineticMath.easeOutQuint(logoIn)) * 26f * density
            val expandExit = 1f + (1f - emblemExitFade) * 0.06f
            val scale = (0.86f + 0.14f * riseEase) * expandExit
            val combinedAlpha = KineticMath.easeOutQuint(KineticMath.windowProgress(logoIn, 0f, 0.55f)) * emblemExitFade

            canvas.save()
            canvas.translate(cx, cy + offsetY)
            canvas.scale(scale, scale)

            val orbitRadius = 72f * density
            arcRect.set(-orbitRadius, -orbitRadius, orbitRadius, orbitRadius)

            // Frosted hairline orbital track
            ringStrokePaint.strokeWidth = strokeThinDp
            ringStrokePaint.color = Color.argb((combinedAlpha * 42f).toInt().coerceIn(0, 255), 245, 245, 247)
            canvas.drawOval(arcRect, ringStrokePaint)

            // Tapered rotating optical comet closing 360 deg + glowing photon head
            val sweepP = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.10f, 0.62f))
            val headDeg = -90f + 390f * sweepP
            val arcSpan = min(290f, 360f * sweepP + 28f)
            drawCometArc(
                canvas = canvas,
                bounds = arcRect,
                startDeg = headDeg - arcSpan,
                sweepDeg = arcSpan,
                alphaMultiplier = combinedAlpha,
                emeraldMix = 0f,
                strokePx = 2f * density
            )

            // Secondary harmonic full ring glow once sweep completes
            val lockShimmer = KineticMath.windowProgress(t, 0.48f, 0.72f)
            if (lockShimmer > 0f) {
                ringStrokePaint.strokeWidth = strokeThinDp
                ringStrokePaint.color = Color.argb((lockShimmer * combinedAlpha * 115f).toInt(), 255, 138, 61)
                canvas.drawCircle(0f, 0f, orbitRadius, ringStrokePaint)
            }

            val tipX = KineticMath.orbitCos(headDeg) * orbitRadius
            val tipY = KineticMath.orbitSin(headDeg) * orbitRadius
            drawOpticalPhoton(canvas, tipX, tipY, combinedAlpha, emerald = false, radiusDp = 11f)

            drawOfficialLogo(canvas, 0f, 0f, 94f * density, combinedAlpha)
            canvas.restore()
        }

        // Stage 3: CastViewer Display Wordmark + Subtitle
        val textIn = KineticMath.windowProgress(t, 0.22f, 0.54f)
        if (textIn > 0f && emblemExitFade > 0.01f) {
            val textEase = KineticMath.easeOutQuint(textIn)
            val textAlpha = (textEase * emblemExitFade * 255f).toInt().coerceIn(0, 255)
            val slideY = (1f - textEase) * 16f * density
            val titleY = cy + 120f * density + slideY

            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.textSize = 40f * scaledDensity
            titlePaint.letterSpacing = -0.015f
            titlePaint.color = Color.argb(textAlpha, 245, 245, 247)
            canvas.drawText("CastViewer", cx, titleY, titlePaint)

            val subIn = KineticMath.windowProgress(t, 0.30f, 0.58f)
            if (subIn > 0f) {
                val subEase = KineticMath.easeOutQuint(subIn)
                val subAlpha = (subEase * emblemExitFade * 210f).toInt().coerceIn(0, 255)
                badgePaint.textAlign = Paint.Align.CENTER
                badgePaint.textSize = 12.5f * scaledDensity
                badgePaint.letterSpacing = 0.24f
                badgePaint.color = Color.argb(subAlpha, 255, 122, 46)
                canvas.drawText(localizedIntroSubtitle(), cx, titleY + 28f * density, badgePaint)
            }
        }

        if (t >= 1.0f) {
            finishAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Animation 2: Connect Portal (Dual counter-rotating orbitals, smooth Fire-Orange -> Emerald
     * chromatic lock + double sonar shockwave + radial aperture iris opening onto live stream).
     */
    private fun drawCastConnect(canvas: Canvas, w: Float, h: Float) {
        val t = (elapsedAnimMs.toFloat() / CONNECT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val cx = w * 0.5f
        val cy = h * 0.45f

        val veilIn = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.14f))
        val irisP = KineticMath.windowProgress(t, 0.58f, 1.0f)
        val centerFade = 1f - KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.54f, 0.86f))
        val emeraldMix = KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.38f, 0.54f))

        if (irisP > 0f) {
            val activeRimColor = KineticMath.lerpColorArgb(COLOR_FIRE_ORANGE, COLOR_EMERALD, emeraldMix)
            drawFeatheredIrisBackdrop(canvas, w, h, cx, cy, irisP, activeRimColor)
        } else {
            surfacePaint.color = Color.argb((veilIn * 242f).toInt().coerceIn(0, 255), 8, 9, 12)
            canvas.drawRect(0f, 0f, w, h, surfacePaint)
        }

        // Cross-fade aura from Fire-Orange to Emerald at lock moment
        val auraStrength = veilIn * centerFade
        val auraRadius = min(w, h) * 0.25f
        if (emeraldMix < 0.98f) {
            drawVolumetricAura(canvas, cx, cy, auraRadius, auraStrength * (1f - emeraldMix), emerald = false)
        }
        if (emeraldMix > 0.02f) {
            drawVolumetricAura(canvas, cx, cy, auraRadius * 1.04f, auraStrength * emeraldMix, emerald = true)
        }

        if (centerFade > 0.01f) {
            val enterEase = KineticMath.easeOutBackSubtle(KineticMath.windowProgress(t, 0.04f, 0.34f))
            val lockBump = KineticMath.windowProgress(t, 0.44f, 0.72f)
            val bumpScale = if (lockBump > 0f) sin(lockBump * PI.toFloat()) * 0.045f else 0f
            val scale = (0.88f + 0.12f * enterEase + bumpScale) * (1f + (1f - centerFade) * 0.06f)
            val combinedAlpha = veilIn * centerFade

            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)

            val outerR = 72f * density
            val innerR = 56f * density
            arcRect.set(-outerR, -outerR, outerR, outerR)
            innerArcRect.set(-innerR, -innerR, innerR, innerR)

            ringStrokePaint.strokeWidth = strokeThinDp
            ringStrokePaint.color = Color.argb((combinedAlpha * 42f).toInt(), 245, 245, 247)
            canvas.drawOval(arcRect, ringStrokePaint)

            val sweepP = KineticMath.easeOutExpo(KineticMath.windowProgress(t, 0.04f, 0.52f))
            val outerHeadDeg = -90f + 360f * sweepP
            val outerSpan = min(300f, 360f * sweepP + 20f)
            drawCometArc(canvas, arcRect, outerHeadDeg - outerSpan, outerSpan, combinedAlpha, emeraldMix, 2f * density)

            // Counter-rotating inner harmonic whisper arc
            val innerHeadDeg = 90f - 240f * sweepP
            drawCometArc(canvas, innerArcRect, innerHeadDeg, 160f * sweepP, combinedAlpha * 0.55f, emeraldMix, strokeThinDp)

            // Dual staggered sonar waves upon lock
            drawSonarWave(canvas, outerR, KineticMath.windowProgress(t, 0.42f, 0.84f), combinedAlpha, COLOR_EMERALD)
            drawSonarWave(canvas, outerR, KineticMath.windowProgress(t, 0.49f, 0.90f), combinedAlpha * 0.72f, COLOR_EMERALD)

            val tipX = KineticMath.orbitCos(outerHeadDeg) * outerR
            val tipY = KineticMath.orbitSin(outerHeadDeg) * outerR
            drawOpticalPhoton(canvas, tipX, tipY, combinedAlpha, emerald = emeraldMix > 0.5f, radiusDp = 11.5f)

            drawOfficialLogo(canvas, 0f, 0f, 88f * density, combinedAlpha)
            canvas.restore()

            // Smooth typography reveal
            val textEase = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.08f, 0.36f))
            val textAlpha = (textEase * combinedAlpha * 255f).toInt().coerceIn(0, 255)
            val slideY = (1f - textEase) * 14f * density
            val badgeY = cy + 114f * density + slideY
            val titleY = badgeY + 32f * density

            val badgeColor = KineticMath.lerpColorArgb(COLOR_FIRE_ORANGE, COLOR_EMERALD, emeraldMix)
            badgePaint.textAlign = Paint.Align.CENTER
            badgePaint.textSize = 12f * scaledDensity
            badgePaint.letterSpacing = 0.22f
            badgePaint.color = Color.argb(
                (textAlpha * 0.94f).toInt(),
                Color.red(badgeColor),
                Color.green(badgeColor),
                Color.blue(badgeColor)
            )
            canvas.drawText(localizedConnectBadge(), cx, badgeY, badgePaint)

            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.textSize = 31f * scaledDensity
            titlePaint.letterSpacing = -0.015f
            titlePaint.color = Color.argb(textAlpha, 245, 245, 247)
            canvas.drawText(localizedConnectTitle(portalSenderName), cx, titleY, titlePaint)
        }

        if (t >= 1.0f) {
            finishAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun drawSonarWave(
        canvas: Canvas,
        baseRadius: Float,
        waveProgress: Float,
        alphaMultiplier: Float,
        color: Int
    ) {
        if (waveProgress <= 0f || waveProgress >= 1f) return
        val expand = KineticMath.easeOutExpo(waveProgress)
        val r = baseRadius + expand * 36f * density
        val a = ((1f - waveProgress) * (1f - waveProgress) * alphaMultiplier * 190f).toInt().coerceIn(0, 255)
        if (a <= 2) return
        ringStrokePaint.strokeWidth = strokeThinDp
        ringStrokePaint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(0f, 0f, r, ringStrokePaint)
    }

    /**
     * Animation 3: Apple TV / tvOS Top-Center Floating Dynamic Glass Banner (non-intrusive over live stream,
     * zero clipPath overhead, soft volumetric top gleam + animated optical icon badge).
     */
    private fun drawSourceSwitch(canvas: Canvas, w: Float, h: Float) {
        val t = (elapsedAnimMs.toFloat() / SOURCE_SWITCH_DURATION_MS.toFloat()).coerceIn(0f, 1f)

        val enterP = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.26f))
        val exitP = KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.72f, 1.0f))
        val masterAlpha = (KineticMath.windowProgress(t, 0f, 0.16f) * (1f - exitP)).coerceIn(0f, 1f)

        val cx = w * 0.5f
        val cy = h * 0.135f

        drawVolumetricAura(canvas, cx, cy, 210f * density, masterAlpha * 0.58f, emerald = false)

        val cardW = min(w - 72f * density, 436f * density)
        val cardH = 82f * density
        val cardCorner = 24f * density
        val scale = 0.92f + 0.08f * enterP - 0.04f * exitP
        val translateY = (1f - enterP) * -22f * density - exitP * 10f * density

        canvas.save()
        canvas.translate(cx, cy + translateY)
        canvas.scale(scale, scale)

        cardRect.set(-cardW * 0.5f, -cardH * 0.5f, cardW * 0.5f, cardH * 0.5f)

        // Multi-stop dark obsidian glass container
        surfacePaint.color = Color.argb((masterAlpha * 238f).toInt().coerceIn(0, 255), 11, 12, 17)
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, surfacePaint)

        // Hairline frosted rim border
        ringStrokePaint.strokeWidth = 1f * density
        ringStrokePaint.color = Color.argb((masterAlpha * 56f).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, ringStrokePaint)

        // Subtle orange rim glow hint
        ringStrokePaint.color = Color.argb((masterAlpha * 68f).toInt().coerceIn(0, 255), 255, 107, 26)
        canvas.drawRoundRect(cardRect, cardCorner, cardCorner, ringStrokePaint)

        // Top glass specular gleam
        drawGlassTopSheen(canvas, cardRect, 26f * density, masterAlpha)

        // Bottom tapered optical laser gleam (bounded cleanly without clipPath)
        val sweep = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.08f, 0.76f))
        val filamentMaxHalf = cardW * 0.42f
        val filamentCenter = -cardW * 0.22f + sweep * (cardW * 0.44f)
        val filamentSpan = filamentMaxHalf * sin(t * PI.toFloat()).coerceAtLeast(0.25f)
        drawHorizonFilament(
            canvas,
            filamentCenter.coerceIn(-cardW * 0.25f, cardW * 0.25f),
            cardRect.bottom - 1f * density,
            filamentSpan,
            masterAlpha * sin(t * PI.toFloat()),
            1.6f * density
        )

        // Circular obsidian well on left holding the morphing vector glyph
        val iconCx = -cardW * 0.5f + 46f * density
        val badgeWellR = 24f * density
        surfacePaint.color = Color.argb((masterAlpha * 225f).toInt(), 20, 22, 30)
        canvas.drawCircle(iconCx, 0f, badgeWellR, surfacePaint)
        ringStrokePaint.strokeWidth = 1f * density
        ringStrokePaint.color = Color.argb((masterAlpha * 95f).toInt(), 255, 107, 26)
        canvas.drawCircle(iconCx, 0f, badgeWellR, ringStrokePaint)

        val morphP = KineticMath.easeOutBackSubtle(KineticMath.windowProgress(t, 0.06f, 0.44f))
        drawMorphingSourceIcon(canvas, iconCx, 0f, morphP, masterAlpha, switchIsWindow)

        val textLeftX = -cardW * 0.5f + 86f * density
        val maxTextWidth = cardW - 106f * density

        badgePaint.textAlign = Paint.Align.LEFT
        badgePaint.textSize = 11f * scaledDensity
        badgePaint.letterSpacing = 0.20f
        badgePaint.color = Color.argb((masterAlpha * 242f).toInt().coerceIn(0, 255), 255, 122, 46)
        canvas.drawText(localizedSwitchBadge(switchIsWindow), textLeftX, -7f * density, badgePaint)
        badgePaint.textAlign = Paint.Align.CENTER

        titlePaint.textAlign = Paint.Align.LEFT
        titlePaint.textSize = 20.5f * scaledDensity
        titlePaint.letterSpacing = -0.01f
        titlePaint.color = Color.argb((masterAlpha * 255f).toInt().coerceIn(0, 255), 245, 245, 247)
        val ellipsizedSource = TextUtils.ellipsize(
            switchSourceName,
            titlePaint,
            maxTextWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedSource, textLeftX, 18f * density, titlePaint)
        titlePaint.textAlign = Paint.Align.CENTER

        canvas.restore()

        if (t >= 1.0f) {
            finishAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun drawMorphingSourceIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        morphP: Float,
        alpha: Float,
        isWindow: Boolean
    ) {
        val strokeAlpha = (alpha * 242f).toInt().coerceIn(0, 255)
        val accentAlpha = (alpha * KineticMath.clamp01(morphP) * 242f).toInt().coerceIn(0, 255)
        val w = (10f + 3f * morphP) * density
        val h = (7.5f + 1.8f * morphP) * density
        val corner = 3f * density

        iconStrokePaint.strokeWidth = 1.5f * density

        if (isWindow) {
            val shift = 3.2f * density * morphP
            secondaryWindowRect.set(
                cx - w + shift,
                cy - h - shift * 0.7f,
                cx + w + shift,
                cy + h - shift * 0.7f
            )
            iconStrokePaint.color = Color.argb((strokeAlpha * 0.42f).toInt(), 245, 245, 247)
            canvas.drawRoundRect(secondaryWindowRect, corner, corner, iconStrokePaint)

            iconRect.set(
                cx - w - shift * 0.45f,
                cy - h + shift * 0.35f,
                cx + w - shift * 0.45f,
                cy + h + shift * 0.35f
            )
            iconStrokePaint.color = Color.argb(strokeAlpha, 245, 245, 247)
            canvas.drawRoundRect(iconRect, corner, corner, iconStrokePaint)

            val barY = iconRect.top + 4.8f * density
            iconStrokePaint.color = Color.argb(accentAlpha, 255, 107, 26)
            canvas.drawLine(iconRect.left + 1.5f * density, barY, iconRect.right - 1.5f * density, barY, iconStrokePaint)
        } else {
            val monOffsetY = -1.8f * density * morphP
            iconRect.set(cx - w, cy - h + monOffsetY, cx + w, cy + h + monOffsetY)
            iconStrokePaint.color = Color.argb(strokeAlpha, 245, 245, 247)
            canvas.drawRoundRect(iconRect, corner, corner, iconStrokePaint)

            val standTop = iconRect.bottom
            val standBottom = standTop + 4f * density * morphP
            val baseHalfW = 6f * density * morphP
            iconStrokePaint.color = Color.argb(accentAlpha, 255, 107, 26)
            canvas.drawLine(cx, standTop, cx, standBottom, iconStrokePaint)
            canvas.drawLine(cx - baseHalfW, standBottom, cx + baseHalfW, standBottom, iconStrokePaint)
        }
    }

    /**
     * Animation 4: Continuous VSync Pause State & Unpause Emerald Bloom.
     */
    private fun drawPauseState(canvas: Canvas, w: Float, h: Float) {
        val elapsed = elapsedAnimMs
        val t = (elapsed.toFloat() / PAUSE_TRANSITION_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val cx = w * 0.5f

        if (pauseTargetState) {
            val enterEase = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.55f))
            val enterScale = KineticMath.easeOutBackSubtle(KineticMath.windowProgress(t, 0f, 0.58f))
            val scrimP = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.45f))
            val breathe = KineticMath.breatheWave(elapsed, cycleHz = 0.45f)
            val cy = h * 0.5f

            surfacePaint.color = Color.argb((scrimP * 188f).toInt().coerceIn(0, 255), 6, 7, 11)
            canvas.drawRect(0f, 0f, w, h, surfacePaint)

            drawVolumetricAura(canvas, cx, cy, 240f * density, enterEase * (0.72f + 0.24f * breathe), emerald = false)

            val pillText = localizedPausedPersistentPill()
            subtitlePaint.textSize = 12.5f * scaledDensity
            subtitlePaint.letterSpacing = 0.05f
            val pillTextW = subtitlePaint.measureText(pillText)

            val cardW = max(min(w * 0.66f, 456f * density), pillTextW + 84f * density).coerceAtMost(w - 56f * density)
            val cardH = 236f * density
            val cardCorner = 28f * density
            val scale = 0.90f + 0.10f * enterScale

            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)

            cardRect.set(-cardW * 0.5f, -cardH * 0.5f, cardW * 0.5f, cardH * 0.5f)
            surfacePaint.color = Color.argb((enterEase * 240f).toInt(), 11, 12, 17)
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, surfacePaint)

            ringStrokePaint.strokeWidth = 1.25f * density
            ringStrokePaint.color = Color.argb((enterEase * (78f + 92f * breathe)).toInt(), 255, 107, 26)
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, ringStrokePaint)

            drawGlassTopSheen(canvas, cardRect, 28f * density, enterEase)

            // Living orbital emblem
            val emblemY = -58f * density
            val emblemR = 32f * density
            surfacePaint.color = Color.argb((enterEase * 240f).toInt(), 19, 21, 28)
            canvas.drawCircle(0f, emblemY, emblemR, surfacePaint)

            arcRect.set(-emblemR, emblemY - emblemR, emblemR, emblemY + emblemR)
            ringStrokePaint.strokeWidth = strokeThinDp
            ringStrokePaint.color = Color.argb((enterEase * 44f).toInt(), 245, 245, 247)
            canvas.drawOval(arcRect, ringStrokePaint)

            val sweepEnter = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.05f, 0.65f))
            val idleSpinDeg = if (t < 1f) 0f else (elapsed - PAUSE_TRANSITION_DURATION_MS) * 0.045f
            val headDeg = -90f + 360f * sweepEnter + idleSpinDeg
            val spanDeg = 270f * sweepEnter

            canvas.save()
            canvas.translate(0f, emblemY)
            arcRect.set(-emblemR, -emblemR, emblemR, emblemR)
            drawCometArc(canvas, arcRect, headDeg - spanDeg, spanDeg, enterEase * (0.82f + 0.18f * breathe), 0f, stroke175Dp)
            val tipX = KineticMath.orbitCos(headDeg) * emblemR
            val tipY = KineticMath.orbitSin(headDeg) * emblemR
            drawOpticalPhoton(canvas, tipX, tipY, enterEase, emerald = false, radiusDp = 8.5f)
            canvas.restore()

            val morphToPause = KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.05f, 0.52f))
            drawMorphingPlayPauseGlyph(canvas, 0f, emblemY, 12.5f * density, morphToPause, enterEase)

            badgePaint.textAlign = Paint.Align.CENTER
            badgePaint.textSize = 11.5f * scaledDensity
            badgePaint.letterSpacing = 0.22f
            badgePaint.color = Color.argb((enterEase * 242f).toInt(), 255, 122, 46)
            canvas.drawText(localizedPauseBadge(true), 0f, -3f * density, badgePaint)

            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.textSize = 24f * scaledDensity
            titlePaint.letterSpacing = -0.015f
            titlePaint.color = Color.argb((enterEase * 255f).toInt(), 245, 245, 247)
            canvas.drawText(localizedPauseTitle(true), 0f, 27f * density, titlePaint)

            subtitlePaint.textAlign = Paint.Align.CENTER
            subtitlePaint.textSize = 13f * scaledDensity
            subtitlePaint.letterSpacing = 0.02f
            subtitlePaint.color = Color.argb((enterEase * 178f).toInt(), 184, 188, 200)
            canvas.drawText(localizedPausedSubtext(), 0f, 50f * density, subtitlePaint)

            // Bottom frosted chip
            val chipW = min(cardW - 36f * density, pillTextW + 52f * density)
            val chipH = 34f * density
            val chipCy = 84f * density
            val chipCorner = 17f * density
            iconRect.set(-chipW * 0.5f, chipCy - chipH * 0.5f, chipW * 0.5f, chipCy + chipH * 0.5f)
            surfacePaint.color = Color.argb((enterEase * 224f).toInt(), 24, 26, 35)
            canvas.drawRoundRect(iconRect, chipCorner, chipCorner, surfacePaint)

            ringStrokePaint.strokeWidth = 1f * density
            ringStrokePaint.color = Color.argb((enterEase * (65f + 75f * breathe)).toInt(), 255, 107, 26)
            canvas.drawRoundRect(iconRect, chipCorner, chipCorner, ringStrokePaint)

            subtitlePaint.textSize = 11.5f * scaledDensity
            subtitlePaint.letterSpacing = 0.05f
            subtitlePaint.color = Color.argb((enterEase * 244f).toInt(), 245, 245, 247)
            val ellipsizedPill = TextUtils.ellipsize(pillText, subtitlePaint, chipW - 24f * density, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsizedPill, 0f, chipCy + 4f * density, subtitlePaint)

            canvas.restore()

            // Continuous VSync orbital motion & glow breathing while paused
            postInvalidateOnAnimation()
            return
        }

        // Resume animation: morph || -> ▶ + Emerald sonar burst
        val cy = h * 0.46f
        val enterP = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.30f))
        val exitFade = 1f - KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.56f, 1.0f))
        val centerAlpha = (enterP * exitFade).coerceIn(0f, 1f)

        val vigAlpha = (centerAlpha * 72f).toInt().coerceIn(0, 255)
        if (vigAlpha > 2) {
            surfacePaint.color = Color.argb(vigAlpha, 8, 9, 12)
            canvas.drawRect(0f, 0f, w, h, surfacePaint)
        }

        if (centerAlpha > 0.01f) {
            drawVolumetricAura(canvas, cx, cy, 200f * density, centerAlpha, emerald = true)

            val scale = 0.90f + 0.10f * KineticMath.easeOutBackSubtle(KineticMath.windowProgress(t, 0f, 0.42f)) +
                (1f - exitFade) * 0.08f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)

            val radius = 46f * density
            surfacePaint.color = Color.argb((centerAlpha * 232f).toInt(), 10, 11, 16)
            canvas.drawCircle(0f, 0f, radius, surfacePaint)

            arcRect.set(-radius, -radius, radius, radius)
            ringStrokePaint.strokeWidth = strokeThinDp
            ringStrokePaint.color = Color.argb((centerAlpha * 46f).toInt(), 245, 245, 247)
            canvas.drawOval(arcRect, ringStrokePaint)

            val sweepP = KineticMath.easeOutExpo(KineticMath.windowProgress(t, 0.04f, 0.56f))
            val headDeg = -90f + 360f * sweepP
            drawCometArc(canvas, arcRect, headDeg - min(290f, 360f * sweepP + 20f), min(290f, 360f * sweepP + 20f), centerAlpha, 1f, 2f * density)
            drawSonarWave(canvas, radius, KineticMath.windowProgress(t, 0.25f, 0.92f), centerAlpha, COLOR_EMERALD)

            val tipX = KineticMath.orbitCos(headDeg) * radius
            val tipY = KineticMath.orbitSin(headDeg) * radius
            drawOpticalPhoton(canvas, tipX, tipY, centerAlpha, emerald = true, radiusDp = 10f)

            val morphToPause = 1f - KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.04f, 0.46f))
            drawMorphingPlayPauseGlyph(canvas, 0f, 0f, 15f * density, morphToPause, centerAlpha)

            canvas.restore()

            val badgeY = cy + 76f * density
            val titleY = badgeY + 27f * density
            badgePaint.textAlign = Paint.Align.CENTER
            badgePaint.textSize = 11.5f * scaledDensity
            badgePaint.letterSpacing = 0.22f
            badgePaint.color = Color.argb((centerAlpha * 240f).toInt(), 34, 197, 94)
            canvas.drawText(localizedPauseBadge(false), cx, badgeY, badgePaint)

            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.textSize = 24.5f * scaledDensity
            titlePaint.letterSpacing = -0.015f
            titlePaint.color = Color.argb((centerAlpha * 255f).toInt(), 245, 245, 247)
            canvas.drawText(localizedPauseTitle(false), cx, titleY, titlePaint)
        }

        if (t >= 1.0f) {
            finishAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Smooth geometric quad morphing between Play triangle (pauseAmount = 0f) and Pause bars (pauseAmount = 1f).
     */
    private fun drawMorphingPlayPauseGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfSize: Float,
        pauseAmount: Float,
        alpha: Float
    ) {
        KineticMath.computePlayPauseMorphQuads(cx, cy, halfSize, pauseAmount, morphCoords)
        morphFillPaint.color = Color.argb((KineticMath.clamp01(alpha) * 252f).toInt().coerceIn(0, 255), 245, 245, 247)

        morphLeftPath.reset()
        morphLeftPath.moveTo(morphCoords[0], morphCoords[1])
        morphLeftPath.lineTo(morphCoords[2], morphCoords[3])
        morphLeftPath.lineTo(morphCoords[4], morphCoords[5])
        morphLeftPath.lineTo(morphCoords[6], morphCoords[7])
        morphLeftPath.close()
        canvas.drawPath(morphLeftPath, morphFillPaint)

        morphRightPath.reset()
        morphRightPath.moveTo(morphCoords[8], morphCoords[9])
        morphRightPath.lineTo(morphCoords[10], morphCoords[11])
        morphRightPath.lineTo(morphCoords[12], morphCoords[13])
        morphRightPath.lineTo(morphCoords[14], morphCoords[15])
        morphRightPath.close()
        canvas.drawPath(morphRightPath, morphFillPaint)
    }

    /**
     * Animation 5: Disconnect / Session Ended — clean closing veil + unwinding optical comet +
     * smooth radial aperture reveal onto the restored Waiting UI.
     */
    private fun drawCastDisconnect(canvas: Canvas, w: Float, h: Float) {
        val t = (elapsedAnimMs.toFloat() / DISCONNECT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val cx = w * 0.5f
        val cy = h * 0.45f

        if (t >= 0.52f) {
            triggerRevealIfNeeded()
        }

        val veilIn = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0f, 0.22f))
        val irisOut = KineticMath.windowProgress(t, 0.56f, 1.0f)
        val contentFade = 1f - KineticMath.easeInOutQuint(KineticMath.windowProgress(t, 0.54f, 0.88f))
        val masterAlpha = (KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.04f, 0.34f)) * contentFade).coerceIn(0f, 1f)

        if (irisOut > 0f) {
            drawFeatheredIrisBackdrop(canvas, w, h, cx, cy, irisOut, COLOR_FIRE_ORANGE)
        } else {
            surfacePaint.color = Color.argb((veilIn * 244f).toInt().coerceIn(0, 255), 8, 9, 12)
            canvas.drawRect(0f, 0f, w, h, surfacePaint)
        }

        drawVolumetricAura(canvas, cx, cy, min(w, h) * 0.36f, masterAlpha, emerald = false)

        if (masterAlpha > 0.01f) {
            val decelP = KineticMath.easeOutQuint(KineticMath.windowProgress(t, 0.08f, 0.72f))
            val scale = 1.05f - 0.05f * decelP - (1f - contentFade) * 0.04f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)

            val orbitR = 62f * density
            arcRect.set(-orbitR, -orbitR, orbitR, orbitR)

            ringStrokePaint.strokeWidth = strokeThinDp
            ringStrokePaint.color = Color.argb((masterAlpha * 40f).toInt(), 245, 245, 247)
            canvas.drawOval(arcRect, ringStrokePaint)

            val headDeg = 220f + decelP * 115f
            val spanDeg = 260f * (1f - decelP * 0.72f)
            drawCometArc(canvas, arcRect, headDeg - spanDeg, spanDeg, masterAlpha, 0f, stroke175Dp)

            val tipX = KineticMath.orbitCos(headDeg) * orbitR
            val tipY = KineticMath.orbitSin(headDeg) * orbitR
            drawOpticalPhoton(canvas, tipX, tipY, masterAlpha, emerald = false, radiusDp = 9.5f)

            drawOfficialLogo(canvas, 0f, 0f, 84f * density, masterAlpha)
            canvas.restore()

            val textAlpha = (masterAlpha * 255f).toInt().coerceIn(0, 255)
            val badgeY = cy + 104f * density
            val titleY = badgeY + 30f * density

            badgePaint.textAlign = Paint.Align.CENTER
            badgePaint.textSize = 12f * scaledDensity
            badgePaint.letterSpacing = 0.22f
            badgePaint.color = Color.argb((textAlpha * 0.92f).toInt(), 255, 122, 46)
            canvas.drawText(localizedDisconnectBadge(), cx, badgeY, badgePaint)

            titlePaint.textAlign = Paint.Align.CENTER
            titlePaint.textSize = 28f * scaledDensity
            titlePaint.letterSpacing = -0.015f
            titlePaint.color = Color.argb(textAlpha, 245, 245, 247)
            canvas.drawText(localizedDisconnectTitle(), cx, titleY, titlePaint)
        }

        if (t >= 1.0f) {
            finishAnimation()
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun finishAnimation() {
        triggerRevealIfNeeded()
        isRunning = false
        currentMode = AnimationMode.NONE
        visibility = GONE
        val cb = onCompleteCallback
        onCompleteCallback = null
        onRevealStartCallback = null
        cb?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAudioTrack()
    }
}
