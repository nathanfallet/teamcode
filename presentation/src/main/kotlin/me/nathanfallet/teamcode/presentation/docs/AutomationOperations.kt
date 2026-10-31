package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.Parameters
import me.nathanfallet.teamcode.api.models.*

private const val OWNER_TEXT = "An automation runs as its owner (quota, accounts, MCP servers, skills, GitHub link): only the owner arms, " +
    "changes or really tests it; an administrator reads, previews, pauses and deletes the team's. See docs/automations.md."
private const val TRIGGER_TEXT = "No login: the proof is either the headers `TeamCode-Timestamp` and `TeamCode-Signature: v1=<hex>` " +
    "(HMAC-SHA256 of `<timestamp>.<raw body>` keyed with the automation's secret, at most five minutes old), or the secret itself as the last " +
    "path segment. Every refusal before the proof is the same `401 invalid_signature`. The body is a JSON object of at most 64 KiB; " +
    "`Idempotency-Key` or `TeamCode-Delivery` (or, without them, the body within a minute) deduplicates a delivery " +
    "(`Idempotency-Replayed: true`)."

private fun Parameters.Builder.automationId() = pathId("id", "The automation's identifier.")
private fun Parameters.Builder.runId() = pathId("runId", "The run's identifier.")
private fun Parameters.Builder.version() = pathId("version", "The automation version the sender was set up for (`1`, `2`, ...), or `latest`.")
private fun Parameters.Builder.token() = pathId("token", "The automation's secret (`whsec_...`), for a sender that cannot sign.")

private fun Operation.Builder.trigger(id: String, versioned: Boolean, secretUrl: Boolean) {
    operationId = id; tag(ApiTags.AUTOMATIONS)
    summary = if (secretUrl) "Trigger a webhook automation by its secret URL" else "Trigger a webhook automation"
    description = TRIGGER_TEXT + if (versioned) " This URL runs only while the automation is at that version (`409 automation_version_changed`)." else ""
    parameters { automationId(); if (versioned) version(); if (secretUrl) token() }
    responses {
        ok<AutomationTriggerAcceptedView>("Recorded; the session starts later (the status may already be `skipped_overlap`).", HttpStatusCode.Accepted)
        error(HttpStatusCode.Unauthorized, "Unknown automation, wrong or missing proof (`invalid_signature`).")
        conflict("Paused (`automation_disabled`), or the version in the URL is no longer current (`automation_version_changed`).")
        error(HttpStatusCode.PayloadTooLarge, "The body is over 64 KiB (`payload_too_large`).")
        error(HttpStatusCode.UnprocessableEntity, "Not a JSON object, or a variable does not validate (`invalid_payload`, naming paths and rules, never values); recorded as an `invalid_payload` run.")
        error(HttpStatusCode.TooManyRequests, "More than 30 requests a minute (`automation_rate_limit`), or the automation's runs per day (`automation_budget`).")
    }
}

