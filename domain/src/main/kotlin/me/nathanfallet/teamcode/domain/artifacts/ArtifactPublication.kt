package me.nathanfallet.teamcode.domain.artifacts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.team.*

/** Where a publication came from. PR 2 publishes through the API only; runs and cloud sessions come with their launchers. */
data class ArtifactOrigin(val kind: String = "api", val runId: String? = null, val sessionId: String? = null, val epoch: Int? = null)

data class UploadFile(val sha256: String, val size: Long)

/**
 * Whether a member may publish right now: false with the inactive reason (`artifact_storage_missing`,
 * `artifact_storage_unverified`, `artifacts_disabled`) or `artifact_publish_forbidden` when only administrators publish.
 */
data class ArtifactPolicy(val canPublish: Boolean, val reason: String?)

data class ArtifactFile(val path: String, val sha256: String, val size: Long, val contentType: String, val role: String)

/**
 * An artifact as one caller sees it. [relation] is `owner`, `editor`, `viewer`, `team` (a team or public artifact) or
 * `admin` (an administrator reading another member's personal artifact).
 */
data class Artifact(
    val id: String, val teamId: String, val ownerId: String, val ownerName: String, val title: String, val description: String, val icon: String,
    val access: ArtifactAccess, val publicFollowsLatest: Boolean, val publicVersion: Int?, val currentVersion: Int, val state: String,
    val storedBytes: Long, val origin: ArtifactOrigin, val createdAt: Long, val updatedAt: Long, val publishedAt: Long?, val expiresAt: Long?,
    val relation: String,
)

data class ArtifactVersion(
    val artifactId: String, val number: Int, val entry: String, val digest: String, val fileCount: Int, val totalBytes: Long,
    val createdBy: String, val createdAt: Long, val baseVersion: Int, val originSessionId: String?, val originEpoch: Int?,
    val files: List<ArtifactFile> = emptyList(),
)

data class ArtifactMember(val memberId: String, val username: String, val role: ArtifactMemberRole, val reason: String, val addedBy: String, val addedAt: Long, val originSessionId: String?)
data class ArtifactSharing(val ownerId: String, val members: List<ArtifactMember>)

data class ArtifactUpload(val uploadId: String, val artifactId: String, val missing: List<String>, val expiresAt: Long)

/** A blob of an upload, ready to be checked: what was announced for it. */
data class UploadBlob(val teamId: String, val artifactId: String, val sha256: String, val size: Long)

/** One chunk stored; [complete] once all of the blob's chunks are in. [stored] when the blob was already checked and kept. */
data class ChunkReceipt(val complete: Boolean, val received: Int, val total: Int, val stored: Boolean = false)

/** A validated commit, ready for the transaction that checks the base version, the blobs, the rate and the quotas. */
class CommitPlan(
    val uploadId: String, val entry: String, val files: List<ArtifactFile>, val digest: String, val baseVersion: Int,
    val title: String?, val description: String?, val icon: String?, val access: ArtifactAccess?,
)

data class Published(val artifact: Artifact, val version: ArtifactVersion, val replayed: Boolean)

data class ArtifactPage(val artifacts: List<Artifact>, val nextBefore: String?)

