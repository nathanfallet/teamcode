package me.nathanfallet.teamcode.companion

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.di.appModules
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
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

/** The demo data of the screenshots and the whole-app tests: two accounts, eight sessions, eight artifacts, one transcript. */
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

internal fun demoSessions() = FakeSessionRepository().apply {
    val minute = 60_000L
    val names = listOf(
        "Fix the flaky login test" to SessionState.ACTIVE, "Migrate billing to the new API" to SessionState.READY,
        "Review the release checklist" to SessionState.READY, "Speed up the CI cache" to SessionState.ACTIVE,
        "Write the onboarding guide" to SessionState.READY, "Upgrade Kotlin to 2.4" to SessionState.PREPARING,
        "Audit the payment webhooks" to SessionState.READY, "Prototype the search page" to SessionState.READY,
    )
    val active = names.mapIndexed { index, (name, state) ->
        cloudSession("s$index", state, createdAt = DEMO_NOW - (index * 17 + 2) * minute, host = if (index == 3) laptop() else null)
            .copy(name = name, repositories = listOf("acme/web", "acme/api", "acme/infra")[index % 3].let { listOf(it) }, effort = if (index % 2 == 0) "high" else null)
    }
    active.forEach { byId[it.id] = it }
    pages = { group, _ ->
        when (group) {
            SessionGroup.ACTIVE -> SessionPage(active, null)
            SessionGroup.PAUSED -> SessionPage(listOf(cloudSession("p1", SessionState.PAUSED, createdAt = DEMO_NOW - 300 * minute).copy(name = "Refactor the settings screen")), null)
            SessionGroup.ENDED -> SessionPage(emptyList(), null)
        }
    }
    changes = flow { emit(LiveUpdate.Opened(false)); awaitCancellation() }
}

internal fun demoTranscript() = object : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
        val at = DEMO_NOW - 600_000
        val events = listOf(
            TranscriptEvent.UserMessage(1, at, "The login test fails about once in ten runs on CI. Can you find out why and fix it?", "in-1", "m1"),
            TranscriptEvent.AgentMessage(2, at + 5_000, "I'll start by reading the test and running it in a loop to reproduce the failure.", null),
            TranscriptEvent.ToolCall(3, at + 6_000, "t1", "Bash", "{\n  \"command\": \"./gradlew :web:test --tests LoginTest --rerun\"\n}", "./gradlew :web:test --tests LoginTest --rerun", null),
            TranscriptEvent.ToolResult(4, at + 40_000, "t1", "BUILD SUCCESSFUL in 31s", false, 0, null),
            TranscriptEvent.ToolCall(5, at + 41_000, "t2", "Read", "{\n  \"file_path\": \"web/src/test/LoginTest.kt\"\n}", "web/src/test/LoginTest.kt", null),
            TranscriptEvent.ToolResult(6, at + 42_000, "t2", "class LoginTest { ... }", false, null, null),
            TranscriptEvent.AgentMessage(7, at + 60_000,
                "Found it: the test reads the session cookie before the redirect completes. **The fix** waits for the redirect, then asserts:\n\n" +
                    "```kotlin\nwaitUntil { page.url.endsWith(\"/home\") }\nassertNotNull(cookies[\"session\"])\n```\n\nI ran it 50 times locally without a failure.", null),
            TranscriptEvent.Turn(8, at + 61_000, TurnPhase.entries.first { it.wire == "completed" }, 61_000, 6),
            TranscriptEvent.UserMessage(9, at + 120_000, "Great, open a pull request with that change.", "in-2", "m1"),
        )
        return TranscriptPage(events, null, 9)
    }
    override fun follow(accountId: String, sessionId: String, after: Long) = flow<LiveUpdate<TranscriptEvent>> { emit(LiveUpdate.Opened(false)); awaitCancellation() }
}

internal fun demoArtifacts() = FakeArtifactRepository().apply {
    val titles = listOf("Q3 latency report", "Login flakiness analysis", "Release 2.4 checklist", "Billing API migration plan", "CI cache benchmark",
        "Onboarding guide draft", "Webhook audit findings", "Search page prototype")
    val list = titles.mapIndexed { index, title ->
        artifact("r$index", title, version = index % 3 + 1, updatedAt = DEMO_NOW - (index * 45 + 5) * 60_000L).copy(description = "Published by a session: $title.")
    }
    list.forEach { byId[it.id] = it }
    pages = { _, _ -> ArtifactPage(list, null) }
}

