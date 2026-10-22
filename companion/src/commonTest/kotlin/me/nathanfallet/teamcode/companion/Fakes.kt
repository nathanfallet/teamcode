package me.nathanfallet.teamcode.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore

val server = ServerInfo("https://team.example", "0.7.0", 1, setOf("cloud.sessions.v1"))

fun member(id: String = "m1", username: String = "ada", passwordChangeRequired: Boolean = false, role: String = "MEMBER") =
    Member(id, username, role, passwordChangeRequired)

class FakeAuthRepository : AuthRepository {
    val calls = mutableListOf<String>()
    var info: ServerInfo = server
    /** Login answers, consumed in order; the last one repeats. */
    val sessions = ArrayDeque(listOf(LoginSession("token-1", 2_000_000L, member())))
    var meResult: () -> Member = { member() }
    var logoutFailure: Exception? = null
    var loginFailure: Exception? = null
    val released = mutableListOf<String>()

    override suspend fun serverInfo(origin: String): ServerInfo { calls += "capabilities $origin"; return info.copy(origin = origin) }
    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession {
        calls += "login $origin $username $password $deviceName $installationId"
        loginFailure?.let { throw it }
        return if (sessions.size > 1) sessions.removeFirst() else sessions.first()
    }
    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) { calls += "password $token $current $replacement" }
    override suspend fun me(accountId: String): Member { calls += "me $accountId"; return meResult() }
    override suspend fun logout(accountId: String) { calls += "logout $accountId"; logoutFailure?.let { throw it } }
    var renameFailure: Exception? = null
    override suspend fun renameCurrentDevice(accountId: String, name: String): String { calls += "rename $accountId $name"; renameFailure?.let { throw it }; return name }
    override suspend fun release(accountId: String) { released += accountId }
    val abandoned = mutableListOf<String>()
    override fun abandon(origin: String, token: String) { abandoned += "$origin $token" }
}

class InMemoryAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    private val state = MutableStateFlow(initial)
    private val active = MutableStateFlow(initial.firstOrNull()?.id)
    override val accounts: StateFlow<List<Account>> = state.asStateFlow()
    override val activeAccountId: StateFlow<String?> = active.asStateFlow()
    override fun save(account: Account) {
        state.value = if (state.value.any { it.id == account.id }) state.value.map { if (it.id == account.id) account else it } else state.value + account
        active.value = account.id
    }
    override fun update(account: Account) { state.value = state.value.map { if (it.id == account.id) account else it } }
    override fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        if (active.value == id) active.value = state.value.firstOrNull()?.id
    }
    override fun setActive(id: String) { active.value = id }
}

class SequenceIds : IdGenerator {
    private var next = 0
    override fun newId() = "00000000-0000-4000-8000-00000000000${next++}"
}

object FixedInstallation : InstallationIdentity {
    override fun id() = "11111111-1111-4111-8111-111111111111"
}

class FixedClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis() = now
}

object FakePlatform : PlatformInfo {
    override val platform = "Test"
    override val deviceModel = "Bench"
    override val appVersion = "0.7.0"
}

fun account(id: String = "a1", origin: String = "https://team.example", memberId: String = "m1", username: String = "ada", signedIn: Boolean = true, expiresAt: Long = 2_000_000L) =
    Account(id, origin, username, memberId, "MEMBER", "TeamCode Test · Bench", expiresAt, "0.7.0", signedIn)

fun credentials() = MemoryCredentialStore()

fun unauthorized(): Member = throw CompanionException.Unauthorized()

fun cloudSession(
    id: String,
    state: me.nathanfallet.teamcode.companion.domain.models.SessionState = me.nathanfallet.teamcode.companion.domain.models.SessionState.READY,
    ownerId: String = "m1",
    createdAt: Long = 1_000,
) = me.nathanfallet.teamcode.companion.domain.models.CloudSession(
    id = id, name = "Session $id", ownerId = ownerId, state = state, phase = null, agentKind = "claude", model = "opus", effort = null,
    profileId = "p1", repositories = emptyList(), lastActivityAt = createdAt, createdAt = createdAt, updatedAt = createdAt, problemCode = null,
)

class FakeFeatures(var features: Set<String> = setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1")) :
    me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository {
    val refreshes = mutableListOf<Boolean>()
    override suspend fun features(accountId: String, refresh: Boolean): Set<String> { refreshes += refresh; return features }
}

/** Sessions answered from a map; the feed is whatever [changes] emits. */
class FakeSessionRepository : me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository {
    val calls = mutableListOf<String>()
    var pages: (me.nathanfallet.teamcode.companion.domain.models.SessionGroup, String?) -> me.nathanfallet.teamcode.companion.domain.models.SessionPage =
        { _, _ -> me.nathanfallet.teamcode.companion.domain.models.SessionPage(emptyList(), null) }
    val byId = mutableMapOf<String, me.nathanfallet.teamcode.companion.domain.models.CloudSession>()
    var changes: kotlinx.coroutines.flow.Flow<me.nathanfallet.teamcode.companion.domain.models.LiveUpdate<me.nathanfallet.teamcode.companion.domain.models.SessionChange>> =
        kotlinx.coroutines.flow.emptyFlow()
    /** Suspends each list load until released, to change things while it is in flight. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun sessions(accountId: String, scope: me.nathanfallet.teamcode.companion.domain.models.SessionScope, group: me.nathanfallet.teamcode.companion.domain.models.SessionGroup, before: String?): me.nathanfallet.teamcode.companion.domain.models.SessionPage {
        calls += "list $scope $group $before"
        gate?.await()
        return pages(group, before)
    }
    override suspend fun session(accountId: String, sessionId: String): me.nathanfallet.teamcode.companion.domain.models.CloudSession {
        calls += "get $sessionId"
        return byId[sessionId] ?: throw CompanionException.NotFound()
    }
    override suspend fun profiles(accountId: String) = emptyList<me.nathanfallet.teamcode.companion.domain.models.Profile>()
    override fun sessionChanges(accountId: String) = changes.also { calls += "feed" }
}
