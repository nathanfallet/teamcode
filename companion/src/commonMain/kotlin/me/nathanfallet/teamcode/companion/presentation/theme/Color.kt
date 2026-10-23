package me.nathanfallet.teamcode.companion.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's color tokens. Dark is the dashboard's palette (DashboardLayout.kt): one green accent on a deep
 * blue-grey base, amber and red for states. Light keeps the same roles with a darker accent (5.1:1 on white).
 * [warnBackground] and [dangerBackground] tint the banners and chips of those states.
 */
@Immutable
data class TeamCodeColors(
    val background: Color,
    val panel: Color,
    val panelAlt: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentText: Color,
    val accentDim: Color,
    val onAccent: Color,
    val chip: Color,
    val chipText: Color,
    val warn: Color,
    val warnBackground: Color,
    val danger: Color,
    val dangerBackground: Color,
    val bubble: Color,
    val onBubble: Color,
    val shade: Color,
    val code: Color,
    val skeleton: Color,
    val isDark: Boolean,
)

val DarkPalette = TeamCodeColors(
    background = Color(0xFF0B0F14),
    panel = Color(0xFF121A22),
    panelAlt = Color(0xFF0F161D),
    line = Color(0xFF1F2A35),
    text = Color(0xFFE6EDF3),
    muted = Color(0xFF8B98A5),
    accent = Color(0xFF3DDC97),
    accentText = Color(0xFF3DDC97),
    accentDim = Color(0xFF1C6B4C),
    onAccent = Color(0xFF06251A),
    chip = Color(0xFF16222C),
    chipText = Color(0xFFBFE9D5),
    warn = Color(0xFFF6C453),
    warnBackground = Color(0xFF2A2412),
    danger = Color(0xFFFF6B6B),
    dangerBackground = Color(0xFF2B1618),
    bubble = Color(0xFF1C6B4C),
    onBubble = Color(0xFFE8FFF4),
    shade = Color(0x99000000),
    code = Color(0xFF0B1117),
    skeleton = Color(0xFF1A2530),
    isDark = true,
)

val LightPalette = TeamCodeColors(
    background = Color(0xFFF4F6F8),
    panel = Color.White,
    panelAlt = Color(0xFFEEF2F5),
    line = Color(0xFFD5DDE4),
    text = Color(0xFF0B0F14),
    muted = Color(0xFF52606D),
    accent = Color(0xFF0F7D52),
    accentText = Color(0xFF0F7D52),
    accentDim = Color(0xFF9FDCBF),
    onAccent = Color.White,
    chip = Color(0xFFE9EEF2),
    chipText = Color(0xFF0D5C3D),
    warn = Color(0xFF8A6100),
    warnBackground = Color(0xFFFFF4D6),
    danger = Color(0xFFC62828),
    dangerBackground = Color(0xFFFDE8E8),
    bubble = Color(0xFFD6F5E6),
    onBubble = Color(0xFF06251A),
    shade = Color(0x590B0F14),
    code = Color(0xFFF4F6F8),
    skeleton = Color(0xFFE3E9EE),
    isDark = false,
)

val LocalTeamCodeColors = staticCompositionLocalOf { DarkPalette }

/** The Material scheme built from the tokens, for the Material components the app still uses (menus, sheets, dialogs). */
fun TeamCodeColors.materialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = bubble,
        onPrimaryContainer = onBubble,
        secondary = muted,
        onSecondary = background,
        secondaryContainer = chip,
        onSecondaryContainer = text,
        tertiary = warn,
        onTertiary = background,
        background = background,
        onBackground = text,
        surface = panel,
        onSurface = text,
        surfaceVariant = panelAlt,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = panelAlt,
        surfaceContainer = panel,
        surfaceContainerHigh = panel,
        surfaceContainerHighest = chip,
        surfaceTint = Color.Transparent,
        outline = line,
        outlineVariant = line,
        error = danger,
        onError = background,
        errorContainer = dangerBackground,
        onErrorContainer = danger,
        scrim = shade,
    )
}
