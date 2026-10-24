package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference

interface PreferencesRepository {
    val theme: StateFlow<ThemePreference>
    fun setTheme(theme: ThemePreference)

    /** The permission mode chosen at the last launch of a session on a device; [PermissionMode.ASK] until one was chosen. */
    val permissionMode: PermissionMode
    fun setPermissionMode(mode: PermissionMode)
}
