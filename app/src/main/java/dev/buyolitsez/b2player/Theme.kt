package dev.buyolitsez.b2player

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Whether the dark theme is on: the setting in Settings, or the system one. */
val LocalDark = compositionLocalOf { true }

// These read the current scheme.
val Gray1: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val Gray2: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
/** Background of the playing track's row and of a swiped row. */
val ActiveRow: Color @Composable get() = if (LocalDark.current) Color(0xFF0D1A12) else Color(0xFFE6F4EC)
val SwipeRow: Color @Composable get() = if (LocalDark.current) Color(0xFF0F2A1C) else Color(0xFFD2EDDC)

/** Accent and background tint taken from the current album cover. */
data class CoverColors(val accent: Color, val background: Color)

fun defaultColors(dark: Boolean) =
    if (dark) CoverColors(Color(0xFF3DDC84), Color(0xFF0B1A12)) else CoverColors(Color(0xFF139452), Color(0xFFE6F4EC))

/** A light accent on a dark tint for the dark theme, a dark accent on a light tint for the light one. */
fun coverColors(bmp: Bitmap, dark: Boolean): CoverColors {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val base = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: return defaultColors(dark)
    val hsl = FloatArray(3); ColorUtils.colorToHSL(base, hsl)
    val accent = floatArrayOf(hsl[0], hsl[1].coerceAtLeast(0.35f), if (dark) hsl[2].coerceIn(0.62f, 0.75f) else hsl[2].coerceIn(0.30f, 0.40f))
    val dom = p.dominantSwatch?.rgb ?: base
    val dh = FloatArray(3); ColorUtils.colorToHSL(dom, dh)
    val bg = floatArrayOf(dh[0], dh[1].coerceAtMost(0.45f), if (dark) 0.13f else 0.90f)
    return CoverColors(Color(ColorUtils.HSLToColor(accent)), Color(ColorUtils.HSLToColor(bg)))
}

private val Dense = Typography().let { t ->
    fun TextStyle.s(size: Int, line: Int = size + 4) = copy(fontSize = size.sp, lineHeight = line.sp)
    t.copy(
        titleLarge = t.titleLarge.s(18).copy(fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.s(15).copy(fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.s(13),
        bodyLarge = t.bodyLarge.s(14),
        bodyMedium = t.bodyMedium.s(13),
        bodySmall = t.bodySmall.s(12),
        labelLarge = t.labelLarge.s(13),
        labelMedium = t.labelMedium.s(12),
        labelSmall = t.labelSmall.s(11),
    )
}

@Composable
fun PlayerTheme(accent: Color, dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, onSecondary = Color.Black,
        background = Color.Black, onBackground = Color(0xFFEDEDED),
        surface = Color.Black, onSurface = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF8A8A8A),
        surfaceVariant = Color(0xFF1C1C1C), surfaceContainer = Color(0xFF111111), surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF1C1C1C), surfaceContainerLow = Color(0xFF111111), surfaceContainerLowest = Color.Black,
        outline = Color(0xFF333333), outlineVariant = Color(0xFF222222),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, secondary = accent, onSecondary = Color.White,
        background = Color.White, onBackground = Color(0xFF161616),
        surface = Color.White, onSurface = Color(0xFF161616), onSurfaceVariant = Color(0xFF6B6B6B),
        surfaceVariant = Color(0xFFE9E9E9), surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFE9E9E9),
        surfaceContainerHighest = Color(0xFFE9E9E9), surfaceContainerLow = Color(0xFFF3F3F3), surfaceContainerLowest = Color.White,
        outline = Color(0xFFCCCCCC), outlineVariant = Color(0xFFE0E0E0),
    )
    CompositionLocalProvider(LocalDark provides dark) { MaterialTheme(colorScheme = scheme, typography = Dense, content = content) }
}
