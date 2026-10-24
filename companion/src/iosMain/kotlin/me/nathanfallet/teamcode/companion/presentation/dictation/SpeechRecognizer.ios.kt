@file:OptIn(ExperimentalForeignApi::class)

package me.nathanfallet.teamcode.companion.presentation.dictation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume

/**
 * iOS: the Speech framework, fed by the microphone through AVAudioEngine, with partial results. Recognition stays on
 * the device when it can for the member's language (`requiresOnDeviceRecognition`), and goes through Apple's servers
 * otherwise, as the keyboard's dictation does. Both permissions are asked on the first tap.
 */
@Composable
actual fun rememberSpeechRecognizer(): SpeechRecognizer? = remember {
    supportedLocale()?.let { IosSpeechRecognizer(SFSpeechRecognizer(locale = it)) }
}

/**
 * The member's language among the ones the framework transcribes, the region first (en-GB), the language otherwise:
 * the recognizer's initializer returns nil for a language it does not know, which Kotlin's constructor cannot express.
 */
private fun supportedLocale(): NSLocale? {
    fun NSLocale.key() = localeIdentifier.replace('_', '-')
    val current = NSLocale.currentLocale
    val supported = SFSpeechRecognizer.supportedLocales().filterIsInstance<NSLocale>()
    return supported.firstOrNull { it.key() == current.key() }
        ?: supported.firstOrNull { it.key().substringBefore('-') == current.key().substringBefore('-') }
}

private class IosSpeechRecognizer(private val recognizer: SFSpeechRecognizer) : SpeechRecognizer {
    /** Ends the utterance of the current [listen]: no more audio, the final words follow. */
    private var finish: (() -> Unit)? = null

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        fun fail(error: SpeechError) {
            trySend(SpeechEvent.Failed(error))
            close()
        }
        if (!authorizeSpeech() || !authorizeMicrophone()) { fail(SpeechError.PERMISSION_DENIED); awaitClose {}; return@callbackFlow }
        if (!recognizer.isAvailable()) { fail(SpeechError.UNAVAILABLE); awaitClose {}; return@callbackFlow }

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, AVAudioSessionModeMeasurement, AVAudioSessionCategoryOptionDuckOthers, null)
        session.setActive(true, null)
        val request = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            addsPunctuation = true
            if (recognizer.supportsOnDeviceRecognition) requiresOnDeviceRecognition = true
        }
        val engine = AVAudioEngine()
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        // No microphone (a simulator without input): the engine would refuse a tap of zero channels.
        if (format.channelCount == 0u || format.sampleRate == 0.0) {
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
            fail(SpeechError.UNAVAILABLE); awaitClose {}; return@callbackFlow
        }
        input.installTapOnBus(0u, 1024u, format) { buffer, _ -> buffer?.let(request::appendAudioPCMBuffer) }
        var tapped = true
        fun release() {
            if (tapped) { input.removeTapOnBus(0u); tapped = false }
            engine.stop()
        }
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            release()
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
            fail(SpeechError.FAILED); awaitClose {}; return@callbackFlow
        }
        val task: SFSpeechRecognitionTask = recognizer.recognitionTaskWithRequest(request) { result, error ->
            when {
                result != null && result.isFinal() -> { trySend(SpeechEvent.Final(result.bestTranscription.formattedString)); close() }
                result != null -> trySend(SpeechEvent.Partial(result.bestTranscription.formattedString))
                error != null -> fail(error(error))
            }
        }
        finish = {
            release()
            request.endAudio()
        }
        awaitClose {
            finish = null
            task.cancel()
            release()
            session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }

    override fun stop() { finish?.invoke() }

    private suspend fun authorizeSpeech(): Boolean = when (SFSpeechRecognizer.authorizationStatus()) {
        SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized -> true
        SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization { status ->
                continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }
        else -> false
    }

    /** Asks once; later answers come from the system settings, and the request then returns at once. */
    private suspend fun authorizeMicrophone(): Boolean = suspendCancellableCoroutine { continuation ->
        AVAudioSession.sharedInstance().requestRecordPermission { granted -> continuation.resume(granted) }
    }

    /**
     * The Speech framework's codes in `kAFAssistantErrorDomain` are not public constants: 1110 is its "failed to
     * recognize any speech" (silence), 203 the server-side recognizer's limit. Anything else is a failure.
     */
    private fun error(error: NSError): SpeechError = when (error.code) {
        1110L -> SpeechError.NO_SPEECH
        203L -> SpeechError.UNAVAILABLE
        else -> SpeechError.FAILED
    }
}
