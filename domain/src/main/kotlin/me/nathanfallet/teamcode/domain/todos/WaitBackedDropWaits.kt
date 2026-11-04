package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.*
import java.util.UUID

/** Asks the session's owner through an ordinary wait (docs/waits.md): `yes` drops the item, `no` keeps it. */
class WaitBackedDropWaits(private val waits: WaitStore) : TodoDropWaits {
    override suspend fun open(actor: Principal, sessionId: String, item: TodoItem, reason: String): String {
        val session = waits.session(actor.teamId, sessionId)?.takeIf { it.ownerId == actor.member.id } ?: throw TeamFailure("not_found", 404, "Session not found.")
        val owner = waits.member(actor.teamId, session.ownerId)?.takeUnless { it.disabled } ?: throw TeamFailure("invalid_assignee", 400, "The session's owner is not an active member.")
        val wait = Wait(
            id = UUID.randomUUID().toString(), teamId = actor.teamId, source = WaitSource.WAIT, kind = WaitKind.HUMAN_REQUEST, resolver = WaitResolver.HUMAN,
            status = WaitStatus.OPEN, sessionId = session.id, sessionName = session.name, requestId = null, assigneeId = owner.id, assigneeName = owner.username,
            createdById = actor.member.id, title = "Drop this item of the todo?".take(WaitRules.MAX_TITLE),
            body = "**${item.name}** (`${item.id}`)\n\n$reason\n\n${item.description}".take(WaitRules.MAX_BODY), refs = listOf(WaitRef("session", id = session.id)), fields = emptyList(),
            actions = listOf(WaitAction(YES, "Drop it", style = "danger"), WaitAction(NO, "Keep it", style = "primary")),
            answer = null, createdAt = waits.now(), resolvedAt = null,
        )
        return waits.create(actor, wait, null, WaitRules.MAX_OPEN_PER_SESSION).wait.id
    }

    override suspend fun cancel(actor: Principal, waitId: String) {
        waits.cancel(actor, waitId, waits.now())
    }

    companion object { const val YES = "yes"; const val NO = "no" }
}
