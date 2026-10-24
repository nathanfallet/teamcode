package me.nathanfallet.teamcode.companion.presentation.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import android.speech.SpeechRecognizer as AndroidRecognizer

/**
 * Android: the system's speech recognizer (`SpeechRecognizer`, the service behind the keyboard's microphone), asked to
 * work offline when the device has the language, with partial results. The microphone permission is asked on the first
 * tap; refused, the composer says so and the system settings can allow it.
 */
@Composable
actual fun rememberSpeechRecognizer(): SpeechRecognizer? {
    val context = LocalContext.current.applicationContext
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pending[0]?.complete(granted) }
    return remember(context, permission) {
        if (!AndroidRecognizer.isRecognitionAvailable(context)) return@remember null
        AndroidSpeechRecognizer(context) {
            val answer = CompletableDeferred<Boolean>()
            pending[0] = answer
            permission.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        }
    }
}

private class AndroidSpeechRecognizer(private val context: Context, private val requestPermission: suspend () -> Boolean) : SpeechRecognizer {
    private var current: AndroidRecognizer? = null

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted && !requestPermission()) {
            send(SpeechEvent.Failed(SpeechError.PERMISSION_DENIED))
            close()
            awaitClose {}
            return@callbackFlow
        }
        val recognizer = AndroidRecognizer.createSpeechRecognizer(context)
        current = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) { text(partialResults)?.let { trySend(SpeechEvent.Partial(it)) } }
            override fun onResults(results: Bundle?) {
                trySend(SpeechEvent.Final(text(results).orEmpty()))
                close()
            }
            override fun onError(error: Int) {
                trySend(SpeechEvent.Failed(error(error)))
                close()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // On the device when it has the language: nothing leaves the phone, and it works without a network.
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
        awaitClose {
            if (current === recognizer) current = null
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate) // The recognizer is created and called on the main thread only.

    override fun stop() { current?.stopListening() }

    private fun text(results: Bundle?): String? = results?.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun error(code: Int): SpeechError = when (code) {
        AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
        AndroidRecognizer.ERROR_NO_MATCH, AndroidRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
        AndroidRecognizer.ERROR_NETWORK, AndroidRecognizer.ERROR_NETWORK_TIMEOUT, AndroidRecognizer.ERROR_SERVER,
        AndroidRecognizer.ERROR_SERVER_DISCONNECTED, AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechError.UNAVAILABLE
        // Busy (the previous listening still closing) and the rest: a retry may work, so not "unavailable".
        else -> SpeechError.FAILED
    }
}
