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
 * editor; an editor stays as is. Three indexed reads and one insert or update per artifact, all bounded.
 */
internal fun JdbcTransaction.grantSessionArtifacts(teamId: String, sessionId: String, formerOwnerId: String, recipientId: String, actorId: String, now: Long) {
    // Published artifacts only: a draft has no version to read and is not listed anywhere.
    // The former owner's own artifacts only: a version the session published into a third member's artifact (as their
    // invited editor) never hands that artifact to someone its owner did not choose.
    val live = (Artifacts.teamId eq teamId) and (Artifacts.state eq "active") and (Artifacts.ownerId eq formerOwnerId) and (Artifacts.ownerId neq recipientId)
    val created = Artifacts.select(Artifacts.id, Artifacts.ownerId).where { (Artifacts.originSessionId eq sessionId) and live }
        .limit(MAX_TRANSFERRED_ARTIFACTS).associate { it[Artifacts.id] to it[Artifacts.ownerId] }
    val updated = (ArtifactVersions innerJoin Artifacts).select(Artifacts.id, Artifacts.ownerId).where { (ArtifactVersions.originSessionId eq sessionId) and live }
        .withDistinct().limit(MAX_TRANSFERRED_ARTIFACTS).associate { it[Artifacts.id] to it[Artifacts.ownerId] }
    val artifacts = (created + updated).entries.take(MAX_TRANSFERRED_ARTIFACTS).associate { it.key to it.value }
    if (artifacts.isEmpty()) return
    val existing = ArtifactMembers.select(ArtifactMembers.artifactId, ArtifactMembers.role)
        .where { (ArtifactMembers.memberId eq recipientId) and (ArtifactMembers.artifactId inList artifacts.keys) }
        .associate { it[ArtifactMembers.artifactId] to it[ArtifactMembers.role] }
    for ((artifactId, ownerId) in artifacts) {
        when (existing[artifactId]) {
            "editor" -> continue
            null -> ArtifactMembers.insert {
                it[ArtifactMembers.artifactId] = artifactId; it[memberId] = recipientId; it[role] = "editor"; it[reason] = "session_transfer"
                it[addedBy] = actorId; it[originSessionId] = sessionId; it[addedAt] = now
            }
            else -> ArtifactMembers.update({ (ArtifactMembers.artifactId eq artifactId) and (ArtifactMembers.memberId eq recipientId) }) {
                it[role] = "editor"; it[reason] = "session_transfer"; it[addedBy] = actorId; it[originSessionId] = sessionId; it[addedAt] = now
            }
        }
        audit(teamId, actorId, "artifact.member_added", "$artifactId:editor:session_transfer", ownerId, null, recipientId)
    }
}
