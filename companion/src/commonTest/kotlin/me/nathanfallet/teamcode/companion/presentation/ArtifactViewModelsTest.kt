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
        ArtifactViewModel(ArtifactRoute("a1", id, version), GetArtifactUseCase(artifacts, guard), OpenArtifactUseCase(artifacts, features, accounts, guard), embedded)

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

    @Test fun aVersionGetsItsOwnTicketAndAMissingPageIsRenewedOnce() = runTest(dispatcher) {
        artifacts.byId[id] = artifact(id, version = 3)
        val model = viewer()
        settle()
        assertEquals(3, model.uiState.value.opened?.ticket?.version)
        assertEquals(listOf(3, 2, 1), model.uiState.value.versions.map { it.number })
        model.selectVersion(1)
        settle()
        assertEquals(1, model.uiState.value.opened?.ticket?.version)
        val first = model.uiState.value.opened?.ticket?.contentUrl
        model.contentMissing()
        settle()
        assertNotEquals(first, model.uiState.value.opened?.ticket?.contentUrl)
        assertNull(model.uiState.value.error)
        // A second 404 on the same version: it is gone, no endless renewal.
        model.contentMissing()
        settle()
        assertNull(model.uiState.value.opened)
        assertNotNull(model.uiState.value.error)
        assertEquals(3, artifacts.calls.count { it.startsWith("ticket") })
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
