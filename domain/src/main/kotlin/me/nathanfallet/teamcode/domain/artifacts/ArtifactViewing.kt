package me.nathanfallet.teamcode.domain.artifacts

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.team.*

/**
 * What a view ticket grants: one version of one artifact, to one signed-in viewer (their member and login session) or,
 * with both null, to anyone while the artifact stays public. [expiresAt] is in epoch milliseconds.
 */
data class ViewTicket(val artifactId: String, val version: Int, val memberId: String?, val tokenId: String?, val expiresAt: Long) {
    val anonymous get() = memberId == null
}

/**
 * Signed view tickets, carried in the content path `/artifact/<id>/<ticket>/<path>`. The key is derived with HKDF-SHA256
 * from the server's credential key under the label `artifact-view`, so it is never the key that seals secrets.
 * A ticket is `<payload>.<mac>`, both base64url: the payload is `<version>:<member>:<token>:<expiry>`, the MAC an
 * HMAC-SHA256 over the artifact id and the payload. Only characters that are safe in a path segment are used.
 */
class ArtifactTickets(serverKey: ByteArray, private val clock: () -> Long = System::currentTimeMillis) {
    private val key = hkdf(serverKey, "teamcode artifact-view".encodeToByteArray())

    fun issue(ticket: ViewTicket): String {
        require(ticket.memberId == null == (ticket.tokenId == null))
        val payload = listOf(ticket.version.toString(), ticket.memberId.orEmpty(), ticket.tokenId.orEmpty(), ticket.expiresAt.toString()).joinToString(":")
        require(payload.count { it == ':' } == 3) { "Ticket fields never contain a colon." }
        return encoder.encodeToString(payload.encodeToByteArray()) + "." + encoder.encodeToString(mac(ticket.artifactId, payload))
    }

