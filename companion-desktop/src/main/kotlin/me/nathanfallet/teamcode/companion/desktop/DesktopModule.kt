package me.nathanfallet.teamcode.companion.desktop

import me.nathanfallet.teamcode.companion.di.platformModule
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.CliAccountLink
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.credentials.KeyringCredentialStore
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The desktop's platform module: the JVM one of `:companion` (keyring, preferences, the JDK HTTP engine), then what
 * replaces or adds to it on a member's computer. Loaded after the shared modules, its definitions override theirs:
 * tokens through [CliCredentialStore], the CLI's installation id, the CLI's account and sessions on this device.
 */
fun desktopModule(environment: DesktopEnvironment): Module = module {
    includes(platformModule(), desktopOverrides(environment))
}

internal fun desktopOverrides(environment: DesktopEnvironment): Module = module {
    single { environment }
    single { CliConfigFile(environment.configPath) }
    single<CredentialStore> {
        CliCredentialStore(KeyringCredentialStore.open(), get()) { id -> get<AccountRepository>().accounts.value.firstOrNull { it.id == id }?.origin }
    }
    single<InstallationIdentity> { SharedInstallationIdentity(environment.configPath, get()) }
    single {
        CliAccountSync(get(), get(), get(), get(), get(), CliAccountSync.describeWith(get()), get<PlatformInfo>().defaultDeviceName)
    } binds arrayOf(CliAccountLink::class)
    single { CliProcesses(get()) }
    single { CliLocator(get(), get()) }
    single {
        RemoteHostSupervisor(get(), get(), get(), get(), get(), get(), get(), get<PlatformInfo>().appVersion, RemoteHostSupervisor.recordsDirectory(environment.configPath))
    }
    single { CliInstaller(get()) }
    single<LocalHost> { DesktopLocalHost(get(), get(), get(), get(), get<PlatformInfo>().appVersion, get(), get()) }
}
