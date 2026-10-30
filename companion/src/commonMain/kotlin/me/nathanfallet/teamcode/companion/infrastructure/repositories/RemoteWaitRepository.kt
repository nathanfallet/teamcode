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
)

private fun WaitRefView.domain() = WaitRef(kind, id, url, title)
private fun WaitActionView.domain() = WaitAction(id, label, requires, style)
private fun WaitFieldView.domain() = WaitField(
    id, FieldType.of(type), label, help, required, default, options.map { WaitOption(it.value, it.label, it.description) }, multiple, allowOther, maxLength,
)
