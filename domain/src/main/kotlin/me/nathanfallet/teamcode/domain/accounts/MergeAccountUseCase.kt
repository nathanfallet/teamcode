package me.nathanfallet.teamcode.domain.accounts

/** Policy-preserving credential update, to be invoked under the repository's transaction/row lock. */
class MergeAccountUseCase {
    /** State of an account created by an enrollment from [origin]. */
    fun initialState(origin: EnrollmentOrigin) = if (origin == EnrollmentOrigin.SIGN_IN) AccountState.READY else AccountState.PENDING_HANDOVER

    fun merge(existing: AccountRecord, verified: VerifiedProviderAccount, expectedVersion: Long, origin: EnrollmentOrigin = EnrollmentOrigin.IMPORT): AccountRecord {
        require(existing.identity == verified.identity) { "Provider identity does not match the stored account." }
        if (existing.credentialVersion != expectedVersion) throw CredentialVersionConflict()
        require(existing.credentialVersion < Long.MAX_VALUE) { "Credential version limit reached." }
        // Display name, disabled state, pool and priority are operator-owned; the email is the provider's. An import keeps the
        // handover state; a fresh sign-in replaces every older token with one only TeamCode holds, so TeamCode owns it.
        val state = if (origin == EnrollmentOrigin.SIGN_IN) AccountState.READY else existing.state
        return existing.copy(credentialVersion = existing.credentialVersion + 1, email = verified.email ?: existing.email, state = state)
    }
}
