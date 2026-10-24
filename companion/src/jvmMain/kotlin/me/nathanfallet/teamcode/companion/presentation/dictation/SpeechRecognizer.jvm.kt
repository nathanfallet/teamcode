package me.nathanfallet.teamcode.companion.presentation.dictation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The desktop has no portable speech API. On macOS the app starts the system's own Dictation (Edit > Start Dictation…)
 * in the focused field: the system transcribes, shows its listening indicator at the caret and types the words through
 * the text input protocol, which the field takes like any input method. Windows and Linux show no microphone: Windows
 * Voice Typing (Win+H) can still be started by the member, the app does not synthesize the shortcut.
 */
@Composable
actual fun rememberSpeechRecognizer(): SpeechRecognizer? = remember { MacDictation.takeIf { it.available } }

private object MacDictation : SpeechRecognizer {
    override val systemDictation = true

    private val objc: ObjC? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return@lazy null
        try { Native.load("objc", ObjC::class.java) } catch (_: Throwable) { null }
    }

    /** Checked once: an Objective-C exception for an unknown selector on AppKit's thread would end the whole app. */
    val available: Boolean by lazy {
        val objc = objc ?: return@lazy false
        val application = application(objc) ?: return@lazy false
        objc.objc_msgSend(application, objc.sel_registerName("respondsToSelector:"), objc.sel_registerName("startDictation:")) != 0.toByte()
    }

    private fun application(objc: ObjC): Pointer? = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"))

    /**
     * `startDictation:` is the action of Edit > Start Dictation…, handled by NSApplication; sent again while dictating,
     * it stops. AppKit is only called on its main thread, which is not the one Compose runs on: the application performs
     * it there.
     */
    override fun toggleSystemDictation() {
        val objc = objc?.takeIf { available } ?: return
        val application = application(objc) ?: return
        objc.objc_msgSend(
            application, objc.sel_registerName("performSelectorOnMainThread:withObject:waitUntilDone:"),
            objc.sel_registerName("startDictation:"), null, false,
        )
    }

    override fun listen(): Flow<SpeechEvent> = emptyFlow()
    override fun stop() {}

    /** The Objective-C runtime, with `objc_msgSend` declared per signature: it must not be called as a variadic function. */
    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?): Pointer?
        /** A `BOOL` result: one byte, the register's upper bits are not defined. */
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, argument: Pointer?): Byte
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, action: Pointer?, argument: Pointer?, wait: Boolean): Pointer?
    }
}
