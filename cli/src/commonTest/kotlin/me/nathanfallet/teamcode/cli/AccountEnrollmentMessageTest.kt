package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.AccountEnrollmentPolicyView
import me.nathanfallet.teamcode.cli.accounts.*
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class AccountEnrollmentMessageTest {
    private val expected = "Only administrators can add accounts on this server. Ask an administrator, or ask them to allow every member to add accounts"

    @Test fun aMemberIsToldWhyBeforeAnyProviderSignIn() = runTest {
        var listed = 0
        val features = listOf(ENROLLMENT_POLICY_FEATURE)
        val refused = assertFailsWith<CliUserException> {
            requireEnrollmentAllowed(features, { AccountEnrollmentPolicyView("administrators", false) }) { listed++; "MEMBER" }
        }
        assertTrue(refused.message.startsWith(expected), refused.message)
        assertContains(refused.message, "Who can add accounts")
        assertEquals(0, listed, "The server's answer is enough; no administrator-only call")
        requireEnrollmentAllowed(features, { AccountEnrollmentPolicyView("members", true) }) { error("unused") }
    }

    @Test fun anOlderServerOnlyAcceptsAdministratorsByRole() = runTest {
        requireEnrollmentAllowed(emptyList(), { error("an older server has no policy endpoint") }) { "OWNER" }
        requireEnrollmentAllowed(emptyList(), { error("unused") }) { "ADMIN" }
        assertTrue(assertFailsWith<CliUserException> { requireEnrollmentAllowed(emptyList(), { error("unused") }) { "MEMBER" } }.message.startsWith(expected))
    }

    @Test fun serverRefusalsBecomeTeamCodesOwnWords() {
        assertTrue(enrollmentRefusal(TeamCodeException(403, "TeamCode returned HTTP 403.", "account_enrollment_forbidden"))!!.message.startsWith(expected))
        assertContains(enrollmentRefusal(TeamCodeException(403, "TeamCode returned HTTP 403.", "account_in_use"))!!.message, "does not need reconnecting")
        assertNull(enrollmentRefusal(TeamCodeException(403, "TeamCode returned HTTP 403.", "forbidden")), "Other failures keep the central handling")
        assertNull(enrollmentRefusal(TeamCodeException(500, "TeamCode returned HTTP 500.", "account_in_use")))
    }
}