    /** The ticket's content when its MAC matches [artifactId] and it has not expired; null otherwise, whatever is wrong. */
    fun verify(artifactId: String, value: String): ViewTicket? {
        if (value.length > MAX_LENGTH || !TICKET.matches(value)) return null
        val (encoded, signature) = value.split('.').takeIf { it.size == 2 } ?: return null
        val decoded = try { decoder.decode(encoded) } catch (_: IllegalArgumentException) { return null }
        val presented = try { decoder.decode(signature) } catch (_: IllegalArgumentException) { return null }
        // Only the canonical encoding: a ticket has one spelling, so it is cached and compared as one string.
        if (encoder.encodeToString(decoded) != encoded || encoder.encodeToString(presented) != signature) return null
        val payload = decoded.decodeToString()
        if (!MessageDigest.isEqual(presented, mac(artifactId, payload))) return null
        val fields = payload.split(':').takeIf { it.size == 4 } ?: return null
        val version = fields[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val expires = fields[3].toLongOrNull() ?: return null
        if (expires <= clock()) return null
        val member = fields[1].ifEmpty { null }; val token = fields[2].ifEmpty { null }
        if (member == null != (token == null)) return null
        return ViewTicket(artifactId, version, member, token, expires)
    }

    private fun mac(artifactId: String, payload: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("$artifactId\n$payload".encodeToByteArray())

    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private val TICKET = Regex("[A-Za-z0-9_-]{4,400}\\.[A-Za-z0-9_-]{43}")
        private const val MAX_LENGTH = 450

        /** RFC 5869 with SHA-256, an all-zero salt and one block of output. */
        internal fun hkdf(input: ByteArray, info: ByteArray): ByteArray {
            require(input.size >= 32) { "The server key must hold at least 32 bytes." }
            fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
            val prk = hmac(ByteArray(32), input)
            return hmac(prk, info + byteArrayOf(1))
        }
    }
}

/** What a viewer may see right now: the artifact (as that viewer sees it) and one version with its files. */
data class ArtifactShell(val artifact: Artifact, val version: ArtifactVersion, val versions: List<ArtifactVersion>, val sharing: ArtifactSharing?, val ticket: String,
    /** Signed in and allowed to read the artifact as a member (not only through its public link). */
    val member: Boolean)

/** One file to send, or only its metadata when the viewer's cached copy is current. */
class ArtifactContent(val file: ArtifactFile, val bytes: ByteArray?, val totalSize: Long, val range: LongRange?, val anonymous: Boolean, val entry: Boolean)

/** A version a ticket may still show, with the team whose bucket holds it. */
data class ViewableVersion(val teamId: String, val version: ArtifactVersion)

/** An artifact's public link as anyone sees it: no viewer, never the owner's identity beyond what the page shows. */
data class PublicArtifact(val artifact: Artifact, val version: ArtifactVersion)

/** `416`: the requested range is outside the file of [size] bytes, or names several ranges. */
class ArtifactRangeFailure(val size: Long) : Exception("The requested range cannot be served.")

/** A single requested byte range, already checked against the file size. */
sealed interface ByteRange {
    data object Whole : ByteRange
    data class Part(val range: LongRange) : ByteRange
    data object Unsatisfiable : ByteRange
}

/**
 * Serving artifacts on the server's own host: the viewer page `/artifact/<id>` reads with the signed-in member's
 * rights (or the public link's), and mints a ticket for one version; content under `/artifact/<id>/<ticket>/` is
 * authorized by that ticket only. Every ticket is checked again against MySQL (login session alive, access still
 * granted, artifacts still active) at most [recheckMillis] after its previous check on this replica.
 */
class ArtifactViewing(
    private val artifacts: ArtifactService,
    private val store: ArtifactStore,
    private val admin: ArtifactAdminStore,
    private val objects: ArtifactObjectStore,
    private val tickets: ArtifactTickets,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recheckMillis: Long = 30_000,
    /** The deployment's team ([docs/groups.md]: one per server), whose availability an anonymous visitor meets. */
    private val serverTeam: String = "default",
) {
    /** Whether artifacts are active for [teamId], for the dashboard menu; never throws for a missing configuration. */
    suspend fun availability(teamId: String): ArtifactAvailability = store.availability(teamId)
    /** Whether artifacts are active for the server's team, as a visitor who is not signed in meets them. */
    suspend fun serverAvailability(): ArtifactAvailability = store.availability(serverTeam)

    /**
     * The viewer page of [artifactId] for [viewer] (null: not signed in). A member who can read it gets its versions
     * ([requested], or the current one) and, if they may manage it, its members; anyone gets a public artifact's served
     * version. Anything else is `404`, as if it did not exist.
     */
    suspend fun shell(viewer: Principal?, artifactId: String, requested: Int?): ArtifactShell {
        if (!ArtifactService.ID.matches(artifactId)) throw notFound()
        val now = clock()
        if (viewer != null && viewer.serviceScopes == null) {
            val readable = try { artifacts.artifact(viewer, artifactId) } catch (error: TeamFailure) {
                if (error.status != 404 && error.status != 409) throw error
                null
            }
            if (readable != null) {
                if (readable.currentVersion < 1) throw notFound()
                val number = requested ?: readable.currentVersion
                val version = artifacts.version(viewer, artifactId, number)
                val manager = readable.relation in setOf("owner", "editor", "admin") || viewer.member.role.isAdministrator
                val sharing = if (manager) artifacts.members(viewer, artifactId) else null
                // Every use is checked against the login session too, so signing out stops the content within the recheck.
                val ticket = tickets.issue(ViewTicket(artifactId, number, viewer.member.id, viewer.tokenId, now + MEMBER_TICKET_MILLIS))
                return ArtifactShell(readable, version, artifacts.versions(viewer, artifactId), sharing, ticket, member = true)
            }
        }
        val public = store.publicArtifact(artifactId) ?: throw notFound()
        if (requested != null && requested != public.version.number) throw notFound()
        val ticket = tickets.issue(ViewTicket(artifactId, public.version.number, null, null, now + PUBLIC_TICKET_MILLIS))
        return ArtifactShell(public.artifact, public.version, listOf(public.version), null, ticket, member = false)
    }

    /** The current version number for a signed-in member, so the viewer page can offer a newer one. */
    suspend fun latest(viewer: Principal, artifactId: String): Int = artifacts.artifact(viewer, artifactId).currentVersion

    /**
     * The file at [path] (the entry page when empty) of the ticket's version, with [range] applied. `404` for any
     * ticket that does not verify or no longer grants access, and for a path the version does not hold.
     * With [readBytes] false only the metadata is returned (a conditional request answered `304`).
     */
    suspend fun content(artifactId: String, ticketValue: String, path: String, range: String?, readBytes: Boolean = true): ArtifactContent {
        val ticket = tickets.verify(artifactId, ticketValue) ?: throw notFound()
        val granted = granted(ticketValue, ticket) ?: throw notFound()
        val file = (if (path.isEmpty()) granted.files.singleOrNull { it.path == granted.entry } else granted.files.singleOrNull { it.path == path }) ?: throw notFound()
        val entry = file.path == granted.entry
        // The entry page is rewritten (skeleton, Markdown), so it is always sent whole.
        val requested = if (entry) ByteRange.Whole else parseRange(range, file.size)
        if (requested == ByteRange.Unsatisfiable) throw ArtifactRangeFailure(file.size)
        val part = (requested as? ByteRange.Part)?.range
        if (!readBytes) return ArtifactContent(file, null, file.size, part, ticket.anonymous, entry)
        val teamId = granted.teamId
        val connection = admin.connection(teamId) ?: throw notFound()
        val stored = try { objects.get(connection, ArtifactKeys.blob(connection.prefix, teamId, artifactId, file.sha256), part) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw TeamFailure("artifact_storage_unavailable", 503, "The artifact storage did not answer. Reload the page.", retryAfterSeconds = 5) }
            ?: throw TeamFailure("artifact_storage_unavailable", 503, "The file is missing from the artifact storage.")
        val expected = part?.let { it.last - it.first + 1 } ?: file.size
        if (stored.bytes.size.toLong() != expected) throw TeamFailure("artifact_storage_unavailable", 503, "The artifact storage answered an unexpected size.")
        return ArtifactContent(file, stored.bytes, file.size, part, ticket.anonymous, entry)
    }

    private class Grant(val teamId: String, val entry: String, val files: List<ArtifactFile>, val checkedAt: Long)
    private val grants = object : LinkedHashMap<String, Grant>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Grant>) = size > MAX_CACHED_TICKETS
    }

    /** The version a ticket shows if its viewer may still see it, checked against MySQL at most every [recheckMillis]. */
    private suspend fun granted(value: String, ticket: ViewTicket): Grant? {
        val now = clock()
        synchronized(grants) { grants[value] }?.takeIf { now - it.checkedAt < recheckMillis }?.let { return it }
        val viewable = store.viewable(ticket.artifactId, ticket.version, ticket.memberId, ticket.tokenId)
        if (viewable == null) { synchronized(grants) { grants.remove(value) }; return null }
        val grant = Grant(viewable.teamId, viewable.version.entry, viewable.version.files, now)
        synchronized(grants) { grants[value] = grant }
        return grant
    }

    private fun notFound() = TeamFailure("not_found", 404, "Artifact not found.")

    companion object {
        /** A member's ticket lives as long as a dashboard session at most (8 hours), and never beyond the session itself. */
        const val MEMBER_TICKET_MILLIS = 8 * 3_600_000L
        const val PUBLIC_TICKET_MILLIS = 3_600_000L
        private const val MAX_CACHED_TICKETS = 10_000

        /**
         * One `bytes=a-b`, `bytes=a-` or `bytes=-n` range. Several ranges are refused (`416`, multipart is not
         * served); a header that is not a byte range is ignored, as HTTP allows.
         */
        fun parseRange(header: String?, size: Long): ByteRange {
            if (header == null) return ByteRange.Whole
            val spec = header.trim()
            if (!spec.startsWith("bytes=")) return ByteRange.Whole
            val value = spec.removePrefix("bytes=").trim()
            if (',' in value) return ByteRange.Unsatisfiable
            val match = Regex("(\\d{0,19})-(\\d{0,19})").matchEntire(value) ?: return ByteRange.Whole
            val (first, last) = match.destructured
            if (first.isEmpty() && last.isEmpty()) return ByteRange.Whole
            if (size == 0L) return ByteRange.Unsatisfiable
            val range = if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return ByteRange.Whole
                if (suffix == 0L) return ByteRange.Unsatisfiable
                maxOf(0, size - suffix)..(size - 1)
            } else {
                val start = first.toLongOrNull() ?: return ByteRange.Whole
                val end = if (last.isEmpty()) Long.MAX_VALUE else (last.toLongOrNull() ?: return ByteRange.Whole)
                if (end < start) return ByteRange.Whole // Not a valid range: ignored.
                if (start >= size) return ByteRange.Unsatisfiable
                start..minOf(end, size - 1)
            }
            return if (range.first == 0L && range.last == size - 1) ByteRange.Whole else ByteRange.Part(range)
        }
    }
}
