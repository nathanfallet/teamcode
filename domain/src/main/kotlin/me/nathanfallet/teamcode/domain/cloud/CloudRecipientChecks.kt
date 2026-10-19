package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.policy.CheckModelPolicyUseCase
import me.nathanfallet.teamcode.domain.policy.ModelPolicyStore
import me.nathanfallet.teamcode.domain.team.*

/**
 * Whether a member may own a session: the checks that creation applies to an owner, applied to the recipient of a
 * transfer. Built in: an enabled member who completed password setup, the profile assignment (administrators use
 * every profile), and `create` under the team's access policy. [checks] add what other parts of TeamCode own:
 * model and effort policy, quota, and the variable, MCP and skill assignments once they reach cloud sessions.
 */
class CloudRecipientPolicy(
    private val store: CloudSessionStore,
    private val access: CloudAccessService? = null,
    private val checks: List<CloudRecipientCheck> = emptyList(),
) {
    suspend fun problems(session: CloudSession, recipientId: String): List<CloudProblem> {
        val recipient = store.member(session.teamId, recipientId) ?: return listOf(CloudProblem("recipient_not_found", "The recipient is not a team member."))
        if (recipient.disabled || recipient.passwordChangeRequired)
            return listOf(CloudProblem("recipient_unavailable", "The recipient is disabled or has not completed password setup."))
        val problems = mutableListOf<CloudProblem>()
        val profile = store.profile(session.teamId, session.profileId)
        if (profile == null || (recipient.role != Role.ADMIN && recipient.id !in profile.assignedMemberIds))
            problems += CloudProblem("profile_unassigned", "The recipient is not assigned to the session's profile.")
        if (access != null && CloudPermission.CREATE !in access.permissions(session.teamId, recipient, recipient.id))
            problems += CloudProblem("cloud_permission_denied", "The team's cloud access policy does not let the recipient own sessions.")
        for (check in checks) problems += check.problems(session, recipient)
        return problems
    }

    /** Fails with `422 recipient_ineligible`, naming every problem code. */
    suspend fun require(session: CloudSession, recipientId: String) {
        val problems = problems(session, recipientId)
        if (problems.isNotEmpty()) throw ineligible(problems)
    }

    companion object {
        fun ineligible(problems: List<CloudProblem>) = TeamFailure("recipient_ineligible", 422,
            "The recipient cannot own this session: ${problems.joinToString { it.code }}.")
    }
}

/**
 * The session's recorded model and effort against the team's model policy, exactly as inference will check them,
 * so a transfer never hands over a session whose agent would be refused on its first request.
 */
class ModelPolicyRecipientCheck(private val policies: ModelPolicyStore, private val check: CheckModelPolicyUseCase = CheckModelPolicyUseCase()) : CloudRecipientCheck {
    override suspend fun problems(session: CloudSession, recipient: Member): List<CloudProblem> {
        val model = session.agentModel
        val effort = session.agentEffort
        if (model == null && effort == null) return emptyList()
        val policy = policies.get(session.teamId)
        return try {
            if (model != null) check.check(policy, model, model, effort, null)
            else if (effort !in policy.allowedEfforts) throw TeamFailure("effort_not_allowed", 403, "This reasoning effort is not allowed.")
            emptyList()
        } catch (failure: TeamFailure) {
            listOf(CloudProblem(failure.code, "The session's agent model or effort is not allowed by the team's model policy."))
        }
    }
}

/**
 * The recipient must have an inference allowance at all. Today's consumption is not checked: a transfer neither
 * resets nor borrows allowances, and future work waits for the recipient's allowance like any of their requests.
 */
class QuotaRecipientCheck(private val allowances: MemberAllowanceSource) : CloudRecipientCheck {
    override suspend fun problems(session: CloudSession, recipient: Member): List<CloudProblem> =
        if (allowances.dailyAllowance(session.teamId, recipient.id) > 0) emptyList()
        else listOf(CloudProblem("recipient_no_quota", "The recipient has no inference allowance (their pool weight or daily allowance is zero)."))
}
