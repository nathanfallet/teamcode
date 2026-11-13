package me.nathanfallet.teamcode.domain.memory

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.requireMemory

/** Why two entries are queued for a person: identical text that curation does not decide alone, or one title with two different texts. */
enum class ReviewKind(val wire: String) { DUPLICATE("duplicate"), SAME_TITLE("same_title") }

/** A pair of entries curation did not decide by itself: [olderId] would be superseded by [newerId] if the reviewer applies it. */
data class MemoryReview(
    val id: String, val scope: MemoryScope, val ownerId: String?, val kind: ReviewKind, val reason: String,
    val olderId: String, val olderTitle: String, val olderOrigin: MemoryOrigin, val newerId: String, val newerTitle: String, val newerOrigin: MemoryOrigin, val createdAt: Long,
)

/** An entry curation replaced by [supersededById]; it is kept, and restorable, until [MemoryRules.SUPERSEDED_DAYS] after [supersededAt]. */
data class SupersededMemory(val id: String, val scope: MemoryScope, val ownerId: String?, val title: String, val summary: String, val authorId: String, val supersededById: String?, val supersededAt: Long)

data class CurationSettings(val enabled: Boolean, val lastRunAt: Long? = null, val lastRunId: String? = null, val lastSuperseded: Int = 0, val lastQueued: Int = 0)

data class CurationReport(val scopes: Int, val superseded: Int, val queued: Int, val expired: Int)

/**
 * Persistence port of the curation (docs/memory.md, "Curation"). Visibility is the same as the memory's: a personal entry, its reviews and
 * its superseded copies are their owner's alone, no administrator included; a team entry's are the administrators' (and the author's for restoring).
 */
interface MemoryCurationStore {
    suspend fun settings(actor: Principal): CurationSettings
    suspend fun setEnabled(actor: Principal, enabled: Boolean): CurationSettings
    /** Open reviews: the caller's own personal ones, and the team's for an administrator; oldest first. */
    suspend fun reviews(actor: Principal, limit: Int): List<MemoryReview>
    /** Applies (the older entry is superseded by the newer one) or dismisses (both stay; never queued again) a review. */
    suspend fun resolve(actor: Principal, id: String, apply: Boolean): MemoryReview
    suspend fun superseded(actor: Principal, limit: Int): List<SupersededMemory>
    /** Brings a superseded entry back; the entry that replaced it, if still active, becomes the superseded one. Audited `memory.restored`. */
    suspend fun restore(actor: Principal, id: String): MemoryEntry
    /**
     * One run for every team whose administrator turned the curation on: at most [maxScopes] scopes (a team's, or one member's personal entries) that changed
     * since their last pass or were last curated over [MemoryRules.RECURATE_DAYS] ago; then purges what is past its retention. Never calls a model.
     */
    suspend fun curate(runId: String, maxScopes: Int): CurationReport
}

class MemoryCurationService(private val store: MemoryCurationStore) {
    private fun interactive(actor: Principal): Principal {
        actor.requireMemory()
        if (actor.serviceScopes != null) throw TeamFailure("memory_team_forbidden", 403, "Curation is handled by a signed-in member.")
        return actor
    }
    private fun administrator(actor: Principal): Principal {
        interactive(actor)
        if (!actor.member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Only an administrator changes the curation.")
        return actor
    }

    suspend fun settings(actor: Principal) = store.settings(administrator(actor))
    suspend fun setEnabled(actor: Principal, enabled: Boolean) = store.setEnabled(administrator(actor), enabled)
    suspend fun reviews(actor: Principal) = store.reviews(interactive(actor), REVIEW_PAGE)
    suspend fun resolve(actor: Principal, id: String, apply: Boolean) = store.resolve(interactive(actor), id, apply)
    suspend fun superseded(actor: Principal) = store.superseded(interactive(actor), REVIEW_PAGE)
    suspend fun restore(actor: Principal, id: String) = store.restore(interactive(actor), id)

    /** The server's periodic work: one run, identified by [runId] in the audit trail of everything it changes. */
    suspend fun run(runId: String): CurationReport = store.curate(runId, MAX_SCOPES)

    private companion object { const val REVIEW_PAGE = 100; const val MAX_SCOPES = 20 }
}
