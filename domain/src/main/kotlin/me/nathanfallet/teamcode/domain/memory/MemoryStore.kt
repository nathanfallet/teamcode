package me.nathanfallet.teamcode.domain.memory

import me.nathanfallet.teamcode.domain.team.Principal

/**
 * Persistence port for the memory. Implementations revalidate the actor inside the transaction, keep every personal entry
 * invisible to anyone but its owner (in every query, not only in the routes) and append an audit event with each write that
 * carries identifiers only, never a title or content.
 */
interface MemoryStore {
    /** The caller's active entries: the team's and the caller's own personal ones, newest first, [tags] (any of them) and [scope] filter. */
    suspend fun index(actor: Principal, scope: MemoryScope?, tags: List<String>, after: MemoryCursor?, limit: Int): MemoryPage
    /** Entries whose title, summary or tags contain every term, same visibility as [index]. */
    suspend fun search(actor: Principal, terms: List<String>, tags: List<String>, limit: Int): List<MemoryIndexEntry>
    /** One visible entry, counted as a read. */
    suspend fun read(actor: Principal, id: String): MemoryEntry
    suspend fun create(actor: Principal, entry: NewMemory, origin: MemoryOrigin, sessionId: String?, status: MemoryStatus = MemoryStatus.ACTIVE): MemoryEntry
    /** Remembers the wait that asks the owner about a proposal (the wait is opened after the proposal is stored). */
    suspend fun attachProposalWait(id: String, waitId: String)
    /** The team's proposals still waiting: every member's for an interactive administrator, the caller's own otherwise, oldest first. */
    suspend fun proposals(actor: Principal, limit: Int): List<MemoryEntry>
    /** An interactive administrator (or the author, for a rejection) accepts or rejects a waiting proposal and closes its wait; `409 memory_not_proposed` otherwise. */
    suspend fun decide(actor: Principal, id: String, accept: Boolean): MemoryEntry
    /**
     * Applies what happened to the proposals since: an answered or cancelled wait accepts (with the owner's edits when valid) or rejects, a proposal
     * older than [MemoryRules.PROPOSAL_DAYS] is rejected and its wait cancelled, and rejected entries older than [MemoryRules.REJECTED_DAYS] are deleted.
     * Idempotent and safe on several replicas; returns how many proposals changed.
     */
    suspend fun settle(limit: Int): Int
    suspend fun update(actor: Principal, id: String, update: MemoryUpdate): MemoryEntry
    suspend fun delete(actor: Principal, id: String)
}
