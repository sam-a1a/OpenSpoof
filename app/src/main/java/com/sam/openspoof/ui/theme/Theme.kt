package com.sam.openspoof.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Used only when the device has no wallpaper palette to borrow. A green
// "you are somewhere else" accent, to read differently from a real GPS fix.
private val SeedLight = lightColorScheme(
    primary = Color(0xFF206C4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F5C7),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4C6357),
    tertiary = Color(0xFF3D6473),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF8CD8AC),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFA8F5C7),
    secondary = Color(0xFFB3CCBE),
    tertiary = Color(0xFFA4CDDE),
)

/**
 * Material 3 Expressive theme.
 *
 * [MaterialExpressiveTheme] differs from [androidx.compose.material3.MaterialTheme] in that it
 * defaults components to their expressive shapes and, more importantly here, installs a
 * [MotionScheme]. The expressive scheme is spring-physics based rather than duration based, so
 * component transitions can overshoot and settle instead of easing to a stop.
 */
@Composable
fun OpenSpoofTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // minSdk is 33, so wallpaper-derived dynamic color is always available.
    val context = LocalContext.current
    val colorScheme = when {
        darkTheme -> runCatching { dynamicDarkColorScheme(context) }.getOrDefault(SeedDark)
        else -> runCatching { dynamicLightColorScheme(context) }.getOrDefault(SeedLight)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
