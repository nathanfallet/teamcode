package me.nathanfallet.teamcode.companion.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference

/** Corner radii: cards and sheets 14 dp, buttons and fields 10 dp. */
object Radii {
    val card = RoundedCornerShape(14.dp)
    val control = RoundedCornerShape(10.dp)
    val segment = RoundedCornerShape(12.dp)
    val segmentItem = RoundedCornerShape(9.dp)
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

private val TeamCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = Radii.control,
    medium = Radii.card,
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** The tokens of the current theme: `TeamCode.colors.panel`. */
object TeamCode {
    val colors: TeamCodeColors
        @Composable @ReadOnlyComposable get() = LocalTeamCodeColors.current
}

@Composable
fun TeamCodeTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colors = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalTeamCodeColors provides colors) {
        MaterialTheme(
            colorScheme = colors.materialScheme(),
            typography = TeamCodeTypography,
            shapes = TeamCodeShapes,
            content = content,
        )
    }
}
