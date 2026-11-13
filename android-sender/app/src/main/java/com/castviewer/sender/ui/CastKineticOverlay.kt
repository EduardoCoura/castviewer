package com.castviewer.sender.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castviewer.sender.R
import com.castviewer.sender.model.KineticAnimEvent
import com.castviewer.sender.model.KineticAnimType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hardware-accelerated, zero-recomposition Minimalist Kinetic Precision overlay aligned with Desktop & Fire TV.
 * Reads animation progress strictly during GPU/Draw phases (graphicsLayer / drawBehind / Canvas) so weak and
 * mid-range Android devices maintain 60+ FPS with zero per-frame Compose layout churn.
 */
@Composable
fun CastKineticOverlay(
    event: KineticAnimEvent?,
    animationsEnabled: Boolean = true,
    lowEndDevice: Boolean = false,
    onDismissed: (Long) -> Unit
) {
    if (event == null) return
    if (!animationsEnabled) {
        LaunchedEffect(event.id) {
            onDismissed(event.id)
        }
        return
    }

    val context = LocalContext.current
    val progress = remember(event.id) { Animatable(0f) }
    var visible by remember(event.id) { mutableStateOf(true) }
    val lite = remember(lowEndDevice) { lowEndDevice || isLowMemoryDevice(context) }

    LaunchedEffect(event.id) {
        val ageMs = System.currentTimeMillis() - event.createdAtMs
        // Only skip non-essential setting badges on very weak devices; COLD_INTRO always plays cleanly
        // with zero-recomposition GPU layers whenever animationsEnabled is true.
        val skipForLite = lite && event.type == KineticAnimType.SETTING_BADGE
        if (ageMs > 4500L || skipForLite) {
            visible = false
            onDismissed(event.id)
            return@LaunchedEffect
        }
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f
        }
        if (scale <= 0f) {
            visible = false
            onDismissed(event.id)
            return@LaunchedEffect
        }

        val durationMs = when (event.type) {
            KineticAnimType.COLD_INTRO -> if (lite) 820 else 960
            KineticAnimType.CAST_CONNECT -> if (lite) 760 else 880
            KineticAnimType.CAST_DISCONNECT -> 680
            KineticAnimType.PAUSE_STATE -> 580
            KineticAnimType.SETTING_BADGE -> 640
        }

        // Let first composition + font/logo GPU upload settle so frame 1 of the tween never drops.
        if (event.type == KineticAnimType.COLD_INTRO) {
            withFrameNanos { }
            withFrameNanos { }
        } else if (event.type == KineticAnimType.CAST_CONNECT) {
            withFrameNanos { }
        }

        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
        visible = false
        onDismissed(event.id)
    }

    if (!visible) return

    val progressReader: () -> Float = remember(progress) {
        { progress.value.coerceIn(0f, 1f) }
    }

    when (event.type) {
        KineticAnimType.COLD_INTRO,
        KineticAnimType.CAST_CONNECT -> FullscreenConnectOrIntroOverlay(
            event = event,
            progressReader = progressReader,
            lite = lite
        )

        KineticAnimType.CAST_DISCONNECT -> FullscreenDisconnectOverlay(
            event = event,
            progressReader = progressReader,
            lite = lite
        )

        KineticAnimType.PAUSE_STATE,
        KineticAnimType.SETTING_BADGE -> CompactKineticBadgeOverlay(
            event = event,
            progressReader = progressReader
        )
    }
}

