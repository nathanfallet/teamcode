package me.nathanfallet.teamcode.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.ServerCapabilities
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AuthRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore

val server = ServerInfo("https://team.example")
val capabilities = ServerCapabilities("0.7.0", 1, setOf("cloud.sessions.v1"))

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

    var announced: ServerCapabilities = capabilities
    var capabilitiesFailure: Exception? = null
    override suspend fun discover(origin: String): ServerInfo { calls += "discover $origin"; return info.copy(origin = origin) }
    override suspend fun capabilities(origin: String, token: String): ServerCapabilities {
        calls += "capabilities $origin $token"
        capabilitiesFailure?.let { throw it }
        return announced
    }
    override suspend fun capabilities(accountId: String): ServerCapabilities { calls += "capabilities $accountId"; return announced }
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
    host: me.nathanfallet.teamcode.companion.domain.models.SessionHost? = null,
    problemCode: String? = null,
) = me.nathanfallet.teamcode.companion.domain.models.CloudSession(
    id = id, name = "Session $id", ownerId = ownerId, state = state, phase = null, agentKind = "claude", model = "opus", effort = null,
    profileId = "p1", repositories = emptyList(), lastActivityAt = createdAt, createdAt = createdAt, updatedAt = createdAt, problemCode = problemCode,
    kind = if (host != null) me.nathanfallet.teamcode.companion.domain.models.SessionKind.REMOTE else me.nathanfallet.teamcode.companion.domain.models.SessionKind.CLOUD,
    host = host,
)

/** A remote session's host: a named macOS laptop running in `app`, online unless said otherwise. */
fun laptop(online: Boolean = true, name: String? = "Ada's MacBook", removed: Boolean = false) =
    me.nathanfallet.teamcode.companion.domain.models.SessionHost(name, "macos", "15.1", "app", online, removed, "ask")

class FakeFeatures(var features: Set<String> = setOf("cloud.sessions.v1", "cloud.transcript.v1", "cloud.messages.v1", "cloud.events.v1", "cloud.permissions.v1")) :
    me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository {
    val refreshes = mutableListOf<Boolean>()
    override suspend fun features(accountId: String, refresh: Boolean): Set<String> { refreshes += refresh; return features }
}

