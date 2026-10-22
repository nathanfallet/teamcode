package me.nathanfallet.teamcode.domain.artifacts

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.team.*

/** `x-amz-server-side-encryption` sent with every object written; R2 ignores it. */
enum class ServerSideEncryption(val wire: String) {
    NONE("none"), AES256("AES256");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** Who may publish artifacts once they are on. */
enum class WhoCanPublish(val wire: String) {
    EVERYONE("everyone"), ADMINS("admins");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** Who may make an artifact public: its owner (the default), administrators only, or nobody (every public link stops). */
enum class PublicSharing(val wire: String) {
    OWNERS("owners_of_artifacts"), ADMINS("admins"), OFF("off");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** The team's S3-compatible bucket. The secret access key is never part of it: it stays sealed in persistence. */
data class ArtifactStorage(
    val teamId: String, val endpoint: String, val region: String, val bucket: String, val prefix: String, val pathStyle: Boolean,
    val accessKeyId: String, val serverSideEncryption: ServerSideEncryption,
    /** The last successful connection test of this [version]; null after any change. */
    val verifiedAt: Long?, val version: Int, val updatedAt: Long,
)

/** What an administrator saves. A null [secretAccessKey] keeps the stored one (refused when the endpoint or bucket changes). */
class ArtifactStorageInput(
    val endpoint: String, val region: String, val bucket: String, val prefix: String, val pathStyle: Boolean,
    val accessKeyId: String, val secretAccessKey: String?, val serverSideEncryption: ServerSideEncryption,
) {
    override fun toString() = "ArtifactStorageInput(endpoint=$endpoint, bucket=$bucket, prefix=$prefix, secretAccessKey=[REDACTED])"
}

/** A decrypted connection, for the object store adapter only. Never logged or serialized. */
class ArtifactStorageConnection(
    val endpoint: String, val region: String, val bucket: String, val prefix: String, val pathStyle: Boolean,
    val accessKeyId: String, val secretAccessKey: String, val serverSideEncryption: ServerSideEncryption,
) {
    override fun toString() = "ArtifactStorageConnection(endpoint=$endpoint, bucket=$bucket, secretAccessKey=[REDACTED])"
}

data class ArtifactQuotas(
    val memberBytes: Long = 2L * ArtifactAdministrationService.GIB, val teamBytes: Long = 50L * ArtifactAdministrationService.GIB,
    val artifactsPerMember: Int = 500, val publishesPerHour: Int = 60,
)

/** Retention counts from an artifact's last publication; a change applies to artifacts published afterwards. */
data class ArtifactRetention(val personalDays: Int? = null, val sharedDays: Int? = null, val maxVersionsPerArtifact: Int = 50)

data class ArtifactSettings(
    val enabled: Boolean = false,
    val whoCanPublish: WhoCanPublish = WhoCanPublish.EVERYONE,
    val publicSharing: PublicSharing = PublicSharing.OWNERS,
    val quotas: ArtifactQuotas = ArtifactQuotas(),
    val retention: ArtifactRetention = ArtifactRetention(),
    /** 0 until saved once; a save names the version it replaces. */
    val version: Int = 0,
    val updatedAt: Long? = null,
)

/**
 * Whether artifacts work right now: switched on, with a stored bucket whose current version passed a connection test.
 * Saving the bucket again, a failed test or removing it turns them off until a test passes. [reason] says why not.
 */
data class ArtifactAvailability(val active: Boolean, val reason: String?) {
    companion object {
        fun of(settings: ArtifactSettings, storage: ArtifactStorage?) = when {
            storage == null -> ArtifactAvailability(false, "artifact_storage_missing")
            storage.verifiedAt == null -> ArtifactAvailability(false, "artifact_storage_unverified")
            !settings.enabled -> ArtifactAvailability(false, "artifacts_disabled")
            else -> ArtifactAvailability(true, null)
        }
    }
}

/** One step of a connection test. [code] is the S3 error code (`AccessDenied`, `NoSuchBucket`, ...) or a short local code, never a response body. */
data class StorageTestStep(val step: String, val ok: Boolean, val status: Int? = null, val code: String? = null)
data class StorageTestResult(val ok: Boolean, val steps: List<StorageTestStep>)

/** What an object store call returns when the bucket answers with an error. The body is never kept, only its `<Code>`. */
class ObjectStoreException(val status: Int?, val code: String) : Exception("The object store answered ${status ?: "nothing"} ($code).")

class StoredObject(val bytes: ByteArray, val size: Long, val contentRange: String?, val etag: String?)
data class ObjectHead(val size: Long, val etag: String?)
data class ObjectListing(val keys: List<ListedObject>, val nextContinuationToken: String?)
data class ListedObject(val key: String, val size: Long)

/**
 * The five operations artifacts need from an S3-compatible bucket. Keys are full object keys (prefix included). A
 * missing object is null, never an exception. An implementation refuses endpoints whose host resolves to an address
 * [CloudAdministrationService.allowedResolvedAddress] refuses.
 */
interface ArtifactObjectStore {
    suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String = "application/octet-stream")
    /** [range] is an inclusive byte range; the answer is then `206` with its `Content-Range`. [maxBytes] bounds what is read. */
    suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange? = null, maxBytes: Int = MAX_OBJECT_READ): StoredObject?
    suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead?
    suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String? = null, maxKeys: Int = 1000): ObjectListing
    /** Up to 1000 keys; returns the keys the bucket could not delete. A missing key counts as deleted. */
    suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String>

