package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeArtifactRepository
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.artifact
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.GetArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.LoadArtifactsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val artifacts = FakeArtifactRepository()
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))
    private val features = FakeFeatures(setOf("artifacts.v1", "artifacts.tickets.v1"))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewer(version: Int? = null, embedded: Boolean = true) =
        ArtifactViewModel(ArtifactRoute("a1", id, version), GetArtifactUseCase(artifacts, accounts, guard), OpenArtifactUseCase(artifacts, features, accounts, guard), embedded)

    @Test fun theGalleryLoadsOnePageAtATimeAndAScopeChangeStartsOver() = runTest(dispatcher) {
        artifacts.pages = { scope, before -> if (before == null) ArtifactPage(listOf(artifact("${scope.wire}-1")), "c1") else ArtifactPage(listOf(artifact("${scope.wire}-2")), null) }
        val model = ArtifactsViewModel(ArtifactsRoute("a1", sessionId = "s1"), LoadArtifactsUseCase(artifacts, features, guard))
        settle()
        assertEquals(listOf("team-1"), model.uiState.value.artifacts.map { it.id })
        model.loadMore(); model.loadMore()
        settle()
        assertEquals(listOf("list team s1 null", "list team s1 c1"), artifacts.calls)
        assertEquals(listOf("team-1", "team-2"), model.uiState.value.artifacts.map { it.id })
        model.onQuery("TEAM-2")
        assertEquals(listOf("team-2"), model.uiState.value.shown.map { it.id })
        model.setScope(ArtifactScope.MINE)
        settle()
        assertEquals(listOf("mine-1"), model.uiState.value.artifacts.map { it.id })
    }

    @Test fun theGallerySaysWhenTheServerHasNoArtifacts() = runTest(dispatcher) {
        features.features = emptySet()
        val model = ArtifactsViewModel(ArtifactsRoute("a1"), LoadArtifactsUseCase(artifacts, features, guard))
        settle()
        assertNotNull(model.uiState.value.error)
        assertTrue(artifacts.calls.isEmpty())
    }

    @Test fun aVersionGetsItsOwnTicketAndAnExpiredOneIsRenewed() = runTest(dispatcher) {
        artifacts.byId[id] = artifact(id, version = 3)
        // A ticket still valid comes back unchanged; [expired] makes the next one differ.
        var expired = 0
        artifacts.tickets = { a, v -> me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket(a, v ?: 1, "https://team.example/artifact/$a/t$expired/", 9_000_000) }
        val model = viewer()
        settle()
        assertEquals(3, model.uiState.value.opened?.ticket?.version)
        assertEquals(listOf(3, 2, 1), model.uiState.value.versions.map { it.number })
        assertEquals("https://team.example/artifact/$id", model.uiState.value.viewerUrl)
        model.selectVersion(1)
        settle()
        assertEquals(1, model.uiState.value.opened?.ticket?.version)
        // The link copied or opened is the version shown.
        assertEquals("https://team.example/artifact/$id?v=1", model.uiState.value.versionUrl)
        val reload = model.uiState.value.reload
        // A 404 with a ticket still valid: a link to a page the version lacks, nothing reloads by itself.
        model.contentMissing("https://team.example/artifact/$id/t0/missing.html")
        settle()
        assertTrue(model.uiState.value.missingPage)
        assertEquals(reload, model.uiState.value.reload)
        model.restart()
        assertFalse(model.uiState.value.missingPage)
        assertEquals(reload + 1, model.uiState.value.reload)
        // Expired: the new ticket differs and loads, whatever the device clock says.
        expired = 1
        model.contentMissing("https://team.example/artifact/$id/t0/notes.html")
        settle()
        assertEquals("https://team.example/artifact/$id/t1/", model.uiState.value.opened?.ticket?.contentUrl)
        assertEquals(reload + 2, model.uiState.value.reload)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.missingPage)
        // The page that answered is asked again under the new ticket, not the entry page: a ticket older than the
        // rounding step differs without having expired, so only a second 404, on a fresh ticket, says the page is missing.
        assertEquals("https://team.example/artifact/$id/t1/notes.html", model.uiState.value.page)
        model.contentMissing("https://team.example/artifact/$id/t1/notes.html")
        settle()
        assertTrue(model.uiState.value.missingPage)
        model.restart()
        assertNull(model.uiState.value.page)
        // A renewal that fails for a passing reason keeps the version shown, its ticket still good.
        artifacts.tickets = { _, _ -> throw me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server(429, "artifact_ticket_rate_limited") }
        model.contentMissing("https://team.example/artifact/$id/t1/notes.html")
        settle()
        assertEquals("https://team.example/artifact/$id/t1/", model.uiState.value.opened?.ticket?.contentUrl)
        assertTrue(model.uiState.value.missingPage)
        assertNull(model.uiState.value.error)
    }

    @Test fun aCrashedPageIsLoadedAgainOnce() = runTest(dispatcher) {
        artifacts.byId[id] = artifact(id)
        val model = viewer()
        settle()
        val reload = model.uiState.value.reload
        model.contentCrashed()
        assertEquals(reload + 1, model.uiState.value.reload)
        model.contentCrashed()
        assertNull(model.uiState.value.opened)
        assertNotNull(model.uiState.value.error)
    }

    @Test fun aNewerVersionIsOfferedNotLoadedAndTheDesktopAsksForNoTicket() = runTest(dispatcher) {
        artifacts.byId[id] = artifact(id, version = 2)
        val model = viewer()
        model.start()
        settle()
        artifacts.byId[id] = artifact(id, version = 3)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, model.uiState.value.newerVersion)
        assertEquals(2, model.uiState.value.opened?.ticket?.version)
        model.showNewer()
        settle()
        assertEquals(3, model.uiState.value.opened?.ticket?.version)
        assertNull(model.uiState.value.newerVersion)
        model.stop()

        artifacts.calls.clear()
        val desktop = viewer(embedded = false)
        settle()
        assertEquals(3, desktop.uiState.value.version)
        assertTrue(artifacts.calls.none { it.startsWith("ticket") })
        assertFalse(desktop.uiState.value.loading)
    }

    @Test fun aVersionTheArtifactDoesNotHaveIsGone() = runTest(dispatcher) {
        artifacts.byId[id] = artifact(id, version = 2)
        val model = viewer(version = 7)
        settle()
        assertTrue(model.uiState.value.gone)
        assertTrue(artifacts.calls.none { it.startsWith("ticket") })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
