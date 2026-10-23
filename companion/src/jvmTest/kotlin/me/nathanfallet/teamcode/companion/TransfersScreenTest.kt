package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferListUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute
import me.nathanfallet.teamcode.companion.presentation.screens.TransfersScreen
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import kotlin.test.*

/** The transfers list on the desktop target: an offer to accept, and a refusal in plain words. */
@OptIn(ExperimentalTestApi::class)
class TransfersScreenTest {
    @Test fun anOfferShowsItsActionsAndARefusalIsWorded() = runComposeUiTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1", memberId = "m2")))
        val transfers = FakeTransferRepository()
        val offer = transfer("t1", from = "m1abcdef99", to = "m2")
        transfers.pages = { _, _ -> TransferPage(listOf(offer), null) }
        transfers.decision = { _, _ -> throw CompanionException.TransferRefused("recipient_ineligible", listOf("profile_unassigned", "recipient_no_quota")) }
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        val model = TransfersViewModel(TransfersRoute("a1"), TransferListUseCase(transfers, FakeFeatures(setOf("cloud.transfer.v1")), accounts, guard), accounts)
        setContent { TeamCodeTheme { TransfersScreen(model, onBack = {}, onOpenSession = {}, clock = FixedClock()) } }
        waitUntil { model.uiState.value.list.loaded }
        onNodeWithText("Offered").assertExists()
        // A member sees the other side by ID and themselves as "you".
        onNodeWithText("m1abcdef → you").assertExists()
        onNodeWithTag("cancel-t1").assertDoesNotExist()
        onNodeWithTag("accept-t1").performClick()
        waitUntil { model.uiState.value.error != null }
        onNodeWithText("This member cannot take over the session: they are not assigned to the session's profile; they have no usage allowance.").assertExists()
        assertEquals(TransferState.OFFERED, model.uiState.value.list.transfers.single().state)
    }
}
