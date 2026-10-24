package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.presentation.dictation.Dictation
import me.nathanfallet.teamcode.companion.presentation.dictation.DictationState
import me.nathanfallet.teamcode.companion.presentation.dictation.SpeechError
import me.nathanfallet.teamcode.companion.presentation.dictation.SpeechEvent
import me.nathanfallet.teamcode.companion.presentation.dictation.SpeechRecognizer
import kotlin.test.*

/** A recognizer the test speaks into: each [say] is what the platform would report next. */
private class FakeRecognizer(override val systemDictation: Boolean = false) : SpeechRecognizer {
    private var events = Channel<SpeechEvent>(Channel.UNLIMITED)
    var listens = 0
    var stops = 0
    var toggles = 0
    var cancelled = 0

    override fun listen(): Flow<SpeechEvent> {
        listens++
        events = Channel(Channel.UNLIMITED)
        val current = events
        return flow { current.consumeAsFlow().collect { emit(it); if (it is SpeechEvent.Final || it is SpeechEvent.Failed) current.close() } }
            .onCompletion { cause -> if (cause != null) cancelled++ }
    }

    override fun stop() { stops++ }
    override fun toggleSystemDictation() { toggles++ }
    fun say(event: SpeechEvent) { events.trySend(event) }
    fun end() { events.close() }
}

/** The composer's draft, as the view model keeps it. */
private class Draft(var text: String = "") {
    val read = { text }
    val write = { value: String -> text = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictationTest {
    private fun TestScope.dictation(recognizer: SpeechRecognizer) = Dictation(recognizer, backgroundScope)

    @Test fun partialResultsStreamIntoTheDraftAndTheFinalOneEndsListening() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        assertEquals(DictationState.Listening, dictation.uiState.value)
        recognizer.say(SpeechEvent.Partial("fix the")); runCurrent()
        assertEquals("fix the", draft.text)
        recognizer.say(SpeechEvent.Partial("fix the login test")); runCurrent()
        assertEquals("fix the login test", draft.text)
        recognizer.say(SpeechEvent.Final("Fix the login test.")); runCurrent()
        assertEquals("Fix the login test.", draft.text)
        assertEquals(DictationState.Idle, dictation.uiState.value)
    }

    @Test fun theWordsFollowWhatTheMemberHadTyped() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft("Then")
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("run it")); runCurrent()
        assertEquals("Then run it", draft.text)
        // An empty partial (the recognizer revising its first guess) gives the typed text back as it was.
        recognizer.say(SpeechEvent.Partial("")); runCurrent()
        assertEquals("Then", draft.text)
    }

    @Test fun aSecondTapAsksForTheFinalWordsInsteadOfDroppingThem() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("hello")); runCurrent()
        dictation.toggle(draft.read, draft.write); runCurrent()
        assertEquals(1, recognizer.stops)
        assertEquals(1, recognizer.listens, "a second tap does not listen again")
        assertEquals(DictationState.Listening, dictation.uiState.value, "listening until the final words arrive")
        recognizer.say(SpeechEvent.Final("Hello.")); runCurrent()
        assertEquals("Hello.", draft.text)
        assertEquals(DictationState.Idle, dictation.uiState.value)
    }

    @Test fun anEmptyFinalResultKeepsTheWordsAlreadyShown() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft("Note:")
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("check the logs")); runCurrent()
        recognizer.say(SpeechEvent.Final("")); runCurrent()
        assertEquals("Note: check the logs", draft.text)
        assertEquals(DictationState.Idle, dictation.uiState.value)
    }

    @Test fun nothingIsEverSent() = runTest {
        // The dictation only writes the draft: there is no send callback to call, and the draft stays for review.
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Final("ship it")); runCurrent()
        assertEquals("ship it", draft.text)
    }

    @Test fun aRefusedPermissionIsShownAndTheNextTapListensAgain() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft("kept")
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Failed(SpeechError.PERMISSION_DENIED)); runCurrent()
        assertEquals(DictationState.Failed(SpeechError.PERMISSION_DENIED), dictation.uiState.value)
        assertEquals("kept", draft.text)
        dictation.toggle(draft.read, draft.write); runCurrent()
        assertEquals(2, recognizer.listens)
        assertEquals(DictationState.Listening, dictation.uiState.value)
    }

    @Test fun anErrorCanBeDismissed() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("half")); runCurrent()
        recognizer.say(SpeechEvent.Failed(SpeechError.UNAVAILABLE)); runCurrent()
        assertEquals("half", draft.text, "the words heard before the error stay")
        dictation.dismissError()
        assertEquals(DictationState.Idle, dictation.uiState.value)
    }

    @Test fun silenceIsNoError() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Failed(SpeechError.NO_SPEECH)); runCurrent()
        assertEquals(DictationState.Idle, dictation.uiState.value)
    }

    @Test fun aRecognizerThatEndsWithoutFinalWordsReturnsToIdle() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("so")); runCurrent()
        recognizer.end(); runCurrent()
        assertEquals(DictationState.Idle, dictation.uiState.value)
        assertEquals("so", draft.text)
    }

    @Test fun aRecognizerThatThrowsIsAFailure() = runTest {
        val recognizer = object : SpeechRecognizer {
            override fun listen(): Flow<SpeechEvent> = flow { throw IllegalStateException("audio engine") }
            override fun stop() {}
        }
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        assertEquals(DictationState.Failed(SpeechError.FAILED), dictation.uiState.value)
    }

    @Test fun typingWhileListeningStopsListeningAndKeepsWhatWasTyped() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("first")); runCurrent()
        draft.text = "first, edited by hand"
        recognizer.say(SpeechEvent.Partial("first words")); runCurrent()
        assertEquals("first, edited by hand", draft.text)
        assertEquals(DictationState.Idle, dictation.uiState.value)
        assertEquals(1, recognizer.cancelled, "the recognizer stopped listening")
    }

    @Test fun cancellingDropsTheRestAndReleasesTheRecognizer() = runTest {
        val recognizer = FakeRecognizer()
        val dictation = dictation(recognizer)
        val draft = Draft()
        dictation.toggle(draft.read, draft.write); runCurrent()
        recognizer.say(SpeechEvent.Partial("about to send")); runCurrent()
        dictation.cancel(); runCurrent()
        assertEquals(DictationState.Idle, dictation.uiState.value)
        assertEquals(1, recognizer.cancelled)
        // The draft was sent and cleared: words that would still arrive do not refill it.
        draft.text = ""
        recognizer.say(SpeechEvent.Final("about to send it")); runCurrent()
        assertEquals("", draft.text)
    }

    @Test fun systemDictationOnlyTogglesTheOperatingSystem() = runTest {
        val recognizer = FakeRecognizer(systemDictation = true)
        val dictation = dictation(recognizer)
        val draft = Draft("typed")
        dictation.toggle(draft.read, draft.write); runCurrent()
        dictation.toggle(draft.read, draft.write); runCurrent()
        assertEquals(2, recognizer.toggles)
        assertEquals(0, recognizer.listens)
        assertEquals(DictationState.Idle, dictation.uiState.value, "the system shows its own listening indicator")
        assertEquals("typed", draft.text)
    }
}
