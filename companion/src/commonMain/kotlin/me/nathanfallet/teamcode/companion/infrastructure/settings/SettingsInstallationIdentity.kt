package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity

/** The installation id in the preferences: drawn once, never removed by a sign-out. */
class SettingsInstallationIdentity(private val settings: Settings, private val ids: IdGenerator) : InstallationIdentity {
    override fun id(): String = settings.getStringOrNull(KEY)?.takeIf { UUID.matches(it) } ?: ids.newId().also { settings.putString(KEY, it) }

    private companion object {
        const val KEY = "installation.id"
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
