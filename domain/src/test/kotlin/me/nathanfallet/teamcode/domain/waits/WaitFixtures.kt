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
        val sessions = mutableMapOf("s1" to WaitSession("s1", "team", "ada", "Fix the login", "claude"), "s2" to WaitSession("s2", "team", "bob", null, "codex"))
        val waits = linkedMapOf<String, Wait>()
        val keys = mutableMapOf<Pair<String, String>, String>()
        val audit = mutableListOf<String>()
        override suspend fun now() = clock
        override suspend fun revalidate(actor: Principal) {}
        override suspend fun member(teamId: String, memberId: String) = members[memberId]
        override suspend fun session(teamId: String, sessionId: String) = sessions[sessionId]
        /** Token id → session, for execution credentials. */
        val executions = mutableMapOf<String, String>()
        override suspend fun executionSession(tokenId: String) = executions[tokenId]
        override suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange {
            idempotencyKey?.let { key -> keys[actor.member.id to key]?.let { return WaitChange(waits.getValue(it), false) } }
            if (waits.values.count { it.sessionId == wait.sessionId && it.status == WaitStatus.OPEN } >= limit) throw TeamFailure("wait_limit", 409, "Too many open waits.")
            waits[wait.id] = wait
            idempotencyKey?.let { keys[actor.member.id to it] = wait.id }
            return WaitChange(wait, true)
        }
        override suspend fun find(teamId: String, id: String) = waits[id]
        override suspend fun list(teamId: String, query: WaitQuery): WaitPage {
            val all = waits.values.filter { (query.sessionId == null || it.sessionId == query.sessionId) && (query.assignee == null || it.assigneeId == query.assignee) && (query.status == null || it.status == query.status) && (query.resolvers == null || it.resolver in query.resolvers!!) }.sortedByDescending { it.createdAt }
            val start = query.before?.let { id -> all.indexOfFirst { it.id == id } + 1 } ?: 0
            val page = all.drop(start).take(query.limit)
            return WaitPage(page, page.lastOrNull()?.id?.takeIf { start + page.size < all.size })
        }
        /** The sealed secrets of the real store, in the clear here: by session and variable name. */
        val kept = linkedMapOf<Pair<String, String>, WaitSecret>()
        override suspend fun serveSecrets(teamId: String, sessionId: String, ownerId: String): ServedSecrets {
            if (sessions[sessionId]?.ownerId != ownerId) return ServedSecrets()
            val live = kept.filter { (key, secret) -> key.first == sessionId && secret.expiresAt > clock }
            val once = live.filter { it.value.oneTime }.keys
            once.forEach { kept.remove(it) }
            return ServedSecrets(live.entries.associate { it.key.second to it.value.value }, once.map { it.second }.toSet())
        }
        override suspend fun purgeSecrets(limit: Int): Int { val gone = kept.filter { it.value.expiresAt <= clock }.keys.take(limit); gone.forEach { kept.remove(it) }; return gone.size }
        override suspend fun answer(actor: Principal, id: String, answer: WaitAnswer, secrets: List<WaitSecret>): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            audit += "wait.answered:$id:${actor.member.id}"
            secrets.forEach { kept[wait.sessionId!! to it.envName] = it }
            if (secrets.isNotEmpty()) audit += "wait.secret_provided:$id:${secrets.joinToString(",") { it.fieldId }}"
            return WaitChange(wait.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt).also { waits[id] = it }, true)
        }
        override suspend fun eventTarget(id: String) = waits[id]?.takeIf { it.resolver == WaitResolver.EVENT }?.let { EventTarget(it.id, it.timer?.tokenHash, it.status == WaitStatus.OPEN) }
        override suspend fun dueWaits(limit: Int) = waits.values.filter { it.status == WaitStatus.OPEN && it.timer != null && it.timer.dueAt <= clock }
            .sortedBy { it.timer!!.dueAt }.take(limit).map { DueWait(it.id, it.teamId, it.resolver, it.timer!!.onTimeout) }
        val owed = mutableSetOf<String>()
        var turnFailedOpened = 0
        override suspend fun resolve(id: String, cause: ResolveCause, resolution: WaitResolution, openTurnFailed: Boolean, wake: Boolean): WaitChange? {
            val wait = waits[id] ?: return null
            val eligible = wait.status == WaitStatus.OPEN && wait.resolver != WaitResolver.HUMAN && (cause == ResolveCause.EVENT || wait.timer!!.dueAt <= clock)
            if (!eligible) return WaitChange(wait, false)
            audit += "wait.${resolution.action}:$id"
            if (openTurnFailed) turnFailedOpened++
            if (wake) owed += id
            return WaitChange(wait.copy(status = WaitStatus.RESOLVED, answer = WaitAnswer(resolution.action, resolution.values, "", resolution.at), resolvedAt = resolution.at).also { waits[id] = it }, true)
        }
        override suspend fun pendingWakes(limit: Int) = owed.mapNotNull { waits[it] }.take(limit)
        override suspend fun wakeDelivered(id: String) { owed -= id }
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

/** Remembers what was queued on sessions and can be made to fail. */
class FakeSessions : WaitSessions {
    val delivered = mutableListOf<Triple<String, String, String>>()
    val seenKeys = mutableSetOf<Pair<String, String>>()
    val authorized = mutableListOf<String>()
    var refuse: TeamFailure? = null
    var failDelivery: Exception? = null
    override suspend fun authorize(actor: Principal, sessionId: String) { refuse?.let { throw it }; authorized += "${actor.member.id}:$sessionId" }
    override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) {
        failDelivery?.let { throw it }
        // Idempotent per session, author and key, like the conversation store.
        if (seenKeys.add(Triple(sessionId, actor.member.id, clientKey).let { it.first + "/" + it.second to it.third })) delivered += Triple(sessionId, actor.member.id, text)
    }
}
