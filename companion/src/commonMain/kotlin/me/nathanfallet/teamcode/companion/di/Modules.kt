package me.nathanfallet.teamcode.companion.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.usecases.auth.ChangePasswordUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RenameDeviceUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignInUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignOutUseCase
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAuthRepository
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsInstallationIdentity
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import me.nathanfallet.teamcode.companion.infrastructure.system.RandomIdGenerator
import me.nathanfallet.teamcode.companion.infrastructure.system.SystemClock
import org.koin.dsl.module

/** Implementations; the platform module adds Settings, the CredentialStore, the HTTP engine and PlatformInfo. */
val infrastructureModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<Clock> { SystemClock }
    single<IdGenerator> { RandomIdGenerator }
    single { TeamCodeClientProvider(get(), get(), get()) }
    single<AccountRepository> { SettingsAccountRepository(get()) }
    single<PreferencesRepository> { SettingsPreferencesRepository(get()) }
    single<InstallationIdentity> { SettingsInstallationIdentity(get(), get()) }
    single<AuthRepository> { RemoteAuthRepository(get(), get(), get()) }
}

val domainModule = module {
    single { InspectServerUseCase(get()) }
    single { SignInUseCase(get(), get(), get(), get(), get()) }
    single { ChangePasswordUseCase(get(), get()) }
    single { SignOutUseCase(get(), get(), get()) }
    single { RefreshAccountUseCase(get(), get(), get(), get()) }
    single { RenameDeviceUseCase(get(), get()) }
}
