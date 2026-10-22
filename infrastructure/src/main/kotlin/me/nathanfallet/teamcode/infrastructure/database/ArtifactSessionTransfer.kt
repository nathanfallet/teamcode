package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** At most this many artifacts of one session are shared at a transfer; a session publishes far fewer. */
internal const val MAX_TRANSFERRED_ARTIFACTS = 500

/**
 * In the transaction that moves cloud session [sessionId] to [recipientId]: every artifact created or updated in the
 * session (published, owned by the session's former owner) gets the recipient as an `editor` with reason `session_transfer`, so the
 * recipient's agent can read and republish it. The artifact keeps its owner. An existing viewer invitation becomes
 * editor; an editor stays as is. Three indexed reads and at most three batched writes, all bounded.
 */
internal fun JdbcTransaction.grantSessionArtifacts(teamId: String, sessionId: String, formerOwnerId: String, recipientId: String, actorId: String, now: Long) {
    // Published artifacts only: a draft has no version to read and is not listed anywhere.
    // The former owner's own artifacts only: a version the session published into a third member's artifact (as their
    // invited editor) never hands that artifact to someone its owner did not choose.
    // ...but what the former owner received from this same session at an earlier transfer is passed on (A -> B -> C).
    val inherited = ArtifactMembers.select(ArtifactMembers.artifactId).where {
        (ArtifactMembers.memberId eq formerOwnerId) and (ArtifactMembers.reason eq "session_transfer") and (ArtifactMembers.originSessionId eq sessionId)
    }
    val live = (Artifacts.teamId eq teamId) and (Artifacts.state eq "active") and (Artifacts.ownerId neq recipientId) and
        ((Artifacts.ownerId eq formerOwnerId) or (Artifacts.id inSubQuery inherited))
    val created = Artifacts.select(Artifacts.id, Artifacts.ownerId).where { (Artifacts.originSessionId eq sessionId) and live }
        .limit(MAX_TRANSFERRED_ARTIFACTS).associate { it[Artifacts.id] to it[Artifacts.ownerId] }
    val updated = (ArtifactVersions innerJoin Artifacts).select(Artifacts.id, Artifacts.ownerId).where { (ArtifactVersions.originSessionId eq sessionId) and live }
        .withDistinct().limit(MAX_TRANSFERRED_ARTIFACTS).associate { it[Artifacts.id] to it[Artifacts.ownerId] }
    val artifacts = (created + updated).entries.take(MAX_TRANSFERRED_ARTIFACTS).associate { it.key to it.value }
    if (artifacts.isEmpty()) return
    val existing = ArtifactMembers.select(ArtifactMembers.artifactId, ArtifactMembers.role)
        .where { (ArtifactMembers.memberId eq recipientId) and (ArtifactMembers.artifactId inList artifacts.keys) }
        .associate { it[ArtifactMembers.artifactId] to it[ArtifactMembers.role] }
    // Three batched writes whatever the number of artifacts: the new invitations, the upgraded ones, their audit events.
    val granted = artifacts.filterKeys { existing[it] != "editor" }
    if (granted.isEmpty()) return
    val fresh = granted.keys.filter { existing[it] == null }
    if (fresh.isNotEmpty()) ArtifactMembers.batchInsert(fresh, shouldReturnGeneratedValues = false) { artifactId ->
        this[ArtifactMembers.artifactId] = artifactId; this[ArtifactMembers.memberId] = recipientId; this[ArtifactMembers.role] = "editor"
        this[ArtifactMembers.reason] = "session_transfer"; this[ArtifactMembers.addedBy] = actorId; this[ArtifactMembers.originSessionId] = sessionId; this[ArtifactMembers.addedAt] = now
    }
    val upgraded = granted.keys - fresh.toSet()
    // An invitation keeps its provenance (who invited, why): only its role changes, so it is never passed on later.
    if (upgraded.isNotEmpty()) ArtifactMembers.update({ (ArtifactMembers.memberId eq recipientId) and (ArtifactMembers.artifactId inList upgraded) }) {
        it[role] = "editor"
    }
    AuditEvents.batchInsert(granted.entries, shouldReturnGeneratedValues = false) { (artifactId, ownerId) ->
        this[AuditEvents.teamId] = teamId; this[AuditEvents.actorId] = actorId; this[AuditEvents.action] = "artifact.member_added"
        this[AuditEvents.subjectId] = "$artifactId:editor:session_transfer"; this[AuditEvents.occurredAt] = now
        this[AuditEvents.effectiveMemberId] = ownerId; this[AuditEvents.relatedMemberId] = recipientId
    }
}
