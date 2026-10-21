package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.*

/**
 * Member and group authorization for cloud sessions. A member holds a permission on a session when a rule of the
 * team policy names them (`member:<id>`), one of their groups (`group:<id>`) or `everyone`, with scope `team`, or
 * with scope `own` on a session they own. Administrators hold every permission. A rule never widens the
 * management routes (pause, resume, delete), which stay with the owner and administrators.
 */
class CloudAccessService(
    private val store: CloudConversationStore,
    private val sessions: CloudSessionStore,
    private val groups: CloudGroupDirectory = CloudGroupDirectory.NONE,
) {
    suspend fun policy(principal: Principal): CloudAccessPolicy {
        principal.requireAdmin()
        sessions.revalidate(principal)
        return store.accessPolicy(principal.teamId)
    }

    suspend fun savePolicy(principal: Principal, expectedVersion: Int, rules: List<CloudAccessRule>): CloudAccessPolicy {
        principal.requireAdmin()
        validate(rules)
        for (rule in rules) when (val subject = rule.subject) {
            is CloudAccessSubject.MemberSubject -> if (sessions.member(principal.teamId, subject.memberId) == null)
                throw TeamFailure("member_not_found", 422, "Rule subject ${subject.wire} is not a team member.")
            is CloudAccessSubject.GroupSubject -> if (!groups.exists(principal.teamId, subject.groupId))
                throw TeamFailure("group_not_found", 422, "Rule subject ${subject.wire} is not a team group.")
            CloudAccessSubject.Everyone -> Unit
        }
        return store.saveAccessPolicy(principal, expectedVersion, rules)
    }

    /**
     * The permissions [member] holds on a session owned by [ownerId] (or on creation, with [ownerId] = the member).
     * [administrator] is false for a key without `admin:cloud`, which then holds only what the rules give its owner; a read by an administrator's `team:read` key passes true.
     */
    suspend fun permissions(teamId: String, member: Member, ownerId: String, administrator: Boolean = member.role.isAdministrator): Set<CloudPermission> {
        if (administrator) return CloudPermission.entries.toSet()
        val policy = store.accessPolicy(teamId)
        var resolvedGroups: Set<String>? = null // Resolved only when a group rule needs it.
        val granted = mutableSetOf<CloudPermission>()
        for (rule in policy.rules) {
            val matches = when (val subject = rule.subject) {
                CloudAccessSubject.Everyone -> true
                is CloudAccessSubject.MemberSubject -> subject.memberId == member.id
                is CloudAccessSubject.GroupSubject -> subject.groupId in (resolvedGroups ?: groups.groupsOf(teamId, member.id).also { resolvedGroups = it })
            }
            if (!matches || (rule.scope == CloudAccessScope.OWN && ownerId != member.id)) continue
            granted += if (rule.scope == CloudAccessScope.TEAM) rule.permissions - CloudPermission.CREATE else rule.permissions
        }
        return granted
    }

    /** Fails with `403 cloud_permission_denied` unless [member] holds [permission] for [ownerId]. */
    suspend fun require(teamId: String, member: Member, ownerId: String, permission: CloudPermission, administrator: Boolean = member.role.isAdministrator) {
        if (permission !in permissions(teamId, member, ownerId, administrator))
            throw TeamFailure("cloud_permission_denied", 403, "The team's cloud access policy does not allow ${permission.wire} here.")
    }

    companion object {
        fun validate(rules: List<CloudAccessRule>) {
            if (rules.size > 200) throw TeamFailure("invalid_access_policy", 400, "Use at most 200 rules.")
            if (rules.any { it.permissions.isEmpty() || (it.scope == CloudAccessScope.TEAM && it.permissions == setOf(CloudPermission.CREATE)) })
                throw TeamFailure("invalid_access_policy", 400, "Give each rule at least one permission; create applies only with scope own.")
            if (rules.groupBy { it.subject to it.scope }.any { it.value.size > 1 })
                throw TeamFailure("invalid_access_policy", 400, "Use one rule per subject and scope.")
        }
    }
}
