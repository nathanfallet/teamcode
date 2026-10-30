package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** An artifact opened over a conversation from a link in it: its [route], and its viewer page for the browser. */
data class ArtifactOverlayTarget(val route: ArtifactRoute, val viewerUrl: String) {
    companion object {
        /** Kept across a rotation or a process death, as plain strings: the overlay opens again on the same artifact and version. */
        val Saver: Saver<ArtifactOverlayTarget?, Any> = Saver(
            save = { target -> target?.let { listOf(it.route.accountId, it.route.artifactId, it.route.version?.toString().orEmpty(), it.viewerUrl) } ?: emptyList<String>() },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST") (saved as List<String>).takeIf { it.size == 4 }
                    ?.let { (account, artifact, version, url) -> ArtifactOverlayTarget(ArtifactRoute(account, artifact, version.toIntOrNull()), url) }
            },
        )
    }
}

/**
 * The view models of the overlay, in a store of their own kept by the screen underneath: they survive a rotation with
 * it, and [clear] drops them when the overlay closes instead of keeping them until the screen goes.
 */
class ArtifactOverlayViewModels : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    fun clear() = viewModelStore.clear()
    override fun onCleared() = clear()
}

/**
 * An artifact over the screen that opened it (a conversation): the whole screen on a phone, a large dialog on a
 * tablet or a desktop window. The same viewer as the Artifacts tab ([ArtifactScreen]): the content where the platform
 * embeds it, the title, version and "Open in the browser" otherwise (the desktop, for now). The close button, the
 * system back and Escape close it; an artifact that does not load offers [ArtifactOverlayTarget.viewerUrl] in the browser.
 */
@Composable
fun ArtifactOverlay(target: ArtifactOverlayTarget, viewModels: ArtifactOverlayViewModels, onDismiss: () -> Unit) {
    val wide = LocalWideLayout.current
    // A dialog is a composition of its own, where the platform provides its own browser handler again: the app's goes in.
    val browser = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels, LocalUriHandler provides browser) {
            // One view model per artifact and version opened from the link: another link opens on its own state.
            val viewModel: ArtifactViewModel = koinViewModel(key = "${target.route}") { parametersOf(target.route) }
            // Escape closes it from anywhere in it: the overlay takes the focus when it opens, so a key always has a target.
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { focus.requestFocus() }
            val frame = Modifier.focusRequester(focus).focusable()
                .onKeyEvent { event -> (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismiss() } }
                .testTag("artifact-overlay")
            Box(
                if (wide) Modifier.padding(24.dp).widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight().clip(Radii.card).border(1.dp, TeamCode.colors.line, Radii.card).then(frame)
                else Modifier.fillMaxSize().then(frame),
            ) { ArtifactScreen(viewModel, onBack = null, onClose = onDismiss, fallbackUrl = target.viewerUrl) }
        }
    }
}
