package com.castviewer.sender.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.castviewer.sender.MainActivity
import com.castviewer.sender.R
import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastPreferencesRepository
import com.castviewer.sender.model.CastStateStore
import com.castviewer.sender.model.DeviceSource
import com.castviewer.sender.model.FireStickDevice
import com.castviewer.sender.model.IncomingTakeoverRequest
import com.castviewer.sender.model.KineticAnimType
import com.castviewer.sender.model.PerformancePrefs
import com.castviewer.sender.model.ScreenCaptureMath
import com.castviewer.sender.model.TelemetryStats
import com.castviewer.sender.network.SignalingClient
import java.util.concurrent.Executors

class CastProjectionService : Service() {

    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRtcSenderManager? = null
    private var displayManager: DisplayManager? = null
    private val serviceExecutor = Executors.newSingleThreadExecutor()

    private var activeDevice: FireStickDevice? = null
    private var lastAppliedPrefs: PerformancePrefs? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var webRtcStartedForSession = false
    private var pendingReadyFallback: Runnable? = null
    private var takeoverAutoClearRunnable: Runnable? = null
    private var forceTakeoverForSession = false
    @Volatile
    private var pendingAcceptedTakeoverBy: String? = null
    private var isPaused = false
    private var isStopping = false
    @Volatile
    private var suppressOverlayForBlackout = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || isStopping) return
            val manager = webRtcManager ?: return
            val prefs = CastStateStore.prefs.value
            val size = computeCurrentCaptureDimensions(prefs)
            manager.updateVideoCaptureSize(size.width, size.height, prefs.preset.fps)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                val ip = intent.getStringExtra(EXTRA_HOST_IP).orEmpty()
                val port = intent.getIntExtra(EXTRA_HOST_PORT, 8765)
                val name = intent.getStringExtra(EXTRA_HOST_NAME).orEmpty().ifBlank { "Fire Stick ($ip)" }
                val forceTakeover = intent.getBooleanExtra(EXTRA_FORCE_TAKEOVER, false)

                if (projectionData == null || ip.isBlank() || resultCode != android.app.Activity.RESULT_OK) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Enter MediaProjection foreground IMMEDIATELY after user-consented capture result
                promoteToForeground(name, paused = false)
                val device = FireStickDevice(
                    id = "$ip:$port",
                    name = name,
                    ip = ip,
                    port = port,
                    source = DeviceSource.MANUAL,
                    isOnline = true
                )
                startSession(device, projectionData, forceTakeover)
            }

            ACTION_TOGGLE_PAUSE -> {
                setPaused(!isPaused, notifyFireStick = true)
            }

            ACTION_SYNC_PREFS -> {
                syncLivePrefs(CastStateStore.prefs.value)
            }

            ACTION_SYNC_OVERLAY -> {
                if (intent.hasExtra(EXTRA_SUPPRESS_OVERLAY)) {
                    suppressOverlayForBlackout = intent.getBooleanExtra(EXTRA_SUPPRESS_OVERLAY, false)
                }
                syncExternalOverlay()
            }

            ACTION_DISMISS_OVERLAY -> {
                val current = CastStateStore.prefs.value
                if (current.floatingOverlayEnabled) {
                    val updated = current.copy(floatingOverlayEnabled = false)
                    CastStateStore.updatePrefs(updated)
                    CastPreferencesRepository(applicationContext).savePrefs(updated)
                    lastAppliedPrefs = updated
                }
                CastFloatingOverlayService.stopOverlay(applicationContext)
            }

            ACTION_SAVE_CLIP -> {
                handleSaveClipAction()
            }

            ACTION_TAKE_SCREENSHOT -> {
                handleTakeScreenshotAction()
            }

            ACTION_ACCEPT_TAKEOVER -> {
                respondToTakeoverAction(intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(), accepted = true)
            }

            ACTION_REJECT_TAKEOVER -> {
                respondToTakeoverAction(intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(), accepted = false)
            }

            ACTION_STOP -> {
                shutdownSession(
                    message = "Transmissão encerrada",
                    disconnectBadge = "CONEXÃO ENCERRADA",
                    disconnectTitle = "Espelhamento Encerrado",
                    disconnectSubtitle = activeDevice?.name.orEmpty()
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val prevState = CastStateStore.session.value.connectionState
        shutdownSession(null)
        CastFloatingOverlayService.stopOverlay(applicationContext)
        if (prevState != CastConnectionState.IDLE && prevState != CastConnectionState.ERROR) {
            CastStateStore.updateSession {
                it.copy(
                    connectionState = CastConnectionState.IDLE,
                    statusMessage = "Transmissão encerrada",
                    activeDevice = null,
                    telemetry = com.castviewer.sender.model.TelemetryStats(),
                    isSavingClip = false,
                    isTakingScreenshot = false,
                    busyHostDevice = null,
                    busyActiveSender = null,
                    incomingTakeover = null
                )
            }
        }
        serviceExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startSession(
        device: FireStickDevice,
        projectionData: Intent,
        forceTakeover: Boolean = false
    ) {
        tearDownTransportOnly()
        isStopping = false
        isPaused = false
        webRtcStartedForSession = false
        forceTakeoverForSession = forceTakeover
        pendingAcceptedTakeoverBy = null
        cancelPendingReadyFallback()
        clearIncomingTakeoverUi(null)
        activeDevice = device

        val prefs = CastStateStore.prefs.value
        lastAppliedPrefs = prefs
        ReplayClipRecorder.configure(prefs.replayClipEnabled, prefs.lowRamMode)
        ReplayClipRecorder.setSessionActive(true)

        CastStateStore.updateSession {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                statusMessage = if (forceTakeover) {
                    "Assumindo controle em ${device.name}..."
                } else {
                    "Conectando ao ${device.name} (${device.ip})..."
                },
                activeDevice = device,
                activeAudioMode = prefs.audioMode,
                telemetry = TelemetryStats(),
                warningBanner = null,
                isSavingClip = false,
                clipFeedback = null,
                isTakingScreenshot = false,
                shotFeedback = null,
                busyHostDevice = null,
                busyActiveSender = null,
                incomingTakeover = null
            )
        }

        try {
            displayManager?.registerDisplayListener(displayListener, null)
        } catch (_: Exception) {
        }

        val captureSize = computeCurrentCaptureDimensions(prefs)

        val signaling = SignalingClient(
            host = device.ip,
            port = device.port,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    CastStateStore.updateSession {
                        it.copy(
                            connectionState = CastConnectionState.CONNECTING,
                            statusMessage = "Verificando disponibilidade de ${device.name}..."
                        )
                    }
                    // Wait briefly for Fire Stick onOpen status ("ready" or "busy")
                    // before consuming Android 14 single-use MediaProjection consent token.
                    // When forceTakeoverForSession is active, allow extra time for takeover handshake.
                    val fallback = Runnable {
                        ensureWebRtcStarted(projectionData, captureSize.width, captureSize.height)
                    }
                    pendingReadyFallback = fallback
                    val graceMs = if (forceTakeoverForSession) TAKEOVER_HANDSHAKE_GRACE_MS else READY_HANDSHAKE_GRACE_MS
                    mainHandler.postDelayed(fallback, graceMs)
                }

                override fun onStatus(state: String, message: String, activeSender: String?, timeoutSec: Int?) {
                    if (state.equals("busy", ignoreCase = true)) {
                        cancelPendingReadyFallback()
                        val activeWho = activeSender ?: "outro dispositivo"
                        if (forceTakeoverForSession) {
                            CastStateStore.updateSession {
                                it.copy(statusMessage = "Solicitando permissão para assumir TV de $activeWho...")
                            }
                            serviceExecutor.execute {
                                signalingClient?.sendTakeover(CastStateStore.prefs.value.senderName)
                            }
                            return
                        }
                        val formatted = when {
                            message.isNotBlank() -> message
                            !activeSender.isNullOrBlank() -> "Já conectado em outro dispositivo ($activeSender)."
                            else -> "A TV já está conectada em outro dispositivo."
                        }
                        CastStateStore.markBusyOrPreempted(device, activeWho, formatted)
                        shutdownSession(formatted, isError = true, preserveBusyHost = true)
                    } else if (state.equals("takeover-pending", ignoreCase = true)) {
                        cancelPendingReadyFallback()
                        val waitSec = (timeoutSec ?: 15).coerceAtLeast(5)
                        val activeWho = activeSender ?: "dispositivo atual"
                        CastStateStore.updateSession {
                            it.copy(
                                statusMessage = message.ifBlank {
                                    "Aguardando $activeWho aceitar (troca automática em ${waitSec}s)..."
                                }
                            )
                        }
                    } else if (state.equals("takeover-denied", ignoreCase = true)) {
                        cancelPendingReadyFallback()
                        val activeWho = activeSender ?: "dispositivo atual"
                        val deniedMsg = message.ifBlank { "$activeWho recusou transferir a TV." }
                        CastStateStore.markBusyOrPreempted(device, activeWho, deniedMsg)
                        mainHandler.post {
                            Toast.makeText(applicationContext, deniedMsg, Toast.LENGTH_LONG).show()
                        }
                        shutdownSession(deniedMsg, isError = true, preserveBusyHost = true)
                    } else if (state.equals("ready", ignoreCase = true)) {
                        cancelPendingReadyFallback()
                        ensureWebRtcStarted(projectionData, captureSize.width, captureSize.height)
                    } else if (message.isNotBlank()) {
                        CastStateStore.updateSession { it.copy(statusMessage = message) }
                    }
                }

                override fun onTakeoverRequest(
                    requestId: String,
                    requesterName: String,
                    timeoutSec: Int,
                    message: String
                ) {
                    handleIncomingTakeoverRequest(
                        requestId = requestId,
                        requesterName = requesterName,
                        timeoutSec = timeoutSec
                    )
                }

                override fun onTakeoverCancelled(requestId: String) {
                    clearIncomingTakeoverUi(requestId.ifBlank { null })
                }

                override fun onPreempted(message: String, bySender: String?) {
                    handlePreemptedSession(device, message, bySender)
                }

                override fun onAnswer(sdp: String) {
                    webRtcManager?.onRemoteAnswer(sdp)
                }

                override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                    webRtcManager?.onRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onRemotePause(paused: Boolean) {
                    setPaused(paused, notifyFireStick = false)
                }

                override fun onBye() {
                    shutdownSession(
                        message = "Fire Stick encerrou o espelhamento.",
                        disconnectBadge = "TV DESCONECTADA",
                        disconnectTitle = "Sessão Encerrada pela TV",
                        disconnectSubtitle = device.name
                    )
                }

                override fun onDisconnected(reason: String, remote: Boolean) {
                    if (!isStopping) {
                        val acceptedBy = pendingAcceptedTakeoverBy
                        if (acceptedBy != null) {
                            handlePreemptedSession(device, "", acceptedBy)
                        } else {
                            shutdownSession("Conexão encerrada: $reason", isError = true)
                        }
                    }
                }

                override fun onError(error: String) {
                    if (!isStopping) {
                        shutdownSession(error, isError = true)
                    }
                }
            }
        )
        signalingClient = signaling

        serviceExecutor.execute {
            val ok = signaling.connect(timeoutMs = 6000L)
            if (!ok && !isStopping) {
                shutdownSession("Não foi possível alcançar ${device.ip}:${device.port}", isError = true)
            }
        }
    }

    @Synchronized
    private fun ensureWebRtcStarted(projectionData: Intent, captureWidth: Int, captureHeight: Int) {
        if (isStopping || webRtcStartedForSession) return
        webRtcStartedForSession = true
        val livePrefs = CastStateStore.prefs.value
        CastStateStore.updateSession {
            it.copy(
                connectionState = CastConnectionState.CONNECTING,
                statusMessage = "Negociando vídeo com ${activeDevice?.name ?: "Fire Stick"}..."
            )
        }
        startWebRtc(projectionData, livePrefs, captureWidth, captureHeight)
    }

    private fun cancelPendingReadyFallback() {
        pendingReadyFallback?.let { mainHandler.removeCallbacks(it) }
        pendingReadyFallback = null
    }

    private fun handleSaveClipAction() {
        ReplayClipRecorder.saveLastClipAsync(
            context = applicationContext,
            onStateChanged = { saving, msg ->
                CastStateStore.updateSession {
                    it.copy(isSavingClip = saving, clipFeedback = msg)
                }
                CastQuickTileService.requestSync(applicationContext)
            },
            onFinished = { ok, msg ->
                CastStateStore.updateSession {
                    it.copy(
                        isSavingClip = false,
                        clipFeedback = msg,
                        mediaGeneration = if (ok) it.mediaGeneration + 1 else it.mediaGeneration
                    )
                }
                CastQuickTileService.requestSync(applicationContext)
                mainHandler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun handleTakeScreenshotAction() {
        ReplayClipRecorder.saveKeyframeScreenshotAsync(
            context = applicationContext,
            onProgress = { msg ->
                CastStateStore.updateSession {
                    it.copy(isTakingScreenshot = true, shotFeedback = msg)
                }
            },
            onFinished = { ok, msg ->
                CastStateStore.updateSession {
                    it.copy(
                        isTakingScreenshot = false,
                        shotFeedback = msg,
                        mediaGeneration = if (ok) it.mediaGeneration + 1 else it.mediaGeneration
                    )
                }
                mainHandler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startWebRtc(
        projectionData: Intent,
        prefs: PerformancePrefs,
        captureWidth: Int,
        captureHeight: Int
    ) {
        val manager = WebRtcSenderManager(
            context = applicationContext,
            listener = object : WebRtcSenderManager.Listener {
                override fun onLocalOfferReady(sdp: String, width: Int, height: Int, audioEnabled: Boolean) {
                    val livePrefs = CastStateStore.prefs.value
                    signalingClient?.sendOffer(
                        sdp = sdp,
                        senderName = livePrefs.senderName,
                        imageMode = livePrefs.imageMode,
                        latencyMode = livePrefs.latencyMode,
                        preset = livePrefs.preset,
                        width = width,
                        height = height,
                        audioEnabled = audioEnabled,
                        forceTakeover = forceTakeoverForSession
                    )
                    signalingClient?.sendVideoProfile(livePrefs.preset)
                    signalingClient?.sendImageMode(livePrefs.imageMode)
                    signalingClient?.sendLatencyMode(livePrefs.latencyMode)
                    signalingClient?.sendClockConfig(livePrefs.streamClockEnabled, livePrefs.streamClockCorner)
                }

                override fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                    signalingClient?.sendIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }

                override fun onIceConnected() {
                    CastPreferencesRepository(applicationContext).let { repo ->
                        activeDevice?.let { repo.upsertSavedDevice(it) }
                    }
                    CastStateStore.updateSession {
                        it.copy(
                            connectionState = if (isPaused) CastConnectionState.PAUSED else CastConnectionState.STREAMING,
                            statusMessage = if (isPaused) "Espelhamento pausado" else "Espelhando ao vivo",
                            busyHostDevice = null,
                            busyActiveSender = null
                        )
                    }
                    CastQuickTileService.requestSync(applicationContext)
                    CastPauseTileService.requestSync(applicationContext)
                    syncExternalOverlay()
                }

                override fun onIceDisconnectedOrFailed(reason: String) {
                    if (!isStopping) {
                        shutdownSession(reason, isError = true)
                    }
                }

                override fun onProjectionRevokedBySystem() {
                    if (!isStopping) {
                        shutdownSession(
                            message = "Captura de tela encerrada pelo sistema Android.",
                            disconnectBadge = "CAPTURA INTERROMPIDA",
                            disconnectTitle = "Captura Encerrada pelo Android"
                        )
                    }
                }

                override fun onTelemetryUpdated(stats: TelemetryStats) {
                    CastStateStore.updateSession { it.copy(telemetry = stats) }
                }

                override fun onWarning(message: String) {
                    CastStateStore.updateSession { it.copy(warningBanner = message) }
                }

                override fun onError(message: String) {
                    if (!isStopping) {
                        shutdownSession(message, isError = true)
                    }
                }
            }
        )
        webRtcManager = manager
        manager.start(projectionData, prefs, captureWidth, captureHeight)
    }

    private fun setPaused(paused: Boolean, notifyFireStick: Boolean) {
        isPaused = paused
        webRtcManager?.setPaused(paused)
        if (notifyFireStick) {
            signalingClient?.sendStreamPause(paused)
        }
        val devName = activeDevice?.name ?: "Fire Stick"
        updateNotification(devName, paused)
        CastStateStore.updateSession {
            if (it.connectionState == CastConnectionState.STREAMING || it.connectionState == CastConnectionState.PAUSED) {
                it.copy(
                    connectionState = if (paused) CastConnectionState.PAUSED else CastConnectionState.STREAMING,
                    statusMessage = if (paused) "Espelhamento pausado" else "Espelhando ao vivo"
                )
            } else {
                it
            }
        }
        CastStateStore.emitKineticAnimation(
            type = KineticAnimType.PAUSE_STATE,
            badge = if (paused) "PAUSADO" else "AO VIVO",
            title = if (paused) "Transmissão Pausada" else "Espelhamento Retomado",
            subtitle = if (paused) "Último quadro congelado na TV" else devName,
            accentEmerald = !paused,
            isPausedState = paused
        )
        CastQuickTileService.requestSync(applicationContext)
        CastPauseTileService.requestSync(applicationContext)
    }

    private fun syncLivePrefs(newPrefs: PerformancePrefs) {
        val prev = lastAppliedPrefs
        lastAppliedPrefs = newPrefs
        val manager = webRtcManager ?: return
        val sig = signalingClient ?: return

        if (prev?.preset != newPrefs.preset) {
            val size = computeCurrentCaptureDimensions(newPrefs)
            manager.applyVideoProfile(newPrefs.preset, size.width, size.height)
            sig.sendVideoProfile(newPrefs.preset)
        }
        if (prev?.imageMode != newPrefs.imageMode) {
            manager.applyImageMode(newPrefs.imageMode)
            sig.sendImageMode(newPrefs.imageMode)
        }
        if (prev?.latencyMode != newPrefs.latencyMode) {
            sig.sendLatencyMode(newPrefs.latencyMode)
        }
        if (prev?.streamClockEnabled != newPrefs.streamClockEnabled ||
            prev?.streamClockCorner != newPrefs.streamClockCorner
        ) {
            sig.sendClockConfig(newPrefs.streamClockEnabled, newPrefs.streamClockCorner)
        }
        if (prev?.audioMode != newPrefs.audioMode) {
            manager.setAudioMode(newPrefs.audioMode)
            CastStateStore.updateSession { it.copy(activeAudioMode = newPrefs.audioMode) }
        }
        ReplayClipRecorder.configure(newPrefs.replayClipEnabled, newPrefs.lowRamMode)
        if (prev?.replayClipEnabled != newPrefs.replayClipEnabled) {
            activeDevice?.let { updateNotification(it.name, isPaused) }
        }
        if (prev?.floatingOverlayEnabled != newPrefs.floatingOverlayEnabled) {
            syncExternalOverlay()
        }
    }

    private fun syncExternalOverlay() {
        val state = CastStateStore.session.value.connectionState
        val isActiveCast = !isStopping && (
            state == CastConnectionState.STREAMING ||
                state == CastConnectionState.PAUSED
            )
        val shouldShow = isActiveCast &&
            CastStateStore.prefs.value.floatingOverlayEnabled &&
            !suppressOverlayForBlackout
        CastFloatingOverlayService.syncOverlayVisibility(applicationContext, shouldShow)
    }

    private fun tearDownTransportOnly() {
        cancelPendingReadyFallback()
        cancelTakeoverAutoClear()
        clearTakeoverNotificationOnly()
        suppressOverlayForBlackout = false
        ReplayClipRecorder.setSessionActive(false)
        CastFloatingOverlayService.stopOverlay(applicationContext)
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        signalingClient?.close()
        signalingClient = null
        webRtcManager?.dispose()
        webRtcManager = null
    }

    private fun handlePreemptedSession(
        device: FireStickDevice,
        message: String,
        bySender: String?
    ) {
        clearIncomingTakeoverUi(null)
        pendingAcceptedTakeoverBy = null
        val owner = bySender?.trim()?.ifBlank { null } ?: "outro dispositivo"
        val msg = message.trim().ifBlank {
            "$owner assumiu a TV. Toque em \"Assumir TV\" para voltar para este celular."
        }
        CastStateStore.markBusyOrPreempted(device, owner, msg)
        shutdownSession(
            message = msg,
            isError = true,
            preserveBusyHost = true,
            emitDisconnectAnimation = true,
            disconnectBadge = "TV TRANSFERIDA",
            disconnectTitle = "Outro Dispositivo Assumiu",
            disconnectSubtitle = msg
        )
    }

    private fun shutdownSession(
        message: String?,
        isError: Boolean = false,
        preserveBusyHost: Boolean = false,
        emitDisconnectAnimation: Boolean = !preserveBusyHost,
        disconnectBadge: String? = null,
        disconnectTitle: String? = null,
        disconnectSubtitle: String? = null
    ) {
        if (isStopping) return
        isStopping = true
        pendingAcceptedTakeoverBy = null
        tearDownTransportOnly()

        if (message != null) {
            CastStateStore.updateSession {
                it.copy(
                    connectionState = if (isError) CastConnectionState.ERROR else CastConnectionState.IDLE,
                    statusMessage = message,
                    activeDevice = null,
                    telemetry = TelemetryStats(),
                    isSavingClip = false,
                    isTakingScreenshot = false,
                    busyHostDevice = if (preserveBusyHost) it.busyHostDevice else null,
                    busyActiveSender = if (preserveBusyHost) it.busyActiveSender else null,
                    incomingTakeover = null
                )
            }
            if (emitDisconnectAnimation) {
                CastStateStore.emitKineticAnimation(
                    type = KineticAnimType.CAST_DISCONNECT,
                    badge = disconnectBadge ?: if (isError) "SINAL INTERROMPIDO" else "CONEXÃO ENCERRADA",
                    title = disconnectTitle ?: if (isError) "Conexão Encerrada" else "Espelhamento Encerrado",
                    subtitle = disconnectSubtitle ?: message
                )
            }
        } else {
            CastStateStore.clearIncomingTakeover(null)
        }
        CastQuickTileService.requestSync(applicationContext)
        CastPauseTileService.requestSync(applicationContext)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun handleIncomingTakeoverRequest(
        requestId: String,
        requesterName: String,
        timeoutSec: Int
    ) {
        if (isStopping || requestId.isBlank()) return
        val safeTimeout = timeoutSec.coerceIn(5, 60)
        val req = IncomingTakeoverRequest(
            requestId = requestId,
            requesterName = requesterName,
            timeoutSec = safeTimeout,
            expiresAtMs = System.currentTimeMillis() + safeTimeout * 1000L
        )
        CastStateStore.setIncomingTakeover(req)
        showIncomingTakeoverNotification(req)

        cancelTakeoverAutoClear()
        val clearTask = Runnable {
            clearIncomingTakeoverUi(requestId)
        }
        takeoverAutoClearRunnable = clearTask
        mainHandler.postDelayed(clearTask, safeTimeout * 1000L + 500L)
    }

    private fun respondToTakeoverAction(requestId: String, accepted: Boolean) {
        val currentReq = CastStateStore.session.value.incomingTakeover
        val effectiveId = requestId.ifBlank {
            currentReq?.requestId.orEmpty()
        }
        pendingAcceptedTakeoverBy = if (accepted) {
            currentReq?.requesterName?.ifBlank { null } ?: "Outro dispositivo"
        } else {
            null
        }
        clearIncomingTakeoverUi(effectiveId.ifBlank { null })
        if (effectiveId.isBlank()) return
        serviceExecutor.execute {
            signalingClient?.sendTakeoverResponse(effectiveId, accepted)
        }

        val devName = activeDevice?.name ?: "Fire Stick"
        if (accepted) {
            CastStateStore.updateSession {
                it.copy(statusMessage = "Transferindo $devName...")
            }
        } else {
            CastStateStore.updateSession {
                it.copy(
                    statusMessage = if (isPaused) "Espelhamento pausado" else "Espelhando ao vivo"
                )
            }
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "Solicitação recusada — transmissão mantida.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun clearIncomingTakeoverUi(requestId: String?) {
        cancelTakeoverAutoClear()
        clearTakeoverNotificationOnly()
        CastStateStore.clearIncomingTakeover(requestId)
    }

    private fun cancelTakeoverAutoClear() {
        takeoverAutoClearRunnable?.let { mainHandler.removeCallbacks(it) }
        takeoverAutoClearRunnable = null
    }

    private fun clearTakeoverNotificationOnly() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(TAKEOVER_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun showIncomingTakeoverNotification(req: IncomingTakeoverRequest) {
        try {
            val openAppIntent = PendingIntent.getActivity(
                this,
                200,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val acceptIntent = PendingIntent.getService(
                this,
                201,
                Intent(this, CastProjectionService::class.java).apply {
                    action = ACTION_ACCEPT_TAKEOVER
                    putExtra(EXTRA_REQUEST_ID, req.requestId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val rejectIntent = PendingIntent.getService(
                this,
                202,
                Intent(this, CastProjectionService::class.java).apply {
                    action = ACTION_REJECT_TAKEOVER
                    putExtra(EXTRA_REQUEST_ID, req.requestId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val msgText = "Toque em Aceitar para transferir ou Recusar. " +
                "Sem resposta, a troca acontece em ${req.timeoutSec}s."

            val notif = NotificationCompat.Builder(this, CHANNEL_TAKEOVER_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("${req.requesterName} quer assumir a TV")
                .setContentText(msgText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msgText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setTimeoutAfter(req.timeoutSec.coerceAtLeast(5) * 1000L)
                .setColor(0xFFFF5A1F.toInt())
                .setContentIntent(openAppIntent)
                .addAction(0, "Aceitar", acceptIntent)
                .addAction(0, "Recusar", rejectIntent)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(TAKEOVER_NOTIFICATION_ID, notif)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun computeCurrentCaptureDimensions(prefs: PerformancePrefs): ScreenCaptureMath.CaptureDimensions {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        var physicalW = 1080
        var physicalH = 2400

        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                physicalW = bounds.width()
                physicalH = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    physicalW = metrics.widthPixels
                    physicalH = metrics.heightPixels
                }
            }
        }

        return ScreenCaptureMath.computeMod16CaptureSize(physicalW, physicalH, prefs.preset)
    }

    private fun promoteToForeground(targetName: String, paused: Boolean) {
        val notification = buildNotification(targetName, paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(targetName: String, paused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(targetName, paused))
    }

    private fun buildNotification(targetName: String, paused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastProjectionService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CastProjectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clipIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, CastProjectionService::class.java).apply { action = ACTION_SAVE_CLIP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shotIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, CastProjectionService::class.java).apply { action = ACTION_TAKE_SCREENSHOT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (paused) "Retomar" else "Pausar"
        val bodyText = if (paused) {
            "Transmissão pausada em $targetName"
        } else {
            "Espelhando ao vivo para $targetName"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CastViewer")
            .setContentText(bodyText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFFF5A1F.toInt())
            .setContentIntent(openAppIntent)
            .addAction(0, pauseLabel, toggleIntent)

        if (CastStateStore.prefs.value.replayClipEnabled) {
            builder.addAction(0, "Clip 30s", clipIntent)
        }

        return builder
            .addAction(0, "Parar", stopIntent)
            .addAction(0, "Foto", shotIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val takeoverChannel = NotificationChannel(
                CHANNEL_TAKEOVER_ID,
                "Solicitações para assumir a TV",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Permite aceitar ou recusar quando outro dispositivo pede para assumir a TV."
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(takeoverChannel)
        }
    }

    companion object {
        const val ACTION_START = "com.castviewer.sender.action.START"
        const val ACTION_STOP = "com.castviewer.sender.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.castviewer.sender.action.TOGGLE_PAUSE"
        const val ACTION_SYNC_PREFS = "com.castviewer.sender.action.SYNC_PREFS"
        const val ACTION_SYNC_OVERLAY = "com.castviewer.sender.action.SYNC_OVERLAY"
        const val ACTION_DISMISS_OVERLAY = "com.castviewer.sender.action.DISMISS_OVERLAY"
        const val ACTION_SAVE_CLIP = "com.castviewer.sender.action.SAVE_CLIP"
        const val ACTION_TAKE_SCREENSHOT = "com.castviewer.sender.action.TAKE_SCREENSHOT"
        const val ACTION_ACCEPT_TAKEOVER = "com.castviewer.sender.action.ACCEPT_TAKEOVER"
        const val ACTION_REJECT_TAKEOVER = "com.castviewer.sender.action.REJECT_TAKEOVER"
        private const val READY_HANDSHAKE_GRACE_MS = 400L
        private const val TAKEOVER_HANDSHAKE_GRACE_MS = 2500L

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_HOST_PORT = "host_port"
        const val EXTRA_HOST_NAME = "host_name"
        const val EXTRA_FORCE_TAKEOVER = "force_takeover"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_SUPPRESS_OVERLAY = "suppress_overlay"

        private const val CHANNEL_ID = "castviewer_projection_channel"
        private const val CHANNEL_TAKEOVER_ID = "castviewer_takeover_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAKEOVER_NOTIFICATION_ID = 1002

        fun saveReplayClip(context: Context) {
            dispatchLiveAction(context, ACTION_SAVE_CLIP)
        }

        fun takeScreenshot(context: Context) {
            dispatchLiveAction(context, ACTION_TAKE_SCREENSHOT)
        }

        fun togglePause(context: Context) {
            dispatchLiveAction(context, ACTION_TOGGLE_PAUSE)
        }

        fun syncOverlay(context: Context, suppress: Boolean? = null) {
            try {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CastProjectionService::class.java).apply {
                        action = ACTION_SYNC_OVERLAY
                        if (suppress != null) {
                            putExtra(EXTRA_SUPPRESS_OVERLAY, suppress)
                        }
                    }
                )
            } catch (_: Exception) {
            }
        }

        fun disableFloatingOverlay(context: Context) {
            dispatchLiveAction(context, ACTION_DISMISS_OVERLAY)
        }

        fun respondToTakeover(context: Context, requestId: String, accepted: Boolean) {
            try {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CastProjectionService::class.java).apply {
                        action = if (accepted) ACTION_ACCEPT_TAKEOVER else ACTION_REJECT_TAKEOVER
                        putExtra(EXTRA_REQUEST_ID, requestId)
                    }
                )
            } catch (_: Exception) {
            }
        }

        private fun dispatchLiveAction(context: Context, action: String) {
            try {
                context.applicationContext.startService(
                    Intent(context.applicationContext, CastProjectionService::class.java).apply {
                        this.action = action
                    }
                )
            } catch (_: Exception) {
            }
        }
    }
}
