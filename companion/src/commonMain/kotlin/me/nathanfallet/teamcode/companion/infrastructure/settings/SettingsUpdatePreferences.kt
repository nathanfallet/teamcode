package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import me.nathanfallet.teamcode.companion.domain.repositories.UpdatePreferences

class SettingsUpdatePreferences(private val settings: Settings) : UpdatePreferences {
    override var dismissedVersion: String?
        get() = settings.getStringOrNull(DISMISSED_KEY)
        set(value) = if (value == null) settings.remove(DISMISSED_KEY) else settings.putString(DISMISSED_KEY, value)
    override var lastAttemptMillis: Long?
        get() = settings.getLongOrNull(ATTEMPT_KEY)
        set(value) = if (value == null) settings.remove(ATTEMPT_KEY) else settings.putLong(ATTEMPT_KEY, value)
    override var latestVersion: String?
        get() = settings.getStringOrNull(LATEST_KEY)
        set(value) = if (value == null) settings.remove(LATEST_KEY) else settings.putString(LATEST_KEY, value)

    private companion object {
        const val DISMISSED_KEY = "updates.dismissedVersion"
        const val ATTEMPT_KEY = "updates.lastAttempt"
        const val LATEST_KEY = "updates.latestVersion"
    }
}
