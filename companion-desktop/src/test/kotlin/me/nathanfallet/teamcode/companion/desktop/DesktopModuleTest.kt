package me.nathanfallet.teamcode.companion.desktop

import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.services.CliAccountLink
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import org.koin.dsl.koinApplication
import kotlin.test.*

/** The desktop's definitions replace the shared ones: tokens, installation id, the CLI's account, local sessions. */
class DesktopModuleTest {
    @Test fun theDesktopOverridesTheSharedDefinitions() = withTemporaryDirectory { directory ->
        val environment = DesktopEnvironment(mapOf("HOME" to directory.path), configOverride = "${directory.path}/teamcode/config.json")
        val koin = koinApplication { modules(appModules(desktopModule(environment)) + inMemorySettings) }.koin
        try {
            assertIs<CliCredentialStore>(koin.get<CredentialStore>())
            assertIs<SharedInstallationIdentity>(koin.get<InstallationIdentity>())
            assertIs<CliAccountSync>(koin.get<CliAccountLink>())
            assertIs<DesktopLocalHost>(koin.get<LocalHost>())
            // Only the desktop provides the updater the update banner needs.
            assertIs<DesktopAppUpdater>(koin.get<AppUpdater>())
            // The app and the CLI of this computer share one installation id, beside config.json.
            val id = koin.get<InstallationIdentity>().id()
            assertEquals(id, java.io.File(directory, "teamcode/installation-id").readText())
        } finally { koin.close() }
    }
}
