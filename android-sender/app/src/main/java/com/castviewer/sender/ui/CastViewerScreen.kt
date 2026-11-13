package com.castviewer.sender.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.castviewer.sender.cast.CastProjectionService
import com.castviewer.sender.model.CastMediaItem
import com.castviewer.sender.model.ClipGalleryRepository
import kotlin.math.roundToInt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.castviewer.sender.R
import com.castviewer.sender.model.AudioCaptureMode
import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastSessionState
import com.castviewer.sender.model.DeviceSource
import com.castviewer.sender.model.FireStickDevice
import com.castviewer.sender.model.HardwareTier
import com.castviewer.sender.model.ImageMode
import com.castviewer.sender.model.IncomingTakeoverRequest
import com.castviewer.sender.model.LatencyMode
import com.castviewer.sender.model.PerformancePrefs
import com.castviewer.sender.model.QrUriParser
import com.castviewer.sender.model.VideoPreset
import java.util.Locale

private enum class HomeTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CAST("Transmitir", Icons.Rounded.CastConnected, Icons.Outlined.Cast),
    SETTINGS("Ajustes", Icons.Rounded.Settings, Icons.Outlined.Settings)
}

@Composable
fun CastViewerScreen(
    session: CastSessionState,
    prefs: PerformancePrefs,
    devices: List<FireStickDevice>,
    selectedDeviceId: String?,
    mdnsScanning: Boolean,
    amoledBlackoutActive: Boolean,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onToggleAmoledBlackout: (Boolean) -> Unit,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onManualTargetAdded: (QrUriParser.ParsedTarget) -> Unit,
    onUpdatePrefs: ((PerformancePrefs) -> PerformancePrefs) -> Unit,
    onResetAutoHardware: () -> Unit,
    onStartCast: () -> Unit,
    onForceTakeoverCast: (FireStickDevice) -> Unit,
    onDismissBusyBanner: () -> Unit,
    onRespondTakeover: (String, Boolean) -> Unit,
    onDismissKineticAnim: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onSaveClip: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopCast: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeTab by remember { mutableStateOf(HomeTab.CAST) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showOverlayGuideDialog by remember { mutableStateOf(false) }
    var galleryRefreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(hasOverlayPermission) {
        if (hasOverlayPermission) {
            showOverlayGuideDialog = false
        }
    }
    var recentMedia by remember { mutableStateOf<List<CastMediaItem>>(emptyList()) }
    var audioPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermQuickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermGranted = granted
        if (!granted) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                galleryRefreshTick++
                audioPermGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(session.mediaGeneration, galleryRefreshTick, activeTab) {
        recentMedia = ClipGalleryRepository.loadRecentMedia(context, limit = 16)
    }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()
    val isLive = session.connectionState == CastConnectionState.STREAMING ||
        session.connectionState == CastConnectionState.PAUSED
    val isConnecting = session.connectionState == CastConnectionState.CONNECTING

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = CastColors.BgCanvas,
        topBar = {
            M3TopAppBar(
                session = session,
                animationsEnabled = prefs.animationsEnabled
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = activeTab == HomeTab.CAST || isLive || isConnecting,
                    enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(200)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
                ) {
                    FloatingCastDock(
                        session = session,
                        prefs = prefs,
                        selectedDevice = selectedDevice,
                        onStartCast = {
                            val busyTarget = session.busyHostDevice
                            if (busyTarget != null && selectedDevice?.id == busyTarget.id) {
                                onForceTakeoverCast(busyTarget)
                            } else {
                                onStartCast()
                            }
                        },
                        onTogglePause = onTogglePause,
                        onSaveClip = onSaveClip,
                        onStopCast = onStopCast
                    )
                }

                MaterialYouBottomNavBar(
                    activeTab = activeTab,
                    isLive = isLive,
                    onSelectTab = { activeTab = it }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val incomingTakeover = session.incomingTakeover
            AnimatedVisibility(
                visible = incomingTakeover != null && isLive,
                enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                if (incomingTakeover != null) {
                    IncomingTakeoverBanner(
                        request = incomingTakeover,
                        onAccept = { onRespondTakeover(incomingTakeover.requestId, true) },
                        onDeny = { onRespondTakeover(incomingTakeover.requestId, false) }
                    )
                }
            }

            AnimatedVisibility(
                visible = !session.warningBanner.isNullOrBlank(),
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                NoticeBanner(
                    text = session.warningBanner.orEmpty(),
                    accent = CastColors.Amber,
                    bg = CastColors.AmberSoft
                )
            }

            AnimatedVisibility(
                visible = session.connectionState == CastConnectionState.ERROR && session.busyHostDevice == null,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                NoticeBanner(
                    text = session.statusMessage,
                    accent = CastColors.Danger,
                    bg = CastColors.DangerSoft
                )
            }

            val busyHost = session.busyHostDevice
            AnimatedVisibility(
                visible = busyHost != null && !isLive && !isConnecting,
                enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                if (busyHost != null) {
                    BusySessionTakeoverBanner(
                        device = busyHost,
                        activeSender = session.busyActiveSender ?: "outro dispositivo",
                        detailMessage = session.statusMessage,
                        onForceTakeover = { onForceTakeoverCast(busyHost) },
                        onDismiss = onDismissBusyBanner
                    )
                }
            }

            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> (fullWidth / 5) * direction }
                    ) + fadeIn(tween(220))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> -(fullWidth / 6) * direction }
                        ) + fadeOut(tween(180))
                    )
                },
                label = "mainTabSlide"
            ) { currentTab ->
                if (currentTab == HomeTab.CAST) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AnimatedVisibility(
                            visible = isLive,
                            enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) +
                                fadeIn(tween(240)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(180))
                        ) {
                            LiveTelemetryCard(
                                session = session,
                                prefs = prefs,
                                onTogglePause = onTogglePause,
                                onSaveClip = onSaveClip,
                                onTakeScreenshot = onTakeScreenshot,
                                onActivateAmoled = { onToggleAmoledBlackout(true) },
                                onQuickOptimize720p = {
                                    onUpdatePrefs {
                                        it.copy(
                                            preset = VideoPreset.PRESET_720P60,
                                            userCustomized = true
                                        )
                                    }
                                }
                            )
                        }

                        DevicePickerSection(
                            devices = devices,
                            selectedDeviceId = selectedDevice?.id,
                            mdnsScanning = mdnsScanning,
                            locked = isLive || isConnecting,
                            onSelectDevice = onSelectDevice,
                            onRemoveDevice = onRemoveDevice,
                            onRefreshDiscovery = onRefreshDiscovery,
                            onOpenQrScanner = onOpenQrScanner,
                            onOpenManualIp = { showManualDialog = true }
                        )

                        AudioSourceSection(
                            selectedMode = prefs.audioMode,
                            audioPermissionGranted = audioPermGranted,
                            onRequestAudioPermission = {
                                audioPermQuickLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onSelectMode = { mode ->
                                onUpdatePrefs { it.copy(audioMode = mode) }
                            }
                        )

                        VideoProfileSection(
                            prefs = prefs,
                            onSelectPreset = { preset ->
                                onUpdatePrefs { it.copy(preset = preset, userCustomized = true) }
                            },
                            onSelectImageMode = { mode ->
                                onUpdatePrefs { it.copy(imageMode = mode) }
                            },
                            onSelectLatencyMode = { latency ->
                                onUpdatePrefs { it.copy(latencyMode = latency) }
                            }
                        )

                        QuickClipsGallerySection(
                            items = recentMedia,
                            context = context,
                            onRefresh = { galleryRefreshTick++ }
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsTabContent(
                            prefs = prefs,
                            hasOverlayPermission = hasOverlayPermission,
                            onOpenOverlayGuide = { showOverlayGuideDialog = true },
                            onUpdatePrefs = onUpdatePrefs,
                            onResetAutoHardware = onResetAutoHardware
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (amoledBlackoutActive && isLive) {
        AmoledBlackoutOverlay(
            session = session,
            replayClipEnabled = prefs.replayClipEnabled,
            onSaveClip = onSaveClip,
            onTakeScreenshot = onTakeScreenshot,
            onRespondTakeover = onRespondTakeover,
            onStopCast = onStopCast,
            onWake = { onToggleAmoledBlackout(false) }
        )
    }

    if (showManualDialog) {
        ManualIpDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { target ->
                showManualDialog = false
                onManualTargetAdded(target)
            }
        )
    }

    var bubbleDragging by remember { mutableStateOf(false) }
    var bubbleOverCloseZone by remember { mutableStateOf(false) }
    val bubbleToastContext = LocalContext.current

    AnimatedVisibility(
        visible = prefs.floatingOverlayEnabled && isLive && !amoledBlackoutActive && !hasOverlayPermission && bubbleDragging,
        enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.88f, animationSpec = tween(140)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.88f, animationSpec = tween(140)),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 26.dp)
    ) {
        val dropBg by animateColorAsState(
            targetValue = if (bubbleOverCloseZone) Color(0xFFE11D48) else Color(0xDD111827),
            animationSpec = tween(120),
            label = "bubbleDropBg"
        )
        Surface(
            color = dropBg,
            shape = CircleShape,
            border = BorderStroke(
                width = 1.dp,
                color = if (bubbleOverCloseZone) Color(0xFFFFA4B6) else Color(0x55FFFFFF)
            ),
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (bubbleOverCloseZone) "Solte para remover" else "Arraste aqui para remover bolha",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (prefs.floatingOverlayEnabled && isLive && !amoledBlackoutActive && !hasOverlayPermission) {
        InAppFloatingBubble(
            isPaused = session.connectionState == CastConnectionState.PAUSED,
            replayClipEnabled = prefs.replayClipEnabled,
            onTogglePause = onTogglePause,
            onSaveClip = onSaveClip,
            onTakeScreenshot = onTakeScreenshot,
            onStopCast = onStopCast,
            onPinExternalOverlay = { showOverlayGuideDialog = true },
            onDismissBubble = {
                bubbleDragging = false
                bubbleOverCloseZone = false
                onUpdatePrefs { it.copy(floatingOverlayEnabled = false) }
                android.widget.Toast.makeText(
                    bubbleToastContext,
                    "Bolha removida da tela. Reative em Ajustes quando quiser.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onDragStateChange = { dragging, overClose ->
                bubbleDragging = dragging
                bubbleOverCloseZone = overClose
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 14.dp)
        )
    }

    CastKineticOverlay(
        event = session.kineticAnim,
        animationsEnabled = prefs.animationsEnabled,
        lowEndDevice = prefs.lowSpecMode || prefs.lowRamMode,
        onDismissed = onDismissKineticAnim
    )

    if (showOverlayGuideDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayGuideDialog = false },
            containerColor = CastColors.BgSurfaceElevated,
            titleContentColor = CastColors.TextPrimary,
            textContentColor = CastColors.TextSecondary,
            title = {
                Text(
                    text = "Ativar Bolha Fora do App",
                    fontWeight = FontWeight.Bold,
                    color = CastColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "Para a bolha continuar na tela em jogos, YouTube e outros apps:\n\n" +
                        "1. Toque em \"Abrir Ajustes\" abaixo\n" +
                        "2. Procure \"CastViewer\" na lista e ligue a chave\n" +
                        "3. Volte para cá — a bolha externa ativa automaticamente!",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayGuideDialog = false
                        onRequestOverlayPermission()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CastColors.FireOrange,
                        contentColor = Color.White
                    )
                ) {
                    Text("Abrir Ajustes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayGuideDialog = false }) {
                    Text("Agora não", color = CastColors.TextSecondary)
                }
            }
        )
    }
    }
}

