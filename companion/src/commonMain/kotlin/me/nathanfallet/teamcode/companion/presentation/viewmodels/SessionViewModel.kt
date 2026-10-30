package me.nathanfallet.teamcode.companion.presentation.viewmodels

import me.nathanfallet.teamcode.companion.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AnswerOutcome
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionAnswer
import me.nathanfallet.teamcode.companion.domain.models.QuestionChoice
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.usecases.files.SessionFilesUseCase
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.PermissionRules
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.openPermissionRequests
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.reconciled
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute

enum class LiveState { CONNECTING, LIVE, RECONNECTING, ENDED, STOPPED }

/** A short message under the composer; the fixed ones are worded by the screen, from its string resources. */
sealed interface SessionNotice {
    data class Text(val text: Failure) : SessionNotice
    data object NothingRunning : SessionNotice
    /** A `409`: the request was answered by someone else, expired or ended; its card is gone. */
    data class PermissionClosed(val code: String?) : SessionNotice
    /** A `400`: the server refused the answer; the draft stays. */
    data class PermissionInvalid(val code: String?) : SessionNotice
    /** A `403 permission_answer_denied`: only the owner or an administrator decides a tool use. */
    data object PermissionDenied : SessionNotice
    /** An accepted answer the agent did not take (an `input` event rejected it): the request is still open. */
    data class PermissionRejected(val reason: String?) : SessionNotice
    /** Send was tapped while a file still uploads, or one failed: wait for it, retry it or remove it. */
    data object FilesNotReady : SessionNotice
}

/** Where an answer to a permission request stands. */
enum class AnswerState { EDITING, SENDING, FAILED, SENT }

/**
 * The member's answer to one open request: the question shown ([step]), what was picked for each question (by its
 * index), and the answer on its way. [sending] and [key] go together: a retry sends exactly that answer under that key,
 * and any edit drops both, so a changed answer never reuses a key.
 */
data class PermissionDraft(
    val step: Int = 0,
    val choices: Map<Int, QuestionChoice> = emptyMap(),
    val state: AnswerState = AnswerState.EDITING,
    val sending: PermissionAnswer? = null,
    val key: String? = null,
    val inputId: String? = null,
    val reason: String? = null,
)

/** The request shown, oldest open first, with its draft; [waiting] counts the other open ones. */
data class PermissionCard(val request: TranscriptEvent.PermissionRequest, val draft: PermissionDraft, val waiting: Int) {
    val requestId: String get() = request.requestId.orEmpty()
    /** A question whose input was left out (or unreadable) can only be declined, like a tool use whose input was. */
    val answerable: Boolean get() = !request.inputLeftOut && (!request.question || request.questions.isNotEmpty())
    val step: Int get() = draft.step.coerceIn(0, (request.questions.size - 1).coerceAtLeast(0))
    val current: AgentQuestion? get() = request.questions.getOrNull(step)
    val last: Boolean get() = step >= request.questions.size - 1
    val currentAnswered: Boolean get() = current?.let { PermissionRules.answer(it, draft.choices[it.index]) != null } == true
    val editable: Boolean get() = draft.state == AnswerState.EDITING || draft.state == AnswerState.FAILED
}

