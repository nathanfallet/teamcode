package me.nathanfallet.teamcode.companion.demo

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.GitHubLinkRepository
import me.nathanfallet.teamcode.companion.domain.repositories.McpServerRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.repositories.CachedFeaturesRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteArtifactRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAuthRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteDeviceRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteGitHubLinkRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteMcpServerRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionFileRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTranscriptRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTransferRepository
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsAccountRepository
import org.koin.dsl.module

/**
 * The built-in demo: binds each repository the app uses to a router that answers the demo account from memory
 * ([DemoMode]) and every other account from its server (the `Remote…` implementations the other modules provide).
 * Loaded after them, before the platform module.
 */
val demoModule = module {
    single { DemoMode(get()) }
    single { DemoCredentialStore(get()) }
    single { StartDemoUseCase(get(), get()) }
    single<AccountRepository> { DemoAccountRepository(get<SettingsAccountRepository>(), get()) }
    single<AuthRepository> { DemoAwareAuthRepository(get<RemoteAuthRepository>(), DemoAuthRepository(get())) }
    single<FeaturesRepository> { DemoAwareFeaturesRepository(get<CachedFeaturesRepository>(), DemoFeaturesRepository(get())) }
    single<SessionRepository> { DemoAwareSessionRepository(get<RemoteSessionRepository>(), DemoSessionRepository(get())) }
    single<TranscriptRepository> { DemoAwareTranscriptRepository(get<RemoteTranscriptRepository>(), DemoTranscriptRepository(get())) }
    single<ArtifactRepository> { DemoAwareArtifactRepository(get<RemoteArtifactRepository>(), DemoArtifactRepository(get())) }
    single<SessionFileRepository> { get<RemoteSessionFileRepository>().let { DemoAwareSessionFileRepository(it, DemoSessionFileRepository(it)) } }
    single<DeviceRepository> { DemoAwareDeviceRepository(get<RemoteDeviceRepository>(), DemoDeviceRepository) }
    single<TransferRepository> { DemoAwareTransferRepository(get<RemoteTransferRepository>(), DemoTransferRepository) }
    single<SubscriptionAccountRepository> {
        get<RemoteSubscriptionAccountRepository>().let { DemoAwareSubscriptionAccountRepository(it, DemoSubscriptionAccountRepository(it.types)) }
    }
    single<GitHubLinkRepository> { DemoAwareGitHubLinkRepository(get<RemoteGitHubLinkRepository>(), DemoGitHubLinkRepository) }
    single<McpServerRepository> { DemoAwareMcpServerRepository(get<RemoteMcpServerRepository>(), DemoMcpServerRepository) }
}

/** "Try the demo" on the sign-in screen: offered only on a device without any account, so a demo never mixes with a real one. */
class StartDemoUseCase(private val accounts: AccountRepository, private val platform: PlatformInfo) {
    /** False once an account exists, or where the accounts are not the demo-aware ones (a test's own repository). */
    val offered: Boolean get() = accounts is DemoAccountRepository && accounts.accounts.value.isEmpty()

    operator fun invoke(): Account {
        val demo = accounts as? DemoAccountRepository ?: error("The demo needs the demo-aware account repository.")
        return demo.startDemo(platform.defaultDeviceName)
    }
}