@Composable
private fun InAppFloatingBubble(
    isPaused: Boolean,
    replayClipEnabled: Boolean,
    onTogglePause: () -> Unit,
    onSaveClip: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onStopCast: () -> Unit,
    onPinExternalOverlay: () -> Unit,
    onDismissBubble: () -> Unit,
    onDragStateChange: (dragging: Boolean, overDismissZone: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val screenW = metrics.widthPixels.toFloat()
    val screenH = metrics.heightPixels.toFloat()
    val densityPx = metrics.density

    var expanded by remember { mutableStateOf(false) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val statusColor = if (isPaused) CastColors.Amber else CastColors.Emerald

    fun isOverCloseZone(x: Float, y: Float): Boolean {
        val anchorX = screenW - 56f * densityPx
        val anchorY = 92f * densityPx
        val centerX = anchorX + x
        val centerY = anchorY + y
        return kotlin.math.abs(centerX - (screenW * 0.5f)) <= 108f * densityPx &&
            centerY >= (screenH - 165f * densityPx)
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(screenW, screenH) {
                detectDragGestures(
                    onDragStart = {
                        onDragStateChange(true, isOverCloseZone(offsetX, offsetY))
                    },
                    onDragEnd = {
                        val shouldDismiss = isOverCloseZone(offsetX, offsetY)
                        onDragStateChange(false, false)
                        if (shouldDismiss) {
                            expanded = false
                            onDismissBubble()
                        }
                    },
                    onDragCancel = {
                        onDragStateChange(false, false)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val minX = -(screenW - 140f * densityPx).coerceAtLeast(100f)
                    val maxX = 0f
                    val minY = -(52f * densityPx)
                    val maxY = (screenH - 110f * densityPx).coerceAtLeast(160f)
                    offsetX = (offsetX + dragAmount.x).coerceIn(minX, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(minY, maxY)
                    onDragStateChange(true, isOverCloseZone(offsetX, offsetY))
                }
            }
    ) {
        if (!expanded) {
            Surface(
                onClick = { expanded = true },
                shape = CircleShape,
                color = Color(0xEE101218),
                border = BorderStroke(1.25.dp, statusColor.copy(alpha = 0.72f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(statusColor, CircleShape)
                    )
                    Text(
                        text = if (isPaused) "PAUSADA" else "BOLHA",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = CastColors.TextPrimary
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF2101218),
                border = BorderStroke(1.25.dp, statusColor.copy(alpha = 0.65f)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                        Text(
                            text = if (isPaused) "EM PAUSA" else "NO AR",
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = statusColor
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { expanded = false },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Minimizar bolha",
                                    tint = CastColors.TextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    expanded = false
                                    onDismissBubble()
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remover bolha da tela",
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalIconButton(
                            onClick = onTogglePause,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (isPaused) "Retomar" else "Pausar",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (replayClipEnabled) {
                            FilledTonalIconButton(
                                onClick = onSaveClip,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Movie,
                                    contentDescription = "Salvar Clip 30s",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        FilledTonalIconButton(
                            onClick = onTakeScreenshot,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoCamera,
                                contentDescription = "Captura de tela",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onStopCast,
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = CastColors.DangerSoft,
                                contentColor = CastColors.Danger
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Parar transmissão",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            expanded = false
                            onPinExternalOverlay()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = CastColors.PrimaryContainer,
                        border = BorderStroke(1.dp, CastColors.FireOrange.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = CastColors.FireOrangeBright,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Fixar fora do app",
                                fontFamily = BarlowCondensedFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = CastColors.FireOrangeBright
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun M3TopAppBar(
    session: CastSessionState,
    animationsEnabled: Boolean = true
) {
    val logoLive = session.connectionState == CastConnectionState.STREAMING
    val logoScale by animateFloatAsState(
        targetValue = if (logoLive) 1.07f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "logoLiveScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CastColors.BgCanvas)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_castviewer_logo_official),
                contentDescription = "CastViewer Logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .scale(logoScale)
                    .clip(RoundedCornerShape(13.dp))
                    .border(
                        width = 1.dp,
                        color = if (logoLive) CastColors.FireOrange else CastColors.BorderSubtle,
                        shape = RoundedCornerShape(13.dp)
                    )
            )
            Column {
                Text(
                    text = "CASTVIEWER",
                    fontFamily = BarlowDisplayFamily,
                    fontSize = 21.sp,
                    lineHeight = 24.sp,
                    letterSpacing = 0.5.sp,
                    color = CastColors.TextPrimary
                )
                Text(
                    text = "ANDROID SENDER",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    color = CastColors.TextMuted
                )
            }
        }

        StatusPill(
            connectionState = session.connectionState,
            animationsEnabled = animationsEnabled
        )
    }
}

@Composable
private fun StatusPill(
    connectionState: CastConnectionState,
    animationsEnabled: Boolean = true
) {
    val (label, targetDotColor, targetBgColor) = when (connectionState) {
        CastConnectionState.IDLE -> Triple("PRONTO", CastColors.TextSecondary, CastColors.BgSurfaceElevated)
        CastConnectionState.CONNECTING -> Triple("CONECTANDO", CastColors.Amber, CastColors.AmberSoft)
        CastConnectionState.STREAMING -> Triple("AO VIVO", CastColors.Emerald, CastColors.EmeraldSoft)
        CastConnectionState.PAUSED -> Triple("PAUSADO", CastColors.Amber, CastColors.AmberSoft)
        CastConnectionState.ERROR -> Triple("ATENÇÃO", CastColors.Danger, CastColors.DangerSoft)
    }

    val dotColor by animateColorAsState(targetDotColor, tween(220), label = "pillDotColor")
    val bgColor by animateColorAsState(targetBgColor, tween(220), label = "pillBgColor")
    val shouldPulse = animationsEnabled && (
        connectionState == CastConnectionState.STREAMING ||
            connectionState == CastConnectionState.CONNECTING
        )
    val dotAlphaState = if (shouldPulse) {
        val pulseTransition = rememberInfiniteTransition(label = "statusDotPulse")
        pulseTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotPulseAlpha"
        )
    } else {
        null
    }

    Surface(
        color = bgColor,
        shape = CircleShape,
        border = BorderStroke(1.dp, CastColors.BorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlphaState?.value ?: 1.0f }
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = CastColors.TextPrimary
            )
        }
    }
}

/**
 * Material You Bottom Navigation Bar based on Figma Pack (n7oWAq5HgF7IqF5TxmwfDX, 74:1986 & 117:2728):
 * #2D2F33 surface container extending behind system gesture area, 80dp tab bar height,
 * animated 64dp x 32dp stadium indicator pill, and Barlow Condensed 12sp labels.
 */
@Composable
private fun MaterialYouBottomNavBar(
    activeTab: HomeTab,
    isLive: Boolean,
    onSelectTab: (HomeTab) -> Unit
) {
    Surface(
        color = CastColors.NavBarSurface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = CastColors.BorderSubtle.copy(alpha = 0.55f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(80.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    MaterialYouNavTab(
                        tab = tab,
                        selected = selected,
                        showLiveDot = isLive && tab == HomeTab.CAST,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialYouNavTab(
    tab: HomeTab,
    selected: Boolean,
    showLiveDot: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 64.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "navPillWidth"
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180),
        label = "navPillAlpha"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) CastColors.NavActiveIcon else CastColors.NavBarOnSurfaceVariant,
        animationSpec = tween(180),
        label = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) CastColors.TextPrimary else CastColors.NavBarOnSurfaceVariant,
        animationSpec = tween(180),
        label = "navLabelColor"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .height(80.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier.size(width = 64.dp, height = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = indicatorWidth, height = 32.dp)
                    .alpha(indicatorAlpha)
                    .clip(CircleShape)
                    .background(CastColors.NavActivePillContainer)
            )
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                contentDescription = tab.label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            if (showLiveDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 12.dp)
                        .size(8.dp)
                        .background(CastColors.Emerald, CircleShape)
                        .border(1.5.dp, CastColors.NavBarSurface, CircleShape)
                )
            }
        }

        Text(
            text = tab.label,
            fontFamily = BarlowCondensedFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1
        )
    }
}

@Composable
private fun FloatingCastDock(
    session: CastSessionState,
    prefs: PerformancePrefs,
    selectedDevice: FireStickDevice?,
    onStartCast: () -> Unit,
    onTogglePause: () -> Unit,
    onSaveClip: () -> Unit,
    onStopCast: () -> Unit
) {
    val isLive = session.connectionState == CastConnectionState.STREAMING ||
        session.connectionState == CastConnectionState.PAUSED
    val isConnecting = session.connectionState == CastConnectionState.CONNECTING
    val isPaused = session.connectionState == CastConnectionState.PAUSED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CastColors.BgCanvas)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            color = CastColors.BgSurfaceElevated,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, CastColors.BorderSubtle),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = when {
                    isConnecting -> 1
                    isLive -> 2
                    else -> 0
                },
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.97f, animationSpec = tween(180)))
                        .togetherWith(
                            fadeOut(tween(140)) + scaleOut(targetScale = 0.97f, animationSpec = tween(140))
                        )
                },
                label = "bottomDockTransition"
            ) { dockState ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (dockState) {
                        1 -> {
                            FilledTonalButton(
                                onClick = onStopCast,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = CastColors.NavBarSurface,
                                    contentColor = CastColors.TextPrimary
                                ),
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CastColors.FireOrange
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Conectando... Toque para cancelar",
                                    fontFamily = BarlowCondensedFamily,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        2 -> {
                            FilledTonalButton(
                                onClick = onTogglePause,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = CastColors.NavBarSurface,
                                    contentColor = CastColors.TextPrimary
                                ),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isPaused) "Retomar" else "Pausar",
                                    fontFamily = BarlowCondensedFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (prefs.replayClipEnabled) {
                                FilledTonalButton(
                                    onClick = onSaveClip,
                                    enabled = !session.isSavingClip,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = CastColors.PrimaryContainer,
                                        contentColor = CastColors.OnPrimaryContainer
                                    ),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    if (session.isSavingClip) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(15.dp),
                                            strokeWidth = 2.dp,
                                            color = CastColors.FireOrangeBright
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Videocam,
                                            contentDescription = "Salvar últimos 30s",
                                            tint = CastColors.FireOrangeBright,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (session.isSavingClip) "..." else "30s",
                                        fontFamily = BarlowCondensedFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Button(
                                onClick = onStopCast,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CastColors.FireOrange,
                                    contentColor = Color.White
                                ),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Parar",
                                    fontFamily = BarlowCondensedFamily,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        else -> {
                            val canCast = selectedDevice != null
                            val isDirectTakeover = canCast && session.busyHostDevice?.id == selectedDevice?.id
                            Button(
                                onClick = onStartCast,
                                enabled = canCast,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CastColors.FireOrange,
                                    contentColor = Color.White,
                                    disabledContainerColor = CastColors.NavBarSurface,
                                    disabledContentColor = CastColors.TextMuted
                                ),
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDirectTakeover) Icons.Rounded.SwapHoriz else Icons.Rounded.Cast,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isDirectTakeover -> "Assumir TV (${selectedDevice?.name})"
                                        canCast -> "Transmitir para ${selectedDevice?.name}"
                                        else -> "Selecione ou escaneie um Fire Stick"
                                    },
                                    fontFamily = BarlowCondensedFamily,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryCard(
    session: CastSessionState,
    prefs: PerformancePrefs,
    onTogglePause: () -> Unit,
    onSaveClip: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onActivateAmoled: () -> Unit,
    onQuickOptimize720p: () -> Unit
) {
    val stats = session.telemetry
    val isPaused = session.connectionState == CastConnectionState.PAUSED
    val needsDownscaleHint = !isPaused &&
        prefs.preset.maxShortEdge > 720 &&
        ((prefs.preset.fps >= 60 && stats.fps != null && stats.fps < 44) ||
            (stats.rttMs != null && stats.rttMs >= 85))
    val borderAccent by animateColorAsState(
        targetValue = if (isPaused) CastColors.Amber else CastColors.Emerald,
        animationSpec = tween(220),
        label = "liveBorder"
    )

    SectionCard(borderColor = borderAccent.copy(alpha = 0.65f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPaused) "TRANSMISSÃO PAUSADA" else "ESPELHANDO NA TV",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = if (isPaused) CastColors.Amber else CastColors.Emerald
                )
                Text(
                    text = session.activeDevice?.name ?: "Fire Stick",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = CastColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (prefs.replayClipEnabled) {
                FilledTonalButton(
                    onClick = onSaveClip,
                    enabled = !session.isSavingClip,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CastColors.PrimaryContainer,
                        contentColor = CastColors.OnPrimaryContainer
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (session.isSavingClip) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = CastColors.FireOrangeBright
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = CastColors.FireOrangeBright,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (session.isSavingClip) "Salvando..." else "Clipar 30s",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onTakeScreenshot,
                enabled = !session.isTakingScreenshot,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CastColors.BgInset,
                    contentColor = CastColors.TextPrimary
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (session.isTakingScreenshot) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = CastColors.FireOrangeBright
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = null,
                        tint = CastColors.FireOrangeBright,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (session.isTakingScreenshot) "Capturando..." else "Screenshot",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = onActivateAmoled,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CastColors.BgInset,
                    contentColor = CastColors.TextPrimary
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DarkMode,
                    contentDescription = null,
                    tint = CastColors.Emerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Tela AMOLED",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(
            visible = !session.shotFeedback.isNullOrBlank(),
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(150)) + fadeOut(tween(150))
        ) {
            Text(
                text = session.shotFeedback.orEmpty(),
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = CastColors.Emerald,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryChip(
                label = "LATÊNCIA",
                value = stats.rttMs?.let { "$it ms" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "FPS",
                value = stats.fps?.let { "$it" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "TAXA",
                value = stats.bitrateMbps?.let { String.format(Locale.US, "%.1f Mb", it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "CODEC",
                value = stats.codecLabel,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onTogglePause,
            shape = CircleShape,
            border = BorderStroke(1.dp, CastColors.BorderStrong),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                tint = CastColors.TextPrimary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPaused) "Retomar transmissão" else "Pausar transmissão",
                fontFamily = BarlowCondensedFamily,
                color = CastColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        AnimatedVisibility(
            visible = !session.clipFeedback.isNullOrBlank(),
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
        ) {
            val feedbackText = session.clipFeedback.orEmpty()
            val isSaved = feedbackText.startsWith("Clip salvo", ignoreCase = true)
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSaved) CastColors.EmeraldSoft else CastColors.BgInset,
                            RoundedCornerShape(16.dp)
                        )
                        .border(
                            1.dp,
                            if (isSaved) CastColors.Emerald.copy(alpha = 0.5f) else CastColors.BorderSubtle,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = feedbackText,
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        color = CastColors.TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = needsDownscaleHint,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
        ) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CastColors.AmberSoft, RoundedCornerShape(16.dp))
                        .border(1.dp, CastColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Wi-Fi/GPU oscilando em 1080p",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 13.sp,
                        color = CastColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onQuickOptimize720p,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Mudar p/ 720p60",
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CastColors.FireOrangeBright
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(CastColors.BgInset, RoundedCornerShape(16.dp))
            .border(1.dp, CastColors.BorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontFamily = BarlowCondensedFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            color = CastColors.TextMuted
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = BarlowCondensedFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = CastColors.TextPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun DevicePickerSection(
    devices: List<FireStickDevice>,
    selectedDeviceId: String?,
    mdnsScanning: Boolean,
    locked: Boolean,
    onSelectDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenManualIp: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ONDE ESPELHAR",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = CastColors.FireOrangeBright
                )
                Text(
                    text = if (mdnsScanning) "Buscando Fire Stick na rede..." else "Dispositivos na rede",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CastColors.TextPrimary
                )
            }

            FilledTonalIconButton(
                onClick = onRefreshDiscovery,
                enabled = !locked,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = CastColors.BgSurfaceElevated,
                    contentColor = CastColors.TextPrimary
                ),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Buscar novamente",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenQrScanner,
                enabled = !locked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CastColors.FireOrange,
                    contentColor = Color.White,
                    disabledContainerColor = CastColors.BgSurfaceElevated,
                    disabledContentColor = CastColors.TextMuted
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ler QR Code",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = onOpenManualIp,
                enabled = !locked,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CastColors.BgSurfaceElevated,
                    contentColor = CastColors.TextPrimary,
                    disabledContainerColor = CastColors.BgInset,
                    disabledContentColor = CastColors.TextMuted
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Digitar IP",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CastColors.BgInset, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Abra o CastViewer no Fire Stick (mesmo Wi-Fi ou cabo LAN) ou toque em Ler QR Code.",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextSecondary
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { dev ->
                    DeviceItemRow(
                        device = dev,
                        selected = dev.id == selectedDeviceId,
                        locked = locked,
                        onSelect = { onSelectDevice(dev.id) },
                        onRemove = { onRemoveDevice(dev.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItemRow(
    device: FireStickDevice,
    selected: Boolean,
    locked: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) 22.dp else 16.dp,
        animationSpec = tween(180),
        label = "deviceCorner"
    )
    val bg by animateColorAsState(
        targetValue = if (selected) CastColors.PrimaryContainer else CastColors.BgSurfaceElevated,
        animationSpec = tween(180),
        label = "devBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) CastColors.FireOrange else CastColors.BorderSubtle,
        animationSpec = tween(180),
        label = "devBorder"
    )
    val badgeBg by animateColorAsState(
        targetValue = if (selected) CastColors.FireOrange else CastColors.BgInset,
        animationSpec = tween(180),
        label = "devIconBg"
    )

    val tag = when (device.source) {
        DeviceSource.MDNS -> "Auto LAN"
        DeviceSource.QR -> "QR Code"
        DeviceSource.MANUAL -> "Manual"
        DeviceSource.SAVED -> "Salvo"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(1.2.dp, border, RoundedCornerShape(cornerRadius))
            .clickable(enabled = !locked, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(badgeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = if (selected) Color.White else CastColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                fontFamily = BarlowCondensedFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) CastColors.OnPrimaryContainer else CastColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${device.ip}:${device.port}  •  $tag",
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = if (selected) CastColors.OnPrimaryContainer.copy(alpha = 0.78f) else CastColors.TextSecondary
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (device.isOnline) CastColors.Emerald else CastColors.TextMuted,
                    CircleShape
                )
        )
        if (device.source != DeviceSource.MDNS && !locked) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remover",
                    tint = CastColors.TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioSourceSection(
    selectedMode: AudioCaptureMode,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onSelectMode: (AudioCaptureMode) -> Unit
) {
    SectionCard {
        Text(
            text = "ÁUDIO NA TV",
            fontFamily = BarlowCondensedFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            color = CastColors.FireOrangeBright
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioCaptureMode.entries.forEach { mode ->
                val isInternalUnsupported =
                    mode == AudioCaptureMode.INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                val icon = when (mode) {
                    AudioCaptureMode.OFF -> Icons.AutoMirrored.Rounded.VolumeOff
                    AudioCaptureMode.INTERNAL -> Icons.Rounded.GraphicEq
                    AudioCaptureMode.MIC -> Icons.Rounded.Mic
                }
                OptionTile(
                    title = mode.label,
                    subtitle = if (isInternalUnsupported) "Android 10+" else null,
                    icon = icon,
                    selected = selectedMode == mode,
                    enabled = !isInternalUnsupported,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectMode(mode) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = selectedMode.subtitle,
            fontFamily = BarlowCondensedFamily,
            fontSize = 12.sp,
            color = CastColors.TextSecondary
        )
        if (selectedMode != AudioCaptureMode.OFF && !audioPermissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                onClick = onRequestAudioPermission,
                shape = RoundedCornerShape(10.dp),
                color = CastColors.AmberSoft,
                border = BorderStroke(1.dp, CastColors.Amber.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = null,
                        tint = CastColors.Amber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Permissão de áudio/microfone negada — sem ela a TV e os clipes ficam SEM SOM. Toque para liberar.",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CastColors.Amber
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoProfileSection(
    prefs: PerformancePrefs,
    onSelectPreset: (VideoPreset) -> Unit,
    onSelectImageMode: (ImageMode) -> Unit,
    onSelectLatencyMode: (LatencyMode) -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "QUALIDADE E FLUIDEZ",
                fontFamily = BarlowCondensedFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.0.sp,
                color = CastColors.FireOrangeBright
            )
            Surface(
                color = if (prefs.userCustomized) CastColors.BgSurfaceElevated else CastColors.PrimaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = if (prefs.userCustomized) "MANUAL" else "AUTO",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = if (prefs.userCustomized) CastColors.TextSecondary else CastColors.FireOrangeBright,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Material You Android Quick Settings 2-column grid (from Figma 287:20466 / 304:29060)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickSettingPresetTile(
                    preset = VideoPreset.PRESET_1080P60,
                    icon = Icons.Rounded.HighQuality,
                    selected = prefs.preset == VideoPreset.PRESET_1080P60,
                    recommended = prefs.recommendedPreset == VideoPreset.PRESET_1080P60,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectPreset(VideoPreset.PRESET_1080P60) }
                )
                QuickSettingPresetTile(
                    preset = VideoPreset.PRESET_720P60,
                    icon = Icons.Rounded.Speed,
                    selected = prefs.preset == VideoPreset.PRESET_720P60,
                    recommended = prefs.recommendedPreset == VideoPreset.PRESET_720P60,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectPreset(VideoPreset.PRESET_720P60) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickSettingPresetTile(
                    preset = VideoPreset.PRESET_540P60,
                    icon = Icons.Rounded.Bolt,
                    selected = prefs.preset == VideoPreset.PRESET_540P60,
                    recommended = prefs.recommendedPreset == VideoPreset.PRESET_540P60,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectPreset(VideoPreset.PRESET_540P60) }
                )
                QuickSettingPresetTile(
                    preset = VideoPreset.PRESET_720P30,
                    icon = Icons.Rounded.AutoAwesome,
                    selected = prefs.preset == VideoPreset.PRESET_720P30,
                    recommended = prefs.recommendedPreset == VideoPreset.PRESET_720P30,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectPreset(VideoPreset.PRESET_720P30) }
                )
            }
            QuickSettingPresetTile(
                preset = VideoPreset.PRESET_1080P30,
                icon = Icons.Rounded.HighQuality,
                selected = prefs.preset == VideoPreset.PRESET_1080P30,
                recommended = prefs.recommendedPreset == VideoPreset.PRESET_1080P30,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelectPreset(VideoPreset.PRESET_1080P30) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "PRIORIDADE DE IMAGEM",
            fontFamily = BarlowCondensedFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = CastColors.TextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ImageMode.entries.forEach { mode ->
                OptionTile(
                    title = mode.label,
                    subtitle = if (mode == ImageMode.MOTION) "60 FPS estável" else "+30% Mbps",
                    icon = if (mode == ImageMode.MOTION) Icons.Rounded.Speed else Icons.Rounded.HighQuality,
                    selected = prefs.imageMode == mode,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectImageMode(mode) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "LATÊNCIA DE REDE",
            fontFamily = BarlowCondensedFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = CastColors.TextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LatencyMode.entries.forEach { lat ->
                OptionTile(
                    title = lat.label,
                    subtitle = if (lat == LatencyMode.ULTRA) "Tempo real" else "Anti-travamento",
                    icon = if (lat == LatencyMode.ULTRA) Icons.Rounded.Bolt else Icons.Rounded.Wifi,
                    selected = prefs.latencyMode == lat,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectLatencyMode(lat) }
                )
            }
        }
    }
}

/**
 * Adapted from Figma Android UI Kit Quick Settings SettingTile (304:29060 & 304:29061):
 * 2-column horizontal tile with leading 22dp icon, Title + Meta subtitle column, and M3 morphing radius.
 */
@Composable
private fun QuickSettingPresetTile(
    preset: VideoPreset,
    icon: ImageVector,
    selected: Boolean,
    recommended: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) 24.dp else 16.dp,
        animationSpec = tween(180),
        label = "qsTileCorner"
    )
    val bg by animateColorAsState(
        targetValue = if (selected) CastColors.PrimaryContainer else CastColors.BgSurfaceElevated,
        animationSpec = tween(180),
        label = "presetBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) CastColors.FireOrange else CastColors.BorderSubtle,
        animationSpec = tween(180),
        label = "presetBorder"
    )
    val badgeText = if (recommended) "IDEAL" else preset.badge

    Row(
        modifier = modifier
            .height(78.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(1.2.dp, border, RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) CastColors.FireOrangeBright else CastColors.TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preset.shortLabel,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) CastColors.OnPrimaryContainer else CastColors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = badgeText,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = if (selected || recommended) CastColors.FireOrangeBright else CastColors.TextMuted
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${preset.bitrateBps / 1_000_000} Mbps  •  ${preset.fps} FPS",
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = if (selected) CastColors.OnPrimaryContainer.copy(alpha = 0.78f) else CastColors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OptionTile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) 20.dp else 14.dp,
        animationSpec = tween(180),
        label = "optCorner"
    )
    val bg by animateColorAsState(
        targetValue = if (selected) CastColors.PrimaryContainer else CastColors.BgSurfaceElevated,
        animationSpec = tween(180),
        label = "pillOptBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) CastColors.FireOrange else CastColors.BorderSubtle,
        animationSpec = tween(180),
        label = "pillOptBorder"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(1.2.dp, border, RoundedCornerShape(cornerRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> CastColors.TextMuted
                selected -> CastColors.FireOrangeBright
                else -> CastColors.TextSecondary
            },
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            fontFamily = BarlowCondensedFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> CastColors.TextMuted
                selected -> CastColors.OnPrimaryContainer
                else -> CastColors.TextPrimary
            },
            maxLines = 1
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontFamily = BarlowCondensedFamily,
                fontSize = 11.sp,
                color = if (selected) CastColors.FireOrangeBright else CastColors.TextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SettingsTabContent(
    prefs: PerformancePrefs,
    hasOverlayPermission: Boolean,
    onOpenOverlayGuide: () -> Unit,
    onUpdatePrefs: ((PerformancePrefs) -> PerformancePrefs) -> Unit,
    onResetAutoHardware: () -> Unit
) {
    val hw = prefs.hardwareInfo
    val tierLabel = when (hw?.tier) {
        HardwareTier.HIGH -> "ALTO DESEMPENHO"
        HardwareTier.BALANCED -> "EQUILIBRADO"
        HardwareTier.WEAK -> "CELULAR DE ENTRADA"
        null -> "DETECTADO"
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(CastColors.PrimaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = CastColors.FireOrangeBright,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "HARDWARE DETECTADO",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = CastColors.TextPrimary
                )
            }

            Surface(
                color = CastColors.EmeraldSoft,
                shape = CircleShape
            ) {
                Text(
                    text = tierLabel,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CastColors.Emerald,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (hw != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CastColors.BgInset, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "${hw.deviceModel}  •  ${hw.ramGb} GB RAM  •  ${hw.cpuCores} núcleos",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CastColors.TextPrimary
                )
                Text(
                    text = "Encoder: ${hw.hwEncoderName}",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    color = CastColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = prefs.autoReason.ifBlank { "Perfil ajustado automaticamente para o aparelho." },
            fontFamily = BarlowCondensedFamily,
            fontSize = 13.sp,
            color = CastColors.TextSecondary
        )

        if (prefs.userCustomized) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onResetAutoHardware,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CastColors.PrimaryContainer,
                    contentColor = CastColors.OnPrimaryContainer
                ),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Restaurar Recomendação Automática (${prefs.recommendedPreset.shortLabel})",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.FireOrangeBright,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    SectionCard {
        Text(
            text = "OTIMIZAÇÃO E REPLAY",
            fontFamily = BarlowCondensedFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            color = CastColors.FireOrangeBright
        )
        Spacer(modifier = Modifier.height(6.dp))

        SwitchOptionRow(
            title = "Modo Celular Fraco",
            subtitle = "Alivia aquecimento e reduz bitrate quando a GPU está ocupada",
            checked = prefs.lowSpecMode,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(lowSpecMode = checked, userCustomized = true) }
            }
        )

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Modo Pouca RAM (<= 4 GB)",
            subtitle = "Reduz frequência de telemetria em segundo plano",
            checked = prefs.lowRamMode,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(lowRamMode = checked, userCustomized = true) }
            }
        )

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Compatibilidade de Vídeo (VP8 SW)",
            subtitle = "Ative apenas se a tela ficar verde ou preta em aparelhos antigos",
            checked = prefs.compatMode,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(compatMode = checked, userCustomized = true) }
            }
        )

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Clip Replay 30s (Zero Lag)",
            subtitle = "Salva os últimos 30s de vídeo + áudio na Galeria sem re-encode pesado (${if (prefs.lowRamMode) "buffer compacto" else "qualidade total"})",
            checked = prefs.replayClipEnabled,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(replayClipEnabled = checked, userCustomized = true) }
            }
        )

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Bolha Flutuante Externa",
            subtitle = if (hasOverlayPermission) {
                "Permissão ativa: a bolha fica na tela por cima de jogos, YouTube e outros apps durante o espelhamento"
            } else {
                "Exibe controles fora do app (jogos, YouTube, tela inicial). Requer ativar 'Sobrepor a outros apps' uma vez"
            },
            checked = prefs.floatingOverlayEnabled,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(floatingOverlayEnabled = checked) }
                if (checked && !hasOverlayPermission) {
                    onOpenOverlayGuide()
                }
            }
        )

        AnimatedVisibility(visible = prefs.floatingOverlayEnabled && !hasOverlayPermission) {
            Surface(
                onClick = onOpenOverlayGuide,
                shape = RoundedCornerShape(12.dp),
                color = CastColors.Amber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, CastColors.Amber.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Layers,
                        contentDescription = null,
                        tint = CastColors.Amber,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Falta 1 permissão para sair do app",
                            color = CastColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Toque para abrir Ajustes e ativar CastViewer",
                            color = CastColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "ATIVAR",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CastColors.FireOrangeBright
                    )
                }
            }
        }

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Animações no App",
            subtitle = "Transições leves otimizadas para celular (desative para máximo desempenho)",
            checked = prefs.animationsEnabled,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(animationsEnabled = checked, userCustomized = true) }
            }
        )

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Relógio na TV ao Transmitir",
            subtitle = "Mostra a hora com segundos em um dos cantos da tela da TV durante a transmissão",
            checked = prefs.streamClockEnabled,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(streamClockEnabled = checked) }
            }
        )

        AnimatedVisibility(visible = prefs.streamClockEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = CastColors.FireOrangeBright,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Posição do relógio na tela da TV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CastColors.TextSecondary
                    )
                }
                val cornersTop = listOf("tl" to "Sup. Esq.", "tr" to "Sup. Dir.")
                val cornersBottom = listOf("bl" to "Inf. Esq.", "br" to "Inf. Dir.")
                listOf(cornersTop, cornersBottom).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (code, label) ->
                            val selected = PerformancePrefs.normalizeClockCorner(prefs.streamClockCorner) == code
                            Surface(
                                onClick = {
                                    onUpdatePrefs { it.copy(streamClockCorner = code) }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) CastColors.PrimaryContainer else CastColors.BgInset,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) CastColors.FireOrangeBright else CastColors.BorderSubtle
                                )
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = BarlowCondensedFamily,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) CastColors.FireOrangeBright else CastColors.TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = CastColors.BorderSubtle.copy(alpha = 0.55f))

        SwitchOptionRow(
            title = "Economia AMOLED Automática",
            subtitle = "Escurece a tela do celular em preto puro (#000000, brilho 1%) ao iniciar transmissão longa",
            checked = prefs.amoledSaverEnabled,
            onCheckedChange = { checked ->
                onUpdatePrefs { it.copy(amoledSaverEnabled = checked) }
            }
        )
    }

    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(CastColors.PrimaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = CastColors.FireOrangeBright,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ATALHOS NA BARRA RÁPIDA (QUICK SETTINGS)",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = CastColors.FireOrangeBright
                )
                Text(
                    text = "Puxe a barra do topo do Android → Editar (lápis) → adicione 'Clip 30s • Cast' e 'Pausar Cast' para gravar sem sair da tela cheia.",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.5.sp,
                    color = CastColors.TextSecondary,
                    lineHeight = 17.sp
                )
            }
        }
    }

    SectionCard {
        Text(
            text = "IDENTIFICAÇÃO NA TV",
            fontFamily = BarlowCondensedFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
            color = CastColors.FireOrangeBright
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = prefs.senderName,
            onValueChange = { value ->
                onUpdatePrefs { it.copy(senderName = value.take(32)) }
            },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = CastColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            },
            label = {
                Text(
                    text = "Nome exibido no Fire Stick",
                    fontFamily = BarlowCondensedFamily
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CastColors.FireOrange,
                unfocusedBorderColor = CastColors.BorderStrong,
                focusedTextColor = CastColors.TextPrimary,
                unfocusedTextColor = CastColors.TextPrimary,
                focusedLabelColor = CastColors.FireOrangeBright,
                unfocusedLabelColor = CastColors.TextMuted
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CastColors.BgInset, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = CastColors.TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Rede local compatível com Wi-Fi 5 GHz, Wi-Fi 2.4 GHz ou cabo LAN no mesmo roteador do Fire Stick.",
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = CastColors.TextSecondary
            )
        }
    }
}