    companion object { const val MAX_OBJECT_READ = 17 * 1024 * 1024 }
}

interface ArtifactAdminStore {
    /** The stored bucket, without its secret. Revalidates [actor]. */
    suspend fun storage(actor: Principal): ArtifactStorage?
    /**
     * Saves under the team lock, `409 artifact_storage_conflict` unless [expectedVersion] is current (0 for the first
     * save). Refuses `409 artifact_storage_in_use` when the endpoint, bucket or prefix changes while artifacts exist,
     * and `400 artifact_storage_secret_required` when the endpoint or bucket changes without a new secret. Clears
     * `verifiedAt`.
     */
    suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage
    /** Whether the team has any artifact, in any state, so the bucket cannot move under it. */
    suspend fun inUse(actor: Principal): Boolean
    /**
     * Removes the bucket configuration and its secret; `409 artifact_storage_in_use` while artifacts exist,
     * `artifact_storage_missing` when there is none. Artifacts are then off.
     */
    suspend fun deleteStorage(actor: Principal)
    /**
     * Records a test of storage [version] while it is still current: a pass sets `verifiedAt`, a failure clears it
     * (so artifacts stop until a test passes again). Audited either way.
     */
    suspend fun recordTest(actor: Principal, version: Int, passed: Boolean): ArtifactStorage?
    suspend fun settings(actor: Principal): ArtifactSettings
    /**
     * `409 artifact_settings_conflict` unless `settings.version` is current. Turning artifacts on is refused with
     * `409 artifact_storage_missing` without a stored bucket and `409 artifact_storage_unverified` without a passed
     * test of its current version.
     */
    suspend fun saveSettings(actor: Principal, settings: ArtifactSettings): ArtifactSettings
    /** The decrypted connection for the object store, for the server's own use only. */
    suspend fun connection(teamId: String): ArtifactStorageConnection?
}

