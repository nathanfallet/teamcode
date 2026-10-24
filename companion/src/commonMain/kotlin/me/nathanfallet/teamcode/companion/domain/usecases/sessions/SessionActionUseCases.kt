package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.domain.models.InputReceipt
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** The server's message rules (`POST …/messages`), checked before sending so a refusal never costs a request. */
object MessageRules {
    const val MAX_CHARS = 32_000

    fun valid(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_CHARS && text.none { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }

    /** A message carrying files may have no text at all. */
    fun valid(text: String, withFiles: Boolean): Boolean = (withFiles && text.isEmpty()) || valid(text)
}

/**
 * Sends a message, waking a paused session. The [PendingMessage.clientMessageId] is the idempotency key: the caller
 * keeps it to retry the same message after a network failure, and the server answers a retry with the first receipt.
 */
class SendMessageUseCase(
    private val sessions: SessionRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
    private val ids: IdGenerator,
) {
    /** [files]: uploaded files the message carries; with files, [text] may be empty. */
    fun draft(text: String, files: List<UploadedFile> = emptyList()): PendingMessage {
        require(files.size <= FilePolicy.MAX_FILES) { "A message carries ${FilePolicy.MAX_FILES} files at most." }
        val sent = if (files.isNotEmpty() && text.isBlank()) "" else text
        require(MessageRules.valid(sent, withFiles = files.isNotEmpty())) { "Write a message of up to ${MessageRules.MAX_CHARS} characters." }
        return PendingMessage(ids.newId(), sent, PendingMessage.State.SENDING, files = files)
    }

    /** Sends [message] (a new draft or a failed one being retried); never throws except for cancellation. */
    suspend operator fun invoke(accountId: String, sessionId: String, message: PendingMessage): PendingMessage = try {
        val receipt = guard(accountId) {
            val announced = features.features(accountId)
            if (Features.MESSAGES !in announced) throw CompanionException.Unsupported(Features.MESSAGES)
            if (message.files.isNotEmpty() && Features.FILES !in announced) throw CompanionException.Unsupported(Features.FILES)
            sessions.send(accountId, sessionId, message.text, message.clientMessageId, message.files.map { it.id })
        }
        message.received(receipt)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: CompanionException.Server) {
        // A server failure may pass; a refusal (an invalid message, an ended session) would be refused again.
        val transient = error.statusCode >= 500 || error.statusCode == 408
        message.copy(state = if (transient) PendingMessage.State.FAILED else PendingMessage.State.REFUSED, reason = error.code ?: error.message)
    } catch (error: CompanionException.NotFound) {
        message.copy(state = PendingMessage.State.REFUSED, reason = error.message)
    } catch (error: CompanionException.Unsupported) {
        message.copy(state = PendingMessage.State.REFUSED, reason = error.message)
    } catch (error: Exception) {
        message.copy(state = PendingMessage.State.FAILED, reason = error.message)
    }
}

internal fun PendingMessage.received(receipt: InputReceipt): PendingMessage = copy(
    state = if (receipt.status == "rejected" || receipt.status == "cancelled") PendingMessage.State.REFUSED else PendingMessage.State.SENT,
    inputId = receipt.inputId,
    woke = receipt.woke,
    reason = receipt.reason,
)

/**
 * The messages still shown as pending, given the transcript: one whose `message.user` event arrived is dropped (the
 * transcript shows it), one an `input` event rejected or cancelled is marked refused with the event's reason.
 */
fun List<PendingMessage>.reconciled(events: List<TranscriptEvent>): List<PendingMessage> {
    if (isEmpty()) return this
    // Recorded as a message, or consumed by the agent: a message too large for the event cap is recorded only as a size
    // marker without its input id, but its input is still marked consumed.
    val recorded = events.mapNotNullTo(mutableSetOf()) { (it as? TranscriptEvent.UserMessage)?.inputId } +
        events.mapNotNull { (it as? TranscriptEvent.InputChanged)?.takeIf { e -> e.status == "consumed" }?.inputId }
    val refused = events.filterIsInstance<TranscriptEvent.InputChanged>().filter { it.status == "rejected" || it.status == "cancelled" }.associateBy { it.inputId }
    return mapNotNull { message ->
        val id = message.inputId
        when {
            id == null -> message
            id in recorded -> null
            id in refused -> message.copy(state = PendingMessage.State.REFUSED, reason = refused.getValue(id).reason ?: refused.getValue(id).status)
            else -> message
        }
    }
}

/** Interrupt, pause and resume. An interrupt with nothing running is not a failure. */
class ControlSessionUseCase(private val sessions: SessionRepository, private val guard: AccountGuard, private val ids: IdGenerator) {
    /** A key for one interruption: the caller keeps it until the server answered, so a retry never interrupts a later turn. */
    fun interruptKey(): String = ids.newId()

