package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Links a `tcex_` inference credential (a [Tokens] row limited to inference, for the execution's owner) to the
 * cloud execution it was issued to. Deleting the token deletes the link; the session store deletes the token when
 * the execution ends, a later epoch starts or the owner changes.
 */
object CloudInferenceCredentials : Table("cloud_inference_credentials") {
    val tokenId = reference("token_id", Tokens.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_inference_credentials_token")
    val sessionId = reference("session_id", CloudSessions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "cloud_inference_credentials_session")
    val epoch = integer("epoch")
    val teamId = varchar("team_id", 36)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(tokenId)
    init {
        index("cloud_inference_execution", false, sessionId, epoch)
        index("cloud_inference_expiry", false, expiresAt)
    }
}
