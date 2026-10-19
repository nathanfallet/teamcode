package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The team's GitHub App, its members' links and pending link attempts. Every secret is an AES-GCM envelope bound to
 * its row (team, member or attempt), so a ciphertext copied to another row does not decrypt.
 */
class SqlGitHubStore(private val database: Database, private val cipher: CredentialCipher) : GitHubAppStore, GitHubLinkStore {
    override suspend fun now(): Long = database.query { nowMillis() }

    override suspend fun app(teamId: String): GitHubApp? = database.query {
        GitHubApps.selectAll().where { GitHubApps.teamId eq teamId }.singleOrNull()?.let(::readApp)
    }

    override suspend fun saveApp(actor: Principal, expectedVersion: Int?, input: GitHubAppInput, metadata: GitHubAppMetadata): GitHubApp = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        if (actor.member.role != Role.ADMIN) throw TeamFailure("forbidden", 403, "Administrator access is required.")
        val now = nowMillis()
        val row = GitHubApps.selectAll().where { GitHubApps.teamId eq actor.teamId }.forUpdate().singleOrNull()
        if ((row == null) != (expectedVersion == null) || (row != null && row[GitHubApps.version] != expectedVersion))
            throw TeamFailure("github_app_conflict", 409, "The GitHub App configuration changed. Reload it before saving.")
        val previous = row?.let { decodeApp(actor.teamId, it[GitHubApps.credential]) }
        val secret = StoredAppSecret(input.clientSecret ?: previous!!.clientSecret, input.privateKey ?: previous!!.privateKey)
        val envelope = cipher.encrypt(Json.encodeToString(secret).encodeToByteArray(), appContext(actor.teamId))
        if (row == null) GitHubApps.insert {
            it[teamId] = actor.teamId; it[appId] = input.appId; it[clientId] = input.clientId; it[slug] = metadata.slug; it[name] = metadata.name
            it[credential] = envelope; it[version] = 1; it[updatedBy] = actor.member.id; it[createdAt] = now; it[updatedAt] = now
        } else GitHubApps.update({ (GitHubApps.teamId eq actor.teamId) and (GitHubApps.version eq expectedVersion!!) }) {
            it[appId] = input.appId; it[clientId] = input.clientId; it[slug] = metadata.slug; it[name] = metadata.name
            it[credential] = envelope; it[version] = expectedVersion!! + 1; it[updatedBy] = actor.member.id; it[updatedAt] = now
        }
        audit(actor.teamId, actor.member.id, "github.app.saved", actor.teamId, null, actor.tokenId)
        readApp(GitHubApps.selectAll().where { GitHubApps.teamId eq actor.teamId }.single())
    }

    override suspend fun credentials(teamId: String): GitHubAppCredentials? = database.query {
        GitHubApps.selectAll().where { GitHubApps.teamId eq teamId }.singleOrNull()?.let { row ->
            val secret = decodeApp(teamId, row[GitHubApps.credential])
            GitHubAppCredentials(row[GitHubApps.appId], row[GitHubApps.clientId], secret.clientSecret, secret.privateKey)
        }
    }

    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }

    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()?.let(::readMember)
    }

    override suspend fun startAttempt(actor: Principal, attempt: GitHubLinkAttempt) = database.query {
        requireCurrent(this, actor)
        // Serialized on the member row, so two concurrent starts leave exactly one attempt.
        check(Members.select(Members.id).where { Members.id eq attempt.memberId }.forUpdate().singleOrNull() != null)
        val now = nowMillis()
        // One pending attempt per member; expired attempts of the team go with it, so the table stays small.
        GitHubLinkAttempts.deleteWhere { (GitHubLinkAttempts.memberId eq attempt.memberId) or ((GitHubLinkAttempts.teamId eq attempt.teamId) and (GitHubLinkAttempts.expiresAt lessEq now)) }
        GitHubLinkAttempts.insert {
            it[id] = attempt.id; it[teamId] = attempt.teamId; it[memberId] = attempt.memberId; it[kind] = attempt.kind.wire
            it[secret] = cipher.encrypt(attempt.secret.encodeToByteArray(), attemptContext(attempt.id, attempt.memberId))
            it[browserHash] = attempt.browserHash; it[pollInterval] = attempt.pollIntervalSeconds; it[nextPollAt] = attempt.nextPollAt
            it[expiresAt] = attempt.expiresAt; it[createdAt] = now
        }
        Unit
    }

    override suspend fun attempt(id: String): GitHubLinkAttempt? = database.query {
        GitHubLinkAttempts.selectAll().where { GitHubLinkAttempts.id eq id }.singleOrNull()?.let { row ->
            val member = row[GitHubLinkAttempts.memberId]
            GitHubLinkAttempt(id, row[GitHubLinkAttempts.teamId], member, GitHubLinkKind.entries.single { it.wire == row[GitHubLinkAttempts.kind] },
                cipher.decrypt(row[GitHubLinkAttempts.secret], attemptContext(id, member)).decodeToString(), row[GitHubLinkAttempts.browserHash],
                row[GitHubLinkAttempts.pollInterval], row[GitHubLinkAttempts.nextPollAt], row[GitHubLinkAttempts.expiresAt])
        }
    }

    override suspend fun consumeAttempt(id: String): Boolean = database.query { GitHubLinkAttempts.deleteWhere { GitHubLinkAttempts.id eq id } == 1 }

    override suspend fun deferPoll(id: String, expectedNextPollAt: Long?, intervalSeconds: Int, nextPollAt: Long): Boolean = database.query {
        GitHubLinkAttempts.update({ (GitHubLinkAttempts.id eq id) and (if (expectedNextPollAt == null) Op.TRUE else GitHubLinkAttempts.nextPollAt eq expectedNextPollAt) }) {
            it[pollInterval] = intervalSeconds; it[GitHubLinkAttempts.nextPollAt] = nextPollAt
        } == 1
    }

    override suspend fun saveLink(teamId: String, memberId: String, actor: Principal?, identity: GitHubIdentity, clientId: String, tokens: GitHubTokens): GitHubLink = database.query {
        lockTeam(teamId)
        if (actor != null) requireCurrent(this, actor)
        val member = Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()?.let(::readMember)
        if (member == null || member.disabled || member.passwordChangeRequired) throw TeamFailure("forbidden", 403, "This member cannot link GitHub.")
        val holder = GitHubLinks.select(GitHubLinks.memberId).where { (GitHubLinks.teamId eq teamId) and (GitHubLinks.githubUserId eq identity.userId) }.singleOrNull()?.get(GitHubLinks.memberId)
        if (holder != null && holder != memberId) throw TeamFailure("github_account_in_use", 409, "This GitHub account is already linked to another member of the team.")
        val now = nowMillis()
        val version = (GitHubLinks.select(GitHubLinks.tokenVersion).where { GitHubLinks.memberId eq memberId }.forUpdate().singleOrNull()?.get(GitHubLinks.tokenVersion) ?: 0) + 1
        GitHubLinks.deleteWhere { GitHubLinks.memberId eq memberId }
        GitHubLinks.insert {
            it[GitHubLinks.memberId] = memberId; it[GitHubLinks.teamId] = teamId; it[githubUserId] = identity.userId; it[githubLogin] = identity.login
            it[GitHubLinks.clientId] = clientId; it[credential] = encryptTokens(teamId, memberId, tokens); it[state] = GitHubLinkState.LINKED.wire
            it[accessExpiresAt] = tokens.accessExpiresAt; it[refreshExpiresAt] = tokens.refreshExpiresAt; it[tokenVersion] = version
            it[refreshPending] = false; it[refreshStartedAt] = 0; it[linkedAt] = now; it[refreshedAt] = now
        }
        audit(teamId, actor?.member?.id ?: memberId, "github.link.created", memberId, memberId, actor?.tokenId)
        readLink(GitHubLinks.selectAll().where { GitHubLinks.memberId eq memberId }.single())
    }

    override suspend fun link(teamId: String, memberId: String): GitHubLink? = database.query {
        GitHubLinks.selectAll().where { (GitHubLinks.memberId eq memberId) and (GitHubLinks.teamId eq teamId) }.singleOrNull()?.let(::readLink)
    }

    override suspend fun links(teamId: String): List<GitHubLink> = database.query {
        GitHubLinks.selectAll().where { GitHubLinks.teamId eq teamId }.orderBy(GitHubLinks.githubLogin).limit(1000).map(::readLink)
    }

    override suspend fun removeLink(teamId: String, memberId: String, actor: Principal): GitHubRevocable? = database.query {
        lockTeam(teamId); requireCurrent(this, actor)
        val row = GitHubLinks.selectAll().where { (GitHubLinks.memberId eq memberId) and (GitHubLinks.teamId eq teamId) }.forUpdate().singleOrNull() ?: return@query null
        GitHubLinkAttempts.deleteWhere { GitHubLinkAttempts.memberId eq memberId }
        delete(row, actor)
    }

    override suspend fun removeLinksOfOtherApps(actor: Principal, clientId: String): List<GitHubRevocable> = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        GitHubLinks.selectAll().where { (GitHubLinks.teamId eq actor.teamId) and (GitHubLinks.clientId neq clientId) }.forUpdate().toList().mapNotNull { delete(it, actor) }
    }

    /** Deletes one link row with its journal entry; returns its access token unless it no longer decrypts. */
    private fun JdbcTransaction.delete(row: ResultRow, actor: Principal): GitHubRevocable? {
        val teamId = row[GitHubLinks.teamId]; val memberId = row[GitHubLinks.memberId]
        val token = try { decryptTokens(teamId, memberId, row[GitHubLinks.credential]).accessToken } catch (_: Exception) { null }
        GitHubLinks.deleteWhere { GitHubLinks.memberId eq memberId }
        audit(teamId, actor.member.id, "github.link.removed", memberId, memberId, actor.tokenId)
        return token?.let { GitHubRevocable(row[GitHubLinks.clientId], it) }
    }

    override suspend fun claim(teamId: String, memberId: String, clientId: String, validUntil: Long, usableUntil: Long, staleAfterMillis: Long): GitHubTokenClaim = database.query {
        val row = GitHubLinks.selectAll().where { (GitHubLinks.memberId eq memberId) and (GitHubLinks.teamId eq teamId) }.forUpdate().singleOrNull()
            ?: return@query GitHubTokenClaim.NotLinked
        val link = readLink(row)
        if (link.state != GitHubLinkState.LINKED || link.clientId != clientId) return@query GitHubTokenClaim.ReconnectRequired
        val now = nowMillis()
        val pending = row[GitHubLinks.refreshPending]
        val tokens = decryptTokens(teamId, memberId, row[GitHubLinks.credential])
        val expiresAt = link.accessExpiresAt
        if (pending && row[GitHubLinks.refreshStartedAt] + staleAfterMillis > now)
            return@query if (expiresAt == null || expiresAt > usableUntil) GitHubTokenClaim.Ready(link, tokens.accessToken) else GitHubTokenClaim.Wait
        if (expiresAt == null || expiresAt > validUntil) {
            if (pending) GitHubLinks.update({ GitHubLinks.memberId eq memberId }) { it[refreshPending] = false }
            return@query GitHubTokenClaim.Ready(link, tokens.accessToken)
        }
        val refreshToken = tokens.refreshToken
        if (refreshToken == null || (link.refreshExpiresAt ?: Long.MAX_VALUE) <= now) {
            // Nothing left to refresh with: use the access token while it lasts, then the member links again.
            if (expiresAt > now + 60_000) return@query GitHubTokenClaim.Ready(link, tokens.accessToken)
            GitHubLinks.update({ GitHubLinks.memberId eq memberId }) { it[state] = GitHubLinkState.RECONNECT_REQUIRED.wire; it[refreshPending] = false }
            return@query GitHubTokenClaim.ReconnectRequired
        }
        // Committed before GitHub is called: a second replica waits instead of spending the same refresh token.
        GitHubLinks.update({ GitHubLinks.memberId eq memberId }) { it[refreshPending] = true; it[refreshStartedAt] = now }
        GitHubTokenClaim.Refresh(link, tokens.accessToken, refreshToken, row[GitHubLinks.tokenVersion], now)
    }

    override suspend fun completeRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, tokens: GitHubTokens): Boolean = database.query {
        val envelope = encryptTokens(teamId, memberId, tokens)
        val now = nowMillis()
        GitHubLinks.update({ owned(memberId, teamId, claim) }) {
            it[credential] = envelope; it[accessExpiresAt] = tokens.accessExpiresAt; it[refreshExpiresAt] = tokens.refreshExpiresAt
            it[tokenVersion] = claim.tokenVersion + 1; it[refreshPending] = false; it[refreshedAt] = now
        } == 1
    }

    override suspend fun abandonRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, reconnect: Boolean) = database.query {
        GitHubLinks.update({ owned(memberId, teamId, claim) }) {
            it[refreshPending] = false
            if (reconnect) it[state] = GitHubLinkState.RECONNECT_REQUIRED.wire
        }
        Unit
    }

    /** Only the current refresh owner writes: a replica that was taken over (new start time) or a relink (new version) is fenced. */
    private fun owned(memberId: String, teamId: String, claim: GitHubTokenClaim.Refresh) =
        (GitHubLinks.memberId eq memberId) and (GitHubLinks.teamId eq teamId) and (GitHubLinks.tokenVersion eq claim.tokenVersion) and
            (GitHubLinks.refreshPending eq true) and (GitHubLinks.refreshStartedAt eq claim.startedAt)

    private fun encryptTokens(teamId: String, memberId: String, tokens: GitHubTokens) =
        cipher.encrypt(Json.encodeToString(StoredTokens(tokens.accessToken, tokens.refreshToken)).encodeToByteArray(), linkContext(teamId, memberId))
    private fun decryptTokens(teamId: String, memberId: String, envelope: String) =
        Json.decodeFromString<StoredTokens>(cipher.decrypt(envelope, linkContext(teamId, memberId)).decodeToString())
    private fun decodeApp(teamId: String, envelope: String) = Json.decodeFromString<StoredAppSecret>(cipher.decrypt(envelope, appContext(teamId)).decodeToString())

    private fun appContext(teamId: String) = "github-app:$teamId"
    private fun linkContext(teamId: String, memberId: String) = "github-link:$teamId:$memberId"
    private fun attemptContext(id: String, memberId: String) = "github-attempt:$id:$memberId"

    private fun readApp(row: ResultRow) = GitHubApp(row[GitHubApps.appId], row[GitHubApps.clientId], row[GitHubApps.slug], row[GitHubApps.name], row[GitHubApps.version], row[GitHubApps.updatedAt])
    private fun readLink(row: ResultRow) = GitHubLink(
        row[GitHubLinks.memberId], GitHubIdentity(row[GitHubLinks.githubUserId], row[GitHubLinks.githubLogin]), row[GitHubLinks.clientId],
        GitHubLinkState.entries.single { it.wire == row[GitHubLinks.state] }, row[GitHubLinks.accessExpiresAt], row[GitHubLinks.refreshExpiresAt],
        row[GitHubLinks.linkedAt], row[GitHubLinks.refreshedAt],
    )
}

@Serializable
private class StoredAppSecret(val clientSecret: String, val privateKey: String)
@Serializable
private class StoredTokens(val accessToken: String, val refreshToken: String? = null)
