package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeTransferRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.admin
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAction
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAvailabilityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferRules
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferSessionUseCase
import me.nathanfallet.teamcode.companion.transfer
import kotlin.test.*

class TransferUseCasesTest {
    private val transfers = FakeTransferRepository()
    private val features = FakeFeatures(setOf("cloud.sessions.v1", "cloud.transfer.v1"))

    private fun guard(accounts: InMemoryAccountRepository) = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))

    @Test fun theOwnerOrAnAdministratorTransfersACloudSessionThatIsNeitherPreparingNorEnded() {
        val owner = account(memberId = "m1")
        val other = account(memberId = "m9")
        assertTrue(TransferRules.canTransfer(owner, cloudSession("s1", SessionState.ACTIVE, ownerId = "m1")))
        assertTrue(TransferRules.canTransfer(owner, cloudSession("s1", SessionState.PAUSED, ownerId = "m1")))
        assertFalse(TransferRules.canTransfer(other, cloudSession("s1", ownerId = "m1")))
        assertTrue(TransferRules.canTransfer(admin(memberId = "m9"), cloudSession("s1", ownerId = "m1")))
        // A session on a member's device stays theirs: the action is not offered at all.
        assertFalse(TransferRules.canTransfer(owner, cloudSession("s1", ownerId = "m1").copy(kind = SessionKind.REMOTE)))
        for (state in listOf(SessionState.CREATING, SessionState.PREPARING, SessionState.TRANSFERRING, SessionState.TERMINATED, SessionState.FAILED, SessionState.UNKNOWN)) {
            assertFalse(TransferRules.canTransfer(owner, cloudSession("s1", state, ownerId = "m1")), state.name)
        }
    }

    @Test fun theActionShowsOnlyOnAServerThatAnnouncesTransfers() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val availability = TransferAvailabilityUseCase(accounts, features)
        assertTrue(availability.canTransfer("a1", cloudSession("s1")))
        assertTrue(availability.available("a1"))
        features.features = setOf("cloud.sessions.v1")
        assertFalse(availability.canTransfer("a1", cloudSession("s1")))
        assertFalse(availability.available("a1"))
        assertFalse(TransferAvailabilityUseCase(InMemoryAccountRepository(listOf(account(signedIn = false))), FakeFeatures(setOf("cloud.transfer.v1"))).available("a1"))
    }

    @Test fun theRecipientDecidesAnOfferItsOwnerWithdrawsItAndAnAdministratorDoesBoth() {
        val offer = transfer("t1", from = "m1", to = "m2")
        assertEquals(setOf(TransferAction.ACCEPT, TransferAction.DECLINE), TransferRules.actions(account(memberId = "m2"), offer))
        assertEquals(setOf(TransferAction.CANCEL), TransferRules.actions(account(memberId = "m1"), offer))
        assertEquals(emptySet(), TransferRules.actions(account(memberId = "m3"), offer))
        assertEquals(TransferAction.entries.toSet(), TransferRules.actions(admin(memberId = "m3"), offer))
        // Accepted: only an administrator can still stop it before the owner change; a finished one has no action.
        assertEquals(setOf(TransferAction.CANCEL), TransferRules.actions(admin(memberId = "m3"), offer.copy(state = TransferState.ACCEPTED)))
        assertEquals(emptySet(), TransferRules.actions(account(memberId = "m1"), offer.copy(state = TransferState.ACCEPTED)))
        assertEquals(emptySet(), TransferRules.actions(admin(memberId = "m3"), offer.copy(state = TransferState.COMPLETED)))
    }

    @Test fun aMemberOffersWithoutTheAdministratorsOptionsAndAnAdministratorKeepsThem() = runTest {
        val members = InMemoryAccountRepository(listOf(account()))
        TransferSessionUseCase(transfers, features, members, guard(members))("a1", "s1", "m1", TransferRequest(" m2 ", resume = true, force = true), "k1")
        assertEquals("transfer s1 m2 resume=false force=false k1", transfers.calls.last())
        val admins = InMemoryAccountRepository(listOf(admin()))
        TransferSessionUseCase(transfers, features, admins, guard(admins))("a1", "s1", "m9", TransferRequest("m2", resume = true, force = true), "k2")
        assertEquals("transfer s1 m2 resume=true force=true k2", transfers.calls.last())
    }

    @Test fun anInvalidRecipientOrAServerWithoutTransfersIsRefusedBeforeAnyRequest() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account()))
        val transfer = TransferSessionUseCase(transfers, features, accounts, guard(accounts))
        assertFailsWith<IllegalArgumentException> { transfer("a1", "s1", "m1", TransferRequest("m2/../x"), "k") }
        assertFailsWith<IllegalArgumentException> { transfer("a1", "s1", "m1", TransferRequest("m1"), "k") }
        features.features = setOf("cloud.sessions.v1")
        assertFailsWith<CompanionException.Unsupported> { transfer("a1", "s1", "m1", TransferRequest("m2"), "k") }
        assertTrue(transfers.calls.isEmpty())
    }

    @Test fun anAdministratorPicksAmongTheOtherMembersAndAMemberNamesAnId() = runTest {
        transfers.members = listOf(
            TeamMember("m3", "zoe", false, available = true), TeamMember("m1", "ada", true, available = true),
            TeamMember("m4", "bob", false, available = false), TeamMember("m2", "Carl", false, available = true),
        )
        val admins = InMemoryAccountRepository(listOf(admin()))
        // The owner is left out; available members first, by name.
        assertEquals(listOf("m2", "m3", "m4"), LoadRecipientsUseCase(transfers, admins, guard(admins))("a1", "m1")?.map { it.id })
        val members = InMemoryAccountRepository(listOf(account()))
        transfers.calls.clear()
        assertNull(LoadRecipientsUseCase(transfers, members, guard(members))("a1", "m1"))
        // The member list is an administrator's: a member never asks for it.
        assertTrue(transfers.calls.isEmpty())
    }
}
