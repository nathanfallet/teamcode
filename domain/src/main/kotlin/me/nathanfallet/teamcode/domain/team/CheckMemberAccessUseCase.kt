package me.nathanfallet.teamcode.domain.team

/** Preserve a password-ready enabled owner; ordinary administrators cannot take over ownership. */
class CheckMemberAccessUseCase {
    fun create(actor: Principal, role: Role) {
        actor.requireAdmin()
        if (role == Role.OWNER) actor.requireOwner()
    }
    fun change(actor: Principal, target: Member, role: Role, disabled: Boolean, hasOtherReadyOwner: Boolean) {
        actor.requireAdmin()
        if (target.role == Role.OWNER || role == Role.OWNER) actor.requireOwner()
        if (target.role == Role.OWNER && (role != Role.OWNER || disabled)) preserveOwner(hasOtherReadyOwner)
    }
    fun resetPassword(actor: Principal, target: Member, hasOtherReadyOwner: Boolean) {
        actor.requireAdmin()
        if (target.role == Role.OWNER) { actor.requireOwner(); preserveOwner(hasOtherReadyOwner) }
    }
    private fun preserveOwner(hasOtherReadyOwner: Boolean) {
        if (!hasOtherReadyOwner) throw TeamFailure("last_owner", 409,
            "Keep another enabled owner with a changed password before removing access or resetting this owner's password. Use Change password for your own password.")
    }
}
