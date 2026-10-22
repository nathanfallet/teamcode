package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference

interface PreferencesRepository {
    val theme: StateFlow<ThemePreference>
    fun setTheme(theme: ThemePreference)
}
