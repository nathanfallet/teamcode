package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AnswerOutcome
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionChoice
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/**
 * The server's answer rules (`POST …/permissions`), checked before sending so an incomplete answer never costs a request.
 *
 * The API takes options or a text per question, not both. "Other" is exclusive in a single-choice question (a radio item
 * like the options). In a multi-select question it may go with options: the answer is then sent as a text, the chosen
 * labels in option order followed by the other text, joined with `, `, which is exactly what Claude Code would receive
 * had the runner joined the labels itself.
 */
object PermissionRules {
    const val MAX_TEXT_CHARS = 4_000

    fun validText(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_TEXT_CHARS && text.none { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }

    /** The answer [choice] gives to [question]; null while it does not answer it (nothing chosen, "Other" left empty). */
    fun answer(question: AgentQuestion, choice: QuestionChoice?): QuestionAnswer? {
        choice ?: return null
        val other = choice.otherText.trim()
        if (choice.other && !validText(other)) return null
        val options = choice.options.filter { it in question.options.indices }.sorted()
        if (!question.multiSelect) return when {
            choice.other -> QuestionAnswer(question.index, text = other)
            options.size == 1 -> QuestionAnswer(question.index, options = options)
            else -> null
        }
        return when {
            choice.other && options.isEmpty() -> QuestionAnswer(question.index, text = other)
            choice.other -> (options.map { question.options[it].label } + other).joinToString(", ").takeIf(::validText)?.let { QuestionAnswer(question.index, text = it) }
            options.isNotEmpty() -> QuestionAnswer(question.index, options = options)
            else -> null
        }
    }

    /** The answers to every question, or null while one of them is unanswered. */
    fun answers(questions: List<AgentQuestion>, choices: Map<Int, QuestionChoice>): List<QuestionAnswer>? =
        questions.map { answer(it, choices[it.index]) ?: return null }
}

/**
 * The permission requests still waiting, oldest first, from a transcript ordered by sequence: a request stays open until
 * an `input` event records its answer consumed, a `permission.expired` names it, its turn ends (a `turn` event that
 * finished), or the agent process that asked is gone (a `status` event: it exited, or a new one started).
 */
fun List<TranscriptEvent>.openPermissionRequests(): List<TranscriptEvent.PermissionRequest> {
    val open = LinkedHashMap<String, TranscriptEvent.PermissionRequest>()
    for (event in this) when (event) {
        is TranscriptEvent.PermissionRequest -> event.requestId?.let { open.remove(it); open[it] = event }
        is TranscriptEvent.InputChanged -> if (event.status == "consumed") event.requestId?.let(open::remove)
        is TranscriptEvent.PermissionExpired -> event.requestId?.let(open::remove)
        is TranscriptEvent.Turn -> if (event.phase.finished) open.clear()
        is TranscriptEvent.Status -> open.clear()
        else -> Unit
    }
    return open.values.toList()
}

/**
 * Answers a permission request. The caller keeps the idempotency key ([key]) with the exact answer it was made for, so a
 * retry after a network failure sends the same body under the same key and the server replays its first acknowledgement.
 */
class AnswerPermissionUseCase(
    private val sessions: SessionRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
    private val ids: IdGenerator,
) {
    fun key(): String = ids.newId()

    /** Never throws except for cancellation. */
    suspend operator fun invoke(accountId: String, sessionId: String, answer: PermissionAnswer, key: String): AnswerOutcome = try {
        val receipt = guard(accountId) {
            if (Features.PERMISSIONS !in features.features(accountId)) throw CompanionException.Unsupported(Features.PERMISSIONS)
            sessions.answer(accountId, sessionId, answer, key)
        }
        // A replayed acknowledgement of an answer that was then refused is final like a refusal.
        if (receipt.status == "rejected" || receipt.status == "cancelled") AnswerOutcome.Refused(receipt.reason, null) else AnswerOutcome.Accepted(receipt)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: CompanionException.Server) {
        when {
            error.statusCode >= 500 || error.statusCode == 408 -> AnswerOutcome.Retry(error.message)
            error.statusCode == 409 -> AnswerOutcome.Closed(error.code)
            error.statusCode == 400 -> AnswerOutcome.Invalid(error.code)
            else -> AnswerOutcome.Refused(error.code, error.message)
        }
    } catch (error: CompanionException.RateLimited) {
        AnswerOutcome.Retry(error.message)
    } catch (error: CompanionException.Unreachable) {
        AnswerOutcome.Retry(error.message)
    } catch (error: CompanionException) {
        // Gone, signed out, a server without the feature: sending it again would not help.
        AnswerOutcome.Refused(null, error.message)
    } catch (error: IllegalArgumentException) {
        AnswerOutcome.Refused(null, error.message)
    } catch (error: Exception) {
        AnswerOutcome.Retry(error.message)
    }
}