/** `mine` (owned), `shared` (invited), `team` (everything the caller can read) or `all` (administrators). */
enum class ArtifactScope(val wire: String) {
    MINE("mine"), SHARED("shared"), TEAM("team"), ALL("all");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

data class ArtifactQuery(
    val scope: ArtifactScope = ArtifactScope.TEAM, val ownerId: String? = null, val sessionId: String? = null, val runId: String? = null,
    val limit: Int = ArtifactRules.MAX_LIST, val before: String? = null,
    /** `local_run`, `cloud_session` or `api`. */
    val originKind: String? = null, val access: ArtifactAccess? = null,
)

/** `409 artifact_conflict`: the base version is not the live one. [current] is the live version, for the publisher to merge. */
class ArtifactConflictFailure(val current: ArtifactVersion?) : Exception("The artifact changed: version ${current?.number ?: 0} is live.")

/** `422 artifact_secret_detected`: the blob [sha256] holds a credential of [kind]; the value is never kept or returned. */
class ArtifactSecretFailure(val sha256: String, val kind: String) : Exception("A file holds a secret ($kind).")

/** A deleted artifact whose folder is still to be removed, or a blob to remove from an artifact's folder. */
data class StorageTarget(val teamId: String, val artifactId: String)
data class CollectedBlob(val teamId: String, val artifactId: String, val sha256: String)

/**
 * Persistence of artifacts. Every call revalidates the caller in its transaction; every member call but [delete] is
 * refused while artifacts are not active (`409 artifact_storage_missing`, `artifact_storage_unverified` or
 * `artifacts_disabled`). Writes that count against quotas run under the team lock.
 */
interface ArtifactStore {
    /** The caller's [ArtifactPolicy], read in one transaction; never refused while artifacts are inactive. */
    suspend fun policy(actor: Principal): ArtifactPolicy
    /**
     * Reserves a draft id (refused `409 artifact_quota_exceeded` past the per-member count) or checks that the caller may
     * publish to [artifactId], then records an upload bound to the caller's credential for an hour. `missing` are the
     * announced blobs the artifact does not hold. Refused `429 artifact_rate_limited` past the hourly publications.
     */
    suspend fun startUpload(actor: Principal, artifactId: String?, files: List<UploadFile>, origin: ArtifactOrigin): ArtifactUpload
    /** Stores one chunk of an announced blob of the caller's live upload. */
    suspend fun putChunk(actor: Principal, uploadId: String, sha256: String, number: Int, bytes: ByteArray): ChunkReceipt
    /** The announced blob and its chunks in order, for the check before it goes to the bucket. */
    suspend fun assemble(actor: Principal, uploadId: String, sha256: String): Pair<UploadBlob, ByteArray>
    /** Records the blob as stored in the bucket for this upload and drops its chunks. */
    suspend fun recordBlob(actor: Principal, uploadId: String, sha256: String)
    /**
     * A blob put in the bucket that [uploadId] could not record (a failure, a cancelled request, or the upload expired
     * or removed meanwhile), so it never sits uncounted in the folder: while the upload is live, it is recorded for it,
     * and its expiry collects it unless a version uses it; otherwise it is marked for collection unless the artifact
     * holds it or another live upload announced it.
     */
    suspend fun abandonBlob(blob: UploadBlob, uploadId: String)
    /** Drops the chunks of a refused blob and records the refusal (`artifact.publish_refused`, code only). */
    suspend fun refuseBlob(actor: Principal, uploadId: String, sha256: String, code: String)
    /**
     * Commits a version under the team and artifact locks: a replay returns the earlier version; a stale base throws
     * [ArtifactConflictFailure]; every blob must come from this upload or already be in the artifact
     * (`409 artifact_blob_missing`); then the hourly rate and the byte quotas. Older versions beyond the retention are
     * pruned and the blobs no version uses any more are marked for collection.
     */
    suspend fun commit(actor: Principal, artifactId: String, plan: CommitPlan): Published
    suspend fun artifact(actor: Principal, artifactId: String): Artifact
    suspend fun list(actor: Principal, query: ArtifactQuery): ArtifactPage
    suspend fun versions(actor: Principal, artifactId: String): List<ArtifactVersion>
    /** A version with its files; an administrator's read of another member's personal artifact is audited. */
    suspend fun version(actor: Principal, artifactId: String, number: Int): ArtifactVersion
    suspend fun setAccess(actor: Principal, artifactId: String, access: ArtifactAccess, followLatest: Boolean, pinnedVersion: Int?): Artifact
    suspend fun members(actor: Principal, artifactId: String): ArtifactSharing
    /** [member] is a member id, a username or a linked GitHub login, among the team's active members. */
    suspend fun share(actor: Principal, artifactId: String, member: String, role: ArtifactMemberRole): ArtifactSharing
    suspend fun unshare(actor: Principal, artifactId: String, member: String): ArtifactSharing
    /** Marks the artifact deleted (404 at once); its folder is removed by [pendingDeletions] and [markStorageDeleted]. */
    suspend fun delete(actor: Principal, artifactId: String)

