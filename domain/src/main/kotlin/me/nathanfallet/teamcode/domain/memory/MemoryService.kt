package me.nathanfallet.teamcode.domain.memory

import me.nathanfallet.teamcode.domain.team.*

/**
 * What a member, an execution credential or a key with `memory` may do with the memory. Authorization of the caller is
 * checked here, before persistence; the rules that depend on shared state (visibility, ownership, limits, versions) are the
 * store's, inside its transaction.
 */
class MemoryService(private val store: MemoryStore) {
    suspend fun index(actor: Principal, scope: MemoryScope? = null, tags: List<String> = emptyList(), after: MemoryCursor? = null, limit: Int = MemoryRules.PAGE_LIMIT): MemoryPage {
        actor.requireMemory()
        tags.forEach(MemoryRules::validateTag)
        return store.index(actor, scope, tags.distinct(), after, limit.coerceIn(1, MemoryRules.PAGE_LIMIT))
    }

    suspend fun search(actor: Principal, query: String, tags: List<String> = emptyList(), limit: Int = MemoryRules.SEARCH_LIMIT): List<MemoryIndexEntry> {
        actor.requireMemory()
        tags.forEach(MemoryRules::validateTag)
        return store.search(actor, MemoryRules.terms(query), tags.distinct(), limit.coerceIn(1, MemoryRules.SEARCH_LIMIT))
    }

    suspend fun read(actor: Principal, id: String): MemoryEntry { actor.requireMemory(); return store.read(actor, id) }

    /**
     * Personal entries: any caller with the `memory` right, an agent's included, written directly. Team entries: an
     * interactive member only for now (the validation of an agent's proposal is a later step), so an agent never changes
     * what the whole team reads.
     */
    suspend fun create(actor: Principal, entry: NewMemory, sessionId: String? = null): MemoryEntry {
        actor.requireMemory()
        validate(entry.title, entry.summary, entry.content, entry.tags, entry.refs, entry.expiresAt)
        val human = actor.serviceScopes == null
        if (entry.scope == MemoryScope.TEAM && !human)
            throw TeamFailure("memory_team_forbidden", 403, "An agent or a key cannot write to the team memory yet. Write a personal entry, or ask a member to add it.")
        return store.create(actor, entry, if (human) MemoryOrigin.HUMAN else MemoryOrigin.AGENT, sessionId)
    }

    suspend fun update(actor: Principal, id: String, update: MemoryUpdate): MemoryEntry {
        actor.requireMemory()
        validate(update.title, update.summary, update.content, update.tags, update.refs, update.expiresAt)
        return store.update(actor, id, update)
    }

    suspend fun delete(actor: Principal, id: String) { actor.requireMemory(); store.delete(actor, id) }

    private fun validate(title: String, summary: String, content: String, tags: List<String>, refs: List<String>, expiresAt: Long?) =
        MemoryRules.validate(title, summary, content, tags, refs, expiresAt, System.currentTimeMillis())
}
