package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ServiceKeyAuthorizationTest {
    private val admin = Principal(Member("owner", "owner", Role.ADMIN, false, false), "token", "team")
    @Test fun scopesDoNotInheritAdministratorPrivilegesAndNewActionsStayInteractive() {
        val authorize = AuthorizeCredentialUseCase()
        for (scope in ServiceScope.entries) {
            val key = admin.copy(serviceScopes = setOf(scope))
            assertEquals("credential_scope", assertFailsWith<TeamFailure> { key.requireAdmin() }.code)
            assertFailsWith<TeamFailure> { authorize.check(key, CredentialAction.INTERACTIVE) }
            for (action in listOf(CredentialAction.DISCOVERY, CredentialAction.IDENTITY, CredentialAction.LOGOUT)) authorize.check(key, action)
            for ((action, needed) in listOf(CredentialAction.INFERENCE to ServiceScope.INFERENCE,
                CredentialAction.USAGE_READ to ServiceScope.USAGE_READ, CredentialAction.GUIDANCE to ServiceScope.GUIDANCE,
                CredentialAction.SKILLS_READ to ServiceScope.SKILLS_READ, CredentialAction.SKILLS_PUBLISH to ServiceScope.SKILLS_PUBLISH)) {
                if (scope == needed) authorize.check(key, action)
                else assertEquals("credential_scope", assertFailsWith<TeamFailure> { authorize.check(key, action) }.code)
            }
        }
        for (action in CredentialAction.entries) authorize.check(admin, action)
    }
    @Test fun interactiveTemporaryLoginCanReplacePasswordButCannotAdministerBeforeReplacement() {
        val temporary = admin.copy(member = admin.member.copy(passwordChangeRequired = true))
        temporary.requireInteractive() // Credential kind and readiness are separate checks.
        assertEquals("password_change_required", assertFailsWith<TeamFailure> { temporary.requireAdmin() }.code)
        assertFailsWith<TeamFailure> { temporary.copy(serviceScopes = setOf(ServiceScope.INFERENCE)).requireInteractive() }
    }
    @Test fun serviceKeyLifetimesAndNamesAreBoundedAndSecretsHaveNoDefaultStringRepresentation() {
        for (days in listOf(0, 91, Int.MAX_VALUE)) assertFailsWith<TeamFailure> { ServiceKeyService.validate("CI", setOf(ServiceScope.INFERENCE), days) }
        for (name in listOf("", "a\nb", "x".repeat(121))) assertFailsWith<TeamFailure> { ServiceKeyService.validate(name, setOf(ServiceScope.INFERENCE), 1) }
        assertFailsWith<TeamFailure> { ServiceKeyService.validate("CI", emptySet(), 1) }
        ServiceKeyService.validate("CI's runner", setOf(ServiceScope.INFERENCE), 90)
        val token = "synthetic-service-secret"
        assertFalse(CreatedServiceKey(ServiceKey("id", "owner", "CI", setOf(ServiceScope.INFERENCE), 1, 2), token).toString().contains(token))
    }
}
