package com.castviewer.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.castviewer.sender.cast.CastProjectionService
import com.castviewer.sender.cast.ReplayClipRecorder
import com.castviewer.sender.model.AudioCaptureMode
import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastPreferencesRepository
import com.castviewer.sender.model.CastStateStore
import com.castviewer.sender.model.DeviceSource
import com.castviewer.sender.model.FireStickDevice
import com.castviewer.sender.model.KineticAnimType
import com.castviewer.sender.model.QrUriParser
import com.castviewer.sender.network.FireStickDiscovery
import com.castviewer.sender.ui.CastViewerScreen
import com.castviewer.sender.ui.CastViewerTheme
import com.castviewer.sender.ui.QrScannerDialog

class MainActivity : ComponentActivity() {

    private lateinit var prefsRepo: CastPreferencesRepository
    private lateinit var discovery: FireStickDiscovery

    private var pendingDeviceForCast: FireStickDevice? = null
    private var pendingForceTakeover: Boolean = false
    private var showQrDialogState = mutableStateOf(false)
    private var amoledBlackoutState = mutableStateOf(false)
    private var hasOverlayPermissionState = mutableStateOf(false)
    private var activityForeground = false

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && CastStateStore.prefs.value.audioMode != AudioCaptureMode.OFF) {
            Toast.makeText(
                this,
                "Permissão de áudio negada: transmissão e clipes sairão sem som. Ative Microfone em Ajustes do app.",
                Toast.LENGTH_LONG
            ).show()
            CastStateStore.updateSession {
                it.copy(
                    warningBanner = "Permissão de áudio bloqueada: ative Microfone em Ajustes do app para gravar clipes e transmitir com som."
                )
            }
        } else if (granted) {
            CastStateStore.updateSession { state ->
                if (state.warningBanner?.contains("Microfone", ignoreCase = true) == true) {
                    state.copy(warningBanner = null)
                } else {
                    state
                }
            }
        }
        // Proceed to MediaProjection even if audio permission denied (falls back to Video-Only)
        launchMediaProjectionPrompt()
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        proceedAfterNotificationCheck()
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrDialogState.value = true
        } else {
            Toast.makeText(
                this,
                "Permissão de câmera necessária para escanear o QR Code",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val device = pendingDeviceForCast
        pendingDeviceForCast = null
        val forceTakeover = pendingForceTakeover
        pendingForceTakeover = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && device != null) {
            val intent = Intent(this, CastProjectionService::class.java).apply {
                action = CastProjectionService.ACTION_START
                putExtra(CastProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CastProjectionService.EXTRA_PROJECTION_DATA, data)
                putExtra(CastProjectionService.EXTRA_HOST_IP, device.ip)
                putExtra(CastProjectionService.EXTRA_HOST_PORT, device.port)
                putExtra(CastProjectionService.EXTRA_HOST_NAME, device.name)
                putExtra(CastProjectionService.EXTRA_FORCE_TAKEOVER, forceTakeover)
            }
            ContextCompat.startForegroundService(this, intent)
            val livePrefs = CastStateStore.prefs.value
            CastStateStore.emitKineticAnimation(
                type = KineticAnimType.CAST_CONNECT,
                badge = if (forceTakeover) "ASSUMINDO TV" else "CONEXÃO DIRETA",
                title = "Transmitindo para ${device.name}",
                subtitle = "${livePrefs.preset.shortLabel} • ${livePrefs.latencyMode.label}"
            )
        } else if (device != null) {
            CastStateStore.emitKineticAnimation(
                type = KineticAnimType.CAST_DISCONNECT,
                badge = "CAPTURA CANCELADA",
                title = "Espelhamento Cancelado",
                subtitle = "Permissão de captura não concedida"
            )
            Toast.makeText(this, "Captura de tela cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        prefsRepo = CastPreferencesRepository(this)
        hasOverlayPermissionState.value = Settings.canDrawOverlays(this)
        val initialState = CastStateStore.session.value.connectionState
        if (initialState == CastConnectionState.STREAMING || initialState == CastConnectionState.PAUSED) {
            CastProjectionService.syncOverlay(this, suppress = amoledBlackoutState.value)
        } else {
            com.castviewer.sender.cast.CastFloatingOverlayService.stopOverlay(this)
        }
        discovery = FireStickDiscovery(this, prefsRepo)

        val initialPrefs = prefsRepo.loadInitialPrefs(this)
        CastStateStore.updatePrefs(initialPrefs)
        CastStateStore.setSavedDevices(
            items = prefsRepo.loadSavedDevices(),
            preferredId = prefsRepo.getLastSelectedDeviceId()
        )
        if (savedInstanceState == null &&
            CastStateStore.session.value.connectionState == CastConnectionState.IDLE
        ) {
            CastStateStore.emitKineticAnimation(
                type = KineticAnimType.COLD_INTRO,
                badge = "SINAL PRONTO",
                title = "CASTVIEWER",
                subtitle = "Conexão Direta de Baixa Latência"
            )
        }
        discovery.start()

        setContent {
            CastViewerTheme {
                val session by CastStateStore.session.collectAsState()
                val prefs by CastStateStore.prefs.collectAsState()
                val devices by CastStateStore.devices.collectAsState()
                val selectedId by CastStateStore.selectedDeviceId.collectAsState()
                val mdnsScanning by CastStateStore.mdnsScanning.collectAsState()
                var showQr by remember { showQrDialogState }

                // Pause mDNS multicast discovery while streaming so Wi-Fi stays 100% dedicated to RTP
                LaunchedEffect(session.connectionState, prefs.amoledSaverEnabled) {
                    val activeCast = session.connectionState == CastConnectionState.CONNECTING ||
                        session.connectionState == CastConnectionState.STREAMING ||
                        session.connectionState == CastConnectionState.PAUSED
                    if (activeCast) {
                        discovery.stop()
                    } else {
                        discovery.start()
                        amoledBlackoutState.value = false
                    }
                    if (session.connectionState == CastConnectionState.STREAMING && prefs.amoledSaverEnabled) {
                        amoledBlackoutState.value = true
                    }
                }

                // Dim window brightness cleanly during AMOLED Battery Saver Mode and restore on exit
                LaunchedEffect(amoledBlackoutState.value) {
                    applyAmoledWindowBrightness(amoledBlackoutState.value)
                    syncOverlayIfCasting(suppress = activityForeground && amoledBlackoutState.value)
                }

                CastViewerScreen(
                    session = session,
                    prefs = prefs,
                    devices = devices,
                    selectedDeviceId = selectedId,
                    mdnsScanning = mdnsScanning,
                    amoledBlackoutActive = amoledBlackoutState.value,
                    hasOverlayPermission = hasOverlayPermissionState.value,
                    onRequestOverlayPermission = { openOverlayPermissionSettings() },
                    onToggleAmoledBlackout = { enable ->
                        amoledBlackoutState.value = enable
                    },

                    onSelectDevice = { id ->
                        CastStateStore.selectDevice(id)
                        prefsRepo.setLastSelectedDeviceId(id)
                    },
                    onRemoveDevice = { id ->
                        CastStateStore.removeSavedOrManualDevice(id)
                        prefsRepo.removeSavedDevice(id)
                    },
                    onRefreshDiscovery = { discovery.refresh() },
                    onOpenQrScanner = { openQrScannerWithPermission() },
                    onManualTargetAdded = { target ->
                        addTargetDevice(target, DeviceSource.MANUAL)
                    },
                    onUpdatePrefs = { transform ->
                        val previous = CastStateStore.prefs.value
                        val updated = transform(previous)
                        CastStateStore.updatePrefs(updated)
                        prefsRepo.savePrefs(updated)
                        ReplayClipRecorder.configure(updated.replayClipEnabled, updated.lowRamMode)
                        syncPrefsIfCasting()
                        when {
                            updated.preset != previous.preset -> {
                                CastStateStore.emitKineticAnimation(
                                    type = KineticAnimType.SETTING_BADGE,
                                    badge = "QUALIDADE DE VÍDEO",
                                    title = updated.preset.shortLabel,
                                    subtitle = "${updated.preset.maxLongEdge}x${updated.preset.maxShortEdge} • ${updated.preset.fps} FPS"
                                )
                            }
                            updated.imageMode != previous.imageMode -> {
                                CastStateStore.emitKineticAnimation(
                                    type = KineticAnimType.SETTING_BADGE,
                                    badge = "PERFIL DE IMAGEM",
                                    title = updated.imageMode.label,
                                    subtitle = updated.imageMode.desc
                                )
                            }
                            updated.latencyMode != previous.latencyMode -> {
                                CastStateStore.emitKineticAnimation(
                                    type = KineticAnimType.SETTING_BADGE,
                                    badge = "LATÊNCIA",
                                    title = updated.latencyMode.label,
                                    subtitle = updated.latencyMode.desc,
                                    accentEmerald = true
                                )
                            }
                        }
                    },
                    onResetAutoHardware = {
                        val reset = prefsRepo.resetToAutoRecommended(
                            this,
                            CastStateStore.prefs.value
                        )
                        CastStateStore.updatePrefs(reset)
                        ReplayClipRecorder.configure(reset.replayClipEnabled, reset.lowRamMode)
                        syncPrefsIfCasting()
                        Toast.makeText(
                            this,
                            "Configuração automática restaurada (${reset.recommendedPreset.shortLabel})",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onStartCast = { initiateCastFlow() },
                    onForceTakeoverCast = { busyDev ->
                        initiateCastFlow(overrideTarget = busyDev, forceTakeover = true)
                    },
                    onDismissBusyBanner = {
                        CastStateStore.clearBusyHostBanner()
                    },
                    onRespondTakeover = { reqId, accepted ->
                        CastProjectionService.respondToTakeover(this, reqId, accepted)
                    },
                    onDismissKineticAnim = { eventId ->
                        CastStateStore.clearKineticAnimation(eventId)
                    },
                    onTogglePause = {
                        CastProjectionService.togglePause(this)
                    },
                    onSaveClip = {
                        CastProjectionService.saveReplayClip(this)
                    },
                    onTakeScreenshot = {
                        CastProjectionService.takeScreenshot(this)
                    },
                    onStopCast = {
                        amoledBlackoutState.value = false
                        startService(
                            Intent(this, CastProjectionService::class.java).apply {
                                action = CastProjectionService.ACTION_STOP
                            }
                        )
                    }
                )

                if (showQr) {
                    QrScannerDialog(
                        onTargetScanned = { target ->
                            showQr = false
                            addTargetDevice(target, DeviceSource.QR)
                            Toast.makeText(
                                this,
                                "Fire Stick detectado: ${target.ip}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDismiss = { showQr = false }
                    )
                }
            }
        }
    }

    override fun onStop() {
        activityForeground = false
        applyAmoledWindowBrightness(false)
        syncOverlayIfCasting(suppress = false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        activityForeground = true
        hasOverlayPermissionState.value = Settings.canDrawOverlays(this)
        if (amoledBlackoutState.value) {
            applyAmoledWindowBrightness(true)
        }
        syncPrefsIfCasting()
        syncOverlayIfCasting(suppress = amoledBlackoutState.value)
    }

    override fun onDestroy() {
        discovery.shutdown()
        super.onDestroy()
    }

    private fun applyAmoledWindowBrightness(enable: Boolean) {
        try {
            window.attributes = window.attributes.apply {
                screenBrightness = if (enable) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        } catch (_: Exception) {
        }
    }

    private fun openQrScannerWithPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            showQrDialogState.value = true
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun addTargetDevice(target: QrUriParser.ParsedTarget, source: DeviceSource) {
        val device = FireStickDevice(
            id = "${target.ip}:${target.port}",
            name = "Fire Stick (${target.ip})",
            ip = target.ip,
            port = target.port,
            source = source,
            isOnline = true
        )
        CastStateStore.upsertManualOrQrDevice(device)
        prefsRepo.upsertSavedDevice(device)
        prefsRepo.setLastSelectedDeviceId(device.id)
    }

    private fun initiateCastFlow(
        overrideTarget: FireStickDevice? = null,
        forceTakeover: Boolean = false
    ) {
        val target = overrideTarget ?: CastStateStore.selectedDevice()
        if (target == null) {
            Toast.makeText(this, "Selecione ou escaneie um Fire Stick primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDeviceForCast = target
        pendingForceTakeover = forceTakeover

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        proceedAfterNotificationCheck()
    }

    private fun proceedAfterNotificationCheck() {
        val wantsAudio = CastStateStore.prefs.value.audioMode != AudioCaptureMode.OFF
        if (wantsAudio) {
            val audioGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!audioGranted) {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        CastStateStore.updateSession { state ->
            if (state.warningBanner?.contains("Microfone", ignoreCase = true) == true) {
                state.copy(warningBanner = null)
            } else {
                state
            }
        }
        launchMediaProjectionPrompt()
    }

    private fun launchMediaProjectionPrompt() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openOverlayPermissionSettings() {
        val pkgIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(pkgIntent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Abra Ajustes > Aplicativos > Acesso especial > Sobrepor a outros apps e ative CastViewer.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun syncOverlayIfCasting(suppress: Boolean? = null) {
        val state = CastStateStore.session.value.connectionState
        if (state == CastConnectionState.STREAMING || state == CastConnectionState.PAUSED) {
            CastProjectionService.syncOverlay(this, suppress = suppress)
        }
    }

    private fun syncPrefsIfCasting() {
        val state = CastStateStore.session.value.connectionState
        if (state == CastConnectionState.STREAMING ||
            state == CastConnectionState.PAUSED ||
            state == CastConnectionState.CONNECTING
        ) {
            startService(
                Intent(this, CastProjectionService::class.java).apply {
                    action = CastProjectionService.ACTION_SYNC_PREFS
                }
            )
        }
    }
}
