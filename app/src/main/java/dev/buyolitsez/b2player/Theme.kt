package dev.buyolitsez.b2player

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

val Green = Color(0xFF3DDC84)
val Gray1 = Color(0xFF111111)
val Gray2 = Color(0xFF1C1C1C)
val Muted = Color(0xFF8A8A8A)

/** Accent and background tint taken from the current album cover. */
data class CoverColors(val accent: Color, val background: Color)

val DefaultColors = CoverColors(Green, Color(0xFF0B1A12))

fun coverColors(bmp: Bitmap): CoverColors {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val base = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: return DefaultColors
    val hsl = FloatArray(3); ColorUtils.colorToHSL(base, hsl)
    val accent = floatArrayOf(hsl[0], hsl[1].coerceAtLeast(0.35f), hsl[2].coerceIn(0.62f, 0.75f))
    val dom = p.dominantSwatch?.rgb ?: base
    val dh = FloatArray(3); ColorUtils.colorToHSL(dom, dh)
    val bg = floatArrayOf(dh[0], dh[1].coerceAtMost(0.45f), 0.13f)
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
fun PlayerTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, onSecondary = Color.Black,
        background = Color.Black, onBackground = Color(0xFFEDEDED),
        surface = Color.Black, onSurface = Color(0xFFEDEDED), onSurfaceVariant = Muted,
        surfaceVariant = Gray2, surfaceContainer = Gray1, surfaceContainerHigh = Gray2,
        surfaceContainerHighest = Gray2, surfaceContainerLow = Gray1, surfaceContainerLowest = Color.Black,
        outline = Color(0xFF333333), outlineVariant = Color(0xFF222222),
    )
    MaterialTheme(colorScheme = scheme, typography = Dense, content = content)
}
