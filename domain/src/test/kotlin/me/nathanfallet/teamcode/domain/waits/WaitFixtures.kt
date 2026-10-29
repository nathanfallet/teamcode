package me.nathanfallet.teamcode.domain.waits

import me.nathanfallet.teamcode.domain.team.*

object WaitFixtures {
    val ada = Member("ada", "ada", Role.MEMBER, false, false)
    val bob = Member("bob", "bob", Role.MEMBER, false, false)
    val po = Member("po", "po", Role.MEMBER, false, false)
    val gone = Member("gone", "gone", Role.MEMBER, true, false)
    val admin = Member("admin", "admin", Role.ADMIN, false, false)
    fun principal(who: Member, scopes: Set<ServiceScope>? = null) = Principal(who, "token-${who.id}", "team", scopes)

    /** An in-memory store with the atomic semantics the SQL one must keep. */
    class FakeStore : WaitStore {
        var clock = 1_000L
        val members = listOf(ada, bob, po, gone, admin).associateBy { it.id }
        val sessions = mutableMapOf("s1" to WaitSession("s1", "team", "ada", "Fix the login"), "s2" to WaitSession("s2", "team", "bob", null))
        val waits = linkedMapOf<String, Wait>()
        val keys = mutableMapOf<Pair<String, String>, String>()
        val audit = mutableListOf<String>()
        override suspend fun now() = clock
        override suspend fun revalidate(actor: Principal) {}
        override suspend fun member(teamId: String, memberId: String) = members[memberId]
        override suspend fun session(teamId: String, sessionId: String) = sessions[sessionId]
        override suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange {
            idempotencyKey?.let { key -> keys[actor.member.id to key]?.let { return WaitChange(waits.getValue(it), false) } }
            if (waits.values.count { it.sessionId == wait.sessionId && it.status == WaitStatus.OPEN } >= limit) throw TeamFailure("wait_limit", 409, "Too many open waits.")
            waits[wait.id] = wait
            idempotencyKey?.let { keys[actor.member.id to it] = wait.id }
            return WaitChange(wait, true)
        }
        override suspend fun find(teamId: String, id: String) = waits[id]
        override suspend fun list(teamId: String, query: WaitQuery): WaitPage {
            val all = waits.values.filter { (query.assignee == null || it.assigneeId == query.assignee) && (query.status == null || it.status == query.status) }.sortedByDescending { it.createdAt }
            val start = query.before?.let { id -> all.indexOfFirst { it.id == id } + 1 } ?: 0
            val page = all.drop(start).take(query.limit)
            return WaitPage(page, page.lastOrNull()?.id?.takeIf { start + page.size < all.size })
        }
        override suspend fun answer(actor: Principal, id: String, answer: WaitAnswer): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            audit += "wait.answered:$id:${actor.member.id}"
            return WaitChange(wait.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt).also { waits[id] = it }, true)
        }
        override suspend fun cancel(actor: Principal, id: String, at: Long): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            audit += "wait.cancelled:$id:${actor.member.id}"
            return WaitChange(wait.copy(status = WaitStatus.CANCELLED, resolvedAt = at).also { waits[id] = it }, true)
        }
    }

    /** A plan review: a feedback field and two actions, the second needing it. */
    val review = WaitDefinition(
        sessionId = "s1", title = "Review the plan", body = "Plan attached.",
        refs = listOf(WaitRef("artifact", id = "a1")),
        fields = listOf(WaitField("feedback", FieldType.TEXT, "Feedback")),
        actions = listOf(WaitAction("approve", "Approve", style = "primary"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))),
    )
}
