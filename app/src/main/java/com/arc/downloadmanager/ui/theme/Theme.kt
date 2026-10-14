package com.arc.downloadmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.data.settings.ThemeMode

private val ArcBlue = Color(0xFF1A6BFF)
private val ArcBlueDark = Color(0xFF9DBCFF)
private val ArcTeal = Color(0xFF00B8A9)
private val ArcViolet = Color(0xFF7C4DFF)

private val LightColors =
    lightColorScheme(
        primary = ArcBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF001A41),
        secondary = Color(0xFF565E71),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDAE2F9),
        onSecondaryContainer = Color(0xFF131C2B),
        tertiary = ArcTeal,
        tertiaryContainer = Color(0xFFB7F2EA),
        onTertiaryContainer = Color(0xFF00201C),
        background = Color(0xFFF8FAFF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8FAFF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE1E6F2),
        onSurfaceVariant = Color(0xFF44474E),
        surfaceContainer = Color(0xFFF2F4FB),
        surfaceContainerLow = Color(0xFFF8FAFF),
        outlineVariant = Color(0xFFC3C7D4),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

private val DarkColors =
    darkColorScheme(
        primary = ArcBlueDark,
        onPrimary = Color(0xFF00308C),
        primaryContainer = Color(0xFF0047B3),
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFFBEC6DC),
        onSecondary = Color(0xFF293041),
        secondaryContainer = Color(0xFF3F4759),
        onSecondaryContainer = Color(0xFFDAE2F9),
        tertiary = Color(0xFF4CDACC),
        tertiaryContainer = Color(0xFF005048),
        onTertiaryContainer = Color(0xFFB7F2EA),
        background = Color(0xFF0F1119),
        onBackground = Color(0xFFE3E6F0),
        surface = Color(0xFF0F1119),
        onSurface = Color(0xFFE3E6F0),
        surfaceVariant = Color(0xFF3F4452),
        onSurfaceVariant = Color(0xFFC4C6D0),
        surfaceContainer = Color(0xFF171A24),
        surfaceContainerLow = Color(0xFF13151E),
        outlineVariant = Color(0xFF3F4452),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

/** Softer, rounder geometry than the M3 defaults for a friendlier feel. */
private val ArcShapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )

/** Brand gradient stops used by hero surfaces (works on light and dark themes). */
val ArcBrandGradient =
    listOf(ArcBlue, ArcViolet)

/** Rich diagonal hero gradient: blue → indigo → violet, for hero and about headers. */
val ArcHeroBrush: Brush =
    Brush.linearGradient(
        colors = listOf(ArcBlue, Color(0xFF4A54FF), ArcViolet),
    )

@Composable
fun ArcTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArcTypography,
        shapes = ArcShapes,
        content = content,
    )
}
