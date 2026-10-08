package com.solarmonitor.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Fixed meaning colours: the same in every theme so "yellow = solar" always holds. */
@Immutable
data class EnergyColors(
    val solar: Color, val load: Color, val batt: Color, val grid: Color, val inv: Color,
    val good: Color, val warn: Color, val crit: Color,
)

// Same values as the web dashboard's design tokens (web/src/index.css :root and .dark).
private val LightEnergy = EnergyColors(
    solar = Color(0xFFE09400), load = Color(0xFF2A78D6), batt = Color(0xFF13A571), grid = Color(0xFFD9558B), inv = Color(0xFF5B47D6),
    good = Color(0xFF0CA30C), warn = Color(0xFFE39A00), crit = Color(0xFFD03B3B),
)
private val DarkEnergy = EnergyColors(
    solar = Color(0xFFF5B300), load = Color(0xFF4F9CFF), batt = Color(0xFF2FD38F), grid = Color(0xFFF0679F), inv = Color(0xFF9D8CFF),
    good = Color(0xFF34D058), warn = Color(0xFFFAB219), crit = Color(0xFFFF5D5D),
)

val LocalEnergy = staticCompositionLocalOf { LightEnergy }

// Web tokens: bg, surface-solid (cards), surface-2 / surface-3 (tiles, pressed), text / text-2 / text-3, border.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF0B1020), onPrimary = Color.White, primaryContainer = Color(0xFFE7E9EF), onPrimaryContainer = Color(0xFF0B1020),
    secondary = Color(0xFF4A5165), secondaryContainer = Color(0xFFE7E9EF), onSecondaryContainer = Color(0xFF0B1020),
    background = Color(0xFFF4F5F8), surface = Color(0xFFF4F5F8), onSurface = Color(0xFF0B1020), onSurfaceVariant = Color(0xFF4A5165),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF1F2F6),
    surfaceContainerHigh = Color(0xFFF1F2F6), surfaceContainerHighest = Color(0xFFE7E9EF),
    outline = Color(0xFF646B80), outlineVariant = Color(0x240F172A),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFFF3F5FB), onPrimary = Color(0xFF07090F), primaryContainer = Color(0xFF232733), onPrimaryContainer = Color(0xFFF3F5FB),
    secondary = Color(0xFFAAB1C5), secondaryContainer = Color(0xFF232733), onSecondaryContainer = Color(0xFFF3F5FB),
    background = Color(0xFF07090F), surface = Color(0xFF07090F), onSurface = Color(0xFFF3F5FB), onSurfaceVariant = Color(0xFFAAB1C5),
    surfaceContainerLowest = Color(0xFF0B0E16), surfaceContainerLow = Color(0xFF10141F), surfaceContainer = Color(0xFF181C26),
    surfaceContainerHigh = Color(0xFF181C26), surfaceContainerHighest = Color(0xFF232733),
    outline = Color(0xFF8A92A8), outlineVariant = Color(0x21FFFFFF),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: FontWeight) = androidx.compose.ui.text.font.Font(res, w,
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w.weight)))
private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
/** The web app's fonts (Geist, Geist Mono), bundled so both apps look the same on every phone. */
val Geist = androidx.compose.ui.text.font.FontFamily(weights.map { variable(com.solarmonitor.app.R.font.geist, it) })
val GeistMono = androidx.compose.ui.text.font.FontFamily(weights.map { variable(com.solarmonitor.app.R.font.geist_mono, it) })

private val AppTypography = Typography().let { b ->
    fun TextStyle.g() = copy(fontFamily = Geist)
    val t = Typography(b.displayLarge.g(), b.displayMedium.g(), b.displaySmall.g(), b.headlineLarge.g(), b.headlineMedium.g(), b.headlineSmall.g(),
        b.titleLarge.g(), b.titleMedium.g(), b.titleSmall.g(), b.bodyLarge.g(), b.bodyMedium.g(), b.bodySmall.g(), b.labelLarge.g(), b.labelMedium.g(), b.labelSmall.g())
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Numbers: Geist Mono with tabular digits, like the web's .num class. */
val NumberStyle = TextStyle(fontFamily = GeistMono, fontFeatureSettings = "tnum", letterSpacing = (-0.02).em)

@Composable
fun SolarTheme(theme: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    CompositionLocalProvider(LocalEnergy provides if (dark) DarkEnergy else LightEnergy) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
