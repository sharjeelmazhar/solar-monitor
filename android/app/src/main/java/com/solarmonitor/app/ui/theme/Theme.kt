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
import androidx.compose.ui.unit.sp

/** Fixed meaning colours: the same in every theme so "yellow = solar" always holds. */
@Immutable
data class EnergyColors(
    val solar: Color, val load: Color, val batt: Color, val grid: Color, val inv: Color,
    val good: Color, val warn: Color, val crit: Color,
)

private val LightEnergy = EnergyColors(
    solar = Color(0xFFEDA100), load = Color(0xFF2A78D6), batt = Color(0xFF1BAF7A), grid = Color(0xFFE87BA4), inv = Color(0xFF4A3AA7),
    good = Color(0xFF0CA30C), warn = Color(0xFFFAB219), crit = Color(0xFFD03B3B),
)
private val DarkEnergy = EnergyColors(
    solar = Color(0xFFE0A21A), load = Color(0xFF3987E5), batt = Color(0xFF22B07E), grid = Color(0xFFD55181), inv = Color(0xFF9085E9),
    good = Color(0xFF2EB82E), warn = Color(0xFFFAB219), crit = Color(0xFFE05555),
)

val LocalEnergy = staticCompositionLocalOf { LightEnergy }

private val LightFallback = lightColorScheme(
    primary = Color(0xFF2E5BA8), onPrimary = Color.White, primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF8A6100), secondaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFFAF9F6), surface = Color(0xFFFAF9F6),
    surfaceContainer = Color(0xFFF0EFEA), surfaceContainerHigh = Color(0xFFEAE9E4), surfaceContainerLow = Color(0xFFF5F4F0),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF002E6B), primaryContainer = Color(0xFF13438E), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFF5BD48), secondaryContainer = Color(0xFF684800),
    background = Color(0xFF111110), surface = Color(0xFF111110),
    surfaceContainer = Color(0xFF1C1C1A), surfaceContainerHigh = Color(0xFF252523), surfaceContainerLow = Color(0xFF171716),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

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
