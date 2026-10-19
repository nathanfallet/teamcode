package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class AuditEventView(
    val id: Long, val actorId: String, val action: String, val subjectId: String, val occurredAt: Long,
    val effectiveMemberId: String? = null, val credentialId: String? = null,
    /** Cloud transfers: the other member concerned (the former owner when a session changed owner). */
    val relatedMemberId: String? = null,
)
@Serializable
data class AuditPageView(val events: List<AuditEventView>, val nextBeforeId: Long? = null)