    /** Returns false when there was nothing to interrupt. */
    suspend fun interrupt(accountId: String, sessionId: String, key: String): Boolean = guard(accountId) {
        try {
            sessions.interrupt(accountId, sessionId, key)
            true
        } catch (error: CompanionException.Server) {
            if (error.statusCode == 409 && error.code == "nothing_to_interrupt") false else throw error
        }
    }

    suspend fun pause(accountId: String, sessionId: String): CloudSession = guard(accountId) { sessions.pause(accountId, sessionId) }
    suspend fun resume(accountId: String, sessionId: String): CloudSession = guard(accountId) { sessions.resume(accountId, sessionId) }

    companion object {
        /** Pause and resume are offered to the owner and to administrators. */
        fun canControl(account: Account, session: CloudSession): Boolean = account.isAdministrator || session.ownerId == account.memberId
        fun canPause(session: CloudSession): Boolean = session.state in setOf(SessionState.READY, SessionState.ACTIVE, SessionState.RESUMING)
        /**
         * A remote session resumes only from its own device, which this app never is: a message asks the device to
         * resume it instead (the server refuses an explicit resume from elsewhere).
         */
        fun canResume(session: CloudSession): Boolean = !session.remote && (session.state == SessionState.PAUSED || session.state == SessionState.PAUSING)
        /** A remote session is never transferred (`409 remote_not_transferable`): its files stay on its device. */
        fun canTransfer(session: CloudSession): Boolean = !session.remote
        fun canMessage(session: CloudSession): Boolean = session.state.group != SessionGroup.ENDED && session.state != SessionState.TRANSFERRING
    }
}

class LoadProfilesUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    /** The profiles, and whether the server takes a first message with the session. */
    suspend operator fun invoke(accountId: String): Pair<List<Profile>, Boolean> = guard(accountId) {
        val announced = features.features(accountId, refresh = true)
        if (Features.SESSIONS !in announced) throw CompanionException.Unsupported(Features.SESSIONS)
        sessions.profiles(accountId) to (Features.INITIAL_MESSAGE in announced && Features.MESSAGES in announced)
    }
}

/**
 * Creates a session. The caller keeps [idempotencyKey] for the form's lifetime, so a retry after a network failure
 * returns the session the first attempt created instead of a second one. Fields are trimmed and optional ones left
 * out when empty; the first message only goes when the server announced it (an older server would drop it silently).
 */
class CreateSessionUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, form: NewSession, idempotencyKey: String): CloudSession = guard(accountId) {
        val sent = form.normalized()
        val name = sent.name
        require(name.isNotEmpty() && name.length <= MAX_NAME && name.none(Char::isISOControl)) { "Name the session (up to $MAX_NAME characters)." }
        require(form.profileId.isNotBlank()) { "Choose a profile." }
        val message = sent.initialMessage
        if (message != null) {
            require(MessageRules.valid(message)) { "Write a first message of up to ${MessageRules.MAX_CHARS} characters." }
            if (Features.INITIAL_MESSAGE !in features.features(accountId)) throw CompanionException.Unsupported(Features.INITIAL_MESSAGE)
        }
        sessions.create(accountId, sent, idempotencyKey)
    }

    private companion object {
        const val MAX_NAME = 120
    }
}