    // Serving (PR 3): no caller principal, the viewer is checked from what the ticket names.
    /** Whether artifacts are active for [teamId]: settings and storage read together. */
    suspend fun availability(teamId: String): ArtifactAvailability
    /**
     * The version a public link serves (the pinned one, or the current one), for anyone: null unless artifacts are
     * active, the artifact is active and public, and `publicSharing` is not `off`. Its relation is `public`.
     */
    suspend fun publicArtifact(artifactId: String): PublicArtifact?
    /**
     * Version [number] if a ticket for it still grants access, never audited: for a member ([memberId] with its login
     * session [tokenId]), the session is alive and interactive, the member enabled and ready, and still allowed to read
     * the artifact; for an anonymous ticket (both null), the artifact is still public with sharing on. Artifacts must
     * be active and the artifact not deleted. Null otherwise.
     */
    suspend fun viewable(artifactId: String, number: Int, memberId: String?, tokenId: String?): ViewableVersion?

    // Maintenance, run by one replica at a time.
    /** Drafts never committed after a day become deletions. Returns how many. */
    suspend fun expireDrafts(limit: Int): Int
    /** Artifacts past their retention become deletions, audited `artifact.retention_expired`. */
    suspend fun expireRetention(limit: Int): Int
    /** Expired uploads are dropped; the blobs they stored that nothing uses are marked for collection. */
    suspend fun expireUploads(limit: Int): Int
    suspend fun collectableBlobs(limit: Int): List<CollectedBlob>
    /** Forgets blobs removed from the bucket, if they are still marked for collection. */
    suspend fun forgetBlobs(blobs: List<CollectedBlob>)
    /** Moves blobs whose removal failed behind the others, so one failing bucket does not hold up the rest. */
    suspend fun deferBlobs(blobs: List<CollectedBlob>)
    /** Retries a folder removal later, behind the others. */
    suspend fun deferDeletion(target: StorageTarget)
    /** Deleted artifacts whose folder may still hold files, deleted long enough ago that no upload is still in flight. */
    suspend fun pendingDeletions(limit: Int): List<StorageTarget>
    /** Records the proof that the folder is empty: `storage_deleted_at`, the rows of its content, `artifact.storage_deleted`. */
    suspend fun markStorageDeleted(target: StorageTarget)
}

/**
 * Publication and reading of artifacts: chunked uploads checked before they reach the bucket (hash, size, secrets),
 * versions committed with optimistic concurrency, sharing with members, and deletion. Making public and deleting stay
 * interactive gestures; no key and no agent can do either.
 */
class ArtifactService(private val store: ArtifactStore, private val admin: ArtifactAdminStore, private val objects: ArtifactObjectStore) {
    /** Whether [actor] may publish now, for `teamcode run` and the runner before they offer the artifact tools to an agent. */
    suspend fun policy(actor: Principal): ArtifactPolicy {
        actor.requireArtifactRead()
        val policy = store.policy(actor)
        // A key that may only read gets the answer its publish would get: no tools offered that always fail.
        val writes = actor.serviceScopes?.let { ServiceScope.ARTIFACTS_WRITE in it } ?: true
        return if (policy.canPublish && !writes) ArtifactPolicy(false, "credential_scope") else policy
    }

    suspend fun startUpload(actor: Principal, artifactId: String?, files: List<UploadFile>, origin: ArtifactOrigin = ArtifactOrigin()): ArtifactUpload {
        actor.requireArtifactWrite()
        ArtifactRules.validateAnnouncement(files)
        artifactId?.let(::requireId)
        return store.startUpload(actor, artifactId, files, origin)
    }

