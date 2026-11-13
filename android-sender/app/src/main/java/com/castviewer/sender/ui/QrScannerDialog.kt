package com.castviewer.sender.ui

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.castviewer.sender.model.QrUriParser
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(
    onTargetScanned: (QrUriParser.ParsedTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    var feedbackMessage by remember { mutableStateOf("Aponte a câmera para o QR Code na TV") }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CastColors.BgCanvas)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val provider = try {
                            cameraProviderFuture.get()
                        } catch (_: Exception) {
                            return@addListener
                        }

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val reader = QRCodeReader()
                        val hints = mapOf(DecodeHintType.TRY_HARDER to true)

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analyzer ->
                                analyzer.setAnalyzer(cameraExecutor) { image ->
                                    if (handled.get()) {
                                        image.close()
                                        return@setAnalyzer
                                    }
                                    try {
                                        val decoded = decodeQrFromYuv(image, reader, hints)
                                        if (!decoded.isNullOrBlank()) {
                                            val target = QrUriParser.parseFireStickTarget(decoded)
                                            if (target != null && handled.compareAndSet(false, true)) {
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    onTargetScanned(target)
                                                }
                                            } else if (target == null) {
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    feedbackMessage = "QR lido não pertence ao CastViewer TV"
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {
                                    } finally {
                                        image.close()
                                    }
                                }
                            }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (_: Exception) {
                            feedbackMessage = "Não foi possível iniciar a câmera traseira"
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // Craft Minimalist viewfinder brackets + smooth laser scan line
            val scanTransition = rememberInfiniteTransition(label = "qrLaser")
            val laserFraction by scanTransition.animateFloat(
                initialValue = 0.08f,
                targetValue = 0.92f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "laserY"
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(230.dp)) {
                    val corner = 34.dp.toPx()
                    val stroke = 4.dp.toPx()
                    val w = size.width
                    val h = size.height
                    val c = CastColors.FireOrange

                    // Top-left
                    drawLine(c, Offset(0f, 0f), Offset(corner, 0f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(0f, 0f), Offset(0f, corner), stroke, StrokeCap.Round)
                    // Top-right
                    drawLine(c, Offset(w, 0f), Offset(w - corner, 0f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w, 0f), Offset(w, corner), stroke, StrokeCap.Round)
                    // Bottom-left
                    drawLine(c, Offset(0f, h), Offset(corner, h), stroke, StrokeCap.Round)
                    drawLine(c, Offset(0f, h), Offset(0f, h - corner), stroke, StrokeCap.Round)
                    // Bottom-right
                    drawLine(c, Offset(w, h), Offset(w - corner, h), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w, h), Offset(w, h - corner), stroke, StrokeCap.Round)

                    // Animated scanner beam inside frame
                    val laserY = h * laserFraction
                    val inset = 14.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                c.copy(alpha = 0.9f),
                                Color.Transparent
                            )
                        ),
                        start = Offset(inset, laserY),
                        end = Offset(w - inset, laserY),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = CastColors.BgSurfaceElevated.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.border(1.dp, CastColors.BorderSubtle, RoundedCornerShape(999.dp))
                    ) {
                        Text(
                            text = "ESCANEAR FIRE STICK",
                            fontFamily = BarlowCondensedFamily,
                            color = CastColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(CastColors.BgSurfaceElevated.copy(alpha = 0.88f), RoundedCornerShape(999.dp))
                            .border(1.dp, CastColors.BorderSubtle, RoundedCornerShape(999.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fechar câmera",
                            tint = CastColors.TextPrimary
                        )
                    }
                }

                Surface(
                    color = CastColors.BgSurface.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CastColors.BorderSubtle, RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = feedbackMessage,
                            fontFamily = BarlowCondensedFamily,
                            color = CastColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "QR Code exibido na tela inicial do app CastViewer no Fire Stick",
                            fontFamily = BarlowCondensedFamily,
                            color = CastColors.TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private fun decodeQrFromYuv(
    image: ImageProxy,
    reader: QRCodeReader,
    hints: Map<DecodeHintType, Any>
): String? {
    val format = image.format
    if (format != ImageFormat.YUV_420_888 && format != ImageFormat.YUV_422_888 && format != ImageFormat.YUV_444_888) {
        return null
    }
    val yPlane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val buffer = yPlane.buffer
    buffer.rewind()

    val packedY = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(packedY, 0, packedY.size)
    } else {
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining <= 0) break
            val bytesToRead = minOf(rowStride, remaining)
            buffer.get(rowBuffer, 0, bytesToRead)
            val dstOffset = row * width
            if (pixelStride == 1) {
                System.arraycopy(rowBuffer, 0, packedY, dstOffset, width)
            } else {
                for (col in 0 until width) {
                    packedY[dstOffset + col] = rowBuffer[col * pixelStride]
                }
            }
        }
    }

    return try {
        val source = PlanarYUVLuminanceSource(
            packedY,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decode(bitmap, hints).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
