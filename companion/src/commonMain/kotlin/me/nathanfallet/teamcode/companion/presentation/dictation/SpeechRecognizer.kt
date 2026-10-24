package me.nathanfallet.teamcode.companion.presentation.dictation

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * The platform's own speech transcription behind the composer's microphone: the Speech framework on iOS, the system
 * recognizer on Android, the operating system's dictation on macOS.
 */
interface SpeechRecognizer {
    /**
     * True when the operating system types the words into the focused field itself (macOS Dictation, with its own
     * listening indicator next to the caret): [toggleSystemDictation] starts or stops it and [listen] is never called.
     */
    val systemDictation: Boolean get() = false

    /**
     * Asks for the microphone and speech permissions if needed, then listens: each [SpeechEvent.Partial] holds the whole
     * transcription so far, [SpeechEvent.Final] the last one, and the flow completes after it. Cancelling the collection
     * stops listening and drops what was not transcribed yet.
     */
    fun listen(): Flow<SpeechEvent>

    /** Ends the utterance: the recognizer transcribes what it heard, emits [SpeechEvent.Final] and completes. */
    fun stop()

    /** Starts the operating system's dictation in the focused field, or stops it when it runs (see [systemDictation]). */
    fun toggleSystemDictation() {}
}

sealed interface SpeechEvent {
    /** The transcription so far, replaced by the next one. */
    data class Partial(val text: String) : SpeechEvent

    /** The transcription of the whole utterance. */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val error: SpeechError) : SpeechEvent
}

enum class SpeechError {
    /** The member refused the microphone or speech recognition, now or earlier: only the system settings can allow it. */
    PERMISSION_DENIED,

    /** No recognizer for this language, or none reachable now (offline without an on-device model). */
    UNAVAILABLE,

    /** Nothing was heard. Not shown: the member simply taps again. */
    NO_SPEECH,

    FAILED,
}

/** The platform's recognizer, or null where the app has no native transcription (the microphone is not shown). */
@Composable
expect fun rememberSpeechRecognizer(): SpeechRecognizer?
