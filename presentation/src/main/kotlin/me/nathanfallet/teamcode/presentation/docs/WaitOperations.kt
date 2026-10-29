package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import me.nathanfallet.teamcode.api.models.*

private const val WAIT_TEXT = "A wait is what a session needs from a person before it can go on: fields to fill and actions that end it. " +
    "It is asked of an assignee (the session's owner unless another member is named); the session stays its owner's. See docs/waits.md."

private fun Parameters.Builder.waitId() = pathId("id", "The wait's identifier (for a session's permission request, the session's).")

/** Waits: what sessions need from people, and the unified list with the sessions' own permission requests. */
internal fun ApiCatalog.waitOperations() {
    get("/api/v1/waits") {
        operationId = "listWaits"; tag(ApiTags.WAITS)
        summary = "List what waits for a person"
        description = "The caller's, open ones by default, newest first. The first page also carries the open permission requests and questions of the " +
            "assignee's cloud sessions (`source` `session`, at most 100), answered through the session. `assignee=all` or `assignee=<member id>` for an " +
            "administrator or a `team:read` key. $WAIT_TEXT"
        parameters {
            queryText("assignee", "`all`, or a member's id; the caller by default.")
            queryText("status", "`open` (default), `resolved`, `cancelled` or `all`.")
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "1 to 100 (50 by default).")
        }
        responses {
            ok<WaitPageView>("A page of waits.")
            badRequest("An invalid status, page size or cursor (`invalid_request`, `invalid_cursor`).")
            error(HttpStatusCode.Forbidden, "Another member's waits without administrator access (`forbidden`).")
        }
    }
    post("/api/v1/waits") {
        operationId = "createWait"; tag(ApiTags.WAITS)
        summary = "Ask a person for something"
        description = "The session's owner asks (an agent acts with its owner's credential). Without `assigneeId`, the owner is asked. At most 20 open waits per " +
            "session. The same `Idempotency-Key` returns the first wait (`Idempotency-Replayed: true`). $WAIT_TEXT"
        parameters { headerText("Idempotency-Key", "1 to 128 letters, digits or `. _ : -`; a retry returns the wait it created.") }
        body<CreateWaitRequest>("The wait.")
        responses {
            ok<WaitView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid wait (`invalid_wait`) or assignee (`invalid_assignee`).")
            error(HttpStatusCode.Forbidden, "Not the session's owner (`wait_session_owner_only`).")
            notFound("No such session (`not_found`).")
            conflict("The session holds the maximum of open waits (`wait_limit`).")
            error(HttpStatusCode.UnprocessableEntity, "A secret field, or a wait for a time or an event (`not_available_yet`).")
        }
    }
    get("/api/v1/waits/{id}") {
        operationId = "getWait"; tag(ApiTags.WAITS)
        summary = "Read a wait"
        description = "The assignee, the one who asked and administrators. A session's permission request is read by the session's identifier."
        parameters { waitId() }
        responses { ok<WaitView>("The wait."); notFound("No such wait, or not visible to the caller (`not_found`).") }
    }
    post("/api/v1/waits/{id}/answer") {
        operationId = "answerWait"; tag(ApiTags.WAITS)
        summary = "Answer a wait"
        description = "The assignee, or an interactive administrator (audited). `action` is one of the wait's actions; `values` are checked against its fields. " +
            "The first answer wins. A session's permission request is answered on the session (`POST /api/v1/cloud/sessions/{id}/permissions`)."
        parameters { waitId() }
        body<AnswerWaitRequest>("The action chosen and the values by field id.")
        responses {
            ok<WaitView>("The wait, resolved.")
            badRequest("An unknown action or field, a wrong value, or a missing required field (`invalid_answer`).")
            error(HttpStatusCode.Forbidden, "Not the assignee (`wait_assignee_only`).")
            notFound("No such wait (`not_found`).")
            conflict("Already answered or cancelled (`wait_closed`), or a session's permission request (`answer_through_session`).")
        }
    }
    post("/api/v1/waits/{id}/cancel") {
        operationId = "cancelWait"; tag(ApiTags.WAITS)
        summary = "Withdraw a wait"
        description = "The one who asked, the assignee, or an interactive administrator (audited)."
        parameters { waitId() }
        responses {
            ok<WaitView>("The wait, cancelled.")
            error(HttpStatusCode.Forbidden, "Not allowed (`forbidden`).")
            notFound("No such wait (`not_found`).")
            conflict("Already answered or cancelled (`wait_closed`), or a session's permission request (`cancel_through_session`).")
        }
    }
}
