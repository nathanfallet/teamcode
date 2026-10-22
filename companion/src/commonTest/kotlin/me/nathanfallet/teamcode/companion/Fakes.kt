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
    val released = mutableListOf<String>()

    override suspend fun serverInfo(origin: String): ServerInfo { calls += "capabilities $origin"; return info.copy(origin = origin) }
    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession {
        calls += "login $origin $username $password $deviceName $installationId"
        return if (sessions.size > 1) sessions.removeFirst() else sessions.first()
    }
    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) { calls += "password $token $current $replacement" }
    override suspend fun me(accountId: String): Member { calls += "me $accountId"; return meResult() }
    override suspend fun logout(accountId: String) { calls += "logout $accountId"; logoutFailure?.let { throw it } }
    override suspend fun renameCurrentDevice(accountId: String, name: String): String { calls += "rename $accountId $name"; return name }
    override fun release(accountId: String) { released += accountId }
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