    /**
     * Stores a chunk; at the last one, assembles the blob, checks its hash, size and content, and puts it in the
     * artifact's folder. A secret refuses the blob with [ArtifactSecretFailure] before anything is stored.
     */
    suspend fun putChunk(actor: Principal, uploadId: String, sha256: String, number: Int, bytes: ByteArray): ChunkReceipt {
        actor.requireArtifactWrite()
        requireId(uploadId); ArtifactRules.validateSha(sha256)
        if (bytes.size > ArtifactRules.CHUNK_BYTES) throw ArtifactRules.failure("artifact_chunk_too_large", "Send chunks of at most 1 MiB.", 413)
        val receipt = store.putChunk(actor, uploadId, sha256, number, bytes)
        if (!receipt.complete || receipt.stored) return receipt
        val (blob, content) = store.assemble(actor, uploadId, sha256)
        val kind = try { ArtifactRules.inspect(content, sha256, blob.size) }
            catch (error: TeamFailure) { store.refuseBlob(actor, uploadId, sha256, error.code); throw error }
        if (kind != null) {
            store.refuseBlob(actor, uploadId, sha256, "artifact_secret_detected")
            throw ArtifactSecretFailure(sha256, kind)
        }
        val connection = admin.connection(blob.teamId) ?: throw TeamFailure("artifact_storage_missing", 409, "No artifact storage is configured.")
        try { objects.put(connection, ArtifactKeys.blob(connection.prefix, blob.teamId, blob.artifactId, sha256), content) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw TeamFailure("artifact_storage_unavailable", 503, "The artifact storage did not accept the file. Send the last chunk again.", retryAfterSeconds = 5) }
        // The blob is in the bucket: if it is not recorded, whatever the reason (a cancelled request included), it is
        // marked for collection, without being cancelled itself, so it never sits uncounted in the folder.
        try { store.recordBlob(actor, uploadId, sha256) }
        catch (error: Throwable) {
            withContext(NonCancellable) { try { store.abandonBlob(blob, uploadId) } catch (_: Exception) { } }
            throw error
        }
        return receipt.copy(stored = true)
    }

    suspend fun commit(actor: Principal, artifactId: String, uploadId: String, entry: String, files: Map<String, UploadFile>, baseVersion: Int,
        title: String?, description: String?, icon: String?, access: String?, capabilities: Map<String, String>?): Published {
        actor.requireArtifactWrite()
        requireId(artifactId); requireId(uploadId)
        if (!capabilities.isNullOrEmpty()) throw ArtifactRules.failure("artifact_capabilities_unsupported", "Runtime capabilities are not supported yet; publish without them.")
        if (baseVersion < 0) throw ArtifactRules.failure("invalid_artifact_manifest", "The base version is 0 for a new artifact, or the version you started from.")
        val requested = access?.let { ArtifactAccess.parse(it) ?: throw ArtifactRules.failure("invalid_artifact_access", "Access is personal or team.") }
        // Making an artifact public is a person's gesture on the dashboard or with `teamcode artifacts access`, never a publication.
        if (requested == ArtifactAccess.PUBLIC) throw TeamFailure("artifact_public_interactive", 403, "A publication never makes an artifact public. Its owner does, on the dashboard or with teamcode artifacts access.")
        ArtifactRules.validateDetails(title, description, icon)
        val manifest = ArtifactRules.validateManifest(entry, files)
        val published = store.commit(actor, artifactId, CommitPlan(uploadId, entry, manifest, ArtifactRules.digest(manifest), baseVersion,
            title?.trim(), description?.trim(), icon, requested))
        if (!published.replayed) writeManifestCopy(published)
        return published
    }

