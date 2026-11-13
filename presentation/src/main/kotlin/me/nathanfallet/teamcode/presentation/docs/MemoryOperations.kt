package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

private const val MEMORY_TEXT = "The memory is the team's entries (every member reads them) and the caller's own personal entries (nobody else reads them, administrators " +
    "included). An interactive member, an agent's execution credential or a key with `memory`. See docs/memory.md."

/** The memory (`memory.v1`). */
internal fun ApiCatalog.memoryOperations() {
    get("/api/v1/memory/index") {
        operationId = "listMemory"; tag(ApiTags.MEMORY)
        summary = "The memory index"
        description = "Active, unexpired entries as id, scope, title, one-line summary and tags, newest first, never the content. $MEMORY_TEXT"
        parameters {
            queryText("scope", "`team` or `personal`; both when absent.")
            queryText("tags", "Comma-separated tags: entries having any of them.")
            queryText("after", "The `next` of the previous page.")
            queryNumber("limit", "1 to 50 (default 50).")
        }
        responses { ok<MemoryIndexView>("A page of the index."); badRequest("An invalid scope, tag, cursor or limit (`invalid_memory`).") }
    }
    get("/api/v1/memory/search") {
        operationId = "searchMemory"; tag(ApiTags.MEMORY)
        summary = "Search the memory"
        description = "Entries whose title, summary or tags contain every word of `q` (at most 5 words), newest first, at most 20. The content is not searched. $MEMORY_TEXT"
        parameters {
            queryText("q", "The words to look for.")
            queryText("tags", "Comma-separated tags: entries having any of them.")
            queryNumber("limit", "1 to 20 (default 20).")
        }
        responses { ok<MemorySearchView>("The matching entries."); badRequest("No usable word, or an invalid tag (`invalid_memory`).") }
    }
    post("/api/v1/memory") {
        operationId = "createMemory"; tag(ApiTags.MEMORY)
        summary = "Remember something"
        description = "A personal entry by default. A team entry written by an interactive member is direct; one written by an agent (an execution credential, or a call through an agent's relay) is stored as a proposal and the session's owner is asked through a wait: the response is the entry with status `proposed`, and it joins the team's memory only once accepted. A key that is no agent's session cannot propose (`memory_team_forbidden`). " +
            "Content that looks like a credential is refused. At most 2,000 personal and 5,000 team entries. $MEMORY_TEXT"
        body<CreateMemoryRequest>("The entry.")
        responses {
            ok<MemoryEntryView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid field (`invalid_memory`).")
            error(HttpStatusCode.Forbidden, "A team entry from a key that is no agent's session (`memory_team_forbidden`), or a key without `memory` (`credential_scope`).")
            conflict("The memory is full (`memory_limit`), or 20 proposals of the caller are waiting (`memory_proposals_limit`).")
            error(HttpStatusCode.UnprocessableEntity, "The content looks like a credential (`memory_secret`).")
        }
    }
    get("/api/v1/memory/proposals") {
        operationId = "listMemoryProposals"; tag(ApiTags.MEMORY)
        summary = "Proposals waiting for an answer"
        description = "Team entries an agent proposed, oldest first, at most 100: every member's for an administrator, the caller's own otherwise. Interactive login only. $MEMORY_TEXT"
        responses { ok<MemoryProposalsView>("The proposals."); error(HttpStatusCode.Forbidden, "A key (`memory_team_forbidden`).") }
    }
    post("/api/v1/memory/proposals/{id}/accept") {
        operationId = "acceptMemoryProposal"; tag(ApiTags.MEMORY)
        summary = "Accept a proposal"
        description = "An administrator adds the proposed entry to the team's memory and closes its wait. The owner of the session normally answers the wait instead, where the text can be edited first. $MEMORY_TEXT"
        parameters { pathId("id", "The proposal's identifier.") }
        responses { ok<MemoryEntryView>("The accepted entry."); conflict("Not a proposal waiting for an answer (`memory_not_proposed`)."); error(HttpStatusCode.Forbidden, "Not an administrator (`forbidden`).") }
    }
    post("/api/v1/memory/proposals/{id}/reject") {
        operationId = "rejectMemoryProposal"; tag(ApiTags.MEMORY)
        summary = "Reject a proposal"
        description = "An administrator, or the proposal's author, drops it and closes its wait. Rejected proposals are deleted after 30 days. $MEMORY_TEXT"
        parameters { pathId("id", "The proposal's identifier.") }
        responses { ok<MemoryEntryView>("The rejected entry."); conflict("Not a proposal waiting for an answer (`memory_not_proposed`)."); error(HttpStatusCode.Forbidden, "Neither an administrator nor the author (`forbidden`).") }
    }
    get("/api/v1/memory/{id}") {
        operationId = "readMemory"; tag(ApiTags.MEMORY)
        summary = "Read an entry"
        description = "The entry with its content and references; counts as a read. $MEMORY_TEXT"
        parameters { pathId("id", "The entry's identifier.") }
        responses { ok<MemoryEntryView>("The entry."); notFound("No such entry, or not yours to read (`not_found`).") }
    }
    patch("/api/v1/memory/{id}") {
        operationId = "updateMemory"; tag(ApiTags.MEMORY)
        summary = "Change an entry"
        description = "Replaces the entry. A personal entry is its owner's; a team entry is its author's or an administrator's, and an interactive member's only. $MEMORY_TEXT"
        parameters { pathId("id", "The entry's identifier.") }
        body<UpdateMemoryRequest>("The new entry and the version read.")
        responses {
            ok<MemoryEntryView>("The updated entry.")
            badRequest("An invalid field (`invalid_memory`).")
            notFound("No such entry, or not yours (`not_found`).")
            error(HttpStatusCode.Forbidden, "Not allowed to change this team entry (`memory_team_forbidden`).")
            conflict("The entry changed since it was read (`memory_conflict`).")
            error(HttpStatusCode.UnprocessableEntity, "The content looks like a credential (`memory_secret`).")
        }
    }
    delete("/api/v1/memory/{id}") {
        operationId = "forgetMemory"; tag(ApiTags.MEMORY)
        summary = "Forget an entry"
        description = "Deletes it for good. Same rights as changing it. $MEMORY_TEXT"
        parameters { pathId("id", "The entry's identifier.") }
        responses {
            empty("Forgotten.")
            notFound("No such entry, or not yours (`not_found`).")
            error(HttpStatusCode.Forbidden, "Not allowed to delete this team entry (`memory_team_forbidden`).")
        }
    }
    get("/api/v1/memory/curation") {
        operationId = "getMemoryCuration"; tag(ApiTags.MEMORY)
        summary = "The memory curation setting"
        description = "Whether the team's memory is curated and what the last run did. Administrators, interactive login only. Curation is off until an administrator turns it on; it never calls a model."
        responses { ok<MemoryCurationView>("The setting."); error(HttpStatusCode.Forbidden, "Not an administrator (`forbidden`).") }
    }
    put("/api/v1/memory/curation") {
        operationId = "setMemoryCuration"; tag(ApiTags.MEMORY)
        summary = "Turn the memory curation on or off"
        description = "On: about once an hour the server reads the scopes that changed (the team's entries, and each member's personal ones, alone), supersedes the older of two equal entries, and queues for a person every pair it cannot decide. Audited."
        body<SetMemoryCurationRequest>("The setting.")
        responses { ok<MemoryCurationView>("The setting."); error(HttpStatusCode.Forbidden, "Not an administrator (`forbidden`).") }
    }
    get("/api/v1/memory/reviews") {
        operationId = "listMemoryReviews"; tag(ApiTags.MEMORY)
        summary = "Pairs for a person to decide"
        description = "Open reviews, oldest first, at most 100: the caller's own personal ones, plus the team's for an administrator. Interactive login only. $MEMORY_TEXT"
        responses { ok<MemoryReviewsView>("The reviews.") }
    }
    post("/api/v1/memory/reviews/{id}/apply") {
        operationId = "applyMemoryReview"; tag(ApiTags.MEMORY)
        summary = "Supersede the older entry"
        description = "The older entry of the pair becomes superseded by the newer one (restorable). $MEMORY_TEXT"
        parameters { pathId("id", "The review's identifier.") }
        responses { ok<MemoryReviewView>("The review."); notFound("No such review, or not yours (`not_found`)."); conflict("Already answered (`memory_review_closed`), or an entry is no longer active (`memory_review_stale`).") }
    }
    post("/api/v1/memory/reviews/{id}/dismiss") {
        operationId = "dismissMemoryReview"; tag(ApiTags.MEMORY)
        summary = "Keep both entries"
        description = "Both entries stay; the pair is never queued again. $MEMORY_TEXT"
        parameters { pathId("id", "The review's identifier.") }
        responses { ok<MemoryReviewView>("The review."); notFound("No such review, or not yours (`not_found`)."); conflict("Already answered (`memory_review_closed`).") }
    }
    get("/api/v1/memory/superseded") {
        operationId = "listSupersededMemory"; tag(ApiTags.MEMORY)
        summary = "Superseded entries"
        description = "Entries curation replaced, newest first, at most 100: the caller's own personal ones (nobody else sees them), and the team's for an administrator or their author. Kept 90 days. Interactive login only."
        responses { ok<SupersededMemoriesView>("The entries.") }
    }
    post("/api/v1/memory/{id}/restore") {
        operationId = "restoreMemory"; tag(ApiTags.MEMORY)
        summary = "Restore a superseded entry"
        description = "The entry is active again and the one that replaced it, if still active, becomes the superseded one. Audited `memory.restored`. A personal entry by its owner; a team entry by its author or an administrator."
        parameters { pathId("id", "The entry's identifier.") }
        responses { ok<MemoryEntryView>("The restored entry."); notFound("No such superseded entry, or not yours (`not_found`)."); error(HttpStatusCode.Forbidden, "Not the author or an administrator (`forbidden`)."); conflict("The memory is full (`memory_limit`).") }
    }
}
