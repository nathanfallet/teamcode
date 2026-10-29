package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

private const val WORKFLOW_TEXT = "A workflow is a saved graph of agent, human and sub-workflow steps with typed deliverables and bounded loops. " +
    "This version stores definitions; running them is not available yet. See docs/workflows.md."

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
}
