package me.nathanfallet.teamcode.companion.presentation.dictation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.phosphoricons.Fill
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Microphone
import com.adamglin.phosphoricons.regular.Microphone
import me.nathanfallet.teamcode.companion.presentation.components.Banner
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.Tone
import me.nathanfallet.teamcode.companion.presentation.components.handCursor
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.composer_dictate
import me.nathanfallet.teamcode.companion.resources.composer_dictate_stop
import me.nathanfallet.teamcode.companion.resources.composer_listening
import me.nathanfallet.teamcode.companion.resources.dictation_failed
import me.nathanfallet.teamcode.companion.resources.dictation_permission_denied
import me.nathanfallet.teamcode.companion.resources.dictation_unavailable
import me.nathanfallet.teamcode.companion.resources.files_dismiss
import org.jetbrains.compose.resources.stringResource

/** The composer's dictation, or null where the platform has no native transcription (no microphone is shown). */
@Composable
fun rememberDictation(): Dictation? {
    val recognizer = rememberSpeechRecognizer() ?: return null
    val scope = rememberCoroutineScope()
    val dictation = remember(recognizer, scope) { Dictation(recognizer, scope) }
    // Leaving the session stops the microphone.
    DisposableEffect(dictation) { onDispose { dictation.cancel() } }
    return dictation
}

/** True while the app itself listens (not while the operating system's own dictation does). */
@Composable
fun Dictation.listening(): Boolean = uiState.collectAsStateWithLifecycle().value == DictationState.Listening

/** The placeholder of the composer while listening. */
@Composable
fun listeningHint(): String = stringResource(Res.string.composer_listening)

/**
 * The microphone inside the composer: a tap listens, a second one stops. While listening it is filled and pulses.
 * [field] is focused first where the operating system types into the focused field (macOS).
 */
@Composable
fun DictationButton(dictation: Dictation, draft: () -> String, onDraft: (String) -> Unit, field: FocusRequester) {
    val colors = TeamCode.colors
    val listening = dictation.listening()
    val pulse by rememberInfiniteTransition().animateFloat(1f, 0.55f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    val label = stringResource(if (listening) Res.string.composer_dictate_stop else Res.string.composer_dictate)
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (listening) colors.accent.copy(alpha = pulse) else androidx.compose.ui.graphics.Color.Transparent)
            .handCursor()
            .clickable(role = Role.Button, onClickLabel = label) {
                if (dictation.systemDictation) field.requestFocus()
                dictation.toggle(draft, onDraft)
            }
            .semantics { stateDescription = if (listening) "listening" else "idle" }
            .testTag(if (listening) "dictation-listening" else "dictate"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (listening) PhosphorIcons.Fill.Microphone else PhosphorIcons.Regular.Microphone, contentDescription = label,
            tint = if (listening) colors.onAccent else colors.muted, modifier = Modifier.size(18.dp).alpha(if (listening) 1f else 0.9f),
        )
    }
}

/** Why the microphone stopped, above the composer, until dismissed or the next tap. */
@Composable
fun DictationNotice(dictation: Dictation) {
    val state = dictation.uiState.collectAsStateWithLifecycle().value as? DictationState.Failed ?: return
    val text = stringResource(when (state.error) {
        SpeechError.PERMISSION_DENIED -> Res.string.dictation_permission_denied
        SpeechError.UNAVAILABLE -> Res.string.dictation_unavailable
        SpeechError.NO_SPEECH, SpeechError.FAILED -> Res.string.dictation_failed
    })
    Banner(text, Tone.WARN, Modifier.testTag("dictation-notice"), action = {
        TcButton(stringResource(Res.string.files_dismiss), onClick = dictation::dismissError, kind = ButtonKind.GHOST, small = true)
    })
}
