package me.nathanfallet.teamcode.companion.di

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeychainCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.system.IosPlatformInfo
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

/** iOS: the Keychain, the standard user defaults (metadata only) and the Darwin HTTP engine. */
fun platformModule() = module {
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    single<CredentialStore> { KeychainCredentialStore() }
    single<HttpClientEngineFactory<*>> { Darwin }
    single<PlatformInfo> { IosPlatformInfo }
}
