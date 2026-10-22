package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository

class SettingsPreferencesRepository(private val settings: Settings) : PreferencesRepository {
    private val themeState = MutableStateFlow(
        settings.getStringOrNull(THEME_KEY)?.let { name -> ThemePreference.entries.firstOrNull { it.name == name } } ?: ThemePreference.SYSTEM,
    )
    override val theme: StateFlow<ThemePreference> = themeState.asStateFlow()

    override fun setTheme(theme: ThemePreference) {
        themeState.value = theme
        settings.putString(THEME_KEY, theme.name)
    }

    private companion object {
        const val THEME_KEY = "preferences.theme"
    }
}
