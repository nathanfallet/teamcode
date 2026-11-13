package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.*

private const val MEMORY_BODY_BYTES = 128 * 1024
private val memoryId = Regex("[a-zA-Z0-9-]{1,36}")

private fun MemoryIndexEntry.view() = MemoryIndexEntryView(id, scope.wire, title, summary, tags, updatedAt)
private fun MemoryEntry.view() = MemoryEntryView(id, scope.wire, title, summary, content, tags, refs, status.wire, origin.wire, authorId, sessionId, supersedesId, expiresAt, version, createdAt, updatedAt, lastReadAt, readCount)
private fun id(value: String?) = value?.takeIf { memoryId.matches(it) } ?: throw TeamFailure("not_found", 404, "Memory entry not found.")
private fun tags(value: String?) = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
private fun limit(value: String?, default: Int) = value?.let { it.toIntOrNull() ?: throw MemoryRules.invalid("Use a number as the limit.") } ?: default
private fun scope(value: String?) = value?.let(MemoryScope::parse)
private fun cursor(value: String?) = value?.let {
    val separator = it.indexOf(':')
    val updatedAt = if (separator > 0) it.substring(0, separator).toLongOrNull() else null
    if (updatedAt == null || !memoryId.matches(it.substring(separator + 1))) throw MemoryRules.invalid("Use the cursor a previous page returned.")
    MemoryCursor(updatedAt, it.substring(separator + 1))
}

/** The memory of docs/memory.md: a member's own and the team's entries, for members, their agents and keys with `memory`. */
internal fun Route.memoryRoutes(memory: MemoryService) {
    route("/api/v1/memory") {
        get("/index") {
            val page = memory.index(call.principal, scope(call.request.queryParameters["scope"]), tags(call.request.queryParameters["tags"]),
                cursor(call.request.queryParameters["after"]), limit(call.request.queryParameters["limit"], MemoryRules.PAGE_LIMIT))
            call.respond(MemoryIndexView(page.entries.map { it.view() }, page.next?.let { "${it.updatedAt}:${it.id}" }))
        }
        get("/search") {
            val query = call.request.queryParameters["q"] ?: throw MemoryRules.invalid("Send the words to search for as q.")
            call.respond(MemorySearchView(memory.search(call.principal, query, tags(call.request.queryParameters["tags"]),
                limit(call.request.queryParameters["limit"], MemoryRules.SEARCH_LIMIT)).map { it.view() }))
        }
        post {
            val input = call.jsonBody<CreateMemoryRequest>(MEMORY_BODY_BYTES)
            val created = memory.create(call.principal, NewMemory(MemoryScope.parse(input.scope), input.title, input.summary, input.content, input.tags, input.refs, input.expiresAt))
            call.respond(HttpStatusCode.Created, created.view())
        }
        get("/{id}") { call.respond(memory.read(call.principal, id(call.parameters["id"])).view()) }
        patch("/{id}") {
            val input = call.jsonBody<UpdateMemoryRequest>(MEMORY_BODY_BYTES)
            call.respond(memory.update(call.principal, id(call.parameters["id"]), MemoryUpdate(input.expectedVersion, input.title, input.summary, input.content, input.tags, input.refs, input.expiresAt)).view())
        }
        delete("/{id}") { memory.delete(call.principal, id(call.parameters["id"])); call.respond(HttpStatusCode.NoContent) }
    }
}
