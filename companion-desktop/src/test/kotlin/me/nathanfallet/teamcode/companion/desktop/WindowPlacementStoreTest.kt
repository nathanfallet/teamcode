package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.MapSettings
import java.awt.Rectangle
import kotlin.test.*

class WindowPlacementStoreTest {
    private val laptop = Rectangle(0, 0, 1512, 982)
    private val monitor = Rectangle(1512, -200, 2560, 1440)

    @Test fun nothingSavedOpensAtTheDefaultPlace() {
        assertNull(WindowPlacementStore(MapSettings()).load(listOf(laptop)))
    }

    @Test fun theWindowReopensWhereAndAsLargeAsItWasLeft() {
        val settings = MapSettings()
        WindowPlacementStore(settings).save(SavedWindow(1700, 40, 1400, 900))
        assertEquals(SavedWindow(1700, 40, 1400, 900), WindowPlacementStore(settings).load(listOf(laptop, monitor)))
    }

    @Test fun aMaximizedWindowKeepsTheBoundsItReturnsTo() {
        val settings = MapSettings()
        val store = WindowPlacementStore(settings)
        store.save(SavedWindow(100, 80, 1100, 760))
        store.save(SavedWindow(null, null, 1512, 982, maximized = true))
        assertEquals(SavedWindow(100, 80, 1100, 760, maximized = true), store.load(listOf(laptop)))
        store.save(SavedWindow(100, 80, 1100, 760))
        assertFalse(store.load(listOf(laptop))!!.maximized)
    }

    @Test fun aWindowLeftOnAMonitorNoLongerConnectedIsPlacedByTheSystem() {
        val settings = MapSettings()
        WindowPlacementStore(settings).save(SavedWindow(1700, 40, 1400, 900))
        val loaded = WindowPlacementStore(settings).load(listOf(laptop))!!
        assertNull(loaded.x)
        assertNull(loaded.y)
        // And no larger than the screen it opens on.
        assertEquals(1400 to 900, loaded.width to loaded.height)
        WindowPlacementStore(settings).save(SavedWindow(10, 10, 3000, 2000))
        assertEquals(1512 to 982, WindowPlacementStore(settings).load(listOf(laptop))!!.let { it.width to it.height })
    }

    @Test fun aTitleBarAboveTheScreenOrATinySizeIsCorrected() {
        val settings = MapSettings()
        WindowPlacementStore(settings).save(SavedWindow(200, -500, 300, 200))
        val loaded = WindowPlacementStore(settings).load(listOf(laptop))!!
        assertNull(loaded.y)
        assertEquals(WindowPlacementStore.MIN_WIDTH to WindowPlacementStore.MIN_HEIGHT, loaded.width to loaded.height)
    }
}
