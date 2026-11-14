package dev.buyolitsez.b2player

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

val Gray1 = Color(0xFF141414)
val Gray2 = Color(0xFF1E1E1E)
val Muted = Color(0xFFA3A3A3)
val Faint = Color(0xFF777777)
val SwipeRow = Color(0xFF1E1E1E)
/** Background of the "Liked" card and screen. */
val LikedTint = Color(0xFF5B3A44)

/** Accent and background tint taken from the current album cover. */
data class CoverColors(val accent: Color, val background: Color)

val DefaultColors = CoverColors(Color(0xFF8FD3A8), Color(0xFF22332A))

/** A light accent and a dark tint; the limits keep any cover from giving a neon or muddy color. */
fun coverColors(bmp: Bitmap): CoverColors {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val base = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: return DefaultColors
    val hsl = FloatArray(3); ColorUtils.colorToHSL(base, hsl)
    val accent = floatArrayOf(hsl[0], hsl[1].coerceIn(0.35f, 0.7f), hsl[2].coerceIn(0.66f, 0.76f))
    val dom = p.dominantSwatch?.rgb ?: base
    val dh = FloatArray(3); ColorUtils.colorToHSL(dom, dh)
    val bg = floatArrayOf(dh[0], dh[1].coerceAtMost(0.35f), 0.2f)
    return CoverColors(Color(ColorUtils.HSLToColor(accent)), Color(ColorUtils.HSLToColor(bg)))
}

private val fadeStops = listOf(0f to 1f, 0.12f to 1f, 0.22f to 0.93f, 0.33f to 0.8f, 0.44f to 0.64f, 0.55f to 0.47f, 0.66f to 0.31f, 0.77f to 0.18f, 0.88f to 0.08f, 0.95f to 0.02f, 1f to 0f)

/** [tint] at the top fading out to transparent by [height] px, on an eased curve so there is no visible edge. */
fun fade(tint: Color, height: Float, strength: Float = 1f) =
    Brush.verticalGradient(*fadeStops.map { (at, a) -> at to tint.copy(alpha = a * strength) }.toTypedArray(), startY = 0f, endY = height)

@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private val Type = Typography().let { t ->
    fun TextStyle.s(size: Int, line: Int, weight: Int, spacing: Float = 0f) =
        copy(fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = spacing.sp)
    t.copy(
        // Screen titles, the album title, the track on Now playing.
        headlineLarge = t.headlineLarge.s(32, 38, 800, -0.5f),
        headlineMedium = t.headlineMedium.s(26, 32, 800, -0.4f),
        headlineSmall = t.headlineSmall.s(24, 30, 800, -0.3f),
        titleLarge = t.titleLarge.s(20, 26, 700),
        titleMedium = t.titleMedium.s(16, 21, 600),
        titleSmall = t.titleSmall.s(15, 20, 600),
        bodyLarge = t.bodyLarge.s(15, 20, 500),
        bodyMedium = t.bodyMedium.s(14, 19, 400),
        bodySmall = t.bodySmall.s(13, 18, 400),
        labelLarge = t.labelLarge.s(14, 18, 600),
        // Section labels, written in capitals.
        labelMedium = t.labelMedium.s(13, 18, 600, 0.6f),
        labelSmall = t.labelSmall.s(11, 14, 500),
    )
}

@Composable
fun PlayerTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, onSecondary = Color.Black,
        background = Color.Black, onBackground = Color(0xFFF2F2F2),
        surface = Color.Black, onSurface = Color(0xFFF2F2F2), onSurfaceVariant = Muted,
        surfaceVariant = Gray2, surfaceContainer = Gray1, surfaceContainerHigh = Gray2,
        surfaceContainerHighest = Gray2, surfaceContainerLow = Color(0xFF1A1A1A), surfaceContainerLowest = Color.Black,
        outline = Color(0xFF333333), outlineVariant = Color(0xFF222222),
    )
    MaterialTheme(colorScheme = scheme, typography = Type, content = content)
}
