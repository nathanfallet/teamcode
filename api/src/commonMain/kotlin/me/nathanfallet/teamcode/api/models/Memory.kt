package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Capability advertised by a server with the memory (`GET /api/v1/capabilities`). */
object MemoryApi {
    const val CAPABILITY = "memory.v1"
    /** Set by the local relay on every call it forwards for an agent: the call is an agent's, whoever's login it carries. */
    const val ORIGIN_HEADER = "x-teamcode-origin"
    const val AGENT_ORIGIN = "agent"
}

/** The entries of the team waiting for an answer: an administrator sees all, a member their own. */
@Serializable
data class MemoryProposalsView(val proposals: List<MemoryEntryView>)

/** What an index or a search returns: enough to decide whether to read the entry, never its content. `scope` is `team` or `personal`. */
@Serializable
data class MemoryIndexEntryView(val id: String, val scope: String, val title: String, val summary: String, val tags: List<String> = emptyList(), val updatedAt: Long)

/** One page of the index; pass `next` as `after` for the following page, absent on the last. */
@Serializable
data class MemoryIndexView(val entries: List<MemoryIndexEntryView>, val next: String? = null)

@Serializable
data class MemorySearchView(val entries: List<MemoryIndexEntryView>)

/** `status` is `active` (the only one listed or searched today), `proposed`, `superseded` or `rejected`; `origin` is `human`, `agent` or `curation`. */
@Serializable
data class MemoryEntryView(
    val id: String, val scope: String, val title: String, val summary: String, val content: String, val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(), val status: String, val origin: String, val authorId: String, val sessionId: String? = null,
    val supersedesId: String? = null, val expiresAt: Long? = null, val version: Int, val createdAt: Long, val updatedAt: Long,
    val lastReadAt: Long? = null, val readCount: Int = 0,
)

/**
 * A personal entry (the default) is the caller's alone. A `team` entry is read by every member; only an interactive member writes
 * one for now, an agent or a key is refused (`memory_team_forbidden`). Content that looks like a credential is refused (`memory_secret`).
 */
@Serializable
data class CreateMemoryRequest(
    val title: String, val summary: String, val content: String, val scope: String = "personal", val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(), val expiresAt: Long? = null,
    /** The session an agent proposes a team entry from (its owner is asked); an execution credential names its own. */
    val sessionId: String? = null,
) {
    override fun toString() = "CreateMemoryRequest(title=[REDACTED], scope=$scope)"
}

/** Replaces the whole entry; `expectedVersion` must be the one read. */
@Serializable
data class UpdateMemoryRequest(
    val expectedVersion: Int, val title: String, val summary: String, val content: String, val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(), val expiresAt: Long? = null,
) {
    override fun toString() = "UpdateMemoryRequest(expectedVersion=$expectedVersion, title=[REDACTED])"
}
