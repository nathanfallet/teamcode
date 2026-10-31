package me.nathanfallet.teamcode.companion.domain.usecases.waits

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FieldInput
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitInputProblem
import me.nathanfallet.teamcode.companion.domain.models.WaitInputProblem.Reason
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed

/** Whether the account's server has waits: the screen, its tab and the banner stay hidden otherwise. */
class WaitsAvailabilityUseCase(private val features: FeaturesRepository) {
    suspend operator fun invoke(accountId: String): Boolean = Features.WAITS in features.features(accountId)
}

/** One page of the member's waits in [status] (open by default), keyset-paged by [before]. */
class LoadWaitsUseCase(private val waits: WaitRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, status: WaitStatus = WaitStatus.OPEN, before: String? = null): WaitPage = guard(accountId) {
        if (Features.WAITS !in features.features(accountId)) throw CompanionException.Unsupported(Features.WAITS)
        waits.list(accountId, status, before)
    }
}

/** The open waits that resolve by themselves (a time, a webhook), soonest first. */
class LoadInProgressWaitsUseCase(private val waits: WaitRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): List<Wait> = guard(accountId) {
        if (Features.WAITS !in features.features(accountId)) throw CompanionException.Unsupported(Features.WAITS)
        waits.inProgress(accountId)
    }
}

/** What happened to a withdrawal. Never thrown except for cancellation. */
sealed interface WaitCancelOutcome {
    data object Cancelled : WaitCancelOutcome
    /** It ended first (`wait_closed`) or is gone: the list shows what is left. */
    data object Closed : WaitCancelOutcome
    /** Offline or a busy server: the same withdrawal can be sent again. */
    data object Retry : WaitCancelOutcome
}

/** Withdraws a wait the member no longer needs (an in-progress one). */
class CancelWaitUseCase(private val waits: WaitRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, waitId: String): WaitCancelOutcome = try {
        guard(accountId) { waits.cancel(accountId, waitId) }
        WaitCancelOutcome.Cancelled
    } catch (error: CancellationException) {
        throw error
    } catch (error: CompanionException.Unauthorized) {
        throw error
    } catch (error: CompanionException.NotFound) {
        WaitCancelOutcome.Closed
    } catch (error: CompanionException.Server) {
        if (error.statusCode == 409 || error.statusCode == 404 || error.statusCode == 403) WaitCancelOutcome.Closed else WaitCancelOutcome.Retry
    } catch (error: Exception) {
        WaitCancelOutcome.Retry
    }
}

/** What happened to an answer. Never thrown except for cancellation. */
sealed interface WaitAnswerOutcome {
    data class Answered(val wait: Wait) : WaitAnswerOutcome
    /** Someone answered or cancelled it first (`wait_closed`), or it is gone: the list shows what is left. */
    data object Closed : WaitAnswerOutcome
    /** A field to fix, found here before sending anything. */
    data class Problem(val problem: WaitInputProblem) : WaitAnswerOutcome
    /** The server refused a value (`invalid_answer`). */
    data object Refused : WaitAnswerOutcome
    /** Offline or a busy server: the same answer can be sent again. */
    data object Retry : WaitAnswerOutcome
    data class Failed(val error: Exception) : WaitAnswerOutcome
}

