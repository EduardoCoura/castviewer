package com.castviewer.sender.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castviewer.sender.R

/**
 * Android Material You / Material 3 tonal palette harmonized with CastViewer
 * Fire-Orange branding and Figma Material You Dark Surface tokens (#2D2F33 bottom nav,
 * tonal surface containers, and subtle outlineVariant separators).
 */
object CastColors {
    // Material 3 Tonal Surface Ladder (Dark)
    val BgCanvas = Color(0xFF0E0F13)              // surface
    val SurfaceContainerLowest = Color(0xFF090A0D)
    val BgInset = Color(0xFF15161C)               // surfaceContainerLow
    val BgSurface = Color(0xFF1B1D24)             // surfaceContainer
    val BgSurfaceElevated = Color(0xFF23252E)     // surfaceContainerHigh
    val SurfaceContainerHighest = Color(0xFF2D2F33)

    // Material You Bottom Navigation Bar tokens (from Figma n7oWAq5HgF7IqF5TxmwfDX 74:1986)
    val NavBarSurface = Color(0xFF2D2F33)         // material-you/sys/dark/surface-container
    val NavBarOutline = Color(0xFF8E9099)         // material-you/sys/dark/outline
    val NavBarOnSurfaceVariant = Color(0xFFC4C7C5)// material-you/sys/dark/on-surface-variant
    val NavActivePillContainer = Color(0xFF4A2010)// burnt Fire-Orange secondary-container pill
    val NavActiveIcon = Color(0xFFFFD3C4)         // on-secondary-container

    // Hairlines & Borders
    val BorderSubtle = Color(0xFF2E313B)          // outlineVariant
    val BorderStrong = Color(0xFF474B59)          // outline
    val BorderActive = Color(0x99FF5A1F)

    // Brand Fire-Orange Tonal Pairings
    val FireOrange = Color(0xFFFF5A1F)            // primary
    val FireOrangeBright = Color(0xFFFF8A5C)      // glowing highlight on tonal fills
    val FireOrangeSoft = Color(0x28FF5A1F)
    val PrimaryContainer = Color(0xFF3E1A0C)      // active Quick Settings tile container
    val OnPrimaryContainer = Color(0xFFFFDAD0)

    // Neutral Text Hierarchy
    val TextPrimary = Color(0xFFF4F4F7)           // onSurface
    val TextSecondary = Color(0xFFB8BCC6)         // onSurfaceVariant
    val TextMuted = Color(0xFF858996)

    // Semantic Status Tones
    val Emerald = Color(0xFF34D399)
    val EmeraldSoft = Color(0xFF103326)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFF36240B)
    val Danger = Color(0xFFF87171)
    val DangerSoft = Color(0xFF471515)
}

val BarlowDisplayFamily = FontFamily(
    Font(R.font.barlow_bold_italic, FontWeight.Bold)
)

val BarlowCondensedFamily = FontFamily(
    Font(R.font.barlow_condensed, FontWeight.Normal),
    Font(R.font.barlow_condensed, FontWeight.Medium),
    Font(R.font.barlow_condensed, FontWeight.SemiBold),
    Font(R.font.barlow_condensed, FontWeight.Bold)
)

private val CastDarkScheme = darkColorScheme(
    primary = CastColors.FireOrange,
    onPrimary = Color.White,
    primaryContainer = CastColors.PrimaryContainer,
    onPrimaryContainer = CastColors.OnPrimaryContainer,
    secondary = CastColors.FireOrangeBright,
    onSecondary = Color(0xFF1E0900),
    secondaryContainer = CastColors.NavActivePillContainer,
    onSecondaryContainer = CastColors.NavActiveIcon,
    background = CastColors.BgCanvas,
    onBackground = CastColors.TextPrimary,
    surface = CastColors.BgCanvas,
    onSurface = CastColors.TextPrimary,
    surfaceVariant = CastColors.BgSurfaceElevated,
    onSurfaceVariant = CastColors.TextSecondary,
    surfaceContainerLowest = CastColors.SurfaceContainerLowest,
    surfaceContainerLow = CastColors.BgInset,
    surfaceContainer = CastColors.BgSurface,
    surfaceContainerHigh = CastColors.BgSurfaceElevated,
    surfaceContainerHighest = CastColors.SurfaceContainerHighest,
    outline = CastColors.BorderStrong,
    outlineVariant = CastColors.BorderSubtle,
    error = CastColors.Danger,
    onError = Color(0xFF3D0002),
    errorContainer = CastColors.DangerSoft,
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * Material 3 type scale adapted from Figma Android UI Kit (350:9184) while preserving
 * CastViewer's BarlowDisplayFamily (display/brand) and BarlowCondensedFamily (UI/titles/labels).
 */
private val CastTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = BarlowDisplayFamily,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        color = CastColors.TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp,
        color = CastColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp,
        color = CastColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.15.sp,
        color = CastColors.TextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
        color = CastColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.35.sp,
        color = CastColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.25.sp,
        color = CastColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.3.sp,
        color = CastColors.TextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        color = CastColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = CastColors.NavBarOnSurfaceVariant
    ),
    labelSmall = TextStyle(
        fontFamily = BarlowCondensedFamily,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = CastColors.TextMuted
    )
)

private val CastShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CastViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CastDarkScheme,
        typography = CastTypography,
        shapes = CastShapes,
        content = content
    )
}
