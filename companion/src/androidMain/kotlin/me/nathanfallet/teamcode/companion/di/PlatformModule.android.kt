package me.nathanfallet.teamcode.companion.di

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeystoreCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.system.AndroidPlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import org.koin.dsl.module

/** Android: tokens encrypted by an Android Keystore key, preferences excluded from backups, the OkHttp engine. */
fun platformModule(context: Context) = module {
    single<Settings> { SharedPreferencesSettings(context.getSharedPreferences("teamcode", Context.MODE_PRIVATE)) }
    single<CredentialStore> { KeystoreCredentialStore(context.getSharedPreferences(KeystoreCredentialStore.FILE, Context.MODE_PRIVATE)) }
    single<HttpClientEngineFactory<*>> { OkHttp }
    single<PlatformInfo> { AndroidPlatformInfo }
    single<SecureRandom> { JavaSecureRandom }
}
