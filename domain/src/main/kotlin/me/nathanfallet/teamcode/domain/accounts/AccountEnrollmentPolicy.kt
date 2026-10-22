package me.nathanfallet.teamcode.domain.accounts

import me.nathanfallet.teamcode.domain.team.*

/**
 * Who may add a subscription account to the pool with `teamcode accounts add`. [ADMINISTRATORS] is the default and the
 * behavior of every server before this setting existed. [MEMBERS] lets every ready interactive member add a fresh
 * sign-in and reconnect an account that needs it; account settings, imports and handovers stay administrator-only.
 */
enum class AccountEnrollmentPolicy(val wire: String) {
    ADMINISTRATORS("administrators"), MEMBERS("members");
    companion object {
        fun parse(value: String): AccountEnrollmentPolicy = entries.firstOrNull { it.wire == value }
            ?: throw TeamFailure("invalid_account_enrollment_policy", 400, "Use \"administrators\" or \"members\" for who can add accounts.")
    }
}

/**
 * The single authority over enrollment writes. It runs once before provider verification (without [existing], to refuse
 * early without any provider call) and again under the team lock with the current row and the stored policy, which is
 * the decision that counts. Callers must pass a principal already revalidated against current membership.
 */
object AccountEnrollmentRules {
    /** Whether [actor] may start `accounts add` at all under [policy]; never throws. */
    fun mayEnroll(actor: Principal, policy: AccountEnrollmentPolicy): Boolean =
        try { authorize(actor, policy, EnrollmentOrigin.SIGN_IN); true } catch (_: TeamFailure) { false }

    fun authorize(actor: Principal, policy: AccountEnrollmentPolicy, origin: EnrollmentOrigin, existing: AccountRecord? = null) {
        actor.requireInteractive()
        actor.requireReady()
        if (actor.member.role.isAdministrator) return
        if (policy != AccountEnrollmentPolicy.MEMBERS) throw TeamFailure(ENROLLMENT_FORBIDDEN, 403, "Only administrators can add accounts on this server.")
        // An import reuses a session another tool may still refresh and waits for a handover, which only an administrator gives.
        if (origin != EnrollmentOrigin.SIGN_IN) throw TeamFailure(IMPORT_FORBIDDEN, 403, "Only administrators can import accounts.")
        if (existing == null) return
        // The identity match already guarantees the same provider account; what is checked here is whose sign-in it replaces.
        // A member may revive an account that serves nobody until someone reconnects it, or refresh their own sign-in of a
        // READY account. A sign-in makes the account READY, so a pending or access-only account (an administrator's
        // handover decision) and a healthy account added by someone else keep their sign-in.
        if (existing.state == AccountState.RECONNECT_REQUIRED || (existing.enrolledBy == actor.member.id && existing.state == AccountState.READY)) return
        throw TeamFailure(ACCOUNT_IN_USE, 403, "This subscription account is already in the pool and is not waiting to be reconnected. Ask an administrator to replace its sign-in.")
    }

    const val ENROLLMENT_FORBIDDEN = "account_enrollment_forbidden"
    const val IMPORT_FORBIDDEN = "account_import_forbidden"
    const val ACCOUNT_IN_USE = "account_in_use"
}

/** The stored policy and whether the asking principal may add accounts under it. */
data class AccountEnrollmentAccess(val policy: AccountEnrollmentPolicy, val allowed: Boolean)
