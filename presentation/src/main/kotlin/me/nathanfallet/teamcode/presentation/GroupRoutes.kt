package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.api.resources.GroupsResource
import me.nathanfallet.teamcode.domain.team.*

internal fun GroupSummary.view() = GroupView(group.id, group.name, group.dailyUnits, group.maxConcurrent, group.archived, usage.chargedAndReservedUnits, usage.activeRequests, canManage)
internal fun GroupDetail.view() = GroupDetailView(summary.view(), members.map { GroupMemberView(it.memberId, it.username, it.role.name, it.paused, it.disabled) })
internal fun GroupPolicyRequest.policy() = GroupPolicy(name, dailyUnits, maxConcurrent, archived)
internal fun groupRole(value: String) = GroupRole.entries.find { it.name == value }
    ?: throw TeamFailure("invalid_group_role", 400, "Unknown group role.")

/** Domain services recheck current scoped permissions on every operation, across replicas. */
internal fun Route.groupRoutes(groups: GroupService) {
    get<GroupsResource> { call.respond(groups.list(call.principal).map { it.view() }) }
    post<GroupsResource> { call.respond(HttpStatusCode.Created, groups.create(call.principal, call.jsonBody<GroupPolicyRequest>().policy()).view()) }
    get<GroupsResource.Id> { call.respond(groups.get(call.principal, it.id).view()) }
    put<GroupsResource.Id> { call.respond(groups.update(call.principal, it.id, call.jsonBody<GroupPolicyRequest>().policy()).view()) }
    put<GroupsResource.Id.Member> {
        val input = call.jsonBody<GroupAssignmentRequest>()
        call.respond(groups.assign(call.principal, it.parent.id, it.memberId, groupRole(input.role), input.paused).view())
    }
    delete<GroupsResource.Id.Member> { groups.remove(call.principal, it.parent.id, it.memberId); call.respond(SuccessResponse()) }
    patch<GroupsResource.Id.Member.Pause> {
        call.respond(groups.pause(call.principal, it.parent.parent.id, it.parent.memberId, call.jsonBody<GroupPauseRequest>().paused).view())
    }
}