/** Sessions answered from a map; the feed is whatever [changes] emits. */
class FakeSessionRepository : me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository {
    val calls = mutableListOf<String>()
    var pages: (me.nathanfallet.teamcode.companion.domain.models.SessionFilter, String?) -> me.nathanfallet.teamcode.companion.domain.models.SessionPage =
        { _, _ -> me.nathanfallet.teamcode.companion.domain.models.SessionPage(emptyList(), null) }
    val byId = mutableMapOf<String, me.nathanfallet.teamcode.companion.domain.models.CloudSession>()
    var sessionFailure: Exception? = null
    var changes: kotlinx.coroutines.flow.Flow<me.nathanfallet.teamcode.companion.domain.models.LiveUpdate<me.nathanfallet.teamcode.companion.domain.models.SessionChange>> =
        kotlinx.coroutines.flow.emptyFlow()
    /** Suspends each list load until released, to change things while it is in flight. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun sessions(accountId: String, scope: me.nathanfallet.teamcode.companion.domain.models.SessionScope, filter: me.nathanfallet.teamcode.companion.domain.models.SessionFilter, before: String?,
        kinds: Set<me.nathanfallet.teamcode.companion.domain.models.SessionKind>): me.nathanfallet.teamcode.companion.domain.models.SessionPage {
        calls += "list $scope $filter $before" + if (kinds.isEmpty()) "" else " " + kinds.map { it.wire }.sorted().joinToString(",")
        gate?.await()
        return pages(filter, before)
    }
    override suspend fun session(accountId: String, sessionId: String): me.nathanfallet.teamcode.companion.domain.models.CloudSession {
        calls += "get $sessionId"
        sessionFailure?.let { throw it }
        return byId[sessionId] ?: throw CompanionException.NotFound()
    }
    var profileList = emptyList<me.nathanfallet.teamcode.companion.domain.models.Profile>()
    override suspend fun profiles(accountId: String) = profileList.also { calls += "profiles" }
    /** Each action's answer; throw from it to fail. */
    var sendResult: (String) -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("in-$it", "queued", false, null) }
    var interruptResult: () -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("int", "queued", false, null) }
    var createResult: (me.nathanfallet.teamcode.companion.domain.models.NewSession) -> me.nathanfallet.teamcode.companion.domain.models.CloudSession = { cloudSession("new") }
    override suspend fun create(accountId: String, session: me.nathanfallet.teamcode.companion.domain.models.NewSession, idempotencyKey: String): me.nathanfallet.teamcode.companion.domain.models.CloudSession {
        calls += "create ${session.name} $idempotencyKey"
        return createResult(session)
    }
    var createOnDeviceResult: (me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession) -> me.nathanfallet.teamcode.companion.domain.models.CloudSession =
        { cloudSession("new").copy(kind = me.nathanfallet.teamcode.companion.domain.models.SessionKind.REMOTE) }
    val deviceCreations = mutableListOf<Pair<me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession, String>>()
    override suspend fun createOnDevice(accountId: String, session: me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession, idempotencyKey: String): me.nathanfallet.teamcode.companion.domain.models.CloudSession {
        calls += "createOnDevice ${session.name} $idempotencyKey"
        deviceCreations += session to idempotencyKey
        return createOnDeviceResult(session)
    }
    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>): me.nathanfallet.teamcode.companion.domain.models.InputReceipt {
        calls += "send $sessionId $text $clientMessageId" + if (fileIds.isEmpty()) "" else " files=" + fileIds.joinToString(",")
        return sendResult(clientMessageId)
    }
    /** Each answer's acknowledgement; throw from it to fail. */
    var answerResult: (me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer) -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt =
        { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("ans-${it.requestId}", "queued", false, null) }
    val answers = mutableListOf<Pair<me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer, String>>()
    override suspend fun answer(accountId: String, sessionId: String, answer: me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer, idempotencyKey: String): me.nathanfallet.teamcode.companion.domain.models.InputReceipt {
        calls += "answer $sessionId ${answer.requestId} $idempotencyKey"
        answers += answer to idempotencyKey
        return answerResult(answer)
    }
    override suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): me.nathanfallet.teamcode.companion.domain.models.InputReceipt { calls += "interrupt $sessionId $idempotencyKey"; return interruptResult() }
    var stopTaskResult: () -> me.nathanfallet.teamcode.companion.domain.models.InputReceipt = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("stop", "queued", false, null) }
    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String, idempotencyKey: String): me.nathanfallet.teamcode.companion.domain.models.InputReceipt { calls += "stopTask $sessionId $taskId $idempotencyKey"; return stopTaskResult() }
    override suspend fun pause(accountId: String, sessionId: String) = (byId[sessionId] ?: cloudSession(sessionId)).copy(state = me.nathanfallet.teamcode.companion.domain.models.SessionState.PAUSING).also { calls += "pause $sessionId" }
    override suspend fun end(accountId: String, sessionId: String) { calls += "end $sessionId"; endFailure?.let { throw it } }
    var endFailure: Exception? = null
    var renameFailure: Exception? = null
    override suspend fun rename(accountId: String, sessionId: String, name: String): me.nathanfallet.teamcode.companion.domain.models.CloudSession {
        calls += "rename $sessionId $name"; renameFailure?.let { throw it }
        return (byId[sessionId] ?: cloudSession(sessionId)).copy(name = name)
    }
    override suspend fun resume(accountId: String, sessionId: String) = (byId[sessionId] ?: cloudSession(sessionId)).copy(state = me.nathanfallet.teamcode.companion.domain.models.SessionState.RESUMING).also { calls += "resume $sessionId" }
    override fun sessionChanges(accountId: String, kinds: Set<me.nathanfallet.teamcode.companion.domain.models.SessionKind>, waiting: Boolean, waits: Boolean, renames: Boolean, workflows: Boolean) =
        changes.also { calls += "feed" + (if (kinds.isEmpty()) "" else " " + kinds.map { it.wire }.sorted().joinToString(",")) + (if (waiting) " waiting" else "") + (if (waits) " waits" else "") + (if (renames) " renames" else "") + if (workflows) " workflows" else "" }
}

