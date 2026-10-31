package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.api.models.WaitActionView
import me.nathanfallet.teamcode.api.models.WaitFieldView
import me.nathanfallet.teamcode.api.models.WaitRefView
import me.nathanfallet.teamcode.api.models.WaitView
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitOption
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitRef
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository

/** The waits over the typed client (`/api/v1/waits`), one request per call. */
class RemoteWaitRepository(private val calls: AccountCalls) : WaitRepository {
    override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage = calls.withAccount(accountId) { client ->
        val page = client.waits(status = status.wire, before = before, limit = PAGE)
        WaitPage(page.waits.map { it.domain() }, page.nextBefore)
    }

    override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait =
        calls.withAccount(accountId, resource = true) { it.answerWait(waitId, action, values).domain() }

    override suspend fun inProgress(accountId: String): List<Wait> = calls.withAccount(accountId) { client ->
        // Two bounded reads (a session holds at most 20 open waits): the server lists one resolver at a time besides "all", which would page through people's waits too.
        (client.waits(status = "open", limit = PAGE, resolver = "time").waits + client.waits(status = "open", limit = PAGE, resolver = "event").waits)
            .map { it.domain() }.sortedWith(compareBy<Wait> { it.dueOrTimeout ?: Long.MAX_VALUE }.thenBy { it.id })
    }

    override suspend fun cancel(accountId: String, waitId: String): Wait = calls.withAccount(accountId, resource = true) { it.cancelWait(waitId).domain() }

    private companion object { const val PAGE = 50 }
}

internal fun WaitView.domain() = Wait(
    id = id,
    source = if (source == "session") WaitSource.SESSION else WaitSource.WAIT,
    kind = WaitKind.of(kind),
    open = status == "open",
    sessionId = sessionId,
    sessionName = sessionName,
    requestId = requestId,
    assigneeId = assigneeId,
    title = title,
    body = body,
    refs = refs.map { it.domain() },
    fields = fields.map { it.domain() },
    actions = actions.map { it.domain() },
    createdAt = createdAt,
    cancelled = status == "cancelled",
    resolvedAt = resolvedAt,
    answer = answer?.let { WaitAnswer(it.action, it.values, it.authorId, it.answeredAt) },
    resolver = WaitResolver.of(resolver),
    dueAt = dueAt,
    timeoutAt = timeoutAt,
    onTimeout = onTimeout,
    eventSource = eventSource,
)

private fun WaitRefView.domain() = WaitRef(kind, id, url, title)
private fun WaitActionView.domain() = WaitAction(id, label, requires, style)
private fun WaitFieldView.domain() = WaitField(
    id, FieldType.of(type), label, help, required, default, options.map { WaitOption(it.value, it.label, it.description) }, multiple, allowOther, maxLength,
)
