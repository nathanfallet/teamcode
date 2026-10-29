package me.nathanfallet.teamcode.companion.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket
import me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.McpServer
import me.nathanfallet.teamcode.companion.domain.models.Member
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.models.ServerCapabilities
import me.nathanfallet.teamcode.companion.domain.models.ServerInfo
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
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

/*
 * The demo's side of every repository: answered from the running [DemoMode]'s memory, never from a network. The
 * routing repositories ([DemoAwareSessionRepository]...) send the demo account's calls here and every other account's to
 * the server.
 */

/** The demo's sessions: messages and new sessions get the scripted reply; pause, resume and end apply at once. */
class DemoSessionRepository(private val demo: DemoMode) : SessionRepository {
    override suspend fun sessions(accountId: String, scope: SessionScope, filter: SessionFilter, before: String?, kinds: Set<SessionKind>): SessionPage {
        // Every demo session is the member's own: both scopes list the same sessions.
        val listed = demo.store().sessions.value.values.filter { it.state in filter }.sortedWith(compareByDescending<CloudSession> { it.createdAt }.thenByDescending { it.id })
        return SessionPage(if (before == null) listed else emptyList(), null)
    }

    override suspend fun session(accountId: String, sessionId: String): CloudSession = demo.store().session(sessionId)

    override suspend fun profiles(accountId: String): List<Profile> = DemoContent.profiles

    override suspend fun create(accountId: String, session: NewSession, idempotencyKey: String): CloudSession {
        val store = demo.store()
        val profile = DemoContent.profiles.firstOrNull { it.id == session.profileId } ?: DemoContent.profiles.first()
        val created = DemoContent.session(store.newId("session"), session.name, SessionState.READY, store.now(), profile, session.model)
            .copy(effort = session.effort)
        store.add(created)
        store.exchange(created.id, session.initialMessage)
        return store.session(created.id)
    }

    override suspend fun createOnDevice(accountId: String, session: NewDeviceSession, idempotencyKey: String): CloudSession =
        throw CompanionException.Unsupported("cloud.sessions.remote.daemon.v1")

    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>): InputReceipt {
        val store = demo.store()
        val session = store.session(sessionId)
        if (session.state.group == SessionGroup.ENDED) throw CompanionException.Server(409, "session_ended")
        // A message wakes a paused session, as on a server.
        val woke = session.state == SessionState.PAUSED
        if (woke) store.move(sessionId, SessionState.READY)
        return InputReceipt(store.exchange(sessionId, text), "consumed", woke, null)
    }

    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt =
        InputReceipt(demo.store().newId("answer"), "consumed", false, null)

    override suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String): InputReceipt =
        InputReceipt(demo.store().newId("interrupt"), "consumed", false, null)

    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String, idempotencyKey: String): InputReceipt =
        InputReceipt(demo.store().newId("stop"), "consumed", false, null)

    override suspend fun pause(accountId: String, sessionId: String): CloudSession = demo.store().move(sessionId, SessionState.PAUSED)

    override suspend fun resume(accountId: String, sessionId: String): CloudSession = demo.store().move(sessionId, SessionState.READY)

    override suspend fun end(accountId: String, sessionId: String) { demo.store().move(sessionId, SessionState.TERMINATED) }

    override fun sessionChanges(accountId: String, kinds: Set<SessionKind>, waiting: Boolean): Flow<LiveUpdate<SessionChange>> = channelFlow {
        val store = demo.store()
        // Listening before saying the feed is open: a change made right after it is never missed.
        launch(start = CoroutineStart.UNDISPATCHED) { store.feed.collect { send(LiveUpdate.Item(it)) } }
        send(LiveUpdate.Opened(false))
    }
}

/** The demo's transcripts, live: what a message or a new session adds shows at once. */
class DemoTranscriptRepository(private val demo: DemoMode) : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage {
        val store = demo.store()
        store.session(sessionId)
        val events = store.transcripts.value[sessionId].orEmpty()
        return TranscriptPage(if (before == null) events else emptyList(), null, events.lastOrNull()?.seq ?: 0)
    }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow {
        val store = demo.store()
        store.session(sessionId)
        emit(LiveUpdate.Opened(true))
        var last = after
        val watched = combine(store.transcripts, store.sessions) { transcripts, sessions -> transcripts[sessionId].orEmpty() to sessions[sessionId]?.state }
        // Like the server's stream: every event once, in order, then `Ended` once the session ended.
        emitAll(watched.transformWhile { (events, state) ->
            for (event in events.filter { it.seq > last }) {
                emit(LiveUpdate.Item(event))
                last = event.seq
            }
            val ended = state?.group == SessionGroup.ENDED
            if (ended) emit(LiveUpdate.Ended)
            !ended
        })
    }
}

/** The demo's gallery. There is no content to show: the demo server announces no tickets. */
class DemoArtifactRepository(private val demo: DemoMode) : ArtifactRepository {
    override suspend fun artifacts(accountId: String, scope: ArtifactScope, sessionId: String?, before: String?): ArtifactPage =
        ArtifactPage(if (before == null && sessionId == null) demo.store().artifacts else emptyList(), null)

