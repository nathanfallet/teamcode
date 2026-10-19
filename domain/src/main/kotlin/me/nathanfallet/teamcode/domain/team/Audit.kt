package me.nathanfallet.teamcode.domain.team

/** Metadata only. No credential material, request bodies or provider responses belong in this journal. */
data class AuditEvent(
    val id: Long, val actorId: String, val action: String, val subjectId: String, val occurredAt: Long,
    /** Set for delegated cloud actions: the member the action applied to and the initiating credential id. */
    val effectiveMemberId: String? = null, val credentialId: String? = null,
)
data class AuditPage(val events: List<AuditEvent>, val nextBeforeId: Long?)

interface AuditService {
    suspend fun list(actor: Principal, beforeId: Long? = null): AuditPage
    companion object {
        const val PAGE_SIZE = 50
        fun validateCursor(beforeId: Long?) {
            if (beforeId != null && beforeId <= 0) throw TeamFailure("invalid_cursor", 400, "Use a positive audit event identifier.")
        }
    }
}
