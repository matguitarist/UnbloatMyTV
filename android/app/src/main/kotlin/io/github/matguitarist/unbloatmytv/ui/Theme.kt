package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The desktop apps' colours, so the three look like one product. */
@Immutable
data class AppColors(
    val bg: Color, val card: Color, val cardBorder: Color, val divider: Color,
    val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentFg: Color, val accentSoft: Color,
    val warnFg: Color, val warnBg: Color, val okFg: Color, val okBg: Color, val errFg: Color, val errBg: Color,
    val idle: Color,
)

private val Light = AppColors(
    bg = Color(0xFFF3F4F6), card = Color.White, cardBorder = Color(0xFFE2E4E8), divider = Color(0xFFECEDF0),
    text = Color(0xFF16181D), muted = Color(0xFF5D6470), faint = Color(0xFF8A909A),
    accent = Color(0xFF2459D6), accentFg = Color(0xFF2459D6), accentSoft = Color(0xFFE9F0FF),
    warnFg = Color(0xFF8A5300), warnBg = Color(0xFFFFF5E1), okFg = Color(0xFF127556), okBg = Color(0xFFE5F6EF),
    errFg = Color(0xFFB42E24), errBg = Color(0xFFFDECEA), idle = Color(0xFFECEEF1),
)

private val Dark = AppColors(
    bg = Color(0xFF15171B), card = Color(0xFF1F2228), cardBorder = Color(0xFF2E323A), divider = Color(0xFF2A2E35),
    text = Color(0xFFEDEFF3), muted = Color(0xFFA3AAB5), faint = Color(0xFF7A818C),
    accent = Color(0xFF4D7CF0), accentFg = Color(0xFF7FA6FF), accentSoft = Color(0xFF22304D),
    warnFg = Color(0xFFF2BE6B), warnBg = Color(0xFF3A2E18), okFg = Color(0xFF5FD3A7), okBg = Color(0xFF173328),
    errFg = Color(0xFFFF8A80), errBg = Color(0xFF3B1E1C), idle = Color(0xFF2A2E35),
)

val LocalAppColors = staticCompositionLocalOf { Light }

object AppTheme {
    val colors: AppColors @Composable get() = LocalAppColors.current
}

@Composable
fun UnbloatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = Color.White, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentFg,
        background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
        surfaceVariant = c.idle, onSurfaceVariant = c.muted, outline = c.cardBorder, outlineVariant = c.divider,
        surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.bg,
        error = c.errFg, errorContainer = c.errBg,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.White, primaryContainer = c.accentSoft, onPrimaryContainer = c.accentFg,
        background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
        surfaceVariant = c.idle, onSurfaceVariant = c.muted, outline = c.cardBorder, outlineVariant = c.divider,
        surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.bg,
        error = c.errFg, errorContainer = c.errBg,
    )
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    )
    val shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp))
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
