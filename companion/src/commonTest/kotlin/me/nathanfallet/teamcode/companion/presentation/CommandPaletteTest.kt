package me.nathanfallet.teamcode.companion.presentation

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Plus
import me.nathanfallet.teamcode.companion.presentation.screens.PALETTE_RECENT
import me.nathanfallet.teamcode.companion.presentation.screens.PaletteItem
import me.nathanfallet.teamcode.companion.presentation.screens.PaletteSection
import me.nathanfallet.teamcode.companion.presentation.screens.paletteMatches
import me.nathanfallet.teamcode.companion.presentation.screens.sidebarWidthIndex
import me.nathanfallet.teamcode.companion.presentation.screens.SidebarWidths
import kotlin.test.*

class CommandPaletteTest {
    private fun item(id: String, section: PaletteSection, title: String, detail: String = "", keywords: String = "") =
        PaletteItem(id, section, title, PhosphorIcons.Regular.Plus, detail, keywords) {}

    private val items = listOf(
        item("new", PaletteSection.ACTIONS, "New session"),
        item("settings", PaletteSection.ACTIONS, "Settings", keywords = "account preferences theme"),
    ) + (0 until 10).map { item("s$it", PaletteSection.SESSIONS, "Session $it", detail = "claude · acme/web") } + listOf(
        item("cache", PaletteSection.SESSIONS, "Speed up the CI cache", detail = "codex · acme/infra"),
        item("r1", PaletteSection.ARTIFACTS, "CI cache benchmark", detail = "Ada"),
        item("r2", PaletteSection.ARTIFACTS, "Release checklist", detail = "Grace"),
    )

    @Test fun nothingTypedListsTheActionsThenTheMostRecentRows() {
        val shown = paletteMatches(items, "  ")
        assertEquals(listOf("new", "settings"), shown.take(2).map { it.id })
        assertEquals(PALETTE_RECENT, shown.count { it.section == PaletteSection.SESSIONS })
        assertEquals(listOf("r1", "r2"), shown.filter { it.section == PaletteSection.ARTIFACTS }.map { it.id })
    }

    @Test fun everyWordMustMatchAndTheSectionsKeepTheirOrder() {
        assertEquals(listOf("cache", "r1"), paletteMatches(items, "CI cache").map { it.id })
        // Words in any order, in the detail or the keywords too.
        assertEquals(listOf("cache"), paletteMatches(items, "infra speed").map { it.id })
        assertEquals(listOf("settings"), paletteMatches(items, "theme").map { it.id })
        assertEquals(listOf("r2"), paletteMatches(items, "grace").map { it.id })
        assertTrue(paletteMatches(items, "nothing like this").isEmpty())
    }

    @Test fun aTitleThatStartsWithTheQueryComesFirstInItsSection() {
        val shown = paletteMatches(items + item("x", PaletteSection.SESSIONS, "Fix the session cache"), "session").filter { it.section == PaletteSection.SESSIONS }
        assertEquals("s0", shown.first().id)
        assertEquals("x", shown.last().id)
    }

    @Test fun theSavedSidebarWidthIsUsedOnlyWhenItIsOneOfTheWidths() {
        assertEquals(2, sidebarWidthIndex(2, narrow = true))
        assertEquals(0, sidebarWidthIndex(null, narrow = true))
        assertEquals(1, sidebarWidthIndex(null, narrow = false))
        assertEquals(1, sidebarWidthIndex(SidebarWidths.size, narrow = false))
    }
}
