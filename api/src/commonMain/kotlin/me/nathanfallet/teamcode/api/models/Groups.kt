package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Zero caps add no group limit; member, pool and physical subscription limits still apply. */
@Serializable
data class GroupPolicyRequest(val name: String, val dailyUnits: Long = 0, val maxConcurrent: Int = 0, val archived: Boolean = false)
@Serializable
data class GroupView(val id: String, val name: String, val dailyUnits: Long, val maxConcurrent: Int, val archived: Boolean, val chargedAndReservedUnits: Long, val activeRequests: Int, val canManage: Boolean)
@Serializable
data class GroupMemberView(val memberId: String, val username: String, val role: String, val paused: Boolean, val disabled: Boolean)
@Serializable
data class GroupDetailView(val group: GroupView, val members: List<GroupMemberView>)
@Serializable
data class GroupAssignmentRequest(val role: String = "MEMBER", val paused: Boolean = false)
@Serializable
data class GroupPauseRequest(val paused: Boolean)
/** Own active group constraints, available to an appropriately scoped usage key without roster access. */
@Serializable
data class GroupAllowanceView(val id: String, val name: String, val dailyUnits: Long, val maxConcurrent: Int, val paused: Boolean, val chargedAndReservedUnits: Long, val activeRequests: Int)
