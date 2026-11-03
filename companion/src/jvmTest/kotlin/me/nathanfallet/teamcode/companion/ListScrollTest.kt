package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.presentation.components.KeepAtTopOnNewFirstRow
import kotlin.test.Test

/** The rule every list of the app shares: a new first row never carries a list at its top down, nor moves one scrolled down. */
@OptIn(ExperimentalTestApi::class)
class ListScrollTest {
    private var rows by mutableStateOf(emptyList<String>())

    private fun androidx.compose.ui.test.ComposeUiTest.show() = setContent {
        val state = rememberLazyListState()
        KeepAtTopOnNewFirstRow(state, rows.firstOrNull() ?: "end")
        Column(Modifier.height(300.dp)) {
            LazyColumn(Modifier.fillMaxWidth().testTag("list"), state = state) {
                items(rows, key = { it }) { Text(it, Modifier.height(60.dp)) }
                item(key = "end") { Text("end", Modifier.height(60.dp)) }
            }
        }
    }

    @Test fun aFirstPageArrivingAboveTheEndRowOpensOnItsFirstRow() = runComposeUiTest {
        show(); waitForIdle()
        rows = (1..20).map { "Row ${"%02d".format(it)}" }
        waitForIdle()
        onNodeWithText("Row 01").assertIsDisplayed()
    }

    @Test fun aNewFirstRowKeepsAListAtTheTopAtTheTop() = runComposeUiTest {
        rows = (2..20).map { "Row ${"%02d".format(it)}" }
        show(); waitForIdle()
        rows = listOf("Row 01") + rows
        waitForIdle()
        onNodeWithText("Row 01").assertIsDisplayed()
    }

    @Test fun aNewFirstRowDoesNotMoveAListScrolledDown() = runComposeUiTest {
        rows = (2..30).map { "Row ${"%02d".format(it)}" }
        show(); waitForIdle()
        onNodeWithTag("list").performScrollToIndex(12); waitForIdle()
        onNodeWithText("Row 14").assertIsDisplayed()
        rows = listOf("Row 01") + rows
        waitForIdle()
        onNodeWithText("Row 14").assertIsDisplayed()
    }
}