/** Members' automations: cron and webhook triggers that start cloud sessions, or workflow runs, as their owner. */
internal fun ApiCatalog.automationOperations() {
    get("/api/v1/automations") {
        operationId = "listAutomations"; tag(ApiTags.AUTOMATIONS)
        summary = "List automations"
        description = "The caller's own, newest first; `owner=all` or `owner=<member id>` for an administrator or a `team:read` key. $OWNER_TEXT"
        parameters {
            queryText("owner", "`all`, or a member's id; the caller by default.")
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "1 to 100 (50 by default).")
        }
        responses { ok<AutomationPageView>("A page of automations."); badRequest("An invalid page size or cursor (`invalid_limit`, `invalid_cursor`).") }
    }
    post("/api/v1/automations") {
        operationId = "createAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Create an automation"
        description = "Created disabled. A webhook automation's secret is in the response once. The action starts a cloud session (`cloud`) or a run of a " +
            "saved workflow (`workflow`: `workflowId` and `inputs`, each a template over the payload and run variables, checked against the workflow's inputs). $OWNER_TEXT"
        body<CreateAutomationRequest>("The definition.")
        responses {
            ok<CreatedAutomationView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid definition (`invalid_automation`); for a workflow action also an unknown or deleted workflow, an unknown input or a required input without a value or default.")
            error(HttpStatusCode.Forbidden, "The profile is not assigned to the caller (`profile_unassigned`).")
            notFound("No such profile (`profile_not_found`).")
            conflict("The caller owns the team's maximum of automations (`automation_limit`).")
            error(HttpStatusCode.UnprocessableEntity, "A `remote` action (`not_available_yet`).")
        }
    }
    post("/api/v1/automations/preview") {
        operationId = "previewAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Check and render an unsaved definition"
        description = "Renders the prompt (or the inputs of a workflow action) with the payload as the caller's automation would, without saving anything."
        body<PreviewAutomationRequest>("The draft and an optional payload.")
        responses { ok<AutomationPreviewView>("The rendered prompt, or the problems by path."); badRequest("An invalid definition (`invalid_automation`).") }
    }
    get("/api/v1/automations/{id}") {
        operationId = "getAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Read an automation"
        parameters { automationId() }
        responses { ok<AutomationView>("The automation."); notFound("No such automation, or not visible to the caller (`not_found`).") }
    }
    put("/api/v1/automations/{id}") {
        operationId = "updateAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Change an automation"
        description = "The owner only. `version` is the one being replaced; every change makes a new version. The trigger kind cannot change."
        parameters { automationId() }
        body<UpdateAutomationRequest>("The version being replaced and the new definition.")
        responses {
            ok<AutomationView>("Saved.")
            badRequest("An invalid definition (`invalid_automation`).")
            error(HttpStatusCode.Forbidden, "Not the owner (`automation_owner_only`).")
            notFound("No such automation (`not_found`).")
            conflict("The automation changed meanwhile (`stale_version`).")
        }
    }
    delete("/api/v1/automations/{id}") {
        operationId = "deleteAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Delete an automation"
        description = "The owner or an administrator. Its history goes with it; its sessions stay, labelled `automation=<id>`."
        parameters { automationId() }
        responses { empty("Deleted."); notFound("No such automation (`not_found`).") }
    }
    post("/api/v1/automations/{id}/enable") {
        operationId = "enableAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Arm an automation"
        description = "The owner only, signed in: their GitHub link is lent to its sessions from now on. A cron automation's next slot is computed from now."
        parameters { automationId() }
        responses { ok<AutomationView>("Enabled."); error(HttpStatusCode.Forbidden, "Not the owner (`automation_owner_only`)."); notFound("No such automation (`not_found`).") }
    }
    post("/api/v1/automations/{id}/disable") {
        operationId = "disableAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Pause an automation"
        description = "The owner or an administrator. Runs already recorded continue."
        parameters { automationId() }
        responses { ok<AutomationView>("Paused."); notFound("No such automation (`not_found`).") }
    }
    post("/api/v1/automations/{id}/webhook/rotate-secret") {
        operationId = "rotateAutomationSecret"; tag(ApiTags.AUTOMATIONS)
        summary = "Rotate a webhook automation's secret"
        description = "The owner only. The new secret is in the response once; the former one is accepted 10 more minutes."
        parameters { automationId() }
        responses { ok<CreatedAutomationView>("The automation and its new secret."); notFound("No such automation (`not_found`)."); conflict("Not a webhook automation (`not_webhook`).") }
    }
    post("/api/v1/automations/{id}/run") {
        operationId = "runAutomation"; tag(ApiTags.AUTOMATIONS)
        summary = "Test an automation"
        description = "`dryRun: true` renders the saved definition with the payload and records nothing (owner or administrator). Otherwise a real run as " +
            "the owner (the owner, signed in or with a `cloud:write` key), with an `Idempotency-Key`; it costs the owner's quota like a real trigger. " +
            "A webhook automation needs a payload; a cron one takes none."
        parameters { automationId(); headerText("Idempotency-Key", "Required for a real test: the same key replays the same run (`Idempotency-Replayed: true`).") }
        body<RunAutomationRequest>("The payload and whether it is a dry run.")
        responses {
            ok<AutomationPreviewView>("The dry run.")
            ok<AutomationRunView>("The recorded run.", HttpStatusCode.Accepted)
            badRequest("No or an invalid `Idempotency-Key` for a real test (`idempotency_key_required`, `invalid_idempotency_key`).")
            error(HttpStatusCode.Forbidden, "A real test of another member's automation (`automation_owner_only`), or a key without `cloud:write` (`credential_scope`).")
            notFound("No such automation (`not_found`).")
            error(HttpStatusCode.UnprocessableEntity, "A payload that does not fit (`payload_required`, `payload_not_accepted`, `invalid_payload`), or a reused key with another payload (`idempotency_conflict`).")
        }
    }
    get("/api/v1/automations/{id}/runs") {
        operationId = "listAutomationRuns"; tag(ApiTags.AUTOMATIONS)
        summary = "An automation's history"
        description = "Newest first, kept 14 days after each run ends."
        parameters {
            automationId()
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "1 to 100 (50 by default).")
            queryList("source", "Only these sources (`cron`, `webhook`, `test`).")
            queryList("status", "Only these statuses.")
        }
        responses { ok<AutomationRunPageView>("A page of runs."); notFound("No such automation (`not_found`).") }
    }
    get("/api/v1/automations/{id}/runs/{runId}") {
        operationId = "getAutomationRun"; tag(ApiTags.AUTOMATIONS)
        summary = "Read a run"
        description = "With its extracted variables; sensitive ones are masked."
        parameters { automationId(); runId() }
        responses { ok<AutomationRunView>("The run."); notFound("No such automation or run (`not_found`).") }
    }
    post("/api/v1/automations/{id}/runs/{runId}/cancel") {
        operationId = "cancelAutomationRun"; tag(ApiTags.AUTOMATIONS)
        summary = "Cancel a run"
        description = "A waiting run ends at once; a working one ends `cancelled` once its session is paused. The owner or an administrator."
        parameters { automationId(); runId() }
        responses { ok<AutomationRunView>("The run."); notFound("No such automation or run (`not_found`).") }
    }
    post("/api/v1/automations/{id}/trigger") { trigger("triggerAutomation", versioned = false, secretUrl = false) }
    post("/api/v1/automations/{id}/trigger/{token}") { trigger("triggerAutomationBySecretUrl", versioned = false, secretUrl = true) }
    post("/api/v1/automations/{id}/v/{version}/trigger") { trigger("triggerAutomationVersion", versioned = true, secretUrl = false) }
    post("/api/v1/automations/{id}/v/{version}/trigger/{token}") { trigger("triggerAutomationVersionBySecretUrl", versioned = true, secretUrl = true) }
}
