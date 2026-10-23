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
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeTransferRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.admin
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.TransferRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferError
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import me.nathanfallet.teamcode.companion.transfer
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val transfers = FakeTransferRepository()
    private val features = FakeFeatures(setOf("cloud.sessions.v1", "cloud.transfer.v1"))

    private fun form(accounts: InMemoryAccountRepository, ownerId: String = "m1"): TransferViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return TransferViewModel(TransferRoute("a1", "s1", ownerId), LoadRecipientsUseCase(transfers, accounts, guard),
            TransferSessionUseCase(transfers, features, accounts, guard), accounts, SequenceIds())
    }

    private fun list(accounts: InMemoryAccountRepository): TransfersViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return TransfersViewModel(TransfersRoute("a1"), TransferListUseCase(transfers, accounts, guard), accounts)
    }

    @Test fun anAdministratorSearchesAMemberAndTransfersAtOnceWithOptions() = runTest(dispatcher) {
        transfers.members = listOf(TeamMember("m2", "grace", false, true), TeamMember("m3", "linus", false, false), TeamMember("m1", "ada", true, true))
        val model = form(InMemoryAccountRepository(listOf(admin(memberId = "m9"))))
        settle()
        val state = model.uiState.value
        assertTrue(state.administrator)
        assertEquals(listOf("m2", "m3"), state.members?.map { it.id })
        model.onQuery("GRA")
        assertEquals(listOf("m2"), model.uiState.value.matches.map { it.id })
        // A member who cannot receive it (disabled, not set up) cannot be sent to.
        model.onRecipient("m3")
        assertFalse(model.uiState.value.canSend)
        model.onRecipient("m2"); model.onResume(true); model.onForce(true)
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        assertEquals("transfer s1 m2 resume=true force=true 00000000-0000-4000-8000-000000000004", transfers.calls.last())
        assertEquals(TransferState.ACCEPTED, model.uiState.value.done?.state)
        assertFalse(model.uiState.value.canSend)
    }

    @Test fun aRetryKeepsTheKeyAndAnotherRecipientDrawsANewOne() = runTest(dispatcher) {
        val model = form(InMemoryAccountRepository(listOf(account(memberId = "m1"))))
        settle()
        // A member names the recipient by ID: no member list.
        assertNull(model.uiState.value.members)
        model.onRecipient("m2")
        transfers.transferResult = { throw CompanionException.Unreachable() }
        model.send(); settle()
        assertIs<TransferError.Text>(model.uiState.value.error)
        // Toggling an administrator's option changes nothing a member sends: same key.
        model.onResume(true)
        model.send(); settle()
        val sent = transfers.calls.filter { it.startsWith("transfer") }
        assertEquals(2, sent.size); assertEquals(sent[0], sent[1])
        assertTrue(sent[0].contains("resume=false"))
        model.onRecipient("m3")
        transfers.transferResult = { throw CompanionException.TransferRefused("recipient_ineligible", listOf("profile_unassigned", "recipient_no_quota")) }
        model.send(); settle()
        val third = transfers.calls.last { it.startsWith("transfer") }
        assertNotEquals(sent[0].substringAfterLast(' '), third.substringAfterLast(' '))
        // The refusal keeps its code and every failed check, for the screen to word.
        assertEquals(TransferError.Refused("recipient_ineligible", listOf("profile_unassigned", "recipient_no_quota")), model.uiState.value.error)
        assertNull(model.uiState.value.done)
    }

    @Test fun anOfferIsAcceptedFromTheIncomingListAndARefusalShowsItsCurrentState() = runTest(dispatcher) {
        val offer = transfer("t1", from = "m1", to = "m2")
        transfers.pages = { direction, _ -> if (direction == TransferDirection.INCOMING) TransferPage(listOf(offer), null) else TransferPage(listOf(transfer("t2", from = "m2", to = "m3")), null) }
        val model = list(InMemoryAccountRepository(listOf(account(memberId = "m2"))))
        settle()
        // A member gets no member names: the list shows IDs.
        assertFalse("members" in transfers.calls)
        assertEquals(listOf("t1"), model.uiState.value.list.transfers.map { it.id })
        model.accept(offer); settle()
        assertEquals(TransferState.ACCEPTED, model.uiState.value.list.transfers.single().state)
        assertEquals("accept t1 null", transfers.calls.last { it.startsWith("accept") })
        // Withdrawing someone else's offer is not offered: nothing is sent.
        model.cancel(offer.copy(state = TransferState.OFFERED)); settle()
        assertFalse(transfers.calls.any { it.startsWith("cancel") })

        model.setDirection(TransferDirection.OUTGOING); settle()
        val mine = model.uiState.value.list.transfers.single()
        transfers.decision = { _, _ -> throw CompanionException.TransferRefused("transfer_not_open") }
        transfers.pages = { _, _ -> TransferPage(listOf(mine.copy(state = TransferState.DECLINED)), null) }
        model.cancel(mine); settle()
        assertEquals(TransferError.Refused("transfer_not_open", emptyList()), model.uiState.value.error)
        // The list is read again: the offer was declined meanwhile.
        assertEquals(TransferState.DECLINED, model.uiState.value.list.transfers.single().state)
    }

    @Test fun anAdministratorSeesNamesAndPagesFurther() = runTest(dispatcher) {
        transfers.pages = { _, before -> if (before == null) TransferPage(listOf(transfer("t1")), "t1") else TransferPage(listOf(transfer("t1"), transfer("t0")), null) }
        val model = list(InMemoryAccountRepository(listOf(admin(memberId = "m9"))))
        settle()
        assertEquals(mapOf("m2" to "grace", "m1" to "ada"), model.uiState.value.names)
        model.loadMore(); settle()
        // Already listed transfers are not shown twice.
        assertEquals(listOf("t1", "t0"), model.uiState.value.list.transfers.map { it.id })
        assertNull(model.uiState.value.list.nextBefore)
    }
}

/** Runs what is due within a second of virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
