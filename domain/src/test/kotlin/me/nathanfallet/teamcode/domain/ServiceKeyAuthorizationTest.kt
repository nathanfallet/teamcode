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
    @Test fun cloudScopesNestReadUnderWriteAndOnlyAdminCloudCarriesAdministratorRights() {
        val authorize = AuthorizeCredentialUseCase()
        val member = admin.copy(member = admin.member.copy(id = "member", role = Role.MEMBER))
        for (scope in ServiceScope.entries) {
            val key = admin.copy(serviceScopes = setOf(scope))
            val reads = scope in setOf(ServiceScope.CLOUD_READ, ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD)
            val writes = scope in setOf(ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD)
            if (reads) authorize.check(key, CredentialAction.CLOUD_READ)
            else assertEquals("credential_scope", assertFailsWith<TeamFailure> { authorize.check(key, CredentialAction.CLOUD_READ) }.code)
            if (writes) authorize.check(key, CredentialAction.CLOUD_WRITE)
            else assertEquals("credential_scope", assertFailsWith<TeamFailure> { authorize.check(key, CredentialAction.CLOUD_WRITE) }.code)
            // An administrator's key is an administrator for cloud sessions only with admin:cloud, and never for interactive administration.
            assertEquals(scope == ServiceScope.ADMIN_CLOUD, key.isCloudAdministrator)
            assertFailsWith<TeamFailure> { key.requireAdmin() }
            assertFalse(member.copy(serviceScopes = setOf(scope)).isCloudAdministrator, "A member's key never becomes an administrator")
        }
        assertTrue(admin.isCloudAdministrator)
        assertFalse(member.isCloudAdministrator)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { admin.copy(serviceScopes = setOf(ServiceScope.CLOUD_WRITE)).requireCloudAdministrator() }.code)
        val temporary = admin.copy(member = admin.member.copy(passwordChangeRequired = true), serviceScopes = setOf(ServiceScope.ADMIN_CLOUD))
        assertEquals("password_change_required", assertFailsWith<TeamFailure> { temporary.requireCloudRead() }.code)
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