    /**
     * A copy of the manifest next to the blobs, for disaster recovery and human reading. MySQL is the record: a failed
     * copy is not a failed publication.
     */
    private suspend fun writeManifestCopy(published: Published) {
        val artifact = published.artifact
        try {
            val connection = admin.connection(artifact.teamId) ?: return
            val version = published.version
            // Every value is validated (published paths, hex digests, known content types), so none needs escaping.
            val files = version.files.joinToString(",") { """{"path":"${it.path}","sha256":"${it.sha256}","size":${it.size},"contentType":"${it.contentType}"}""" }
            val json = """{"artifact":"${artifact.id}","version":${version.number},"entry":"${version.entry}","digest":"${version.digest}","createdAt":${version.createdAt},"files":[$files]}"""
            objects.put(connection, ArtifactKeys.manifest(connection.prefix, artifact.teamId, artifact.id, published.version.number), json.encodeToByteArray(), "application/json")
        } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }

    suspend fun artifact(actor: Principal, artifactId: String): Artifact { actor.requireArtifactRead(); requireId(artifactId); return store.artifact(actor, artifactId) }

    suspend fun list(actor: Principal, query: ArtifactQuery): ArtifactPage {
        actor.requireArtifactRead()
        if (query.limit !in 1..ArtifactRules.MAX_LIST) throw ArtifactRules.failure("invalid_artifact_query", "Use a limit of 1–${ArtifactRules.MAX_LIST}.")
        if (query.scope == ArtifactScope.ALL && !actor.canReadTeam) throw TeamFailure("forbidden", 403, "Only administrators list every artifact.")
        if (listOfNotNull(query.ownerId, query.sessionId, query.runId).any { !FILTER.matches(it) })
            throw ArtifactRules.failure("invalid_artifact_query", "Filter by a member, session or run id: up to 36 letters, digits and dashes.")
        if (query.originKind != null && query.originKind !in ORIGIN_KINDS) throw ArtifactRules.failure("invalid_artifact_query", "The origin is local_run, cloud_session or api.")
        return store.list(actor, query)
    }

    suspend fun versions(actor: Principal, artifactId: String): List<ArtifactVersion> { actor.requireArtifactRead(); requireId(artifactId); return store.versions(actor, artifactId) }

    suspend fun version(actor: Principal, artifactId: String, number: Int): ArtifactVersion {
        actor.requireArtifactRead(); requireId(artifactId)
        if (number < 1) throw TeamFailure("not_found", 404, "Version not found.")
        return store.version(actor, artifactId, number)
    }

