package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class MemberAccessTest {
    private val owner = Principal(Member("owner", "owner", Role.OWNER, false, false), "token", "default")
    private val admin = owner.copy(member = owner.member.copy(id = "admin", role = Role.ADMIN))
    private val access = CheckMemberAccessUseCase()
    @Test fun onlyInteractiveReadyOwnersCanGrantChangeOrRecoverOwnership() {
        owner.requireAdmin(); owner.requireOwner(); admin.requireAdmin()
        assertEquals("owner_required", assertFailsWith<TeamFailure> { admin.requireOwner() }.code)
        for (actor in listOf(owner, admin)) {
            access.create(actor, Role.MEMBER); access.create(actor, Role.ADMIN)
            for (scopes in listOf(setOf(ServiceScope.INFERENCE), ServiceScope.entries.toSet())) {
                assertEquals("credential_scope", assertFailsWith<TeamFailure> { access.create(actor.copy(serviceScopes = scopes), Role.OWNER) }.code)
            }
        }
        access.create(owner, Role.OWNER)
        assertEquals("owner_required", assertFailsWith<TeamFailure> { access.create(admin, Role.OWNER) }.code)
        assertEquals("owner_required", assertFailsWith<TeamFailure> { access.change(admin, admin.member, Role.OWNER, false, true) }.code)
        assertEquals("owner_required", assertFailsWith<TeamFailure> { access.change(admin, owner.member, Role.OWNER, false, true) }.code)
        assertEquals("owner_required", assertFailsWith<TeamFailure> { access.resetPassword(admin, owner.member, true) }.code)
        assertEquals("password_change_required", assertFailsWith<TeamFailure> {
            access.create(owner.copy(member = owner.member.copy(passwordChangeRequired = true)), Role.OWNER)
        }.code)
    }
    @Test fun ownerRemovalDisableAndPasswordResetKeepAnotherReadyOwner() {
        for (role in Role.entries) for (disabled in listOf(true, false)) {
            val removal = role != Role.OWNER || disabled
            if (removal) assertEquals("last_owner", assertFailsWith<TeamFailure> { access.change(owner, owner.member, role, disabled, false) }.code)
            else access.change(owner, owner.member, role, disabled, false)
            access.change(owner, owner.member, role, disabled, true)
        }
        assertEquals("last_owner", assertFailsWith<TeamFailure> { access.resetPassword(owner, owner.member, false) }.code)
        access.resetPassword(owner, owner.member, true)
        access.change(admin, admin.member, Role.MEMBER, false, false)
        access.resetPassword(admin, admin.member, false)
    }
}