private fun isLowMemoryDevice(context: android.content.Context): Boolean {
    return try {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        am.isLowRamDevice || info.totalMem <= 4_400_000_000L
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun FullscreenConnectOrIntroOverlay(
    event: KineticAnimEvent,
    progressReader: () -> Float,
    lite: Boolean
) {
    val staticAccent = if (event.accentEmerald) CastColors.Emerald else CastColors.FireOrange

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val t = progressReader()
                val lockReached = t >= 0.72f
                val dissolveFraction = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
                val easedDissolve = dissolveFraction * dissolveFraction
                val scrimAlpha = when {
                    t < 0.06f -> (t / 0.06f) * 0.96f
                    lockReached -> 0.96f * (1f - easedDissolve)
                    else -> 0.96f
                }
                drawRect(color = Color(0xFF06070A).copy(alpha = scrimAlpha))
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progressReader()
            val buildFraction = (t / 0.72f).coerceIn(0f, 1f)
            val easedBuild = 1f - (1f - buildFraction).pow(3)
            val lockReached = t >= 0.72f
            val dissolveFraction = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val contentAlpha = when {
                t < 0.08f -> t / 0.08f
                lockReached -> (1f - dissolveFraction * 1.15f).coerceIn(0f, 1f)
                else -> 1f
            }
            val accentColor = when {
                event.accentEmerald && lockReached -> CastColors.Emerald
                event.accentEmerald -> lerp(CastColors.FireOrange, CastColors.Emerald, easedBuild)
                lockReached -> CastColors.Emerald
                else -> lerp(CastColors.FireOrange, CastColors.Emerald, (easedBuild - 0.75f).coerceAtLeast(0f) * 4f)
            }

            val center = Offset(size.width / 2f, size.height / 2f - 44.dp.toPx())
            val orbitRadius = 66.dp.toPx()

            if (!lite) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.06f * contentAlpha),
                    radius = orbitRadius * 2.1f,
                    center = center
                )
            }
            drawCircle(
                color = accentColor.copy(alpha = 0.11f * contentAlpha),
                radius = orbitRadius * (1.30f + 0.14f * easedBuild),
                center = center
            )

            // 4 cardinal precision tick marks
            val tickRadius = orbitRadius + 14.dp.toPx()
            val baseAngleOffset = t * 42f
            val tickLen = 6.dp.toPx()
            val tickStroke = 1.6.dp.toPx()
            for (i in 0 until 4) {
                val deg = (i * 90f + baseAngleOffset) * (PI.toFloat() / 180f)
                val cosA = cos(deg)
                val sinA = sin(deg)
                drawLine(
                    color = Color.White.copy(alpha = 0.24f * contentAlpha),
                    start = Offset(center.x + cosA * tickRadius, center.y + sinA * tickRadius),
                    end = Offset(center.x + cosA * (tickRadius + tickLen), center.y + sinA * (tickRadius + tickLen)),
                    strokeWidth = tickStroke,
                    cap = StrokeCap.Round
                )
            }

            // Static precision track ring
            drawCircle(
                color = Color.White.copy(alpha = 0.12f * contentAlpha),
                radius = orbitRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Animated sweep arc + leading photon dot
            val sweep = 360f * easedBuild
            val arcTopLeft = Offset(center.x - orbitRadius, center.y - orbitRadius)
            val arcSize = Size(orbitRadius * 2f, orbitRadius * 2f)
            drawArc(
                color = accentColor.copy(alpha = 0.96f * contentAlpha),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
            )

            if (sweep in 2f..358f) {
                val headRad = (-90f + sweep) * (PI.toFloat() / 180f)
                val headPos = Offset(
                    center.x + cos(headRad) * orbitRadius,
                    center.y + sin(headRad) * orbitRadius
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.45f * contentAlpha),
                    radius = 9.dp.toPx(),
                    center = headPos
                )
                drawCircle(
                    color = Color.White.copy(alpha = contentAlpha),
                    radius = 3.8.dp.toPx(),
                    center = headPos
                )
            }

            if (lockReached) {
                val rippleR = orbitRadius + 115.dp.toPx() * dissolveFraction
                val rippleAlpha = ((1f - dissolveFraction) * 0.65f).coerceIn(0f, 1f)
                drawCircle(
                    color = CastColors.Emerald.copy(alpha = rippleAlpha),
                    radius = rippleR,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    val t = progressReader()
                    val buildFraction = (t / 0.72f).coerceIn(0f, 1f)
                    val easedBuild = 1f - (1f - buildFraction).pow(3)
                    val lockReached = t >= 0.72f
                    val dissolveFraction = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    alpha = when {
                        t < 0.08f -> t / 0.08f
                        lockReached -> (1f - dissolveFraction * 1.15f).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    translationY = (1f - easedBuild) * 10.dp.toPx()
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        val t = progressReader()
                        val buildFraction = (t / 0.72f).coerceIn(0f, 1f)
                        val easedBuild = 1f - (1f - buildFraction).pow(3)
                        val lockReached = t >= 0.72f
                        val dissolveFraction = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
                        val s = 0.86f + 0.15f * easedBuild + if (lockReached) 0.09f * dissolveFraction else 0f
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = 1.5.dp,
                        color = staticAccent.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_castviewer_logo_official),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(34.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(staticAccent.copy(alpha = 0.16f))
                    .border(1.dp, staticAccent.copy(alpha = 0.48f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = event.badge,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    color = staticAccent
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = event.title,
                fontFamily = BarlowDisplayFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                color = CastColors.TextPrimary,
                textAlign = TextAlign.Center
            )

            if (event.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.subtitle,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = CastColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FullscreenDisconnectOverlay(
    event: KineticAnimEvent,
    progressReader: () -> Float,
    lite: Boolean
) {
    val staticRingColor = CastColors.FireOrange

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val t = progressReader()
                val enterAlpha = (t / 0.12f).coerceIn(0f, 1f)
                val exitAlpha = ((1f - t) / 0.24f).coerceIn(0f, 1f)
                val masterAlpha = minOf(enterAlpha, exitAlpha)
                drawRect(color = Color(0xFF06070A).copy(alpha = 0.90f * masterAlpha))
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progressReader()
            val enterAlpha = (t / 0.12f).coerceIn(0f, 1f)
            val exitAlpha = ((1f - t) / 0.24f).coerceIn(0f, 1f)
            val masterAlpha = minOf(enterAlpha, exitAlpha)
            val unwind = (1f - t).coerceIn(0f, 1f)
            val easedUnwind = unwind * unwind
            val ringColor = lerp(Color(0xFF71717A), CastColors.FireOrange, easedUnwind)

            val center = Offset(size.width / 2f, size.height / 2f - 40.dp.toPx())
            val orbitRadius = 62.dp.toPx()

            if (!lite) {
                drawCircle(
                    color = ringColor.copy(alpha = 0.08f * masterAlpha),
                    radius = orbitRadius * 1.8f,
                    center = center
                )
            }

            drawCircle(
                color = Color.White.copy(alpha = 0.10f * masterAlpha),
                radius = orbitRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            drawArc(
                color = ringColor.copy(alpha = 0.92f * masterAlpha),
                startAngle = -90f,
                sweepAngle = 360f * easedUnwind,
                useCenter = false,
                topLeft = Offset(center.x - orbitRadius, center.y - orbitRadius),
                size = Size(orbitRadius * 2f, orbitRadius * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    val t = progressReader()
                    val enterAlpha = (t / 0.12f).coerceIn(0f, 1f)
                    val exitAlpha = ((1f - t) / 0.24f).coerceIn(0f, 1f)
                    alpha = minOf(enterAlpha, exitAlpha)
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .graphicsLayer {
                        val t = progressReader()
                        val unwind = (1f - t).coerceIn(0f, 1f)
                        val s = 0.90f + 0.10f * (unwind * unwind)
                        scaleX = s
                        scaleY = s
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .border(
                        width = 1.5.dp,
                        color = staticRingColor.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(22.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_castviewer_logo_official),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.78f)
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(staticRingColor.copy(alpha = 0.16f))
                    .border(1.dp, staticRingColor.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = event.badge,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    color = CastColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = event.title,
                fontFamily = BarlowDisplayFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = CastColors.TextPrimary,
                textAlign = TextAlign.Center
            )

            if (event.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.subtitle,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 13.sp,
                    color = CastColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CompactKineticBadgeOverlay(
    event: KineticAnimEvent,
    progressReader: () -> Float
) {
    val accent = when {
        event.type == KineticAnimType.PAUSE_STATE && event.isPausedState -> CastColors.Amber
        event.type == KineticAnimType.PAUSE_STATE -> CastColors.Emerald
        event.accentEmerald -> CastColors.Emerald
        else -> CastColors.FireOrangeBright
    }
    val playPath = remember { Path() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 84.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    val t = progressReader()
                    val enter = (t / 0.18f).coerceIn(0f, 1f)
                    val exit = ((1f - t) / 0.25f).coerceIn(0f, 1f)
                    val s = 0.90f + 0.10f * enter
                    scaleX = s
                    scaleY = s
                    alpha = minOf(enter, exit)
                }
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF0B0D12).copy(alpha = 0.94f))
                .border(1.5.dp, accent.copy(alpha = 0.58f), RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Canvas(modifier = Modifier.size(34.dp)) {
                val t = progressReader()
                val ringSweep = ((t / 0.65f).coerceIn(0f, 1f)).let { 1f - (1f - it).pow(2) }
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(
                    color = accent.copy(alpha = 0.16f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * ringSweep,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                if (event.type == KineticAnimType.PAUSE_STATE) {
                    if (event.isPausedState) {
                        val barW = 2.6.dp.toPx()
                        val barH = 11.dp.toPx()
                        val gap = 3.2.dp.toPx()
                        drawLine(
                            color = accent,
                            start = Offset(c.x - gap, c.y - barH / 2f),
                            end = Offset(c.x - gap, c.y + barH / 2f),
                            strokeWidth = barW,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = accent,
                            start = Offset(c.x + gap, c.y - barH / 2f),
                            end = Offset(c.x + gap, c.y + barH / 2f),
                            strokeWidth = barW,
                            cap = StrokeCap.Round
                        )
                    } else {
                        playPath.reset()
                        playPath.moveTo(c.x - 3.5.dp.toPx(), c.y - 5.5.dp.toPx())
                        playPath.lineTo(c.x + 5.5.dp.toPx(), c.y)
                        playPath.lineTo(c.x - 3.5.dp.toPx(), c.y + 5.5.dp.toPx())
                        playPath.close()
                        drawPath(playPath, color = accent)
                    }
                } else {
                    drawCircle(
                        color = accent,
                        radius = 4.dp.toPx(),
                        center = c
                    )
                }
            }

            Column {
                Text(
                    text = event.badge,
                    fontFamily = BarlowCondensedFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = accent
                )
                Text(
                    text = event.title,
                    fontFamily = BarlowDisplayFamily,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = CastColors.TextPrimary
                )
                if (event.subtitle.isNotBlank()) {
                    Text(
                        text = event.subtitle,
                        fontFamily = BarlowCondensedFamily,
                        fontSize = 12.sp,
                        color = CastColors.TextSecondary
                    )
                }
            }
        }
    }
}
