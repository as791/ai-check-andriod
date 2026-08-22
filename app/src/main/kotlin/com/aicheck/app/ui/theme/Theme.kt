package com.aicheck.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = IndigoContainerLight,
    background = NeutralBackgroundLight,
    surface = NeutralSurfaceLight,
    error = HighRed,
    errorContainer = HighRedContainer,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1B1F5C),
    primaryContainer = IndigoContainerDark,
    background = NeutralBackgroundDark,
    surface = NeutralSurfaceDark,
    error = HighRed,
    errorContainer = HighRedContainer,
)

/**
 * Classification colors are intentionally separate from the Material color scheme —
 * they carry meaning (HIGH/UNCERTAIN/LOW) that must stay legible and consistent
 * regardless of dynamic color, in both light and dark mode.
 */
object ClassificationColors {
    val high @Composable get() = if (isSystemInDarkTheme()) HighRedContainer else HighRed
    val highContainer @Composable get() = HighRedContainer
    val uncertain @Composable get() = if (isSystemInDarkTheme()) UncertainAmberContainer else UncertainAmber
    val uncertainContainer @Composable get() = UncertainAmberContainer
    val low @Composable get() = if (isSystemInDarkTheme()) LowGreenContainer else LowGreen
    val lowContainer @Composable get() = LowGreenContainer
}

@Composable
fun AiCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AiCheckTypography,
        content = content,
    )
}