    /** The bytes of one file of a version, read from the bucket. */
    suspend fun file(actor: Principal, artifactId: String, number: Int, path: String): Pair<ArtifactFile, ByteArray> {
        val version = version(actor, artifactId, number)
        val file = version.files.singleOrNull { it.path == path } ?: throw TeamFailure("not_found", 404, "This version has no such file.")
        val connection = admin.connection(actor.teamId) ?: throw TeamFailure("artifact_storage_missing", 409, "No artifact storage is configured.")
        val stored = try { objects.get(connection, ArtifactKeys.blob(connection.prefix, actor.teamId, artifactId, file.sha256)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw TeamFailure("artifact_storage_unavailable", 503, "The artifact storage did not answer. Try again.", retryAfterSeconds = 5) }
            ?: throw TeamFailure("artifact_storage_unavailable", 503, "The file is missing from the artifact storage.")
        return file to stored.bytes
    }

    /** Changing who can see an artifact is interactive: making it public follows `publicSharing`, and no key can. */
    suspend fun setAccess(actor: Principal, artifactId: String, access: String, followLatest: Boolean, pinnedVersion: Int?): Artifact {
        actor.requireInteractive(); actor.requireReady(); requireId(artifactId)
        val target = ArtifactAccess.parse(access) ?: throw ArtifactRules.failure("invalid_artifact_access", "Access is personal, team or public.")
        // Exactly one of the two: not following the latest version without naming one would silently follow it.
        if (followLatest == (pinnedVersion != null) || (pinnedVersion != null && pinnedVersion < 1))
            throw ArtifactRules.failure("invalid_artifact_access", "Either follow the latest version or pin one version.")
        return store.setAccess(actor, artifactId, target, followLatest, pinnedVersion)
    }

    suspend fun members(actor: Principal, artifactId: String): ArtifactSharing { actor.requireArtifactRead(); requireId(artifactId); return store.members(actor, artifactId) }

    /** Inviting a team member is internal to the team: the owner, an invited editor, or their agent may do it. */
    suspend fun share(actor: Principal, artifactId: String, member: String, role: String): ArtifactSharing {
        actor.requireArtifactWrite(); requireId(artifactId)
        val parsed = ArtifactMemberRole.parse(role) ?: throw ArtifactRules.failure("invalid_artifact_member", "The role is viewer or editor.")
        if (member.isBlank() || member.length > 64) throw ArtifactRules.failure("invalid_artifact_member", "Name a member by id, username or linked GitHub login.")
        return store.share(actor, artifactId, member.trim(), parsed)
    }

    suspend fun unshare(actor: Principal, artifactId: String, member: String): ArtifactSharing {
        actor.requireArtifactWrite(); requireId(artifactId)
        if (member.isBlank() || member.length > 64) throw ArtifactRules.failure("invalid_artifact_member", "Name a member by id, username or linked GitHub login.")
        return store.unshare(actor, artifactId, member.trim())
    }

    /** Irreversible: interactive only, by the owner or an administrator. */
    suspend fun delete(actor: Principal, artifactId: String) { actor.requireInteractive(); actor.requireReady(); requireId(artifactId); store.delete(actor, artifactId) }

    private fun requireId(id: String) { if (!ID.matches(id)) throw TeamFailure("not_found", 404, "Artifact not found.") }

    companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val FILTER = Regex("[A-Za-z0-9-]{1,36}")
        /** The origins an artifact records: a `teamcode run` launch, a cloud session, or a direct call. */
        val ORIGIN_KINDS = listOf("local_run", "cloud_session", "api")
    }
}

/**
 * The scheduled work of artifacts, run by one replica at a time every few minutes: drafts and retention, expired
 * uploads, blobs no version uses, and the proven removal of deleted artifacts' folders (deleted, listed again, empty).
 */
class ArtifactMaintenance(private val store: ArtifactStore, private val admin: ArtifactAdminStore, private val objects: ArtifactObjectStore) {
    /** Each step and each team on its own: a failure is retried at the next run, behind the work that succeeds. */
    suspend fun run(batch: Int = 100) {
        step { store.expireDrafts(batch) }
        step { store.expireRetention(batch) }
        step { store.expireUploads(batch) }
        step { collect(batch) }
        step { removeDeleted(batch / 10 + 1) }
    }

    private suspend fun step(work: suspend () -> Unit) {
        try { work() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }

    private suspend fun collect(limit: Int) {
        val blobs = store.collectableBlobs(limit)
        for ((teamId, team) in blobs.groupBy { it.teamId }) {
            val failed = try {
                val connection = admin.connection(teamId)
                if (connection == null) team else {
                    val keys = team.associateBy { ArtifactKeys.blob(connection.prefix, teamId, it.artifactId, it.sha256) }
                    val refused = objects.delete(connection, keys.keys.toList()).toSet()
                    store.forgetBlobs(keys.filterKeys { it !in refused }.values.toList())
                    keys.filterKeys { it in refused }.values.toList()
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { team }
            if (failed.isNotEmpty()) store.deferBlobs(failed)
        }
    }

    private suspend fun removeDeleted(limit: Int) {
        for (target in store.pendingDeletions(limit)) {
            val proven = try {
                val connection = admin.connection(target.teamId)
                if (connection == null) false else {
                    val root = ArtifactKeys.artifactRoot(connection.prefix, target.teamId, target.artifactId)
                    var token: String? = null
                    do {
                        val page = objects.list(connection, root, token)
                        if (page.keys.isNotEmpty()) objects.delete(connection, page.keys.map { it.key })
                        token = page.nextContinuationToken
                    } while (token != null)
                    // The proof: listed again after the deletion, and empty.
                    objects.list(connection, root, maxKeys = 1).keys.isEmpty()
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
            if (proven) store.markStorageDeleted(target) else store.deferDeletion(target)
        }
    }
}
