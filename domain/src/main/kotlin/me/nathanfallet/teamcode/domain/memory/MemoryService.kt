package me.nathanfallet.teamcode.domain.memory

import me.nathanfallet.teamcode.domain.team.*

/**
 * What a member, an execution credential or a key with `memory` may do with the memory. Authorization of the caller is
 * checked here, before persistence; the rules that depend on shared state (visibility, ownership, limits, versions) are the
 * store's, inside its transaction.
 */
class MemoryService(private val store: MemoryStore, private val proposals: MemoryProposalWaits? = null) {
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
     * Personal entries: any caller with the `memory` right, an agent's included, written directly. A team entry written by an interactive member
     * is direct. One written by an agent ([viaAgent]: the call came through an agent's relay, whoever's login it carries, or an execution
     * credential) is stored `PROPOSED` and the session's owner is asked through a wait; it joins the team's memory only once accepted. A key
     * that is no agent's session cannot propose (`memory_team_forbidden`), so nothing but a person's decision changes what the team reads.
     */
    suspend fun create(actor: Principal, entry: NewMemory, sessionId: String? = null, viaAgent: Boolean = false): MemoryEntry {
        actor.requireMemory()
        validate(entry.title, entry.summary, entry.content, entry.tags, entry.refs, entry.expiresAt)
        val agent = viaAgent || actor.serviceScopes != null
        if (entry.scope == MemoryScope.TEAM && agent) return propose(actor, entry, sessionId)
        return store.create(actor, entry, if (agent) MemoryOrigin.AGENT else MemoryOrigin.HUMAN, sessionId)
    }

    private suspend fun propose(actor: Principal, entry: NewMemory, sessionId: String?): MemoryEntry {
        val waits = proposals ?: throw TeamFailure("memory_team_forbidden", 403, "An agent cannot write to the team memory here. Write a personal entry, or ask a member to add it.")
        val session = waits.session(actor, sessionId)
        val stored = store.create(actor, entry, MemoryOrigin.AGENT, session, MemoryStatus.PROPOSED)
        try {
            store.attachProposalWait(stored.id, waits.open(actor, session, stored))
        } catch (error: Exception) {
            // Nobody was asked, so nobody can ever accept it: it must not wait for the expiry.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { try { store.decide(actor, stored.id, accept = false) } catch (_: Exception) {} }
            throw error
        }
        return stored
    }

    suspend fun proposals(actor: Principal): List<MemoryEntry> { actor.requireMemory(); requireInteractive(actor); return store.proposals(actor, PROPOSAL_PAGE) }

    suspend fun decide(actor: Principal, id: String, accept: Boolean): MemoryEntry {
        actor.requireMemory(); requireInteractive(actor)
        return store.decide(actor, id, accept)
    }

    /** Applies the answers, cancellations and expiries of proposals; run by the server's periodic work. */
    suspend fun settle(limit: Int = 100): Int = store.settle(limit)

    private fun requireInteractive(actor: Principal) {
        if (actor.serviceScopes != null) throw TeamFailure("memory_team_forbidden", 403, "Proposals are decided by a signed-in member.")
    }

    suspend fun update(actor: Principal, id: String, update: MemoryUpdate): MemoryEntry {
        actor.requireMemory()
        validate(update.title, update.summary, update.content, update.tags, update.refs, update.expiresAt)
        return store.update(actor, id, update)
    }

    suspend fun delete(actor: Principal, id: String) { actor.requireMemory(); store.delete(actor, id) }

    private companion object { const val PROPOSAL_PAGE = 100 }

    private fun validate(title: String, summary: String, content: String, tags: List<String>, refs: List<String>, expiresAt: Long?) =
        MemoryRules.validate(title, summary, content, tags, refs, expiresAt, System.currentTimeMillis())
}
