package me.nathanfallet.teamcode.companion.presentation.dictation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DictationState {
    data object Idle : DictationState

    data object Listening : DictationState

    /** Shown under the composer until dismissed or until the member taps the microphone again. */
    data class Failed(val error: SpeechError) : DictationState
}

/**
 * The composer's microphone. A tap listens and streams the transcription into the draft, after what the member had
 * typed; a second tap ends the utterance and keeps its final words. The draft is never sent: the member reads it, then
 * sends. Editing the draft while listening (typing, sending it) stops listening, and what the member wrote wins.
 */
class Dictation(private val recognizer: SpeechRecognizer, private val scope: CoroutineScope) {
    private val state = MutableStateFlow<DictationState>(DictationState.Idle)
    val uiState: StateFlow<DictationState> = state.asStateFlow()

    private var job: Job? = null

    /** The operating system listens and types (macOS): the field must have the focus, and the app shows no state. */
    val systemDictation: Boolean get() = recognizer.systemDictation

    /** [draft] reads the field's text now, [onDraft] replaces it. */
    fun toggle(draft: () -> String, onDraft: (String) -> Unit) {
        if (recognizer.systemDictation) {
            // The system types and shows its own indicator; the app only starts or stops it.
            recognizer.toggleSystemDictation()
            return
        }
        if (state.value == DictationState.Listening) {
            recognizer.stop()
            return
        }
        start(draft, onDraft)
    }

    /** Stops listening without waiting for the last words: the screen leaves, or the message is being sent. */
    fun cancel() {
        job?.cancel()
        job = null
        if (state.value == DictationState.Listening) state.value = DictationState.Idle
    }

    fun dismissError() {
        if (state.value is DictationState.Failed) state.value = DictationState.Idle
    }

    private fun start(draft: () -> String, onDraft: (String) -> Unit) {
        job?.cancel()
        val base = draft()
        val prefix = if (base.isEmpty() || base.last().isWhitespace()) base else "$base "
        var written = base
        state.value = DictationState.Listening
        job = scope.launch {
            fun write(text: String): Boolean {
                // The member changed the draft since the last words: theirs wins, and listening stops.
                if (draft() != written) return false
                written = if (text.isEmpty()) base else prefix + text
                onDraft(written)
                return true
            }
            try {
                recognizer.listen().collect { event ->
                    when (event) {
                        is SpeechEvent.Partial -> if (!write(event.text)) { state.value = DictationState.Idle; this@launch.cancel() }
                        is SpeechEvent.Final -> { write(event.text); state.value = DictationState.Idle }
                        is SpeechEvent.Failed -> state.value =
                            if (event.error == SpeechError.NO_SPEECH) DictationState.Idle else DictationState.Failed(event.error)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A recognizer that threw instead of reporting: the words so far stay in the draft.
                state.value = DictationState.Failed(SpeechError.FAILED)
            } finally {
                if (state.value == DictationState.Listening) state.value = DictationState.Idle
            }
        }
    }
}
