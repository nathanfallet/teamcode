package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.todos.*
import me.nathanfallet.teamcode.domain.waits.WaitRef

private val itemId = Regex("[a-z0-9][a-z0-9-]{0,39}")
private val sessionIdPattern = Regex("[a-zA-Z0-9-]{1,36}")
private fun ApplicationCall.itemId() = parameters["item"]!!.also { if (!itemId.matches(it)) throw TeamFailure("not_found", 404, "Item not found.") }
private fun session(value: String?) = value?.also { if (!sessionIdPattern.matches(it)) throw TeamFailure("invalid_request", 400, "Use a session identifier.") }
private fun invalid(message: String) = TeamFailure("invalid_todo", 400, message)

/** A todo with its items, refs and notes: at most 50 items of 4 000 characters and 100 notes each, encoded. */
private const val TODO_BYTES = 512 * 1024

private fun TodoCheckInput.domain(): TodoCheck {
    fun need(value: String?, name: String) = value ?: throw invalid("A $type check needs $name.")
    return when (type) {
        "pr_merged" -> TodoCheck.PrMerged(need(repo, "repo"))
        "pr_closed" -> TodoCheck.PrClosed(need(repo, "repo"), need(reason, "reason"))
        "release_contains" -> TodoCheck.ReleaseContains(need(repo, "repo"), of, commit)
        "ci_green" -> TodoCheck.CiGreen(need(repo, "repo"), of, ref, workflow)
        "command" -> TodoCheck.Command(need(run, "run"), cwd, expectExit, timeoutSeconds)
        "artifact_published" -> TodoCheck.ArtifactPublished(id, minVersion)
        "wait_answered" -> TodoCheck.WaitAnswered(action)
        "attested" -> TodoCheck.Attested(need(what, "what"))
        else -> throw invalid("Use a check type of ${Todos.checkTypes.joinToString(", ")}.")
    }
}

private fun TodoItemInput.domain() = TodoItemDefinition(id, name, description, TodoDoneWhen(doneWhen.text, doneWhen.checks.map { it.domain() }), dependsOn)

private fun TodoCheck.view() = when (this) {
    is TodoCheck.PrMerged -> TodoCheckView(type, repo = repo)
    is TodoCheck.PrClosed -> TodoCheckView(type, repo = repo, reason = reason)
    is TodoCheck.ReleaseContains -> TodoCheckView(type, repo = repo, of = of, commit = commit)
    is TodoCheck.CiGreen -> TodoCheckView(type, repo = repo, of = of, ref = ref, workflow = workflow)
    is TodoCheck.Command -> TodoCheckView(type, run = run, cwd = cwd, expectExit = expectExit, timeoutSeconds = timeoutSeconds)
    is TodoCheck.ArtifactPublished -> TodoCheckView(type, id = id, minVersion = minVersion)
    is TodoCheck.WaitAnswered -> TodoCheckView(type, action = action)
    is TodoCheck.Attested -> TodoCheckView(type, what = what)
}

private fun TodoItem.view() = TodoItemView(
    id, name, description, TodoDoneWhenView(doneWhen.text, doneWhen.checks.map { it.view() }), dependsOn, origin.wire, originReason, status.wire,
    proof?.let { TodoProofView(it.at, it.passed, it.text, it.urls) }, notes.map { TodoNoteView(it.at, it.text) }, dropReason, dropWaitId,
    unverified = doneWhen.checks.all { it is TodoCheck.Attested },
)

internal fun TodoList.view() = TodoView(
    id, sessionId, reference, refs.map { TodoRefInput(it.kind, it.id, it.url, it.title) }, status.wire, stall, items.map { it.view() }, createdAt, updatedAt,
    nextItemId = TodoRules.next(this)?.id,
)

/**
 * A session's todo (docs/todos.md): its owner, or the session's own execution credential (`todo:session`), writes; an
 * administrator reads. The rules and the checks of an item live in the domain; these routes decode and encode.
 */
internal fun Route.todoRoutes(todos: TodoService) {
    route("/api/v1/todos/session") {
        get { call.respond(TodoSessionView(todos.get(call.principal, session(call.request.queryParameters["sessionId"]))?.view())) }
        post {
            val input = call.jsonBody<CreateTodoRequest>(TODO_BYTES)
            val list = todos.create(call.principal, session(input.sessionId), input.reference, input.refs.map { WaitRef(it.kind, it.id, it.url, it.title) }, input.items.map { it.domain() })
            call.respond(HttpStatusCode.Created, TodoSessionView(list.view()))
        }
        post("/items") {
            val input = call.jsonBody<AddTodoItemRequest>(TODO_BYTES)
            call.respond(TodoSessionView(todos.add(call.principal, session(input.sessionId), input.item.domain(), input.reason).view()))
        }
        post("/items/{item}/start") {
            val input = call.jsonBody<StartTodoItemRequest>(TODO_BYTES)
            val (list, marker) = todos.start(call.principal, session(input.sessionId), call.itemId(), input.background, input.agent)
            call.respond(StartTodoItemView(list.view(), marker))
        }
        post("/items/{item}/notes") {
            val input = call.jsonBody<NoteTodoItemRequest>(TODO_BYTES)
            call.respond(TodoSessionView(todos.note(call.principal, session(input.sessionId), call.itemId(), input.text).view()))
        }
        post("/items/{item}/proof") {
            val input = call.jsonBody<CompleteTodoItemRequest>(TODO_BYTES)
            call.respond(TodoSessionView(todos.complete(call.principal, session(input.sessionId), call.itemId(), input.checks.map { TodoCheckReport(it.index, it.passed, it.observed, it.urls) }).view()))
        }
        post("/items/{item}/drop") {
            val input = call.jsonBody<DropTodoItemRequest>(TODO_BYTES)
            call.respond(TodoSessionView(todos.requestDrop(call.principal, session(input.sessionId), call.itemId(), input.reason).view()))
        }
    }
}