@Composable
private fun SwitchOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontFamily = BarlowCondensedFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = CastColors.TextPrimary
            )
            Text(
                text = subtitle,
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = CastColors.TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else {
                null
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedIconColor = CastColors.FireOrange,
                checkedTrackColor = CastColors.FireOrange,
                uncheckedThumbColor = CastColors.TextSecondary,
                uncheckedTrackColor = CastColors.BgInset,
                uncheckedBorderColor = CastColors.BorderStrong
            )
        )
    }
}

@Composable
private fun SectionCard(
    borderColor: Color = CastColors.BorderSubtle.copy(alpha = 0.7f),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = CastColors.BgSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    accent: Color,
    bg: Color
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontFamily = BarlowCondensedFamily,
                fontSize = 13.sp,
                color = CastColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun IncomingTakeoverBanner(
    request: IncomingTakeoverRequest,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    var remainingSec by remember(request.requestId) {
        mutableIntStateOf(
            ((request.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                .toInt()
                .coerceIn(0, request.timeoutSec)
        )
    }

    LaunchedEffect(request.requestId, request.expiresAtMs) {
        while (true) {
            val leftMs = (request.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            remainingSec = ((leftMs + 999L) / 1000L).toInt().coerceIn(0, request.timeoutSec)
            if (leftMs <= 0L) break
            delay(250L)
        }
    }

    Box(modifier = modifier) {
    SectionCard(
        borderColor = CastColors.Amber.copy(alpha = 0.85f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(CastColors.AmberSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = CastColors.Amber,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${request.requesterName} QUER ASSUMIR A TV",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = CastColors.Amber,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CastColors.AmberSoft)
                            .border(1.dp, CastColors.Amber.copy(alpha = 0.55f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${remainingSec}s",
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CastColors.Amber
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Aceite para transferir a transmissão ou recuse para continuar espelhando.",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextPrimary,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onDeny,
                border = BorderStroke(1.dp, CastColors.BorderStrong),
                shape = CircleShape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = CastColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Recusar",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CastColors.TextPrimary
                )
            }

            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CastColors.Emerald,
                    contentColor = Color.Black
                ),
                shape = CircleShape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Aceitar",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    }
}

@Composable
private fun BusySessionTakeoverBanner(
    device: FireStickDevice,
    activeSender: String,
    detailMessage: String,
    onForceTakeover: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionCard(borderColor = CastColors.FireOrange.copy(alpha = 0.7f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(CastColors.PrimaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = CastColors.FireOrangeBright,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TV OCUPADA POR ${activeSender.uppercase(Locale.getDefault())}",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = CastColors.FireOrangeBright
                )
                Text(
                    text = detailMessage.ifBlank { "Sessão ativa em ${device.name}" },
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Envia um pedido ao aparelho atual (ou assume automaticamente em 15s se ninguém responder).",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    color = CastColors.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onForceTakeover,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CastColors.FireOrange,
                    contentColor = Color.White
                ),
                shape = CircleShape,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Assumir TV",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(onClick = onDismiss) {
                Text(
                    text = "Agora não",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun QuickClipsGallerySection(
    items: List<CastMediaItem>,
    context: Context,
    onRefresh: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CLIPS E CAPTURAS RECENTES",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = CastColors.FireOrangeBright
                )
                Text(
                    text = if (items.isEmpty()) {
                        "Movies/CastViewer • Pictures/CastViewer"
                    } else {
                        "${items.size} arquivo(s) salvos neste aparelho"
                    },
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextSecondary
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Atualizar galeria de clips",
                    tint = CastColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (items.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CastColors.BgInset, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = CastColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Seus clips de 30s e screenshots 1-toque aparecerão aqui para assistir ou enviar no WhatsApp e Discord.",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.5.sp,
                    color = CastColors.TextSecondary,
                    lineHeight = 17.sp
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(items, key = { it.id }) { item ->
                    QuickMediaItemCard(item = item, context = context)
                }
            }
        }
    }
}

@Composable
private fun QuickMediaItemCard(
    item: CastMediaItem,
    context: Context
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = ClipGalleryRepository.loadThumbnail(context, item)
    }

    Surface(
        color = CastColors.BgInset,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CastColors.BorderSubtle),
        modifier = Modifier.width(214.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C0E13)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = CastColors.TextMuted,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    val durSec = (item.durationMs / 1000L).coerceAtLeast(1L)
                    val tagText = if (item.isVideo) {
                        if (item.durationMs > 0L) "MP4 • ${durSec}s" else "MP4"
                    } else {
                        "PNG"
                    }
                    Text(
                        text = tagText,
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isVideo) CastColors.FireOrangeBright else CastColors.Emerald,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = formatCompactBytes(item.sizeBytes),
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 10.sp,
                        color = CastColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.displayName,
                fontFamily = BarlowCondensedFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = CastColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = item.folderLabel,
                fontFamily = BarlowCondensedFamily,
                fontSize = 11.sp,
                color = CastColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        if (!ClipGalleryRepository.openMedia(context, item)) {
                            android.widget.Toast.makeText(
                                context,
                                "Nenhum player encontrado para abrir ${item.displayName}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CastColors.NavBarSurface,
                        contentColor = CastColors.TextPrimary
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.Image,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (item.isVideo) "Assistir" else "Abrir",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        ClipGalleryRepository.shareMedia(context, item)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CastColors.FireOrange,
                        contentColor = Color.White
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Enviar",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatCompactBytes(bytes: Long): String {
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1.0f) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

@Composable
private fun AmoledBlackoutOverlay(
    session: CastSessionState,
    replayClipEnabled: Boolean,
    onSaveClip: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onRespondTakeover: (String, Boolean) -> Unit,
    onStopCast: () -> Unit,
    onWake: () -> Unit
) {
    val driftTransition = rememberInfiniteTransition(label = "amoledBurnInGuard")
    val driftY by driftTransition.animateFloat(
        initialValue = -22f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftY"
    )
    val driftX by driftTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 31000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftX"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onWake() })
            }
    ) {
        val incomingTakeover = session.incomingTakeover
        if (incomingTakeover != null) {
            IncomingTakeoverBanner(
                request = incomingTakeover,
                onAccept = { onRespondTakeover(incomingTakeover.requestId, true) },
                onDeny = { onRespondTakeover(incomingTakeover.requestId, false) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(driftX.roundToInt(), driftY.roundToInt()) }
                .alpha(0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DarkMode,
                contentDescription = null,
                tint = CastColors.FireOrangeBright,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "ECONOMIA AMOLED ATIVA",
                fontFamily = BarlowCondensedFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = CastColors.TextPrimary
            )
            val fpsPart = session.telemetry.fps?.let { " • $it FPS" } ?: ""
            Text(
                text = "${session.activeDevice?.name ?: "Fire Stick"}$fpsPart",
                fontFamily = BarlowCondensedFamily,
                fontSize = 13.sp,
                color = CastColors.TextSecondary
            )
            Text(
                text = "Dê 2 toques na tela para restaurar o brilho",
                fontFamily = BarlowCondensedFamily,
                fontSize = 12.sp,
                color = CastColors.TextMuted,
                textAlign = TextAlign.Center
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 34.dp)
                .alpha(0.58f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (replayClipEnabled) {
                OutlinedButton(
                    onClick = onSaveClip,
                    enabled = !session.isSavingClip,
                    border = BorderStroke(1.dp, CastColors.BorderStrong),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (session.isSavingClip) "30s..." else "Clip 30s",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        color = CastColors.TextPrimary
                    )
                }
            }
            OutlinedButton(
                onClick = onTakeScreenshot,
                enabled = !session.isTakingScreenshot,
                border = BorderStroke(1.dp, CastColors.BorderStrong),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (session.isTakingScreenshot) "Foto..." else "Screenshot",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    color = CastColors.TextPrimary
                )
            }
            OutlinedButton(
                onClick = onWake,
                border = BorderStroke(1.dp, CastColors.FireOrange.copy(alpha = 0.6f)),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Acordar",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    color = CastColors.FireOrangeBright
                )
            }
            OutlinedButton(
                onClick = onStopCast,
                border = BorderStroke(1.dp, CastColors.Danger.copy(alpha = 0.6f)),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Parar",
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 12.sp,
                    color = CastColors.Danger
                )
            }
        }
    }
}

/**
 * Styled after Android UI Kit Permission Dialog (446:5298):
 * centered badge icon, Headline Small Barlow title, rounded input, and stacked pill buttons.
 */
@Composable
private fun ManualIpDialog(
    onDismiss: () -> Unit,
    onConfirm: (QrUriParser.ParsedTarget) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = CastColors.BgSurfaceElevated,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, CastColors.BorderSubtle),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(CastColors.PrimaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = CastColors.FireOrangeBright,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Conectar por IP",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = CastColors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Digite o IP exibido no Fire Stick (ex: 192.168.1.50)",
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 14.sp,
                        color = CastColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            errorText = null
                        },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "192.168.1.50",
                                fontFamily = BarlowCondensedFamily
                            )
                        },
                        isError = errorText != null,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CastColors.FireOrange,
                            unfocusedBorderColor = CastColors.BorderStrong,
                            focusedTextColor = CastColors.TextPrimary,
                            unfocusedTextColor = CastColors.TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorText!!,
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 12.sp,
                            color = CastColors.Danger
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val parsed = QrUriParser.parseFireStickTarget(input)
                            if (parsed != null) {
                                onConfirm(parsed)
                            } else {
                                errorText = "IP inválido. Ex: 192.168.1.50 ou ws://192.168.1.50:8765"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CastColors.FireOrange,
                            contentColor = Color.White
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Adicionar dispositivo",
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    FilledTonalButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = CastColors.NavBarSurface,
                            contentColor = CastColors.TextSecondary
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Cancelar",
                            fontFamily = BarlowCondensedFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
