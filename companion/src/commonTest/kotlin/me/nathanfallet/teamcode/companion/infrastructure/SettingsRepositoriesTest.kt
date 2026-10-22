package me.nathanfallet.teamcode.companion.infrastructure

import com.russhwolf.settings.MapSettings
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.domain.models.ThemePreference
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsInstallationIdentity
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import kotlin.test.*

class SettingsRepositoriesTest {
    @Test fun accountsSurviveARestartWithTheActiveOne() {
        val settings = MapSettings()
        val first = SettingsAccountRepository(settings)
        first.save(account("a1")); first.save(account("a2", memberId = "m2"))
        first.setActive("a1")
        first.update(account("a1", signedIn = false))
        val reopened = SettingsAccountRepository(settings)
        assertEquals(listOf("a1", "a2"), reopened.accounts.value.map { it.id })
        assertFalse(reopened.accounts.value.first().signedIn)
        assertEquals("a1", reopened.activeAccountId.value)
        reopened.remove("a1")
        assertEquals("a2", reopened.activeAccountId.value)
        reopened.remove("a2")
        assertNull(SettingsAccountRepository(settings).activeAccountId.value)
    }

    @Test fun aDamagedPreferenceStartsEmpty() {
        val settings = MapSettings("accounts.v1" to "{not json", "accounts.active" to "a1")
        val repository = SettingsAccountRepository(settings)
        assertTrue(repository.accounts.value.isEmpty())
        assertNull(repository.activeAccountId.value)
    }

    @Test fun theInstallationIdIsDrawnOnceAndAnInvalidOneReplaced() {
        val settings = MapSettings("installation.id" to "not-a-uuid")
        val identity = SettingsInstallationIdentity(settings, SequenceIds())
        val id = identity.id()
        assertEquals("00000000-0000-4000-8000-000000000000", id)
        assertEquals(id, SettingsInstallationIdentity(settings, SequenceIds()).id())
    }

    @Test fun theThemeIsKept() {
        val settings = MapSettings()
        SettingsPreferencesRepository(settings).setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, SettingsPreferencesRepository(settings).theme.value)
        assertEquals(ThemePreference.SYSTEM, SettingsPreferencesRepository(MapSettings("preferences.theme" to "PINK")).theme.value)
    }
}