/** Administration of the artifact bucket and settings: interactive administrators only, on the dashboard or the API. */
class ArtifactAdministrationService(
    private val store: ArtifactAdminStore,
    private val objects: ArtifactObjectStore,
    private val probeId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun storage(actor: Principal): ArtifactStorage? { actor.requireAdmin(); return store.storage(actor) }
    suspend fun inUse(actor: Principal): Boolean { actor.requireAdmin(); return store.inUse(actor) }

    suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage {
        actor.requireAdmin()
        validate(input)
        return store.saveStorage(actor, expectedVersion, input)
    }

    suspend fun deleteStorage(actor: Principal) { actor.requireAdmin(); store.deleteStorage(actor) }

    suspend fun settings(actor: Principal): ArtifactSettings { actor.requireAdmin(); return store.settings(actor) }

    /** For administrators: whether artifacts are active and why not. Members learn it from the publication policy (later). */
    suspend fun availability(actor: Principal): ArtifactAvailability { actor.requireAdmin(); return ArtifactAvailability.of(store.settings(actor), store.storage(actor)) }

    suspend fun saveSettings(actor: Principal, settings: ArtifactSettings): ArtifactSettings {
        actor.requireAdmin()
        validate(settings)
        return store.saveSettings(actor, settings)
    }

    /**
     * Writes, reads back (SHA-256 compared), lists and deletes one probe object `<prefix>/<team>/.teamcode-probe-<uuid>`.
     * Every step is reported with its HTTP status and S3 error code only. A failed write stops the test; the delete is
     * attempted whenever the write succeeded.
     */
    suspend fun testStorage(actor: Principal): StorageTestResult {
        actor.requireAdmin()
        val stored = store.storage(actor) ?: throw TeamFailure("artifact_storage_missing", 404, "Save the artifact storage before testing it.")
        val connection = store.connection(actor.teamId) ?: throw TeamFailure("artifact_storage_missing", 404, "Save the artifact storage before testing it.")
        val key = ArtifactKeys.teamRoot(connection.prefix, actor.teamId) + ".teamcode-probe-" + probeId()
        val body = "TeamCode artifact storage probe $key".encodeToByteArray()
        val steps = mutableListOf<StorageTestStep>()
        suspend fun step(name: String, block: suspend () -> String?): Boolean {
            val failure = try { block() }
                catch (error: CancellationException) { throw error }
                catch (error: ObjectStoreException) { steps += StorageTestStep(name, false, error.status, error.code); return false }
                catch (_: Exception) { steps += StorageTestStep(name, false, null, "unreachable"); return false }
            steps += if (failure == null) StorageTestStep(name, true) else StorageTestStep(name, false, null, failure)
            return failure == null
        }
        if (step("put") { objects.put(connection, key, body, "text/plain"); null }) {
            step("get") {
                val read = objects.get(connection, key) ?: return@step "missing"
                if (MessageDigest.isEqual(sha256(read.bytes), sha256(body))) null else "digest_mismatch"
            }
            step("list") { if (objects.list(connection, key, maxKeys = 10).keys.any { it.key == key }) null else "not_listed" }
            step("delete") {
                if (objects.delete(connection, listOf(key)).isNotEmpty()) "not_deleted"
                else if (objects.head(connection, key) != null) "still_present" else null
            }
        }
        val passed = steps.size == 4 && steps.all { it.ok }
        store.recordTest(actor, stored.version, passed)
        return StorageTestResult(passed, steps)
    }

    companion object {
        const val GIB = 1024L * 1024 * 1024
        private val region = Regex("[a-z0-9-]{1,64}")
        private val bucket = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        private val prefixSegment = Regex("[a-z0-9._-]{1,64}")
        private val accessKey = Regex("[A-Za-z0-9+/=._-]{3,256}")

        fun validate(input: ArtifactStorageInput) {
            if (!validEndpoint(input.endpoint)) throw TeamFailure("invalid_artifact_storage", 400, "Use an https endpoint without a path, credentials, query or fragment, such as https://s3.gra.io.cloud.ovh.net.")
            if (!CloudAdministrationService.allowedApiHost(input.endpoint)) throw TeamFailure("artifact_storage_host_not_allowed", 400, "The endpoint must not point at a link-local or cloud metadata address.")
            if (!region.matches(input.region)) throw TeamFailure("invalid_artifact_storage", 400, "Use a region of lowercase letters, digits and dashes, such as us-east-1, gra or auto.")
            if (!bucket.matches(input.bucket) || ".." in input.bucket) throw TeamFailure("invalid_artifact_storage", 400, "Use a bucket name of 3–63 lowercase letters, digits, dots and dashes.")
            if (!validPrefix(input.prefix)) throw TeamFailure("invalid_artifact_storage", 400, "Use a prefix of up to 8 segments of lowercase letters, digits, dots, underscores and dashes separated by slashes, without . or .. segments, or leave it empty.")
            if (!accessKey.matches(input.accessKeyId)) throw TeamFailure("invalid_artifact_storage", 400, "Paste the access key id without spaces.")
            input.secretAccessKey?.let { if (it.length !in 8..512 || it.any { c -> c.isWhitespace() || c.isISOControl() }) throw TeamFailure("invalid_artifact_storage", 400, "Paste the secret access key without spaces.") }
        }

        /** `https://host[:port]`, with an optional trailing slash and nothing else. */
        fun validEndpoint(endpoint: String): Boolean {
            if (!endpoint.startsWith("https://") || endpoint.length > 512 || endpoint.any { it.isWhitespace() || it.isISOControl() }) return false
            val authority = endpoint.removePrefix("https://").removeSuffix("/")
            return authority.isNotEmpty() && authority.none { it in "/?#@\\" }
        }

        fun validPrefix(prefix: String): Boolean {
            if (prefix.isEmpty()) return true
            if (prefix.length > 200) return false
            val segments = prefix.split('/')
            return segments.size <= 8 && segments.all { prefixSegment.matches(it) && it != "." && it != ".." }
        }

        fun validate(settings: ArtifactSettings) {
            val q = settings.quotas
            val r = settings.retention
            if (q.memberBytes !in MIB..MAX_BYTES || q.teamBytes !in MIB..MAX_BYTES || q.artifactsPerMember !in 1..100_000 || q.publishesPerHour !in 1..10_000)
                throw TeamFailure("invalid_artifact_settings", 400, "Use quotas of 1 MiB–100 TiB per member and per team, 1–100000 artifacts per member and 1–10000 publications per hour.")
            if ((r.personalDays != null && r.personalDays !in 1..3650) || (r.sharedDays != null && r.sharedDays !in 1..3650) || r.maxVersionsPerArtifact !in 1..1000)
                throw TeamFailure("invalid_artifact_settings", 400, "Use retentions of 1–3650 days (or indefinite) and 1–1000 versions per artifact.")
        }

        private const val MIB = 1024L * 1024
        private const val MAX_BYTES = 100L * 1024 * GIB
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** The bucket layout: one folder per artifact, content-addressed inside it. */
object ArtifactKeys {
    /** `<prefix>/<team>/`, or `<team>/` without a prefix. */
    fun teamRoot(prefix: String, teamId: String) = (if (prefix.isEmpty()) "" else "$prefix/") + "$teamId/"
    fun artifactRoot(prefix: String, teamId: String, artifactId: String) = teamRoot(prefix, teamId) + "artifacts/$artifactId/"
    fun blob(prefix: String, teamId: String, artifactId: String, sha256: String) = artifactRoot(prefix, teamId, artifactId) + "blobs/$sha256"
    fun manifest(prefix: String, teamId: String, artifactId: String, version: Int) = artifactRoot(prefix, teamId, artifactId) + "versions/$version.json"
}
