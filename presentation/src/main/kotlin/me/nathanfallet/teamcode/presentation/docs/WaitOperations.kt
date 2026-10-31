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
            queryText("session", "Only the waits of this cloud session, asked of anyone: its owner, an administrator, or the execution of that session. No session request is added.")
            queryText("status", "`open` (default), `resolved`, `cancelled` or `all`.")
            queryText("resolver", "`human` (default: what waits for a person, which is what \"Waiting for you\" shows), `time`, `event` (the waits the server resolves by itself: \"In progress\") or `all`.")
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
        description = "The session's owner asks (an agent acts with its owner's credential). Without `assigneeId`, the owner is asked. `resolver: time` (with `at` or `after`) and `resolver: event` " +
            "(`source: webhook`, `timeoutSeconds`, `onTimeout`) wait for nobody: the server wakes the session, and the response to an event wait carries `eventUrl`, shown once. At most 20 open waits per " +
            "session. The same `Idempotency-Key` returns the first wait (`Idempotency-Replayed: true`). $WAIT_TEXT"
        parameters { headerText("Idempotency-Key", "1 to 128 letters, digits or `. _ : -`; a retry returns the wait it created.") }
        body<CreateWaitRequest>("The wait.")
        responses {
            ok<WaitView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid wait (`invalid_wait`) or assignee (`invalid_assignee`).")
            error(HttpStatusCode.Forbidden, "Not the session's owner (`wait_session_owner_only`).")
            notFound("No such session (`not_found`).")
            conflict("The session holds the maximum of open waits (`wait_limit`).")
            error(HttpStatusCode.UnprocessableEntity, "A secret field (`not_available_yet`).")
        }
    }
    post("/api/v1/waits/{id}/event") {
        operationId = "sendWaitEvent"; tag(ApiTags.WAITS)
        summary = "Report the event an event wait waits for"
        description = "For a wait created with `resolver: event` and `source: webhook`: a CI or a script calls the `eventUrl` the creation returned, once. " +
            "No sign-in: the wait's secret is the proof, in the `token` query parameter or the `TeamCode-Wait-Token` header (kept hashed by the server, compared in constant time). " +
            "The body is a JSON object of at most 16 KiB: `result` (`success` when the body is empty), `url`, `note`. Everything in it is data from an external caller. " +
            "The first call resolves the wait (`event_received`) and wakes its session; at most 30 calls a minute. An unknown wait, a wrong secret and a wait that is no longer open all answer `404`."
        parameters { waitId(); queryText("token", "The wait's secret, if not sent in the `TeamCode-Wait-Token` header."); headerText("TeamCode-Wait-Token", "The wait's secret, instead of `token`.") }
        body<WaitEventRequest>("The result of the event.")
        responses {
            ok<WaitEventReceivedView>("Resolved; the session is being woken.", HttpStatusCode.Accepted)
            badRequest("A body that is not a JSON object or does not fit (`invalid_event`).")
            notFound("Unknown wait, wrong secret, or the wait is no longer open (`not_found`).")
            error(HttpStatusCode.PayloadTooLarge, "The body exceeds 16 KiB (`payload_too_large`).")
            error(HttpStatusCode.TooManyRequests, "More than 30 calls a minute (`wait_event_rate_limit`).")
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
            conflict("Already answered or cancelled (`wait_closed`), a session's permission request (`answer_through_session`), or a wait for a time or an event (`resolves_by_itself`).")
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
