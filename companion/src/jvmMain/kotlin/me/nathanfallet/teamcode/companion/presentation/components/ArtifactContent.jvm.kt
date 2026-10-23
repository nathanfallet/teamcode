package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val embedsArtifacts: Boolean = false

/** Never shown: the desktop has no embedded browser, the screen opens the viewer page in the system browser instead. */
@Composable
actual fun ArtifactContentView(contentUrl: String, prefix: String, reload: Int, onExternal: (String) -> Unit, onMissing: (String) -> Unit, onCrashed: () -> Unit, modifier: Modifier) = Unit