data class SessionUiState(
    val session: CloudSession? = null,
    val transcript: Transcript = Transcript(),
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val live: LiveState = LiveState.STOPPED,
    /** A failure that ended the screen's stream or its first load. */
    val error: Failure? = null,
    /** True when the session is gone or no longer visible. */
    val gone: Boolean = false,
    /** Messages sent from here that the transcript does not show yet, or that failed. */
    val pending: List<PendingMessage> = emptyList(),
    val draft: String = "",
    /** The member may pause and resume (owner or administrator). */
    val canControl: Boolean = false,
    val acting: Boolean = false,
    /** The server renames sessions (`cloud.sessions.rename.v1`): the menu offers "Rename". */
    val renames: Boolean = false,
    /** A short result of an action (woken, nothing to interrupt) or its failure. */
    val notice: SessionNotice? = null,
    /** Answers being written or sent, by request id. */
    val drafts: Map<String, PermissionDraft> = emptyMap(),
    /** Requests the server said were closed (`409`) before the transcript did: their card stays hidden. */
    val closedRequests: Set<String> = emptySet(),
    /** The files of the next message (`cloud.files.v1`). */
    val files: FilesUiState = FilesUiState(),
    /** The subagent whose thread shows (its `Agent` call id), null for the main agent's. */
    val thread: String? = null,
    /** The subagents panel opened on demand, where it is a screen of its own or slides over the conversation. */
    val panelOpen: Boolean = false,
    /** The subagents panel hidden by the member, where it sits beside the conversation. */
    val panelHidden: Boolean = false,
    /** The background task whose stop waits for the member's confirmation. */
    val confirmStop: String? = null,
    /** Tasks asked to stop that the runner still reports. */
    val stopping: Set<String> = emptySet(),
) {
    /** The session's agent runs (ready or active): only then has it background tasks. */
    val agentRuns: Boolean get() = session?.state == SessionState.READY || session?.state == SessionState.ACTIVE

    /** Send is offered: some text or an uploaded file, and no file still uploading or failed. */
    val canSend: Boolean get() = files.ready && (draft.isNotBlank() || files.files.isNotEmpty())

    /** The requests still waiting, oldest first; none while the session takes no answer (paused, pausing, ended). */
    val openRequests: List<TranscriptEvent.PermissionRequest> by lazy {
        val state = session?.state
        if (state != null && state != SessionState.READY && state != SessionState.ACTIVE) emptyList()
        else transcript.events.openPermissionRequests().filter { it.requestId !in closedRequests }
    }

    /** The oldest open request, answered first: the agent asked them in this order and their deadlines pass in it. */
    val permission: PermissionCard? get() = openRequests.firstOrNull()?.let { PermissionCard(it, drafts[it.requestId] ?: PermissionDraft(), openRequests.size - 1) }

    /**
     * A turn is running: the session is working (not paused or ended since) and its last loaded `turn` event is
     * `started`. A turn longer than the loaded page has its start outside it: a working session counts as running then.
     */
    val running: Boolean get() {
        val lastTurn = transcript.events.lastOrNull { it is TranscriptEvent.Turn } as TranscriptEvent.Turn?
        return when (session?.state) {
            null -> lastTurn?.phase == TurnPhase.STARTED
            SessionState.ACTIVE -> lastTurn == null || lastTurn.phase == TurnPhase.STARTED
            else -> false
        }
    }
}

/**
 * One session: its header, the newest page of history, older pages on demand, and the live stream after the newest
 * event while the screen is visible ([start], [stop]). Events are merged by sequence, so nothing shows twice.
 */
