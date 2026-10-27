package me.nathanfallet.teamcode.companion.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
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
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore

/*
 * The app's repositories, bound by [demoModule]: each call of the demo account goes to the demo's memory, each call of
 * another account to its server, so the app's use cases and screens run unchanged on the demo and never reach a network
 * for it.
 */

private fun isDemo(accountId: String) = accountId == DemoContent.ACCOUNT_ID

/**
 * The device's accounts, with the demo account while the demo runs. The demo exists only while no real account does:
 * a real account saved (a sign-in, the desktop's CLI account) ends it, and removing the demo account (signing out)
 * drops everything it held. The demo account is never written to [real], so nothing of it outlives the app's process.
 * Every change goes through this repository, which keeps its lists in step at once.
 */
class DemoAccountRepository(private val real: AccountRepository, private val demo: DemoMode) : AccountRepository {
    private val list = MutableStateFlow(currentList())
    private val active = MutableStateFlow(currentActive())
    override val accounts: StateFlow<List<Account>> = list.asStateFlow()
    override val activeAccountId: StateFlow<String?> = active.asStateFlow()

    private fun currentList() = listOfNotNull(demo.account) + real.accounts.value
    private fun currentActive() = if (demo.active) DemoContent.ACCOUNT_ID else real.activeAccountId.value

    private fun refresh() {
        list.value = currentList()
        active.value = currentActive()
    }

    /** Starts the demo on its own account; never while a real account exists. */
    fun startDemo(deviceName: String): Account {
        check(real.accounts.value.isEmpty()) { "The demo starts only on a device without accounts." }
        return demo.start(deviceName).also { refresh() }
    }

    override fun save(account: Account) {
        if (isDemo(account.id)) demo.store().account.value = account
        else {
            demo.end()
            real.save(account)
        }
        refresh()
    }

    override fun update(account: Account) {
        if (isDemo(account.id)) { if (demo.active) demo.store().account.value = account } else real.update(account)
        refresh()
    }

    override fun remove(id: String) {
        if (isDemo(id)) demo.end() else real.remove(id)
        refresh()
    }

    override fun setActive(id: String) {
        if (!isDemo(id)) real.setActive(id)
        refresh()
    }
}

/** The platform's secure store for real accounts; the demo account has no token, and none is read, written or deleted for it. */
class DemoCredentialStore(private val real: CredentialStore) : CredentialStore {
    override val persistent: Boolean get() = real.persistent
    // Not a credential: the account checks only see that the demo account is signed in.
    override suspend fun read(accountId: String): String? = if (isDemo(accountId)) "demo" else real.read(accountId)
    override suspend fun write(accountId: String, token: String) { if (!isDemo(accountId)) real.write(accountId, token) }
    override suspend fun delete(accountId: String) { if (!isDemo(accountId)) real.delete(accountId) }
}

class DemoAwareAuthRepository(private val real: AuthRepository, private val demo: AuthRepository) : AuthRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    // The demo's origin is never asked: it does not resolve, and nothing of the demo may reach a network.
    private fun at(origin: String) = if (origin == DemoContent.ORIGIN) demo else real
    override suspend fun discover(origin: String) = at(origin).discover(origin)
    override suspend fun capabilities(origin: String, token: String) = at(origin).capabilities(origin, token)
    override suspend fun capabilities(accountId: String) = of(accountId).capabilities(accountId)
    override suspend fun login(origin: String, username: String, password: String, deviceName: String, installationId: String) =
        at(origin).login(origin, username, password, deviceName, installationId)
    override suspend fun changePassword(origin: String, token: String, current: String, replacement: String) = at(origin).changePassword(origin, token, current, replacement)
    override suspend fun me(accountId: String) = of(accountId).me(accountId)
    override suspend fun logout(accountId: String) = of(accountId).logout(accountId)
    override suspend fun renameCurrentDevice(accountId: String, name: String) = of(accountId).renameCurrentDevice(accountId, name)
    override suspend fun release(accountId: String) = of(accountId).release(accountId)
    override fun abandon(origin: String, token: String) = at(origin).abandon(origin, token)
}

class DemoAwareFeaturesRepository(private val real: FeaturesRepository, private val demo: FeaturesRepository) : FeaturesRepository {
    override suspend fun features(accountId: String, refresh: Boolean) = (if (isDemo(accountId)) demo else real).features(accountId, refresh)
}