fun artifact(id: String, title: String = "Report $id", version: Int = 1, updatedAt: Long = 1_000) = me.nathanfallet.teamcode.companion.domain.models.Artifact(
    id = id, url = "https://team.example/artifact/$id", title = title, description = "", icon = "chart", access = "team", ownerName = "ada",
    currentVersion = version, originKind = "api", originSessionId = null, updatedAt = updatedAt,
)

/** Artifacts answered from [byId]; [pages] answers the gallery, [tickets] each ticket request. */
class FakeArtifactRepository : me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository {
    val calls = mutableListOf<String>()
    val byId = mutableMapOf<String, me.nathanfallet.teamcode.companion.domain.models.Artifact>()
    var pages: (me.nathanfallet.teamcode.companion.domain.models.ArtifactScope, String?) -> me.nathanfallet.teamcode.companion.domain.models.ArtifactPage =
        { _, _ -> me.nathanfallet.teamcode.companion.domain.models.ArtifactPage(emptyList(), null) }
    var tickets: (String, Int?) -> me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket = { id, version ->
        me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket(id, version ?: 1, "https://team.example/artifact/$id/t${calls.size}/", 9_000_000)
    }
    override suspend fun artifacts(accountId: String, scope: me.nathanfallet.teamcode.companion.domain.models.ArtifactScope, sessionId: String?, before: String?) =
        pages(scope, before).also { calls += "list ${scope.wire} $sessionId $before" }
    override suspend fun artifact(accountId: String, artifactId: String) = (byId[artifactId] ?: throw CompanionException.NotFound()).also { calls += "get $artifactId" }
    override suspend fun versions(accountId: String, artifactId: String): List<me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion> {
        calls += "versions $artifactId"
        val current = byId[artifactId]?.currentVersion ?: throw CompanionException.NotFound()
        return (current downTo 1).map { me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion(it, it * 1_000L, 1, 10) }
    }
    override suspend fun ticket(accountId: String, artifactId: String, version: Int?) = tickets(artifactId, version).also { calls += "ticket $artifactId $version" }
}

/** Online devices and their folders from maps; throw from [devicesResult] or [foldersResult] to fail. */
class FakeDeviceRepository : me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository {
    val calls = mutableListOf<String>()
    var devicesResult: () -> List<me.nathanfallet.teamcode.companion.domain.models.HostDevice> = { emptyList() }
    var foldersResult: (String) -> me.nathanfallet.teamcode.companion.domain.models.DeviceFolders =
        { me.nathanfallet.teamcode.companion.domain.models.DeviceFolders(true, emptyList()) }
    override suspend fun onlineDevices(accountId: String) = devicesResult().also { calls += "devices" }
    override suspend fun folders(accountId: String, deviceId: String) = foldersResult(deviceId).also { calls += "folders $deviceId" }
    var resumableResult: (deviceId: String, folderId: String, before: Long?) -> me.nathanfallet.teamcode.companion.domain.models.ResumableSessions =
        { _, _, _ -> me.nathanfallet.teamcode.companion.domain.models.ResumableSessions(emptyList()) }
    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: Long?) =
        resumableResult(deviceId, folderId, before).also { calls += "resumable $deviceId $folderId $limit" + (before?.let { " before=$it" } ?: "") }
}

fun hostDevice(id: String, name: String = "Device $id", current: Boolean = false) =
    me.nathanfallet.teamcode.companion.domain.models.HostDevice(id, name, "macos", "15.1", current)
