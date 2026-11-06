package me.nathanfallet.teamcode.domain.cloud

import java.security.MessageDigest
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.TimeoutAction
import me.nathanfallet.teamcode.domain.waits.WaitChange
import me.nathanfallet.teamcode.domain.waits.WaitService

/** What an orchestrating session asks for when it starts a child (docs/orchestration.md). Null fields take the parent profile's values. */
data class ChildSessionRequest(
    val name: String,
    /** Null: the parent's own profile. Another one must be listed in the parent profile version's `childProfileIds`. */
    val profileId: String? = null,
    val agentModel: String? = null,
    val agentEffort: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val initialMessage: String? = null,
)

/** Persistence the orchestration service reads besides the session store. */
interface OrchestrationStore {
    /** The cloud session an execution credential (`tcex_`) was issued for, or null for any other credential. */
    suspend fun executionSession(tokenId: String): String?
    /**
     * The direct children of [parentId], newest first, by keyset on `(created_at, id)` after [beforeId], at most [limit]; one
     * indexed range read (`cloud_session_parent`) plus the labels of the page.
     */
    suspend fun children(teamId: String, parentId: String, beforeId: String?, limit: Int): CloudSessionPage
}

/**
 * Session orchestration (docs/orchestration.md): the agent of a cloud session whose profile version enables it starts, reads,
 * messages, interrupts, terminates and waits for its own child sessions, with its execution credential's `sessions:children`
 * scope. Every operation is confined to the **direct children** of the one session the credential was issued for: a sibling's
 * child, a grandchild, the parent itself or any other session of the member is `404`, whatever id the caller names. A grandchild
 * is driven by its own parent, which wakes the root in turn. Business rules live here; the routes only decode and encode.
 */