class SessionViewModel(
    private val route: SessionRoute,
    private val getSession: GetSessionUseCase,
    private val loadTranscript: LoadTranscriptUseCase,
    private val followTranscript: FollowTranscriptUseCase,
    private val sendMessage: SendMessageUseCase,
    private val control: ControlSessionUseCase,
    private val accounts: AccountRepository,
    private val answerPermission: AnswerPermissionUseCase,
    /** Null when the app does not send files: no paperclip, messages without files as before. */
    filesUseCase: SessionFilesUseCase? = null,
) : ViewModel() {
    private val state = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = state.asStateFlow()
    private val files = filesUseCase?.let { useCase ->
        SessionFiles(viewModelScope, route.accountId, route.sessionId, useCase, read = { state.value.files }, write = { change -> state.update { it.copy(files = change(it.files)) } })
    }
    private val loading = Mutex()
    private var follow: Job? = null
    private var header: Job? = null
    private var started = false

    fun start() {
        started = true
        if (!state.value.renames) viewModelScope.launch { if (control.supportsRename(route.accountId)) state.update { it.copy(renames = true) } }
        files?.load()
        // The transcript does not carry pauses, resumes or a failed pod: the header is read again while visible.
        if (header?.isActive != true) header = viewModelScope.launch {
            while (true) {
                delay(HEADER_MILLIS)
                // An ended session no longer changes: nothing to read. A stream that ended while the header still reads
                // a live state (its last read failed) keeps reading until the header says it ended.
                val current = state.value
                if (current.gone || current.session?.state?.group == SessionGroup.ENDED) continue
                refreshSessionQuietly()
            }
        }
        // An ended session sent everything: nothing more to follow.
        if (follow?.isActive == true || state.value.gone || state.value.live == LiveState.ENDED) return
        state.update { it.copy(live = LiveState.CONNECTING, error = null) }
        follow = viewModelScope.launch {
            try {
                // The header first, then the newest page: following starts after its last sequence. A header that cannot be
                // read now (offline, a 5xx) is read again by the header loop; only a session that is gone stops here.
                try { refreshSession() } catch (error: CancellationException) { throw error } catch (error: CompanionException.NotFound) { throw error } catch (_: Exception) { }
                loading.withLock {
                    if (!state.value.loaded) {
                        val page = loadTranscript(route.accountId, route.sessionId)
                        state.update { it.withTranscript(it.transcript.withLatest(page)).copy(loaded = true) }
                    }
                }
                var reconnected = false
                followTranscript(route.accountId, route.sessionId, state.value.transcript.newestSeq).collect { update ->
                    when (update) {
                        is LiveUpdate.Opened -> {
                            state.update { it.copy(live = LiveState.LIVE) }
                            // Back after a cut: the session may have been paused or resumed meanwhile.
                            // Beside the stream, so a slow header never holds up the events.
                            if (reconnected) { reconnected = false; viewModelScope.launch { refreshSessionQuietly() } }
                        }
                        is LiveUpdate.Item -> {
                            // A turn starting or ending settles any interruption kept for a retry: a later tap is a new one.
                            if (update.value is TranscriptEvent.Turn) interruptKey = null
                            state.update { it.withTranscript(it.transcript.withLive(update.value)).withTurn(update.value).withTasks(update.value).copy(live = LiveState.LIVE) }
                        }
                        is LiveUpdate.Reconnecting -> { reconnected = true; state.update { it.copy(live = LiveState.RECONNECTING) } }
                        LiveUpdate.Gap -> Unit
                        LiveUpdate.Ended -> {
                            state.update { it.copy(live = LiveState.ENDED) }
                            // The final state stays whatever the header read does.
                            viewModelScope.launch { refreshSessionQuietly() }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Withdrawn access may come back (or was an expired token, which the guard signed out): retry stays offered.
                state.update { it.copy(live = LiveState.STOPPED, error = error.userMessage(), gone = error is CompanionException.NotFound) }
            }
        }
    }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        header?.cancel(); header = null
        state.update { if (it.live == LiveState.ENDED) it else it.copy(live = LiveState.STOPPED) }
    }

    /** After the stream stopped, follow again; while it runs, the error came from an older page: ask for it again. */
    fun retry() {
        if (!started) return
        state.update { it.copy(error = null) }
        if (follow?.isActive == true) loadOlder() else start()
    }

    fun loadOlder() {
        val before = state.value.transcript.nextBefore ?: return
        if (state.value.loadingOlder) return
        state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                loading.withLock {
                    // Another page may have arrived meanwhile.
                    if (state.value.transcript.nextBefore != before) return@withLock
                    val page = loadTranscript(route.accountId, route.sessionId, before)
                    state.update { it.copy(transcript = it.transcript.withOlder(page)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
            } finally {
                state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    fun onDraftChange(text: String) = state.update { it.copy(draft = text) }

    /**
     * Shows the message at once, then sends it with the uploaded files; the draft and the files are cleared only once it
     * is queued for sending. While a file still uploads or failed, nothing is sent.
     */
    fun send() {
        val attached = files?.uploaded() ?: if (state.value.files.files.isEmpty()) emptyList() else null
        if (attached == null) { state.update { it.copy(notice = SessionNotice.FilesNotReady) }; return }
        val message = try {
            sendMessage.draft(state.value.draft.trim(), attached)
        } catch (error: IllegalArgumentException) {
            state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            return
        }
        // A new message replaces what the last action said (a refused draft, nothing to interrupt).
        state.update { it.copy(draft = "", pending = it.pending + message, notice = null) }
        if (attached.isNotEmpty()) files?.clear()
        deliver(message)
    }

    // ---- Files of the next message ------------------------------------------------------------------------------------

    /** Files picked, dropped or photographed: each is checked, then uploaded at once. */
    fun attach(picked: List<PickedFile>) { if (picked.isNotEmpty()) files?.attach(picked) }
    /** The paperclip while files cannot be added (off for the team, or already the most a message carries). */
    fun explainFiles() { files?.explainUnavailable() }
    fun retryFile(localId: String) { files?.retry(localId) }
    fun removeFile(localId: String) { files?.remove(localId) }
    fun dismissFileNotice() { files?.dismissNotice() }
    /** A picker failed to hand over a file (it could not be read): said like an unreadable file. */
    fun pickFailed() = state.update { it.copy(files = it.files.copy(notice = FileProblem.Unreadable)) }

    /** Sends a failed message again with the same id: the server never queues it twice. */
    fun retry(clientMessageId: String) {
        val message = state.value.pending.firstOrNull { it.clientMessageId == clientMessageId && it.state == PendingMessage.State.FAILED } ?: return
        val sending = message.copy(state = PendingMessage.State.SENDING, reason = null)
        state.update { current -> current.copy(pending = current.pending.map { if (it.clientMessageId == clientMessageId) sending else it }) }
        deliver(sending)
    }

    fun discard(clientMessageId: String) = state.update { current ->
        current.copy(pending = current.pending.filterNot { it.clientMessageId == clientMessageId && it.state != PendingMessage.State.SENDING })
    }

    private fun deliver(message: PendingMessage) {
        viewModelScope.launch {
            val result = sendMessage(route.accountId, route.sessionId, message)
            state.update { current ->
                current.copy(pending = current.pending.map { if (it.clientMessageId == message.clientMessageId) result else it }.reconciled(current.transcript.events))
            }
            // A paused session woke for it (the pending bubble says so): the header changes state.
            if (result.woke) refreshSessionQuietly()
        }
    }

    /** Kept from a tap until the server answers or a turn starts or ends: tapping again after a lost answer repeats the same interruption, never a later turn's. */
    private var interruptKey: String? = null

    fun interrupt() = act {
        val key = interruptKey ?: control.interruptKey().also { interruptKey = it }
        val interrupted = try { control.interrupt(route.accountId, route.sessionId, key) } catch (error: CompanionException) {
            // Answered with a refusal: that interruption is over. Unreachable or a timeout: it may still be queued.
            if (error !is CompanionException.Unreachable && !(error is CompanionException.Server && (error.statusCode >= 500 || error.statusCode == 408))) interruptKey = null
            throw error
        }
        interruptKey = null
        if (!interrupted) state.update { it.copy(notice = SessionNotice.NothingRunning) }
    }
    // ---- Subagents and background tasks -------------------------------------------------------------------------------

    /** Shows the subagent [id]'s thread in place of the main agent's (or beside it, on an extra-large window). */
    fun openThread(id: String) = state.update { it.copy(thread = id) }
    fun backToMain() = state.update { it.copy(thread = null) }
    fun togglePanel() = state.update { it.copy(panelOpen = !it.panelOpen) }
    fun closePanel() = state.update { it.copy(panelOpen = false) }
    fun togglePanelHidden() = state.update { it.copy(panelHidden = !it.panelHidden) }

    /** A stop is asked first: it ends work the agent may still need. */
    fun askStop(taskId: String) = state.update { it.copy(confirmStop = taskId) }
    fun cancelStop() = state.update { it.copy(confirmStop = null) }

    fun confirmStop() {
        val taskId = state.value.confirmStop ?: return
        state.update { it.copy(confirmStop = null, stopping = it.stopping + taskId, notice = null) }
        viewModelScope.launch {
            val stopped = try { control.stopTask(route.accountId, route.sessionId, taskId, control.stopKey()) } catch (error: CancellationException) { throw error } catch (error: Exception) {
                state.update { it.copy(stopping = it.stopping - taskId, notice = SessionNotice.Text(error.userMessage())) }
                return@launch
            }
            if (!stopped) state.update { it.copy(stopping = it.stopping - taskId, notice = SessionNotice.NothingRunning) }
        }
    }

    /** A task report drops the stops it no longer lists: they ended. */
    private fun SessionUiState.withTasks(event: TranscriptEvent): SessionUiState {
        if (event !is TranscriptEvent.Tasks || stopping.isEmpty()) return this
        val live = event.tasks.map { it.id }.toSet()
        return copy(stopping = stopping.filterTo(mutableSetOf()) { it in live })
    }

    fun pause() = act { val session = control.pause(route.accountId, route.sessionId); actions++; state.update { it.copy(session = session) } }
    fun resume() = act { val session = control.resume(route.accountId, route.sessionId); actions++; state.update { it.copy(session = session) } }

    /** Renames the session; a refused name (the rules are the server's) is told in a notice. */
    fun rename(name: String) = act { val session = control.rename(route.accountId, route.sessionId, name); actions++; state.update { it.copy(session = session) } }

    /** Ends the session (its workspace is deleted): the header then reads the server's answer, "Ended" once it is done. */
    fun end() = act { control.end(route.accountId, route.sessionId); actions++; refreshSession() }

    private fun act(action: suspend () -> Unit) {
        if (state.value.acting) return
        state.update { it.copy(acting = true, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            } finally {
                state.update { it.copy(acting = false) }
            }
        }
    }

    /** A live `turn` moves a ready session to working and back, as the server does. */
    private fun SessionUiState.withTurn(event: TranscriptEvent): SessionUiState {
        val session = session ?: return this
        if (event !is TranscriptEvent.Turn) return this
        val next = when {
            event.phase == TurnPhase.STARTED && session.state == SessionState.READY -> SessionState.ACTIVE
            event.phase.finished && session.state == SessionState.ACTIVE -> SessionState.READY
            else -> return this
        }
        return copy(session = session.copy(state = next))
    }

    private fun SessionUiState.withTranscript(transcript: Transcript) =
        copy(transcript = transcript, pending = pending.reconciled(transcript.events)).withReconciledDrafts()

    /**
     * Drafts of requests that closed are dropped; an accepted answer that an `input` event rejected or cancelled (the
     * agent could not take it: the request stays open) goes back to editing, with the reason.
     */
    private fun SessionUiState.withReconciledDrafts(): SessionUiState {
        if (drafts.isEmpty()) return this
        val open = openRequests.mapNotNullTo(mutableSetOf()) { it.requestId }
        val refused = transcript.events.filterIsInstance<TranscriptEvent.InputChanged>().filter { it.status == "rejected" || it.status == "cancelled" }.associateBy { it.inputId }
        var notice = notice
        val kept = drafts.filterKeys { it in open }.mapValues { (_, draft) ->
            val refusal = draft.inputId?.let(refused::get)
            if (draft.state != AnswerState.SENT || refusal == null) draft
            else draft.copy(state = AnswerState.EDITING, sending = null, key = null, inputId = null).also { notice = SessionNotice.PermissionRejected(refusal.reason ?: refusal.status) }
        }
        return copy(drafts = kept, notice = notice)
    }

    // ---- Permission requests ---------------------------------------------------------------------------------------

    /** Picks an option: the only one of a single-choice question (leaving "Other"), or one more of a multi-select one. */
    fun selectOption(requestId: String, question: Int, option: Int) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(options = if (option in choice.options) choice.options - option else choice.options + option)
        else choice.copy(options = setOf(option), other = false)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Picks "Other": exclusive in a single-choice question, toggled beside the options in a multi-select one. */
    fun selectOther(requestId: String, question: Int) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(other = !choice.other) else choice.copy(options = emptySet(), other = true)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Typing in "Other" picks it. */
    fun onOtherText(requestId: String, question: Int, text: String) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(other = true, otherText = text) else choice.copy(options = emptySet(), other = true, otherText = text)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Shows the next question once the current one is answered. */
    fun nextQuestion(requestId: String) = edit(requestId) { card, draft ->
        if (!card.currentAnswered || card.last) draft else draft.copy(step = card.step + 1)
    }

    /** Back to the previous question, its answer kept. */
    fun previousQuestion(requestId: String) = edit(requestId) { card, draft -> draft.copy(step = (card.step - 1).coerceAtLeast(0)) }

    /** On the last question: sends every answer in one request. */
    fun sendAnswers(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable || !card.answerable || !card.request.question || !card.last) return
        val answers = PermissionRules.answers(card.request.questions, card.draft.choices) ?: return
        deliverAnswer(card, PermissionAnswer(requestId, allow = true, answers = answers))
    }

    /** Allows a tool use (owner or administrator; anyone else is told so by the server). */
    fun allowTool(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable || !card.answerable || card.request.question) return
        deliverAnswer(card, PermissionAnswer(requestId, allow = true))
    }

    /** Declines a question or denies a tool use. */
    fun deny(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable) return
        deliverAnswer(card, PermissionAnswer(requestId, allow = false))
    }

    /** Sends a failed answer again, the same body under the same key: the server never applies it twice. */
    fun retryAnswer(requestId: String) {
        val card = cardFor(requestId) ?: return
        val answer = card.draft.sending ?: return
        if (card.draft.state != AnswerState.FAILED) return
        deliverAnswer(card, answer)
    }

    private fun cardFor(requestId: String): PermissionCard? = state.value.permission?.takeIf { it.requestId == requestId && requestId.isNotEmpty() }

    private fun edit(requestId: String, change: (PermissionCard, PermissionDraft) -> PermissionDraft) {
        val card = cardFor(requestId) ?: return
        if (!card.editable) return
        val edited = change(card, card.draft)
        if (edited == card.draft) return
        // Any change after a failure is a new answer: it gets a new key when sent.
        val next = if (edited.choices != card.draft.choices) edited.copy(state = AnswerState.EDITING, sending = null, key = null, reason = null) else edited
        state.update { it.copy(drafts = it.drafts + (requestId to next)) }
    }

    private fun deliverAnswer(card: PermissionCard, answer: PermissionAnswer) {
        val requestId = card.requestId
        // The same answer keeps its key; another one (an edit, Deny after a failed Send) gets a new one.
        val key = card.draft.key?.takeIf { card.draft.sending == answer } ?: answerPermission.key()
        val sending = card.draft.copy(state = AnswerState.SENDING, sending = answer, key = key, reason = null)
        state.update { it.copy(drafts = it.drafts + (requestId to sending), notice = null) }
        viewModelScope.launch {
            val outcome = answerPermission(route.accountId, route.sessionId, answer, key)
            state.update { current ->
                val draft = current.drafts[requestId] ?: sending
                when (outcome) {
                    is AnswerOutcome.Accepted -> current.copy(drafts = current.drafts + (requestId to draft.copy(state = AnswerState.SENT, inputId = outcome.receipt.inputId)))
                    is AnswerOutcome.Retry -> current.copy(drafts = current.drafts + (requestId to draft.copy(state = AnswerState.FAILED, reason = outcome.reason)))
                    is AnswerOutcome.Closed -> current.copy(drafts = current.drafts - requestId, closedRequests = current.closedRequests + requestId, notice = SessionNotice.PermissionClosed(outcome.code))
                    is AnswerOutcome.Invalid -> current.copy(drafts = current.drafts + (requestId to draft.edited()), notice = SessionNotice.PermissionInvalid(outcome.code))
                    is AnswerOutcome.Refused -> current.copy(
                        drafts = current.drafts + (requestId to draft.edited()),
                        notice = if (outcome.code == "permission_answer_denied") SessionNotice.PermissionDenied else SessionNotice.Text(outcome.reason?.let { Failure(it) } ?: outcome.code?.let { Failure(it) } ?: Failure("Something went wrong.", Res.string.error_generic)),
                    )
                }.withReconciledDrafts()
            }
        }
    }

    private fun PermissionDraft.edited() = copy(state = AnswerState.EDITING, sending = null, key = null, reason = null)

    private suspend fun refreshSessionQuietly() {
        try {
            refreshSession()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The header stays as it was; the transcript goes on. An expired login was already signed out by the guard,
            // which takes the app back to sign-in: nothing here may throw, since its callers are plain launches.
        }
    }

    private companion object {
        const val HEADER_MILLIS = 30_000L
    }

    /** Bumped by each action's answer: a header read that started before it is older and is dropped. */
    private var actions = 0

    private suspend fun refreshSession() {
        val asked = actions
        val session = getSession(route.accountId, route.sessionId)
        if (asked != actions) return
        val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
        state.update { it.copy(session = session, canControl = account != null && ControlSessionUseCase.canControl(account, session)) }
    }
}
