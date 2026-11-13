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
    suspend fun create(actor: Principal, entry: NewMemory, origin: MemoryOrigin, sessionId: String?): MemoryEntry
    suspend fun update(actor: Principal, id: String, update: MemoryUpdate): MemoryEntry
    suspend fun delete(actor: Principal, id: String)
}