class DemoAwareSessionRepository(private val real: SessionRepository, private val demo: SessionRepository) : SessionRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun sessions(accountId: String, scope: SessionScope, filter: SessionFilter, before: String?, kinds: Set<SessionKind>) =
        of(accountId).sessions(accountId, scope, filter, before, kinds)
    override suspend fun session(accountId: String, sessionId: String) = of(accountId).session(accountId, sessionId)
    override suspend fun profiles(accountId: String) = of(accountId).profiles(accountId)
    override suspend fun create(accountId: String, session: NewSession, idempotencyKey: String) = of(accountId).create(accountId, session, idempotencyKey)
    override suspend fun createOnDevice(accountId: String, session: NewDeviceSession, idempotencyKey: String) = of(accountId).createOnDevice(accountId, session, idempotencyKey)
    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>) =
        of(accountId).send(accountId, sessionId, text, clientMessageId, fileIds)
    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String) = of(accountId).answer(accountId, sessionId, answer, idempotencyKey)
    override suspend fun interrupt(accountId: String, sessionId: String, idempotencyKey: String) = of(accountId).interrupt(accountId, sessionId, idempotencyKey)
    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String, idempotencyKey: String) = of(accountId).stopTask(accountId, sessionId, taskId, idempotencyKey)
    override suspend fun pause(accountId: String, sessionId: String) = of(accountId).pause(accountId, sessionId)
    override suspend fun resume(accountId: String, sessionId: String) = of(accountId).resume(accountId, sessionId)
    override suspend fun end(accountId: String, sessionId: String) = of(accountId).end(accountId, sessionId)
    override fun sessionChanges(accountId: String, kinds: Set<SessionKind>) = of(accountId).sessionChanges(accountId, kinds)
}

class DemoAwareTranscriptRepository(private val real: TranscriptRepository, private val demo: TranscriptRepository) : TranscriptRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun page(accountId: String, sessionId: String, before: Long?) = of(accountId).page(accountId, sessionId, before)
    override fun follow(accountId: String, sessionId: String, after: Long) = of(accountId).follow(accountId, sessionId, after)
}

class DemoAwareArtifactRepository(private val real: ArtifactRepository, private val demo: ArtifactRepository) : ArtifactRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun artifacts(accountId: String, scope: ArtifactScope, sessionId: String?, before: String?) = of(accountId).artifacts(accountId, scope, sessionId, before)
    override suspend fun artifact(accountId: String, artifactId: String) = of(accountId).artifact(accountId, artifactId)
    override suspend fun versions(accountId: String, artifactId: String) = of(accountId).versions(accountId, artifactId)
    override suspend fun ticket(accountId: String, artifactId: String, version: Int?) = of(accountId).ticket(accountId, artifactId, version)
}

class DemoAwareSessionFileRepository(private val real: SessionFileRepository, private val demo: SessionFileRepository) : SessionFileRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun policy(accountId: String) = of(accountId).policy(accountId)
    override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, contentType: String?, idempotencyKey: String, onProgress: (sent: Long, total: Long) -> Unit) =
        of(accountId).upload(accountId, sessionId, file, contentType, idempotencyKey, onProgress)
    override fun typeFor(name: String) = real.typeFor(name)
}

class DemoAwareDeviceRepository(private val real: DeviceRepository, private val demo: DeviceRepository) : DeviceRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun onlineDevices(accountId: String) = of(accountId).onlineDevices(accountId)
    override suspend fun folders(accountId: String, deviceId: String) = of(accountId).folders(accountId, deviceId)
    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: Long?) =
        of(accountId).resumable(accountId, deviceId, folderId, limit, before)
}

class DemoAwareTransferRepository(private val real: TransferRepository, private val demo: TransferRepository) : TransferRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String) = of(accountId).transfer(accountId, sessionId, request, idempotencyKey)
    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?) = of(accountId).transfers(accountId, direction, before)
    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?) = of(accountId).accept(accountId, transferId, resume)
    override suspend fun decline(accountId: String, transferId: String) = of(accountId).decline(accountId, transferId)
    override suspend fun cancel(accountId: String, transferId: String) = of(accountId).cancel(accountId, transferId)
    override suspend fun recipients(accountId: String) = of(accountId).recipients(accountId)
    override suspend fun members(accountId: String) = of(accountId).members(accountId)
}

class DemoAwareSubscriptionAccountRepository(private val real: SubscriptionAccountRepository, private val demo: SubscriptionAccountRepository) : SubscriptionAccountRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override val types: List<SubscriptionAccountType> get() = real.types
    override suspend fun access(accountId: String) = of(accountId).access(accountId)
    override suspend fun accounts(accountId: String) = of(accountId).accounts(accountId)
    override fun startSignIn(accountId: String, type: SubscriptionAccountType) = of(accountId).startSignIn(accountId, type)
}

class DemoAwareGitHubLinkRepository(private val real: GitHubLinkRepository, private val demo: GitHubLinkRepository) : GitHubLinkRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun offered(accountId: String) = of(accountId).offered(accountId)
    override suspend fun link(accountId: String) = of(accountId).link(accountId)
    override suspend fun start(accountId: String) = of(accountId).start(accountId)
    override suspend fun poll(accountId: String, id: String) = of(accountId).poll(accountId, id)
    override suspend fun unlink(accountId: String) = of(accountId).unlink(accountId)
}

class DemoAwareMcpServerRepository(private val real: McpServerRepository, private val demo: McpServerRepository) : McpServerRepository {
    override suspend fun servers(accountId: String) = (if (isDemo(accountId)) demo else real).servers(accountId)
}
