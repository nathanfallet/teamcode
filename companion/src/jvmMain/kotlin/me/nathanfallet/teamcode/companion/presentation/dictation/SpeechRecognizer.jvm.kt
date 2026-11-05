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
 * the text input protocol, which the field takes like any input method. On Windows 10 and 11 the app presses Win+H,
 * which opens the system's Voice Typing on the focused field the same way. Linux shows no microphone.
 */
@Composable
actual fun rememberSpeechRecognizer(): SpeechRecognizer? = remember { desktopDictation() }

private fun desktopDictation(): SpeechRecognizer? {
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac") -> MacDictation.takeIf { it.available }
        os.startsWith("Windows") -> WindowsVoiceTyping.takeIf { it.available }
        else -> null
    }
}

/**
 * Windows Voice Typing has no API: the Win+H shortcut opens it on the focused field, and pressed again closes it. The
 * shortcut is sent with `keybd_event`; the system types the words through its own input, with its own microphone panel.
 */
internal object WindowsVoiceTyping : SpeechRecognizer {
    override val systemDictation = true

    private const val VK_LWIN = 0x5B
    private const val VK_H = 0x48
    private const val KEYEVENTF_KEYUP = 0x2

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy null
        try { Native.load("user32", User32::class.java) } catch (_: Throwable) { null }
    }

    /** Voice Typing with Win+H exists from Windows 10 (version 1903): the JVM reports 10.0 for Windows 10 and 11. */
    val available: Boolean by lazy {
        user32 != null && (System.getProperty("os.version").orEmpty().substringBefore('.').toIntOrNull() ?: 0) >= 10
    }

    override fun toggleSystemDictation() {
        val user32 = user32?.takeIf { available } ?: return
        try {
            user32.keybd_event(VK_LWIN.toByte(), 0, 0, null)
            user32.keybd_event(VK_H.toByte(), 0, 0, null)
            user32.keybd_event(VK_H.toByte(), 0, KEYEVENTF_KEYUP, null)
        } finally {
            user32.keybd_event(VK_LWIN.toByte(), 0, KEYEVENTF_KEYUP, null)
        }
    }

    override fun listen(): Flow<SpeechEvent> = emptyFlow()
    override fun stop() {}

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun keybd_event(key: Byte, scan: Byte, flags: Int, extra: Pointer?)
    }
}

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
