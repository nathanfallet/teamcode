package me.nathanfallet.teamcode.api.resources

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/capabilities")
class CapabilitiesResource

@Serializable
@Resource("/api/v1/status")
class StatusResource

/** Administrator-only observed history and forecasts for a selected subscription account. */
@Serializable
@Resource("/api/v1/forecasts")
class ForecastsResource(val account: String? = null)

/** Group management requires interactive authentication, including for administrators. */
@Serializable @Resource("/api/v1/groups")
class GroupsResource {
    @Serializable @Resource("{id}")
    class Id(val parent: GroupsResource = GroupsResource(), val id: String) {
        @Serializable @Resource("members/{memberId}")
        class Member(val parent: Id, val memberId: String) {
            @Serializable @Resource("pause")
            class Pause(val parent: Member)
        }
    }
}

@Serializable @Resource("/api/v1/runs")
class RunsResource(val member: String? = null, val all: Boolean = false, val before: String? = null) {
    @Serializable @Resource("{id}")
    class Id(val parent: RunsResource = RunsResource(), val id: String) {
        @Serializable @Resource("heartbeat") class Heartbeat(val parent: Id)
        @Serializable @Resource("end") class End(val parent: Id)
        @Serializable @Resource("policy") class Policy(val parent: Id)
    }
}

/** Interactive administrator history and explicitly expiring daily-allocation exceptions. */
@Serializable @Resource("/api/v1/members/{memberId}/budget-overrides")
class BudgetOverridesResource(val memberId: String, val before: String? = null) {
    @Serializable @Resource("{id}/revoke")
    class Revoke(val parent: BudgetOverridesResource, val id: String)
}

@Serializable @Resource("/api/v1/usage")
class UsageResource {
    @Serializable @Resource("pending")
    class Pending(val parent: UsageResource = UsageResource(), val member: String? = null, val before: String? = null)
    @Serializable @Resource("reconciliations")
    class History(val parent: UsageResource = UsageResource(), val member: String? = null, val before: String? = null)
    @Serializable @Resource("reservations/{id}")
    class Reservation(val parent: UsageResource = UsageResource(), val id: String) {
        @Serializable @Resource("reconcile") class Reconcile(val parent: Reservation)
    }
}
