package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Shared, bounded group management. Membership and role edits serialize with admission. */
class GroupServiceImpl(private val database: Database) : GroupService {
    override suspend fun list(actor: Principal): List<GroupSummary> = database.query {
        authorize(this, actor)
        val assigned = GroupMembers.select(GroupMembers.groupId).where { GroupMembers.memberId eq actor.member.id }
        val groups = TeamGroups.selectAll().where {
            (TeamGroups.teamId eq actor.teamId) and (if (actor.member.role.isAdministrator) Op.TRUE else
                (TeamGroups.archived eq false) and (TeamGroups.id inSubQuery assigned))
        }.orderBy(TeamGroups.name to SortOrder.ASC, TeamGroups.id to SortOrder.ASC).limit(MAX_GROUPS).map(::groupRecord)
        val managers = GroupMembers.select(GroupMembers.groupId).where {
            (GroupMembers.memberId eq actor.member.id) and (GroupMembers.role eq GroupRole.MANAGER.name)
        }.limit(MAX_MEMBERSHIPS).map { it[GroupMembers.groupId] }.toSet()
        val usage = readGroupUsage(actor.teamId, groups.map { it.id }, nowMillis())
        groups.map { GroupSummary(it, usage.getValue(it.id), actor.member.role.isAdministrator || it.id in managers) }
    }

    override suspend fun get(actor: Principal, groupId: String): GroupDetail = database.query {
        authorize(this, actor); detail(actor, access(actor, groupId))
    }

    override suspend fun create(actor: Principal, policy: GroupPolicy): GroupDetail = database.query {
        authorize(this, actor); actor.requireAdmin(); CheckGroupPolicyUseCase().validate(policy)
        if (TeamGroups.select(TeamGroups.id).where { TeamGroups.teamId eq actor.teamId }.count() >= MAX_GROUPS) {
            throw TeamFailure("group_limit", 409, "The team group limit has been reached, including archived groups.")
        }
        val id = UUID.randomUUID().toString()
        TeamGroups.insert {
            it[TeamGroups.id] = id; it[teamId] = actor.teamId; it[name] = policy.name.trim()
            it[dailyUnits] = policy.dailyUnits; it[maxConcurrent] = policy.maxConcurrent; it[archived] = policy.archived
        }
        audit(actor.teamId, actor.member.id, "group.created", id)
        detail(actor, access(actor, id))
    }

    override suspend fun update(actor: Principal, groupId: String, policy: GroupPolicy): GroupDetail = database.query {
        authorize(this, actor); actor.requireAdmin(); access(actor, groupId); CheckGroupPolicyUseCase().validate(policy)
        TeamGroups.update({ TeamGroups.id eq groupId }) {
            it[name] = policy.name.trim(); it[dailyUnits] = policy.dailyUnits; it[maxConcurrent] = policy.maxConcurrent; it[archived] = policy.archived
        }
        audit(actor.teamId, actor.member.id, "group.updated", groupId)
        detail(actor, access(actor, groupId))
    }

    override suspend fun assign(actor: Principal, groupId: String, memberId: String, role: GroupRole, paused: Boolean): GroupDetail = database.query {
        authorize(this, actor); actor.requireAdmin()
        val access = access(actor, groupId)
        if (access.group.archived) throw TeamFailure("group_archived", 409, "Restore this group before assigning members.")
        if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq actor.teamId) }.empty()) throw notFound()
        val existing = GroupMembers.select(GroupMembers.memberId).where { (GroupMembers.groupId eq groupId) and (GroupMembers.memberId eq memberId) }.singleOrNull()
        if (existing == null) {
            if (GroupMembers.select(GroupMembers.groupId).where { GroupMembers.memberId eq memberId }.count() >= MAX_MEMBERSHIPS ||
                GroupMembers.select(GroupMembers.memberId).where { GroupMembers.groupId eq groupId }.count() >= MAX_MEMBERS) {
                throw TeamFailure("group_limit", 409, "The member assignment or group roster limit has been reached.")
            }
        }
        GroupMembers.upsert {
            it[GroupMembers.groupId] = groupId; it[GroupMembers.memberId] = memberId; it[GroupMembers.role] = role.name; it[GroupMembers.paused] = paused
        }
        audit(actor.teamId, actor.member.id, "group.member_assigned", "$groupId/$memberId")
        detail(actor, access)
    }

    override suspend fun remove(actor: Principal, groupId: String, memberId: String): GroupDetail = database.query {
        authorize(this, actor); actor.requireAdmin(); val access = access(actor, groupId)
        val changed = GroupMembers.deleteWhere { (GroupMembers.groupId eq groupId) and (GroupMembers.memberId eq memberId) }
        if (changed == 0) throw notFound()
        // ReservationGroups is deliberately untouched: removing a membership cannot refund spend.
        audit(actor.teamId, actor.member.id, "group.member_removed", "$groupId/$memberId")
        detail(actor, access)
    }

    override suspend fun pause(actor: Principal, groupId: String, memberId: String, paused: Boolean): GroupDetail = database.query {
        authorize(this, actor); val access = access(actor, groupId)
        if (!access.canManage) throw TeamFailure("forbidden", 403, "A group manager or administrator is required.")
        val changed = GroupMembers.update({ (GroupMembers.groupId eq groupId) and (GroupMembers.memberId eq memberId) }) { it[GroupMembers.paused] = paused }
        if (changed == 0) throw notFound()
        audit(actor.teamId, actor.member.id, if (paused) "group.member_paused" else "group.member_resumed", "$groupId/$memberId")
        detail(actor, access)
    }

    private fun authorize(transaction: JdbcTransaction, actor: Principal) {
        actor.requireInteractive(); actor.requireReady(); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor)
    }

    private data class Access(val group: TeamGroup, val canManage: Boolean)
    private fun access(actor: Principal, groupId: String): Access {
        val group = TeamGroups.selectAll().where { (TeamGroups.id eq groupId) and (TeamGroups.teamId eq actor.teamId) }
            .singleOrNull()?.let(::groupRecord) ?: throw notFound()
        if (actor.member.role.isAdministrator) return Access(group, true)
        if (group.archived) throw notFound()
        val role = GroupMembers.select(GroupMembers.role).where { (GroupMembers.groupId eq groupId) and (GroupMembers.memberId eq actor.member.id) }
            .singleOrNull()?.get(GroupMembers.role) ?: throw notFound()
        return Access(group, role == GroupRole.MANAGER.name)
    }

    private fun JdbcTransaction.detail(actor: Principal, access: Access): GroupDetail {
        val group = access.group
        val now = nowMillis()
        val summary = GroupSummary(group, readGroupUsage(actor.teamId, listOf(group.id), now).getValue(group.id), access.canManage)
        val members = (GroupMembers innerJoin Members).select(GroupMembers.memberId, GroupMembers.role, GroupMembers.paused, Members.username, Members.disabled)
            .where { (GroupMembers.groupId eq group.id) and (Members.teamId eq actor.teamId) and
                (if (access.canManage) Op.TRUE else GroupMembers.memberId eq actor.member.id) }
            .orderBy(Members.username).limit(MAX_MEMBERS).map {
                GroupMembership(it[GroupMembers.memberId], it[Members.username], GroupRole.valueOf(it[GroupMembers.role]), it[GroupMembers.paused], it[Members.disabled])
            }
        return GroupDetail(summary, members)
    }
    private fun notFound() = TeamFailure("not_found", 404, "Group or membership not found.")
    companion object { const val MAX_GROUPS = 64; const val MAX_MEMBERSHIPS = 16; const val MAX_MEMBERS = 200 }
}
