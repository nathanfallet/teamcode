package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

private const val WORKFLOW_TEXT = "A workflow is a saved graph of agent, human and sub-workflow steps with typed deliverables and bounded loops. " +
    "See docs/workflows.md."

private fun io.ktor.openapi.Parameters.Builder.workflowId() = pathId("id", "The workflow's identifier.")

/** Workflow definitions and the rule that says who may edit them. */
internal fun ApiCatalog.workflowOperations() {
    get("/api/v1/workflows") {
        operationId = "listWorkflows"; tag(ApiTags.WORKFLOWS)
        summary = "List workflows"
        description = "Every member reads. By id, ascending; `after` is the previous page's `nextAfter`. $WORKFLOW_TEXT"
        parameters {
            queryText("after", "The `nextAfter` of the previous page.")
            queryNumber("limit", "1 to 100 (50 by default).")
        }
        responses { ok<WorkflowPageView>("A page of workflows."); badRequest("An invalid page size or cursor (`invalid_limit`, `invalid_cursor`).") }
    }
    post("/api/v1/workflows") {
        operationId = "createWorkflow"; tag(ApiTags.WORKFLOWS)
        summary = "Create a workflow"
        description = "Members allowed by the editing rule (administrators by default), signed in. Checked at save: the graph, templates, deliverables, human nodes (as waits) and sub-workflows (they exist, never loop, depth). At most 200 per team."
        body<SaveWorkflowRequest>("The workflow.")
        responses {
            ok<WorkflowView>("Created, at version 1.", HttpStatusCode.Created)
            badRequest("An invalid definition (`invalid_workflow`).")
            error(HttpStatusCode.Forbidden, "Not allowed to edit (`workflow_editors_only`).")
            conflict("The name is taken (`workflow_name_taken`), or the team holds the maximum (`workflow_limit`).")
        }
    }
    get("/api/v1/workflows/settings") {
        operationId = "getWorkflowSettings"; tag(ApiTags.WORKFLOWS)
        summary = "Read who may edit workflows"
        description = "The editing rule and whether the caller may edit."
        responses { ok<WorkflowSettingsView>("The rule.") }
    }
    put("/api/v1/workflows/settings") {
        operationId = "updateWorkflowEditors"; tag(ApiTags.WORKFLOWS)
        summary = "Change who may edit workflows"
        description = "Administrators, signed in (audited). `mode` is `all`, `admins` or `members`; `memberIds` only with `members`, active members of the team."
        body<WorkflowEditorsView>("The rule.")
        responses {
            ok<WorkflowSettingsView>("Saved.")
            badRequest("An invalid rule (`invalid_editors`).")
            error(HttpStatusCode.Forbidden, "Not an administrator (`forbidden`).")
        }
    }
    get("/api/v1/workflows/{id}") {
        operationId = "getWorkflow"; tag(ApiTags.WORKFLOWS)
        summary = "Read a workflow"
        parameters { workflowId() }
        responses { ok<WorkflowView>("The workflow."); notFound("No such workflow (`not_found`).") }
    }
    put("/api/v1/workflows/{id}") {
        operationId = "updateWorkflow"; tag(ApiTags.WORKFLOWS)
        summary = "Save a new version of a workflow"
        description = "Same checks and rights as creation. `expectedVersion` is the version the change was made on: a newer one is refused. Every save makes a new version."
        parameters { workflowId() }
        body<SaveWorkflowRequest>("The workflow and the version it was built on.")
        responses {
            ok<WorkflowView>("Saved.")
            badRequest("An invalid definition (`invalid_workflow`).")
            error(HttpStatusCode.Forbidden, "Not allowed to edit (`workflow_editors_only`).")
            notFound("No such workflow (`not_found`).")
            conflict("Saved meanwhile (`workflow_conflict`), or the name is taken (`workflow_name_taken`).")
        }
    }
    delete("/api/v1/workflows/{id}") {
        operationId = "deleteWorkflow"; tag(ApiTags.WORKFLOWS)
        summary = "Delete a workflow"
        description = "Same rights as creation. Returns the deleted definition."
        parameters { workflowId() }
        responses {
            ok<WorkflowView>("Deleted.")
            error(HttpStatusCode.Forbidden, "Not allowed to edit (`workflow_editors_only`).")
            notFound("No such workflow (`not_found`).")
            conflict("Another workflow calls it (`workflow_in_use`).")
        }
    }
    post("/api/v1/workflows/{id}/runs") {
        operationId = "startWorkflowRun"; tag(ApiTags.WORKFLOWS)
        summary = "Start a run"
        description = "Signed in, as the starter: the run's sessions use their quota and GitHub link. `inputs` are the workflow's inputs; `profileId` is the cloud profile of agent nodes that name none. " +
            "At most 20 unfinished runs per member. The same `Idempotency-Key` returns the first run (`Idempotency-Replayed: true`)."
        parameters { workflowId(); headerText("Idempotency-Key", "1 to 128 letters, digits or `. _ : -`; a retry returns the run it created.") }
        body<StartWorkflowRunRequest>("The inputs and the default profile.")
        responses {
            ok<WorkflowRunView>("Started.", HttpStatusCode.Created)
            badRequest("An unknown, missing or too long input (`invalid_inputs`), or no profile for agent nodes that name none (`profile_required`).")
            notFound("No such workflow (`not_found`).")
            conflict("The member has the maximum of unfinished runs (`run_limit`).")
        }
    }
    get("/api/v1/workflows/runs") {
        operationId = "listWorkflowRuns"; tag(ApiTags.WORKFLOWS)
        summary = "List runs"
        description = "The caller's runs, newest first, without their visits. `all=true` for the whole team (administrators)."
        parameters {
            queryText("workflow", "Only the runs of this workflow.")
            queryText("status", "`running`, `waiting`, `succeeded`, `failed` or `cancelled`.")
            queryText("all", "`true`: the whole team's runs (administrators).")
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "1 to 100 (50 by default).")
        }
        responses {
            ok<WorkflowRunPageView>("A page of runs.")
            badRequest("An invalid status, page size or cursor (`invalid_request`, `invalid_limit`, `invalid_cursor`).")
            error(HttpStatusCode.Forbidden, "The team's runs without administrator access (`forbidden`).")
        }
    }
    get("/api/v1/workflows/runs/{id}") {
        operationId = "getWorkflowRun"; tag(ApiTags.WORKFLOWS)
        summary = "Read a run"
        description = "The starter and administrators. With its visits: the node each pass went through, its outcome and output, and the session, wait or child run it points at."
        parameters { pathId("id", "The run's identifier.") }
        responses { ok<WorkflowRunView>("The run."); notFound("No such run, or not visible to the caller (`not_found`).") }
    }
    post("/api/v1/workflows/runs/{id}/cancel") {
        operationId = "cancelWorkflowRun"; tag(ApiTags.WORKFLOWS)
        summary = "Cancel a run"
        description = "The starter or an administrator, signed in. The run ends on the engine's next pass: its open wait is withdrawn and its child run cancelled; its session is left as it is."
        parameters { pathId("id", "The run's identifier.") }
        responses { ok<WorkflowRunView>("Cancellation requested."); notFound("No such run (`not_found`)."); conflict("The run already ended (`run_finished`).") }
    }
    post("/api/v1/workflows/runs/{id}/deliver") {
        operationId = "deliverWorkflowRun"; tag(ApiTags.WORKFLOWS)
        summary = "Deliver what the current agent node was asked for"
        description = "The starter (an agent acts with their credential), once the node's instruction reached its session. `output` is a string for `artifact`, `url` and `text`, a JSON value for `json`; the server checks it against the node's deliverable before accepting it."
        parameters { pathId("id", "The run's identifier.") }
        body<DeliverRequest>("The delivery.")
        responses {
            ok<WorkflowRunView>("Accepted; the engine moves on.")
            badRequest("It does not match the node's deliverable (`invalid_deliverable`).")
            notFound("No such run (`not_found`).")
            conflict("The run waits for no delivery (`nothing_to_deliver`).")
        }
    }
}