    override suspend fun artifact(accountId: String, artifactId: String): Artifact =
        demo.store().artifacts.firstOrNull { it.id == artifactId } ?: throw CompanionException.NotFound()

    override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> {
        val artifact = artifact(accountId, artifactId)
        return (artifact.currentVersion downTo 1).map { ArtifactVersion(it, artifact.updatedAt - (artifact.currentVersion - it) * 3_600_000L, 1, 24_000) }
    }

    override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket =
        throw CompanionException.Unsupported("artifacts.tickets.v1")
}

/** What the demo server announces. */
class DemoFeaturesRepository(private val demo: DemoMode) : FeaturesRepository {
    override suspend fun features(accountId: String, refresh: Boolean): Set<String> = DemoContent.features.also { demo.store() }
}

/**
 * The demo account's side of authentication: the member is always there, a sign-out revokes nothing (there is no
 * session anywhere), and a rename is kept in memory. Signing in to the demo's origin is refused without a request.
 */
class DemoAuthRepository(private val demo: DemoMode) : AuthRepository {
    override suspend fun discover(origin: String): ServerInfo = throw CompanionException.NotTeamCode()
    override suspend fun capabilities(origin: String, token: String): ServerCapabilities = throw CompanionException.NotTeamCode()
    override suspend fun capabilities(accountId: String): ServerCapabilities = ServerCapabilities(DemoContent.SERVER_VERSION, 1, DemoContent.features).also { demo.store() }
    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String): LoginSession =
        throw CompanionException.NotTeamCode()
    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) = throw CompanionException.NotTeamCode()
    override suspend fun me(accountId: String): Member {
        val account = demo.store().account.value
        return Member(account.memberId, account.username, account.role, false)
    }
    override suspend fun logout(accountId: String) = Unit
    override suspend fun renameCurrentDevice(accountId: String, name: String): String = name.also { demo.store() }
    override suspend fun release(accountId: String) = Unit
    override fun abandon(origin: String, token: String) = Unit
}

/** The demo has no devices: the new-session sheet offers cloud sessions only. */
object DemoDeviceRepository : DeviceRepository {
    override suspend fun onlineDevices(accountId: String): List<HostDevice> = emptyList()
    override suspend fun folders(accountId: String, deviceId: String): DeviceFolders = DeviceFolders(false, emptyList())
    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: Long?) = ResumableSessions(emptyList())
}

/** The demo has no team to transfer sessions to (it announces no transfers). */
object DemoTransferRepository : TransferRepository {
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer =
        throw CompanionException.Unsupported("cloud.transfer.v1")
    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?) = TransferPage(emptyList(), null)
    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer = throw CompanionException.NotFound()
    override suspend fun decline(accountId: String, transferId: String): SessionTransfer = throw CompanionException.NotFound()
    override suspend fun cancel(accountId: String, transferId: String): SessionTransfer = throw CompanionException.NotFound()
    override suspend fun recipients(accountId: String): List<TeamMember> = emptyList()
    override suspend fun members(accountId: String): List<TeamMember> = emptyList()
}

/** The demo member is no administrator: the pool is out of reach, and no provider sign-in starts. */
class DemoSubscriptionAccountRepository(override val types: List<SubscriptionAccountType>) : SubscriptionAccountRepository {
    override suspend fun access(accountId: String) = EnrollmentAccess.ADMINISTRATORS_ONLY
    override suspend fun accounts(accountId: String): List<SubscriptionAccount> = emptyList()
    override fun startSignIn(accountId: String, type: SubscriptionAccountType): SubscriptionSignIn = throw CompanionException.Unsupported("accounts.enrollment")
}

/** The demo server has no GitHub App. */
object DemoGitHubLinkRepository : GitHubLinkRepository {
    override suspend fun offered(accountId: String) = false
    override suspend fun link(accountId: String) = GitHubLink(GitHubLinkState.NOT_LINKED, appConfigured = false)
    override suspend fun start(accountId: String): GitHubDeviceLink = throw CompanionException.Unsupported("github.link.v1")
    override suspend fun poll(accountId: String, id: String): GitHubPoll = GitHubPoll.Expired
    override suspend fun unlink(accountId: String) = Unit
}

object DemoMcpServerRepository : McpServerRepository {
    override suspend fun servers(accountId: String): List<McpServer> = emptyList()
}

/** The demo takes no files (it announces no `cloud.files.v1`); [typeFor] needs no server and stays the app's. */
class DemoSessionFileRepository(private val types: SessionFileRepository) : SessionFileRepository {
    override suspend fun policy(accountId: String) = FilePolicy(false, "demo", 0, 0, 1, emptyList())
    override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, contentType: String?, idempotencyKey: String, onProgress: (Long, Long) -> Unit): UploadedFile =
        throw CompanionException.Unsupported("cloud.files.v1")
    override fun typeFor(name: String): String? = types.typeFor(name)
}
