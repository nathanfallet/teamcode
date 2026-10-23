package me.nathanfallet.teamcode.companion.di

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java
import java.util.prefs.Preferences
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeyringCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.system.DesktopPlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import org.koin.dsl.module

/** Desktop: the OS keyring (Keychain, Credential Manager, Secret Service), Java preferences and the JDK HTTP client. */
fun platformModule() = module {
    single<Settings> { PreferencesSettings(Preferences.userRoot().node("me/nathanfallet/teamcode/companion")) }
    single<CredentialStore> { KeyringCredentialStore.open() }
    single<HttpClientEngineFactory<*>> { Java }
    single<PlatformInfo> { DesktopPlatformInfo }
    single<SecureRandom> { JavaSecureRandom }
}
