package me.nathanfallet.teamcode.domain.team

/** Group roles are scoped assignments, independent of global team administration. */
enum class GroupRole { MEMBER, MANAGER }
/** A persisted team group whose positive caps constrain all current members. */
data class TeamGroup(val id: String, val name: String, val dailyUnits: Long = 0, val maxConcurrent: Int = 0, val archived: Boolean = false)
/** Administrator-controlled group policy; archiving deactivates new-admission constraints. */
data class GroupPolicy(val name: String, val dailyUnits: Long = 0, val maxConcurrent: Int = 0, val archived: Boolean = false)
/** Scoped role and admission pause, independent of global role and account status. */
data class GroupMembership(val memberId: String, val username: String, val role: GroupRole, val paused: Boolean, val disabled: Boolean)
/** Original reservation charges and unexpired active slots for one group. */
data class GroupUsage(val chargedAndReservedUnits: Long = 0, val activeRequests: Int = 0)
/** Accessible aggregate state without roster or credential information. */
data class GroupSummary(val group: TeamGroup, val usage: GroupUsage, val canManage: Boolean)
/** Aggregate state and the roster subset visible to the current interactive actor. */
data class GroupDetail(val summary: GroupSummary, val members: List<GroupMembership>)

/** All operations use current shared authorization. Managers cannot edit budgets, assignments or global roles. */
interface GroupService {
    suspend fun list(actor: Principal): List<GroupSummary>
    suspend fun get(actor: Principal, groupId: String): GroupDetail
    suspend fun create(actor: Principal, policy: GroupPolicy): GroupDetail
    suspend fun update(actor: Principal, groupId: String, policy: GroupPolicy): GroupDetail
    suspend fun assign(actor: Principal, groupId: String, memberId: String, role: GroupRole, paused: Boolean = false): GroupDetail
    suspend fun remove(actor: Principal, groupId: String, memberId: String): GroupDetail
    suspend fun pause(actor: Principal, groupId: String, memberId: String, paused: Boolean): GroupDetail
}

/** Reusable policy validation and hard constraints; zero means no extra limit, never extra allocation. */
class CheckGroupPolicyUseCase {
    fun validate(policy: GroupPolicy) {
        if (policy.name.isBlank() || policy.name.length > 100 || policy.name.any { it.code < 32 } ||
            policy.dailyUnits !in 0..100_000_000_000 || policy.maxConcurrent !in 0..128) {
            throw TeamFailure("invalid_group", 400, "Provide a group name, a nonnegative daily cap and a concurrency cap between 0 and 128.")
        }
    }
}
