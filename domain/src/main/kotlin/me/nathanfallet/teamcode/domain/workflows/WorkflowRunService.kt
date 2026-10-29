package me.nathanfallet.teamcode.domain.workflows

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.domain.team.*

/** Pure checks of a run's inputs. */
object WorkflowRunRules {
    private fun invalid(message: String) = TeamFailure("invalid_inputs", 400, message)

    /** The inputs a run starts with: [given] over the defaults, every one declared, required ones present, each within its length. */
    fun inputs(definition: WorkflowDefinition, given: Map<String, String>): Map<String, String> {
        val declared = definition.inputs.associateBy { it.id }
        given.keys.firstOrNull { it !in declared }?.let { throw invalid("The workflow has no input $it.") }
        return definition.inputs.mapNotNull { input ->
            val value = given[input.id] ?: input.default
            if (value.isNullOrEmpty()) { if (input.required) throw invalid("The input ${input.id} is required."); null }
            else { if (value.length > input.maxLength) throw invalid("The input ${input.id} is at most ${input.maxLength} characters."); input.id to value }
        }.toMap()
    }
}

/**
 * Starting, reading, cancelling and delivering into runs (docs/workflows.md). Running a workflow acts as its starter (their
 * sessions, quota and GitHub link), so a run is started signed in, never by a key.
 */
class WorkflowRunService(private val store: WorkflowRunStore) {
    private fun missing() = TeamFailure("not_found", 404, "Run not found.")
    private fun visible(actor: Principal, run: WorkflowRun?): WorkflowRun {
        if (run == null || (run.starterId != actor.member.id && !actor.canReadTeamCloud)) throw missing()
        return run
    }

    suspend fun start(actor: Principal, workflowId: String, inputs: Map<String, String>, profileId: String?, idempotencyKey: String?): RunChange {
        actor.requireInteractive(); actor.requireReady(); store.revalidate(actor)
        val workflow = store.workflow(actor.teamId, workflowId) ?: throw TeamFailure("not_found", 404, "Workflow not found.")
        val definition = workflow.definition
        val resolved = WorkflowRunRules.inputs(definition, inputs)
        if (profileId == null && definition.nodes.any { it.kind == NodeKind.AGENT && it.profileId == null })
            throw TeamFailure("profile_required", 400, "Name the cloud profile agent nodes run with: some of them name none.")
        val now = store.now()
        val start = definition.nodes.first { it.id == definition.start }
        val run = WorkflowEngine.newRun(workflow, actor.member.id, resolved, profileId, null, 0, null, null, now)
        return store.start(actor, run, WorkflowEngine.visit(run.id, 1, start, now), idempotencyKey?.also { require(KEY.matches(it)) { "Use an idempotency key of 1 to 128 letters, digits or . _ : -." } }, MAX_ACTIVE_RUNS)
    }

    suspend fun get(actor: Principal, id: String): Pair<WorkflowRun, List<NodeVisit>> {
        actor.requireCloudRead(); store.revalidate(actor)
        val run = visible(actor, store.find(actor.teamId, id))
        return run to store.visits(run.id)
    }

    /** The caller's runs; [all] (administrators) the team's. */
    suspend fun list(actor: Principal, all: Boolean, workflowId: String?, status: String?, before: String?, limit: Int): RunPage {
        actor.requireCloudRead(); store.revalidate(actor)
        if (limit !in 1..WorkflowService.MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and ${WorkflowService.MAX_PAGE}.")
        val parsed = status?.let { RunStatus.parse(it) ?: throw TeamFailure("invalid_request", 400, "Use ${RunStatus.entries.joinToString(", ") { s -> s.wire }}.") }
        if (all && !actor.canReadTeamCloud) throw TeamFailure("forbidden", 403, "Administrator access is required to read other members' runs.")
        return store.list(actor.teamId, if (all) null else actor.member.id, workflowId, parsed, before, limit)
    }

    /** The starter, or an administrator signed in. The engine ends the run on its next pass. */
    suspend fun cancel(actor: Principal, id: String): WorkflowRun {
        actor.requireInteractive(); actor.requireReady(); store.revalidate(actor)
        val run = visible(actor, store.find(actor.teamId, id))
        if (run.starterId != actor.member.id && !actor.member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Only who started the run, or an administrator, cancels it.")
        if (run.status.terminal) throw TeamFailure("run_finished", 409, "This run already ended.")
        store.requestCancel(actor.teamId, id)
        return store.find(actor.teamId, id) ?: throw missing()
    }

    /** What the current agent node was asked for. The starter (an agent acts with its starter's credential) hands it in once the node's instruction reached the session. */
    suspend fun deliver(actor: Principal, id: String, output: JsonElement): WorkflowRun {
        actor.requireCloudWrite(); actor.requireReady(); store.revalidate(actor)
        val run = store.find(actor.teamId, id)?.takeIf { it.starterId == actor.member.id } ?: throw missing()
        val visit = store.visits(run.id).lastOrNull { it.status == VisitStatus.ACTIVE }
        if (run.status.terminal || visit == null || visit.kind != NodeKind.AGENT || visit.sessionId == null)
            throw TeamFailure("nothing_to_deliver", 409, "This run is not waiting for a delivery.")
        val node = run.definition.nodes.first { it.id == visit.nodeId }
        val text = Deliverables.validate(node.deliverable!!, output)
        if (!store.complete(visit.id, VisitStatus.DONE, WorkflowRules.DELIVERED, text, store.now())) throw TeamFailure("nothing_to_deliver", 409, "This run is not waiting for a delivery.")
        return store.find(actor.teamId, id) ?: throw missing()
    }

    companion object {
        const val MAX_ACTIVE_RUNS = 20
        private val KEY = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
