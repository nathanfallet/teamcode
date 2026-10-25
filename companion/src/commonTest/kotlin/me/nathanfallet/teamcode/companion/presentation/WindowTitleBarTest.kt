package me.nathanfallet.teamcode.companion.presentation

import androidx.compose.ui.unit.dp
import me.nathanfallet.teamcode.companion.presentation.components.WindowTitleBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowTitleBarTest {
    @Test fun macOs26AndLaterSeatsTheButtonsLowerSoTheBarIsTallerAndTheHeaderCentersOnThem() {
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac("15.6.1"))
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac("14"))
        assertEquals(WindowTitleBar.MAC_TAHOE, WindowTitleBar.forMac("26.0"))
        assertEquals(WindowTitleBar.MAC_TAHOE, WindowTitleBar.forMac("27.1.2"))
        // Unknown: the older, smaller bar.
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac(null))
        assertEquals(WindowTitleBar.MAC, WindowTitleBar.forMac("x"))
        assertTrue(WindowTitleBar.MAC_TAHOE.height > WindowTitleBar.MAC.height && WindowTitleBar.MAC_TAHOE.start >= WindowTitleBar.MAC.start)
        assertEquals(0.dp, WindowTitleBar().height)
    }
}