class OrchestrationService(
    private val store: OrchestrationStore,
    private val sessions: CloudSessionStore,
    private val cloud: CloudSessionService,
    private val conversation: CloudConversationService,
    private val waits: WaitService? = null,
) {
    /** The calling session and the orchestration settings of its pinned profile version. */
    class Parent(val session: CloudSession, val spec: CloudOrchestrationSpec)

    /**
     * The session the credential is bound to, checked again on every call: the scope (`403 credential_scope` without it, or for a
     * credential bound to no session), the owner (the credential acts as the member its execution runs for) and the profile version
     * that still allows it at this depth (`403 orchestration_disabled`).
     */
    suspend fun parent(actor: Principal): Parent {
        actor.requireChildren()
        sessions.revalidate(actor)
        val id = store.executionSession(actor.tokenId) ?: throw TeamFailure("credential_scope", 403, "This credential is not bound to a cloud session.")
        val session = sessions.find(actor.teamId, id) ?: throw TeamFailure("credential_scope", 403, "This credential is not bound to a cloud session.")
        if (session.kind != CloudSessionKind.CLOUD || session.ownerId != actor.member.id)
            throw TeamFailure("credential_scope", 403, "This credential does not act for its session's owner any more.")
        val spec = sessions.profileVersion(actor.teamId, session.profileId, session.profileVersion)?.orchestration
        if (spec == null || !spec.allows(session.depth))
            throw TeamFailure("orchestration_disabled", 403, "This session's profile does not let it start child sessions at depth ${session.depth}.")
        return Parent(session, spec)
    }

    /** One of the caller's direct children, or `404`: the same answer for a session that does not exist and for anyone else's. */
    private suspend fun child(actor: Principal, parent: Parent, id: String): CloudSession {
        if (!CloudIdentifier.valid(id)) throw missingChild()
        return sessions.find(actor.teamId, id)?.takeIf { it.parentSessionId == parent.session.id && it.ownerId == actor.member.id } ?: throw missingChild()
    }

    /**
     * Starts a child. The owner, the profile assignment, the access policy, the retained and running caps are those of any session
     * of the member; on top, the parent's cap of children not yet ended (`409 child_session_limit`), checked by the store under the
     * owner's row lock. [idempotencyKey] is required; the digest covers [canonical] (the decoded request) and the parent, so the same
     * key from another parent of the same member is `422 idempotency_conflict`, never a replay of the wrong child.
     */
    suspend fun spawn(actor: Principal, request: ChildSessionRequest, idempotencyKey: String, canonical: String): CloudCreateOutcome {
        val parent = parent(actor)
        val profileId = request.profileId ?: parent.session.profileId
        if (profileId != parent.session.profileId && profileId !in parent.spec.childProfileIds)
            throw TeamFailure("child_profile_not_allowed", 403, "A child uses this session's profile, or one its profile lists in orchestration.childProfileIds.")
        val link = ChildSessionLink(parent.session.id, parent.session.root, parent.session.depth + 1, parent.spec.maxActiveChildren)
        val draft = CloudSessionDraft(request.name, profileId, null, request.agentModel, request.agentEffort, request.labels, null, null, request.initialMessage)
        val digest = sha256("${parent.session.id}\n$canonical")
        return cloud.createChild(actor, draft, IdempotencyRequest(ROUTE, idempotencyKey, digest), link)
    }

    suspend fun list(actor: Principal, beforeId: String?, limit: Int): CloudSessionPage {
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_PAGE.")
        val parent = parent(actor)
        if (beforeId != null && !CloudIdentifier.valid(beforeId)) throw TeamFailure("invalid_cursor", 400, "Unknown session cursor.")
        return store.children(actor.teamId, parent.session.id, beforeId, limit)
    }

    suspend fun get(actor: Principal, id: String): CloudSession = child(actor, parent(actor), id)

    /** A message to a child, written as the owner by the parent's credential; it wakes a paused child like any message. */
    suspend fun send(actor: Principal, id: String, text: String, clientKey: String): InputOutcome {
        val parent = parent(actor)
        return conversation.sendFromParent(actor, child(actor, parent, id), text, clientKey)
    }

    /** The child's newest transcript events (at most [MAX_EVENTS]), oldest first; the caller pages back with [before]. */
    suspend fun transcript(actor: Principal, id: String, before: Long?, limit: Int): CloudEventPage {
        if (limit !in 1..MAX_EVENTS) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_EVENTS.")
        val parent = parent(actor)
        return conversation.transcriptOf(child(actor, parent, id), before, limit)
    }

    suspend fun interrupt(actor: Principal, id: String, clientKey: String?): InputOutcome {
        val parent = parent(actor)
        return conversation.interruptFromParent(actor, child(actor, parent, id), clientKey)
    }

    /** Terminates a child and deletes its workspace, as `DELETE /cloud/sessions/{id}` does; audited `cloud.session.child_terminated`. Idempotent. */
    suspend fun terminate(actor: Principal, id: String): CloudSession {
        val parent = parent(actor)
        return cloud.terminateChild(actor, child(actor, parent, id).id)
    }

    /**
     * A wait on the calling session that resolves when the child ends a turn, starts waiting for a person, is paused or ends; or at
     * once, without a wake message, when the child is already settled. The agent ends its turn and the server wakes it.
     */
    suspend fun waitFor(actor: Principal, id: String, title: String?, timeoutSeconds: Long?, onTimeout: TimeoutAction?, resumeMessage: String?, idempotencyKey: String?): WaitChange {
        val service = waits ?: throw TeamFailure("waits_unavailable", 404, "This server has no waits.")
        val parent = parent(actor)
        val target = child(actor, parent, id)
        return service.createChildWait(actor, parent.session.id, target.id, title ?: "Child session ${target.name.take(150)}", timeoutSeconds, onTimeout, resumeMessage, idempotencyKey)
    }

    companion object {
        const val ROUTE = "POST /cloud/children"
        const val MAX_PAGE = 50
        const val MAX_EVENTS = 50
        private fun missingChild() = TeamFailure("not_found", 404, "No child session of this session has this id.")
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
