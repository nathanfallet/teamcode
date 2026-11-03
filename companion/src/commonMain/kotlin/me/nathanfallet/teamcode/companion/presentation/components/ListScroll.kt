package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Keeps a list at its top when its first row changes. A lazy list holds on to the row it shows by its key, so a row that
 * arrives above the first one shown carries the view down with the old one: a list whose only row was the end one
 * (before its first page) scrolled down to its end, and a live list with a new first row opened a row below the top.
 * A list at the top stays at the top; one scrolled down keeps its place. [firstRow] names the first row: its key, or the
 * key of the end row while there is nothing else.
 */
@Composable
fun KeepAtTopOnNewFirstRow(listState: LazyListState, firstRow: Any?) {
    val shown = remember { arrayOf(firstRow) }
    if (shown[0] != firstRow) {
        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        shown[0] = firstRow
    }
}
