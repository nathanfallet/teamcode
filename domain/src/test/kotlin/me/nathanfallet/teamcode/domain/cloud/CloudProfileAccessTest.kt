package me.nathanfallet.teamcode.domain.cloud

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.nathanfallet.teamcode.domain.team.*

/** Which profiles a member can use, and how creation tells a missing profile from an unassigned one. */
class CloudProfileAccessTest {
    private val adminMember = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleagueMember = Member("colleague-id", "colleague", Role.MEMBER, false, false)
    private val disabledMember = Member("disabled-id", "disabled", Role.MEMBER, true, false)
    private val admin = Principal(adminMember, "session-1", "team")
    private val colleague = Principal(colleagueMember, "session-2", "team")
    private val spec = CloudProfileSpec("cluster-id", "teamcode", "registry.example/toolchain:1")
    private val assigned = CloudProfile("assigned-id", "team", "toolchain", 3, spec, setOf(colleagueMember.id), 1, 1)
    private val reserved = CloudProfile("reserved-id", "team", "reserved", 1, spec, emptySet(), 1, 1)
    private val members = listOf(adminMember, colleagueMember, disabledMember)

    /** As the SQL store: every profile for an administrator, the assigned ones for a member. */
    private inner class Store : CloudAdminStore {
        override suspend fun clusters(actor: Principal) = listOf(CloudCluster("cluster-id", "team", "lab", "https://k8s.example.test", listOf("teamcode"), 10, null, 1, 0, null, 1, 1))
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
        override suspend fun profiles(actor: Principal) = listOf(assigned, reserved).filter { actor.member.role.isAdministrator || actor.member.id in it.assignedMemberIds }
        override suspend fun member(actor: Principal, memberId: String) = members.find { it.id == memberId }
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
        override suspend fun settings(actor: Principal) = CloudSettings()
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
    }
    private val service = CloudAdministrationService(Store(), unused())

    /** Only the reads creation makes before it validates the profile; any other call fails the test. */
    private val sessions = CloudSessionService(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, method, args ->
        when (method.name) {
            "member" -> members.find { it.id == args[1] }
            "profile" -> listOf(assigned, reserved).find { it.id == args[1] }
            else -> error("unexpected ${method.name}")
        }
    } as CloudSessionStore)

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("unexpected ${method.name}") } as T

    private fun failure(code: String, block: suspend () -> Unit): TeamFailure {
        val error = assertFailsWith<TeamFailure> { runBlocking { block() } }
        assertEquals(code, error.code)
        return error
    }

    @Test fun membersListTheirProfilesAndAdministratorsListAnyMembers() = runBlocking {
        assertEquals(listOf("assigned-id"), service.profiles(colleague).map { it.id })
        assertEquals(listOf("assigned-id", "reserved-id"), service.profiles(admin).map { it.id })
        assertEquals(listOf("assigned-id"), service.profiles(admin, colleagueMember.id).map { it.id })
        assertEquals(listOf("assigned-id", "reserved-id"), service.profiles(admin, adminMember.id).map { it.id })
        assertEquals(listOf("assigned-id"), service.profiles(colleague, colleagueMember.id).map { it.id }, "Naming oneself needs no administrator")
        failure("forbidden") { service.profiles(colleague, adminMember.id) }
        failure("forbidden") { service.profiles(admin.copy(serviceScopes = setOf(ServiceScope.CLOUD_READ)), colleagueMember.id) }
        assertEquals(listOf("assigned-id"), service.profiles(admin.copy(serviceScopes = setOf(ServiceScope.ADMIN_CLOUD)), colleagueMember.id).map { it.id })
        failure("invalid_effective_member") { service.profiles(admin, "unknown-id") }
        failure("invalid_effective_member") { service.profiles(admin, disabledMember.id) }
        failure("credential_scope") { service.profiles(colleague.copy(serviceScopes = setOf(ServiceScope.INFERENCE))) }
        assertEquals(mapOf("cluster-id" to "lab"), service.clusterNames(colleague, listOf(assigned)))
        assertEquals(emptyMap(), service.clusterNames(colleague, emptyList()), "No profile, no cluster read")
    }

    @Test fun creationTellsAMissingProfileFromAnUnassignedOne() {
        fun draft(profileId: String) = CloudSessionDraft("Task", profileId, null, null, null, emptyMap(), null)
        failure("profile_not_found") { sessions.create(colleague, null, draft("unknown-id"), null) }
        // The id is random and listed only to the members who may use it: knowing it already, a member learns only
        // that it is not theirs. A name never reaches this check (clients resolve names among the listed profiles).
        val own = failure("profile_unassigned") { sessions.create(colleague, null, draft("reserved-id"), null) }
        assertEquals(403, own.status)
        assertTrue("not assigned to you" in own.message.orEmpty())
        assertFalse("reserved" in own.message.orEmpty(), "Neither the profile's name nor its id is echoed")
        val delegated = failure("profile_unassigned") { sessions.create(admin, colleagueMember.id, draft("reserved-id"), null) }
        assertTrue("effective member" in delegated.message.orEmpty())
        failure("profile_not_found") { sessions.create(admin, colleagueMember.id, draft("unknown-id"), null) }
    }
}
