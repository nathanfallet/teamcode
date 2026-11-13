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
        description = "A personal entry by default. A team entry is written by an interactive member only for now (`memory_team_forbidden` for an agent or a key). " +
            "Content that looks like a credential is refused. At most 2,000 personal and 5,000 team entries. $MEMORY_TEXT"
        body<CreateMemoryRequest>("The entry.")
        responses {
            ok<MemoryEntryView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid field (`invalid_memory`).")
            error(HttpStatusCode.Forbidden, "A team entry from an agent or a key (`memory_team_forbidden`), or a key without `memory` (`credential_scope`).")
            conflict("The memory is full (`memory_limit`).")
            error(HttpStatusCode.UnprocessableEntity, "The content looks like a credential (`memory_secret`).")
        }
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
}
