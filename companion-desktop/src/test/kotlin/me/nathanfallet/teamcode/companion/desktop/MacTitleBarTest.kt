package me.nathanfallet.teamcode.companion.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class MacTitleBarTest {
    @Test fun aDoubleClickOnTheBarDoesWhatTheMemberChoseInSystemSettings() {
        assertEquals(TitleBarDoubleClick.MINIMIZE, titleBarDoubleClick("Minimize\n"))
        assertEquals(TitleBarDoubleClick.NONE, titleBarDoubleClick("None"))
        assertEquals(TitleBarDoubleClick.ZOOM, titleBarDoubleClick("Maximize"))
        assertEquals(TitleBarDoubleClick.ZOOM, titleBarDoubleClick("Fill"))
        // Never changed: macOS zooms.
        assertEquals(TitleBarDoubleClick.ZOOM, titleBarDoubleClick(null))
    }
}
