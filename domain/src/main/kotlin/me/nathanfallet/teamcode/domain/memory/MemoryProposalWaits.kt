package me.nathanfallet.teamcode.domain.memory

import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import java.util.UUID

/** Asks the owner of a session whether an agent's proposal joins the team's memory. */
interface MemoryProposalWaits {
    /** The session a proposal comes from: [requested] for a person's own run, the credential's session for an execution; `400` when there is none. */
    suspend fun session(actor: Principal, requested: String?): String
    /** Opens the wait for the session's owner and returns its id. */
    suspend fun open(actor: Principal, sessionId: String, entry: MemoryEntry): String
}

/**
 * Through an ordinary wait (docs/waits.md): `accept` adds the entry (the owner may edit the three text fields first), `reject` drops it.
 * The answer is applied by [MemoryStore.settle]; the wait wakes the agent's session as any answer does.
 */
class WaitBackedMemoryProposals(private val waits: WaitStore) : MemoryProposalWaits {
    override suspend fun session(actor: Principal, requested: String?): String {
        val session = if (actor.serviceScopes != null) waits.executionSession(actor.tokenId) else requested
        return session?.takeIf { it.isNotBlank() } ?: throw TeamFailure("memory_proposal_session", 400, "A team entry is proposed from a session, whose owner decides: send the session id, or write a personal entry.")
    }

    override suspend fun open(actor: Principal, sessionId: String, entry: MemoryEntry): String {
        val session = waits.session(actor.teamId, sessionId)?.takeIf { it.ownerId == actor.member.id } ?: throw TeamFailure("not_found", 404, "Session not found.")
        val owner = waits.member(actor.teamId, session.ownerId)?.takeUnless { it.disabled } ?: throw TeamFailure("invalid_assignee", 400, "The session's owner is not an active member.")
        val external = "external" in entry.tags
        val body = buildString {
            append("An agent of your session proposes this note for the **team's memory**: every member's agents will read its title and summary, and the whole entry on demand.\n\n")
            if (external) append("> Tagged `external`: it may come from a web page, a ticket or a third party. Check it before accepting.\n\n")
            append("**${entry.title}**\n\n${entry.summary}\n\n${entry.content}")
            if (entry.tags.isNotEmpty()) append("\n\nTags: ${entry.tags.joinToString(", ")}")
            append("\n\nEdit the text below before accepting if it needs fixing. Unanswered, it is dropped after ${MemoryRules.PROPOSAL_DAYS} days.")
        }
        val wait = Wait(
            id = UUID.randomUUID().toString(), teamId = actor.teamId, source = WaitSource.WAIT, kind = WaitKind.HUMAN_REQUEST, resolver = WaitResolver.HUMAN,
            status = WaitStatus.OPEN, sessionId = session.id, sessionName = session.name, requestId = null, assigneeId = owner.id, assigneeName = owner.username,
            createdById = actor.member.id, title = "Add to the team memory: ${entry.title}".take(WaitRules.MAX_TITLE), body = body.take(WaitRules.MAX_BODY),
            refs = listOf(WaitRef("session", id = session.id)),
            fields = listOf(
                WaitField("title", FieldType.TEXT, "Title", default = JsonPrimitive(entry.title), maxLength = MemoryRules.TITLE_LENGTH),
                WaitField("summary", FieldType.TEXT, "Summary", default = JsonPrimitive(entry.summary), maxLength = MemoryRules.SUMMARY_LENGTH),
                WaitField("content", FieldType.TEXT, "Content", default = JsonPrimitive(entry.content), maxLength = MemoryRules.CONTENT_LENGTH),
            ),
            actions = listOf(WaitAction(ACCEPT, "Add to the team memory", style = "primary"), WaitAction(REJECT, "Do not add", style = "danger")),
            answer = null, createdAt = waits.now(), resolvedAt = null,
        )
        return waits.createForSystem(wait, WaitRules.MAX_OPEN_PER_SESSION).wait.id
    }

    companion object { const val ACCEPT = "accept"; const val REJECT = "reject" }
}
