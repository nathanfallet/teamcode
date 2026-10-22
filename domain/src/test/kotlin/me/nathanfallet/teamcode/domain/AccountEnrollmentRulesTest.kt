package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class AccountEnrollmentRulesTest {
    private fun principal(id: String, role: Role, scopes: Set<ServiceScope>? = null) = Principal(Member(id, id, role, false, false), "token-$id", "default", scopes)
    private val admin = principal("admin", Role.ADMIN)
    private val ada = principal("ada", Role.MEMBER)
    private fun account(state: AccountState, enrolledBy: String?) =
        AccountRecord("x", AccountIdentity("default", "anthropic", "x", "org"), "X", 1, false, null, 0, state, null, enrolledBy)
    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code

    @Test fun administratorsOnlyIsTheDefaultAndMembersMayThenOnlySignIn() {
        assertEquals(AccountEnrollmentPolicy.ADMINISTRATORS, AccountEnrollmentPolicy.entries.first())
        AccountEnrollmentRules.authorize(admin, AccountEnrollmentPolicy.ADMINISTRATORS, EnrollmentOrigin.IMPORT, account(AccountState.READY, "grace"))
        assertEquals(AccountEnrollmentRules.ENROLLMENT_FORBIDDEN, code { AccountEnrollmentRules.authorize(ada, AccountEnrollmentPolicy.ADMINISTRATORS, EnrollmentOrigin.SIGN_IN) })
        assertFalse(AccountEnrollmentRules.mayEnroll(ada, AccountEnrollmentPolicy.ADMINISTRATORS))
        assertTrue(AccountEnrollmentRules.mayEnroll(ada, AccountEnrollmentPolicy.MEMBERS))
        assertFalse(AccountEnrollmentRules.mayEnroll(admin.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), AccountEnrollmentPolicy.MEMBERS))
        AccountEnrollmentRules.authorize(ada, AccountEnrollmentPolicy.MEMBERS, EnrollmentOrigin.SIGN_IN)
        assertEquals(AccountEnrollmentRules.IMPORT_FORBIDDEN, code { AccountEnrollmentRules.authorize(ada, AccountEnrollmentPolicy.MEMBERS, EnrollmentOrigin.IMPORT) })
        assertEquals("credential_scope", code { AccountEnrollmentRules.authorize(principal("ada", Role.MEMBER, setOf(ServiceScope.INFERENCE)), AccountEnrollmentPolicy.MEMBERS, EnrollmentOrigin.SIGN_IN) })
        assertEquals("invalid_account_enrollment_policy", code { AccountEnrollmentPolicy.parse("everyone") })
    }

    @Test fun aMemberReplacesOnlyTheirOwnSignInOrOneThatNeedsReconnecting() {
        fun check(state: AccountState, by: String?) = AccountEnrollmentRules.authorize(ada, AccountEnrollmentPolicy.MEMBERS, EnrollmentOrigin.SIGN_IN, account(state, by))
        check(AccountState.READY, "ada")
        check(AccountState.RECONNECT_REQUIRED, "grace")
        check(AccountState.RECONNECT_REQUIRED, null)
        // Their own account, once an administrator made it pending or access-only, is a handover decision a sign-in would undo.
        for (state in listOf(AccountState.ACCESS_ONLY, AccountState.PENDING_HANDOVER)) assertEquals(AccountEnrollmentRules.ACCOUNT_IN_USE, code { check(state, "ada") }, "own $state")
        for (state in listOf(AccountState.READY, AccountState.ACCESS_ONLY, AccountState.PENDING_HANDOVER))
            for (by in listOf("grace", null)) assertEquals(AccountEnrollmentRules.ACCOUNT_IN_USE, code { check(state, by) }, "$state by $by")
    }
}