class AnswerWaitUseCase(private val waits: WaitRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, wait: Wait, actionId: String, inputs: Map<String, FieldInput>): WaitAnswerOutcome {
        val action = wait.actions.firstOrNull { it.id == actionId } ?: return WaitAnswerOutcome.Closed
        val values = try {
            WaitAnswerBuilder.build(wait, action, inputs)
        } catch (problem: WaitInputProblem) {
            return WaitAnswerOutcome.Problem(problem)
        }
        return try {
            WaitAnswerOutcome.Answered(guard(accountId) { waits.answer(accountId, wait.id, action.id, values) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.Unauthorized) {
            throw error
        } catch (error: CompanionException.Server) {
            when {
                error.statusCode == 409 || error.statusCode == 404 -> WaitAnswerOutcome.Closed
                error.statusCode == 400 -> WaitAnswerOutcome.Refused
                error.statusCode >= 500 || error.statusCode == 408 -> WaitAnswerOutcome.Retry
                else -> WaitAnswerOutcome.Failed(error)
            }
        } catch (error: CompanionException.NotFound) {
            WaitAnswerOutcome.Closed
        } catch (error: CompanionException.Unreachable) {
            WaitAnswerOutcome.Retry
        } catch (error: CompanionException.RateLimited) {
            WaitAnswerOutcome.Retry
        } catch (error: Exception) {
            WaitAnswerOutcome.Failed(error)
        }
    }
}

/** What the waits screen does next: load again, or show the connection's state. */
sealed interface WaitsSignal {
    data object Reload : WaitsSignal
    data class Connection(val reconnecting: Boolean) : WaitsSignal
}

/**
 * Keeps the waits current from the app's one feed ([SessionFeed]): a load when the feed opens (so nothing is missed
 * between loading and following), after a gap, and at each event that concerns this member's waits. Without the feed
 * (an older server) nothing is followed: the waits load once and on return to the screen.
 */
class FollowWaitsUseCase(private val feed: SessionFeed, private val features: FeaturesRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String, memberId: String): Flow<WaitsSignal> = guard.flow(accountId, flow {
        val announced = features.features(accountId)
        if (Features.EVENTS !in announced) { emit(WaitsSignal.Reload); return@flow }
        feed.follow(accountId).collect { update ->
            when (update) {
                is LiveUpdate.Opened -> { emit(WaitsSignal.Connection(reconnecting = false)); if (!update.resumed) emit(WaitsSignal.Reload) }
                LiveUpdate.Gap -> emit(WaitsSignal.Reload)
                is LiveUpdate.Item -> if (update.value.concerns(memberId)) emit(WaitsSignal.Reload)
                is LiveUpdate.Reconnecting -> emit(WaitsSignal.Connection(reconnecting = true))
                LiveUpdate.Ended -> Unit
            }
        }
    })

    /** A wait event belongs to its assignee; a session's request, to its owner. The team's other events are not this member's. */
    private fun SessionChange.concerns(memberId: String) = memberId == this.memberId && (type in SessionChange.WAIT_TYPES || type == SessionChange.WAITING || type == SessionChange.ANSWERED)
}

/** The answer's JSON values from what was typed; checks what can be checked before sending (the server checks the rest). */
object WaitAnswerBuilder {
    fun build(wait: Wait, action: WaitAction, inputs: Map<String, FieldInput>): Map<String, JsonElement> {
        val values = linkedMapOf<String, JsonElement>()
        for (field in wait.fields) {
            if (field.type == FieldType.INFO) continue
            val needed = field.required || field.id in action.requires
            fun problem(reason: Reason) = WaitInputProblem(field.id, field.label, reason)
            if (field.type == FieldType.FILE) {
                // A file is attached from the session.
                if (needed) throw problem(Reason.UNSUPPORTED)
                continue
            }
            val input = inputs[field.id]
            val value: JsonElement? = when (field.type) {
                FieldType.SECRET -> (input as? FieldInput.Secret)?.value?.takeIf { it.isNotBlank() }?.let { text ->
                    if (text.encodeToByteArray().size > MAX_SECRET_BYTES || '\u0000' in text) throw problem(Reason.TOO_LONG)
                    JsonPrimitive(text)
                }
                FieldType.CONFIRM -> (input as? FieldInput.Flag)?.let { JsonPrimitive(it.value) }
                FieldType.CHOICE -> (input as? FieldInput.Chosen)?.values?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { chosen ->
                    if (field.multiple) JsonArray(chosen.map(::JsonPrimitive)) else JsonPrimitive(chosen.first())
                }
                FieldType.NUMBER -> (input as? FieldInput.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: throw problem(Reason.NOT_A_NUMBER)
                }
                FieldType.DATE -> (input as? FieldInput.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (!isDate(text)) throw problem(Reason.NOT_A_DATE)
                    JsonPrimitive(text)
                }
                else -> (input as? FieldInput.Text)?.value?.takeIf { it.isNotBlank() }?.let { text ->
                    if (text.length > (field.maxLength ?: DEFAULT_TEXT)) throw problem(Reason.TOO_LONG)
                    JsonPrimitive(text)
                }
            }
            if (value == null) { if (needed) throw problem(Reason.MISSING) else continue }
            values[field.id] = value
        }
        return values
    }

    private const val DEFAULT_TEXT = 8_000
    private const val MAX_SECRET_BYTES = 4096
    private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun isDate(text: String): Boolean {
        val (year, month, day) = DATE.matchEntire(text)?.destructured ?: return false
        val m = month.toInt(); val d = day.toInt(); val y = year.toInt()
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return m in 1..12 && d in 1..days[m - 1]
    }
}
