package me.nathanfallet.teamcode.companion.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The dashboard's palette (DashboardLayout.kt): one green accent on a deep blue-grey base, amber and red for states.
private val Accent = Color(0xFF3DDC97)
private val AccentDim = Color(0xFF1C6B4C)
private val OnAccent = Color(0xFF06251A)
private val Warn = Color(0xFFF6C453)
private val Danger = Color(0xFFFF6B6B)

val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFD6FBEA),
    secondary = Color(0xFF8B98A5),
    onSecondary = Color(0xFF0B0F14),
    secondaryContainer = Color(0xFF16222C),
    onSecondaryContainer = Color(0xFFE6EDF3),
    tertiary = Warn,
    onTertiary = Color(0xFF2B1F00),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121A22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF16222C),
    onSurfaceVariant = Color(0xFF8B98A5),
    surfaceContainer = Color(0xFF0F161D),
    surfaceContainerHigh = Color(0xFF16222C),
    outline = Color(0xFF1F2A35),
    outlineVariant = Color(0xFF1F2A35),
    error = Danger,
    onError = Color(0xFF2D0000),
)

// Light: the same accent, darkened for contrast on white.
val LightColors = lightColorScheme(
    primary = Color(0xFF13885A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF5E2),
    onPrimaryContainer = OnAccent,
    secondary = Color(0xFF52606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9EE),
    onSecondaryContainer = Color(0xFF0B0F14),
    tertiary = Color(0xFF8A6100),
    onTertiary = Color.White,
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF0B0F14),
    surface = Color.White,
    onSurface = Color(0xFF0B0F14),
    surfaceVariant = Color(0xFFE3E9EE),
    onSurfaceVariant = Color(0xFF52606D),
    outline = Color(0xFFC9D2DA),
    outlineVariant = Color(0xFFE3E9EE),
    error = Color(0xFFC62828),
    onError = Color.White,
)
