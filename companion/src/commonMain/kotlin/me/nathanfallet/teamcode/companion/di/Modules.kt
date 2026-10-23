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
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.CachedFeaturesRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTranscriptRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.GetArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.LoadArtifactsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteArtifactRepository
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
    single { AccountCalls(get(), get()) }
    single { ReconnectPolicy() }
    single<FeaturesRepository> { CachedFeaturesRepository(get(), get()) }
    single<SessionRepository> { RemoteSessionRepository(get(), get()) }
    single<TranscriptRepository> { RemoteTranscriptRepository(get(), get()) }
    single<ArtifactRepository> { RemoteArtifactRepository(get()) }
}

val domainModule = module {
    single { InspectServerUseCase(get()) }
    single { SignInUseCase(get(), get(), get(), get(), get()) }
    single { ChangePasswordUseCase(get(), get()) }
    single { SignOutUseCase(get(), get(), get()) }
    single { RefreshAccountUseCase(get(), get(), get(), get()) }
    single { RenameDeviceUseCase(get(), get(), get()) }
    single { AccountGuard(get()) }
    single { LoadSessionsUseCase(get(), get(), get()) }
    single { GetSessionUseCase(get(), get()) }
    single { FollowSessionListUseCase(get(), get(), get()) }
    single { LoadTranscriptUseCase(get(), get(), get()) }
    single { FollowTranscriptUseCase(get(), get()) }
    single { SendMessageUseCase(get(), get(), get(), get()) }
    single { ControlSessionUseCase(get(), get(), get()) }
    single { AnswerPermissionUseCase(get(), get(), get(), get()) }
    single { LoadProfilesUseCase(get(), get(), get()) }
    single { CreateSessionUseCase(get(), get(), get()) }
    single { LoadArtifactsUseCase(get(), get(), get()) }
    single { GetArtifactUseCase(get(), get(), get()) }
    single { OpenArtifactUseCase(get(), get(), get(), get()) }
    single { ResolveLinkUseCase(get()) }
}
