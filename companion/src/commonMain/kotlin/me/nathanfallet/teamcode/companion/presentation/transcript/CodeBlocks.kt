package me.nathanfallet.teamcode.companion.presentation.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Copy
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.transcript_code_copied
import me.nathanfallet.teamcode.companion.resources.transcript_code_copy
import org.jetbrains.compose.resources.stringResource

/**
 * What the copy button puts in the clipboard for a code block's content, or null for an empty block (no button). Any
 * block with content has one, even a single line: a lone command is what one wants to copy. Exactly the content: no
 * fence lines or language (the renderer already took them off), indentation kept, the trailing newline and blank lines
 * dropped.
 */
internal fun copyableCode(code: String): String? {
    val lines = code.lines().map { it.trimEnd('\r') }.dropLastWhile { it.isBlank() }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

/** The Markdown components of the agent's text: the renderer's own, with a copy button on code blocks (inline code has none). */
internal fun transcriptMarkdownComponents(): MarkdownComponents = markdownComponents(
    codeFence = { MarkdownCodeFence(it.content, it.node, it.typography.code) { code, _, style -> CodeBlock(code, style) } },
    codeBlock = { MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, _, style -> CodeBlock(code, style) } },
)

/**
 * A code block that scrolls sideways when a line is wide. The button sits in the top-right corner over room kept free
 * for it, and the block's height depends on the code only: it is final on the first measure, and the "copied" state
 * swaps the icon at the same size (a row growing after its first measure makes the conversation jump while scrolling).
 */
@Suppress("DEPRECATION") // LocalClipboardManager: plain text is all a code block needs, on every platform.
@Composable
private fun CodeBlock(code: String, style: TextStyle) {
    val colors = TeamCode.colors
    val markdownPadding = LocalMarkdownPadding.current.codeBlock
    val copyable = remember(code) { copyableCode(code) }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(LocalMarkdownColors.current.codeBackground)) {
        Text(
            code.trimEnd('\n', '\r'),
            style = style.copy(color = LocalMarkdownColors.current.text),
            softWrap = false,
            modifier = Modifier.padding(end = if (copyable != null) COPY_BUTTON_SIZE else 0.dp).horizontalScroll(rememberScrollState()).padding(markdownPadding),
        )
        if (copyable != null) {
            val clipboard = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(1_500)
                    copied = false
                }
            }
            val label = stringResource(if (copied) Res.string.transcript_code_copied else Res.string.transcript_code_copy)
            Box(
                Modifier.align(Alignment.TopEnd).size(COPY_BUTTON_SIZE).testTag("code-copy")
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = label) { clipboard.setText(AnnotatedString(copyable)); copied = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (copied) PhosphorIcons.Regular.Check else PhosphorIcons.Regular.Copy, label,
                    Modifier.size(16.dp), tint = if (copied) colors.accentText else colors.muted,
                )
            }
        }
    }
}

private val COPY_BUTTON_SIZE = 32.dp

private fun Modifier.fillMaxWidth(): Modifier = this.then(Modifier.fillMaxWidth())
