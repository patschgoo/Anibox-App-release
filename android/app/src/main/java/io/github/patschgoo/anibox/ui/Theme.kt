package io.github.patschgoo.anibox.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.patschgoo.anibox.R

/** AniBox's look on the PC (app/anibox/web/app.css, from Frostbox): dark only. */
object AniColors {
    val bg = Color(0xFF07090C)
    val s1 = Color(0xFF0F1319)
    val s2 = Color(0xFF161B23)
    val line = Color(0xFF1E242E)
    val line2 = Color(0xFF252C37)
    val text = Color(0xFFE9EEF4)
    val muted = Color(0xFF93A0AF)
    val soft = Color(0xFFC9D3DE)
    val acc = Color(0xFF7FD3FF)
    val on = Color(0xFF04121B)
    val gold = Color(0xFFF0C674)
    val orange = Color(0xFFFF7A2F)
    val violet = Color(0xFFC3B4FF)
    val green = Color(0xFF5EE0A0)
    val red = Color(0xFFFF6B6B)
    val placeholder = Color(0xFF121821)
}

val Sora = FontFamily(
    Font(R.font.sora_medium, FontWeight.Medium),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val scheme: ColorScheme = darkColorScheme(
    primary = AniColors.acc,
    onPrimary = AniColors.on,
    primaryContainer = AniColors.s2,
    onPrimaryContainer = AniColors.text,
    secondary = AniColors.acc,
    onSecondary = AniColors.on,
    secondaryContainer = AniColors.s2,
    onSecondaryContainer = AniColors.text,
    tertiary = AniColors.orange,
    background = AniColors.bg,
    onBackground = AniColors.text,
    surface = AniColors.bg,
    onSurface = AniColors.text,
    surfaceVariant = AniColors.s1,
    onSurfaceVariant = AniColors.muted,
    surfaceContainerLowest = AniColors.bg,
    surfaceContainerLow = AniColors.s1,
    surfaceContainer = AniColors.s1,
    surfaceContainerHigh = AniColors.s2,
    surfaceContainerHighest = AniColors.s2,
    surfaceBright = AniColors.s2,
    surfaceDim = AniColors.bg,
    outline = AniColors.line2,
    outlineVariant = AniColors.line,
    error = AniColors.red,
    onError = AniColors.on,
    inverseSurface = AniColors.text,
    inverseOnSurface = AniColors.bg,
    scrim = Color.Black,
)

private fun head(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Sora, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.01).sp)

private fun body(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val typography = Typography(
    displayLarge = head(34, FontWeight.Bold, 40),
    displayMedium = head(30, FontWeight.Bold, 36),
    displaySmall = head(26, FontWeight.Bold, 32),
    headlineLarge = head(26, FontWeight.Bold, 32),
    headlineMedium = head(22, FontWeight.Bold, 28),
    headlineSmall = head(20, FontWeight.SemiBold, 26),
    titleLarge = head(20, FontWeight.SemiBold, 26),
    titleMedium = head(16, FontWeight.SemiBold, 22),
    titleSmall = head(14, FontWeight.SemiBold, 20),
    bodyLarge = body(16, FontWeight.Normal, 23),
    bodyMedium = body(14, FontWeight.Normal, 20),
    bodySmall = body(12, FontWeight.Normal, 17),
    labelLarge = body(14, FontWeight.SemiBold, 20),
    labelMedium = body(12, FontWeight.SemiBold, 16),
    labelSmall = body(11, FontWeight.Medium, 15),
)

/**
 * On a TV (seen from about three metres, 960 x 540 dp on the Shield): nothing under 14, body text 16,
 * like the TV mockups.
 */
private val tvTypography = Typography(
    displayLarge = head(40, FontWeight.Bold, 46),
    displayMedium = head(34, FontWeight.Bold, 40),
    displaySmall = head(30, FontWeight.Bold, 36),
    headlineLarge = head(32, FontWeight.Bold, 38),
    headlineMedium = head(28, FontWeight.Bold, 34),
    headlineSmall = head(28, FontWeight.Bold, 34),
    titleLarge = head(20, FontWeight.SemiBold, 26),
    titleMedium = head(18, FontWeight.SemiBold, 24),
    titleSmall = head(16, FontWeight.SemiBold, 22),
    bodyLarge = body(16, FontWeight.Normal, 24),
    bodyMedium = body(16, FontWeight.Normal, 23),
    bodySmall = body(14, FontWeight.Normal, 20),
    labelLarge = body(16, FontWeight.SemiBold, 22),
    labelMedium = body(14, FontWeight.SemiBold, 20),
    labelSmall = body(14, FontWeight.Medium, 20),
)

@Composable
fun AniBoxTheme(tv: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = if (tv) tvTypography else typography) {
        // Icons and text outside a Surface are light too (the screens draw on the dark background themselves).
        CompositionLocalProvider(LocalContentColor provides AniColors.text, LocalTv provides tv, content = content)
    }
}
