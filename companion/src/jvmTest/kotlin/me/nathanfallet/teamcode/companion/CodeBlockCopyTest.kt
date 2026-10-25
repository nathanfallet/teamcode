package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItem
import me.nathanfallet.teamcode.companion.presentation.transcript.TranscriptItemView
import me.nathanfallet.teamcode.companion.presentation.transcript.copyableCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The copy button of a code block: what it copies, when it is there, and that it never moves the layout. */
@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION")
class CodeBlockCopyTest {
    @Test fun anyBlockWithContentHasACopyTextAndAnEmptyOneHasNone() {
        assertNull(copyableCode(""))
        assertNull(copyableCode("\n\n  \n"))
        assertEquals("./gradlew test", copyableCode("./gradlew test"))
        assertEquals("./gradlew test", copyableCode("./gradlew test\n"))
        assertEquals("a\nb", copyableCode("a\nb"))
    }

    @Test fun theCopiedTextHasNoTrailingNewlineAndKeepsIndentation() {
        assertEquals("a\nb", copyableCode("a\nb\n"))
        assertEquals("a\nb", copyableCode("a\nb\n\n\n"))
        assertEquals("a\nb", copyableCode("a\r\nb\r\n"))
        assertEquals("fun f() {\n    return 1\n}", copyableCode("fun f() {\n    return 1\n}\n"))
        assertEquals("    indented\n  less", copyableCode("    indented\n  less\n\n"))
    }

    @Test fun aFencedBlockOfTwoLinesCopiesItsContentWithoutTheFencesOrTheLanguage() = runComposeUiTest {
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
        }
        setContent {
            TeamCodeTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.width(400.dp)) {
                        TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "Run:\n\n```kotlin\nfun main() {\n    println(1)\n}\n```\n\nDone.", null)), memberId = null)
                    }
                }
            }
        }
        onNodeWithTag("code-copy").performClick()
        assertEquals("fun main() {\n    println(1)\n}", copied)
    }

    @Test fun aFenceWithoutLanguageCopiesItToo() = runComposeUiTest {
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
        }
        setContent {
            TeamCodeTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "```\nls -la\ncd ..\n```", null)), memberId = null) }
                }
            }
        }
        onNodeWithTag("code-copy").performClick()
        assertEquals("ls -la\ncd ..", copied)
    }

    @Test fun aSingleLineBlockHasAButtonAndCopiesItsLine() = runComposeUiTest {
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
        }
        setContent {
            TeamCodeTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "```bash\n./gradlew test\n```", null)), memberId = null) }
                }
            }
        }
        onNodeWithTag("code-copy").performClick()
        assertEquals("./gradlew test", copied)
    }

    @Test fun anIndentedBlockHasAButtonAndCopiesItsContent() = runComposeUiTest {
        var copied: String? = null
        val clipboard = object : ClipboardManager {
            override fun getText() = copied?.let(::AnnotatedString)
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
        }
        setContent {
            TeamCodeTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "Run:\n\n    ./gradlew test\n    ./gradlew build", null)), memberId = null) }
                }
            }
        }
        onNodeWithTag("code-copy").performClick()
        assertEquals("./gradlew test\n./gradlew build", copied)
    }

    @Test fun inlineCodeHasNoButtonEvenAloneOnItsLine() = runComposeUiTest {
        setContent {
            TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "`./gradlew test`", null)), memberId = null) } }
        }
        onNodeWithTag("code-copy").assertDoesNotExist()
    }

    @Test fun inlineCodeInASentenceHasNoButton() = runComposeUiTest {
        setContent {
            TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "Use `foo` here.", null)), memberId = null) } }
        }
        onNodeWithTag("code-copy").assertDoesNotExist()
    }

    @Test fun theButtonDoesNotChangeTheHeightOfTheBlockAfterACopy() = runComposeUiTest {
        setContent {
            TeamCodeTheme { Box(Modifier.width(400.dp)) { TranscriptItemView(TranscriptItem.Agent(TranscriptEvent.AgentMessage(1, 0, "```\na\nb\n```", null)), memberId = null) } }
        }
        val before = onNodeWithTag("agent-1").getBoundsInRoot().let { it.bottom - it.top }
        onNodeWithTag("code-copy").performClick()
        waitForIdle()
        val after = onNodeWithTag("agent-1").getBoundsInRoot().let { it.bottom - it.top }
        assertEquals(before, after)
    }
}
