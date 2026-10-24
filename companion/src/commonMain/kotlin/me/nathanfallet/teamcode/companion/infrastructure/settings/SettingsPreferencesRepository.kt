package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
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

    override val permissionMode: PermissionMode
        get() = PermissionMode.of(settings.getStringOrNull(PERMISSION_MODE_KEY)) ?: PermissionMode.ASK

    override fun setPermissionMode(mode: PermissionMode) = settings.putString(PERMISSION_MODE_KEY, mode.wire)

    override val sidebarWidth: Int?
        get() = settings.getIntOrNull(SIDEBAR_WIDTH_KEY)?.takeIf { it >= 0 }

    override fun setSidebarWidth(index: Int) = settings.putInt(SIDEBAR_WIDTH_KEY, index)

    private companion object {
        const val SIDEBAR_WIDTH_KEY = "preferences.sidebarWidth"
        const val THEME_KEY = "preferences.theme"
        const val PERMISSION_MODE_KEY = "preferences.permissionMode"
    }
}
