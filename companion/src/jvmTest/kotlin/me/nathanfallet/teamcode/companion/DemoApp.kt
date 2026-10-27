package me.nathanfallet.teamcode.companion

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import me.nathanfallet.teamcode.companion.infrastructure.system.JavaSecureRandom
import org.koin.core.module.Module
import org.koin.dsl.module

/** The demo data of the screenshots and the whole-app tests: two accounts, then the built-in demo's sessions, transcripts and artifacts. */
internal const val DEMO_NOW = 1_800_000_000_000L

/** The app's modules on demo data; [sessions] and [artifacts] can be replaced to count what the app asks. */
internal fun demoModules(
    sessions: SessionRepository = demoSessions(),
    artifacts: ArtifactRepository = demoArtifacts(),
    transcript: TranscriptRepository = demoTranscript(),
): List<Module> = appModules(demoModule()) + demoOverrides(sessions, artifacts, transcript)

private fun demoModule() = module {
    single<Settings> { MapSettings() }
    single<CredentialStore> { credentials().also { store -> runBlocking { demoAccounts.forEach { store.write(it.id, "token") } } } }
    single<io.ktor.client.engine.HttpClientEngineFactory<*>> { io.ktor.client.engine.java.Java }
    single<PlatformInfo> { FakePlatform }
    single<SecureRandom> { JavaSecureRandom }
}

private fun demoOverrides(sessions: SessionRepository, artifacts: ArtifactRepository, transcript: TranscriptRepository) = module {
    single<AccountRepository> { InMemoryAccountRepository(demoAccounts) }
    single<AuthRepository> { FakeAuthRepository() }
    single<InstallationIdentity> { FixedInstallation }
    single<Clock> { FixedClock(DEMO_NOW) }
    single<FeaturesRepository> { FakeFeatures(setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1", "cloud.sessions.remote.v1", "artifacts.v1")) }
    single<SessionRepository> { sessions }
    single<TranscriptRepository> { transcript }
    single<ArtifactRepository> { artifacts }
    single<SessionFileRepository> { FakeFileRepository() }
    single<DeviceRepository> { FakeDeviceRepository() }
    single<TransferRepository> { FakeTransferRepository() }
    single<SubscriptionAccountRepository> { FakeSubscriptionAccountRepository() }
}

internal val demoAccounts = listOf(
    account("a1", origin = "https://code.acme.dev", username = "ada", expiresAt = Long.MAX_VALUE),
    account("a2", origin = "https://teamcode.example.org", memberId = "m9", username = "ada.lovelace", expiresAt = Long.MAX_VALUE),
)

/** The built-in demo's sessions ([DemoContent]) on a fake that counts what the app asks. */
internal fun demoSessions() = FakeSessionRepository().apply {
    val all = DemoContent.sessions(DEMO_NOW)
    all.forEach { byId[it.id] = it }
    pages = { filter, _ -> SessionPage(all.filter { it.state in filter }, null) }
    changes = flow { emit(LiveUpdate.Opened(false)); awaitCancellation() }
}

/** The built-in demo's transcripts ([DemoContent]), with no live event. */
internal fun demoTranscript() = object : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
        val events = DemoContent.transcript(sessionId, DEMO_NOW)
        return TranscriptPage(events, null, events.lastOrNull()?.seq ?: 0)
    }
    override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
}

/** The built-in demo's gallery ([DemoContent]). */
internal fun demoArtifacts() = FakeArtifactRepository().apply {
    val list = DemoContent.artifacts(DEMO_NOW)
    list.forEach { byId[it.id] = it }
    pages = { _, _ -> ArtifactPage(list, null) }
}
