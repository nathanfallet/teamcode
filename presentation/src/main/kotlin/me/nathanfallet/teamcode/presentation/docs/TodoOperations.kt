package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import me.nathanfallet.teamcode.api.models.*

private const val TODO_TEXT = "A session keeps one todo: the plan reference and items, each with a complete description and a typed end condition. An item is done only when " +
    "its checks pass. Callers: the session's owner (naming `sessionId`), or the session's execution credential (`todo:session`), confined to its own session. See docs/todos.md."

private fun Parameters.Builder.todoItem() = pathId("item", "The item's identifier: a slug of at most 40 characters.")

/** A session's todo (`todos.v1`). */
internal fun ApiCatalog.todoOperations() {
    get("/api/v1/todos/session") {
        operationId = "getSessionTodo"; tag(ApiTags.TODOS)
        summary = "Read a session's todo"
        description = "The list with its items in order, the proof of the last verification of each, and `nextItemId`. `todo` is null while the session has none. Answers to drop requests are applied on this read. " +
            "The owner, an administrator or a `team:read` key, or the session's execution credential. $TODO_TEXT"
        parameters { queryText("sessionId", "The session; ignored for an execution credential.") }
        responses { ok<TodoSessionView>("The todo, or none."); notFound("No such session (`not_found`).") }
    }
    post("/api/v1/todos/session") {
        operationId = "createSessionTodo"; tag(ApiTags.TODOS)
        summary = "Create a session's todo"
        description = "Once per session, active at once. At most 50 items; each needs a description of 80 to 4 000 characters and at least one typed check. No check names a PR number or a version: " +
            "the PR is found by the marker `startTodoItem` returns. $TODO_TEXT"
        body<CreateTodoRequest>("The reference and the items.")
        responses {
            ok<TodoSessionView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid list (`invalid_todo`, `check_required`, `invalid_check`, `guessed_number`, `description_too_short`, `duplicate_item`, `unknown_dependency`, `dependency_cycle`, `too_many_items`).")
            notFound("No such session (`not_found`).")
            conflict("The session already has a todo (`todo_exists`).")
        }
    }
    post("/api/v1/todos/session/items") {
        operationId = "addTodoItem"; tag(ApiTags.TODOS)
        summary = "Add an item found on the way"
        description = "Marked `discovered` with its `reason`, placed after the item in progress and after its own dependencies. $TODO_TEXT"
        body<AddTodoItemRequest>("The item and why it is needed.")
        responses {
            ok<TodoSessionView>("The list.")
            badRequest("An invalid item (same codes as the creation).")
            notFound("No such session (`not_found`), or it has no todo (`todo_not_found`).")
            conflict("The todo is closed (`todo_closed`).")
        }
    }
    post("/api/v1/todos/session/items/{item}/start") {
        operationId = "startTodoItem"; tag(ApiTags.TODOS)
        summary = "Start an item"
        description = "Puts the item in progress (another item in progress goes back to `todo`, unless `background`) and returns the `marker` to put in the body of the item's pull request. $TODO_TEXT"
        parameters { todoItem() }
        body<StartTodoItemRequest>("Optionally a background agent.")
        responses {
            ok<StartTodoItemView>("The list and the marker.")
            notFound("No such session, todo or item (`not_found`, `todo_not_found`, `unknown_item`).")
            conflict("A dependency is not done (`dependency_open`), the item is closed (`item_closed`) or waits for a drop answer (`drop_pending`).")
        }
    }
    post("/api/v1/todos/session/items/{item}/notes") {
        operationId = "noteTodoItem"; tag(ApiTags.TODOS)
        summary = "Add a note to an item"
        description = "A dated note, at most 2 000 characters and 100 per item. Notes are never rewritten. $TODO_TEXT"
        parameters { todoItem() }
        body<NoteTodoItemRequest>("The note.")
        responses { ok<TodoSessionView>("The list."); notFound("No such session, todo or item (`unknown_item`).") }
    }
    post("/api/v1/todos/session/items/{item}/proof") {
        operationId = "completeTodoItem"; tag(ApiTags.TODOS)
        summary = "Report what an item's checks observed"
        description = "`checks` gives, by position in the item's stored condition, whether each check passed, what was observed and https URLs. The item is done only when every check passed: " +
            "a failed or unknown one, or one not reported, refuses with `409 not_done` and keeps the proof. The agent cannot choose or change the checks. $TODO_TEXT"
        parameters { todoItem() }
        body<CompleteTodoItemRequest>("The outcome of each check.")
        responses {
            ok<TodoSessionView>("The item is done.")
            badRequest("A position outside the condition, a position twice, or an oversized proof (`invalid_proof`).")
            notFound("No such session, todo or item (`unknown_item`).")
            conflict("Not done yet (`not_done`: the message says what is missing), or the item is closed (`item_closed`).")
        }
    }
    get("/api/v1/todos/session/context") {
        operationId = "readTodoContext"; tag(ApiTags.TODOS)
        summary = "The reminder block of the session's todo"
        description = "The plan reference, the list and the next item, as a text without instruction to go on. Read by the `SessionStart` hook (`teamcode cloud todo-context`) after a compaction and on resume; `text` is null without an active list. $TODO_TEXT"
        parameters { queryText("sessionId", "The session; ignored for an execution credential.") }
        responses { ok<TodoContextView>("The block, or null."); notFound("No such session.") }
    }
    post("/api/v1/todos/session/stop") {
        operationId = "stopTodoSession"; tag(ApiTags.TODOS)
        summary = "Decide what a stop of the agent leads to"
        description = "Asked by the Stop hook (`teamcode cloud todo-stop`) of a session, up to three times per stop: `begin` answers `allow` (no list, a finished list, a background agent still running or any open wait of the session) or `verify`; " +
            "`verify` carries the outcome of the cheap checks and the pull requests the session opened, ticks what passed and answers `settle` (everything is closed), `block` (with the message for the agent) or `allow`; `settle` carries every check of the done items and finishes the list or reopens an item. " +
            "A stop with no progress since the last blocked one adds to a counter kept here; at 3 a wait asks the owner and the stop passes. $TODO_TEXT"
        body<TodoStopRequest>("The phase and what the hook observed.")
        responses { ok<TodoStopView>("What to do next."); badRequest("An unknown phase, a position outside a condition or an oversized proof."); notFound("No such session.") }
    }
    post("/api/v1/todos/session/wake") {
        operationId = "wakeTodoSession"; tag(ApiTags.TODOS)
        summary = "What the waits of a local run's session have to say"
        description = "Asked by the Stop hook of a local `teamcode run` (`teamcode cloud todo-stop --wake`, `cloud.sessions.local.v1`), after the stop passed because a wait of the session is open: it holds the request for up to `waitMillis` (at most 30 000) and answers as soon as the answer to a wait arrives, which it hands over once (`messages`, oldest first, each a `[TeamCode wait answered]` message). " +
            "`open` says a wait of the session is still open, so the hook goes on holding the agent. A session that is not a local session, has ended or has no open wait answers `open: false` at once. $TODO_TEXT"
        body<TodoWakeRequest>("How long the server may hold the answer back.")
        responses { ok<TodoWakeView>("The answers that arrived, and whether a wait is still open."); notFound("No such session.") }
    }
    put("/api/v1/members/{id}/todo-stop-hook") {
        operationId = "setMemberTodoStopHook"; tag(ApiTags.TODOS)
        summary = "Turn the todo Stop hook on or off for a member"
        description = "When off, the stops of the member's sessions pass without a question and the server-side reminder leaves them alone; their lists are still kept. A member has the hook on until an administrator turns it off. " +
            "Interactive administrators only; no service key reaches this route."
        body<TodoStopHookRequest>("`enabled`: true to hold the agent while its todo has open items.")
        responses { ok<TodoStopHookView>("The setting now in force."); notFound("No such member of the team.") }
    }
    post("/api/v1/todos/session/hook-error") {
        operationId = "recordTodoHookError"; tag(ApiTags.TODOS)
        summary = "Record a failure of the Stop hook"
        description = "The hook fails open (the agent is never held by a broken hook), and says so here: the list keeps the newest 20 events (`hookEvents`). $TODO_TEXT"
        body<TodoHookErrorRequest>("What failed, at most 500 characters.")
        responses { ok<TodoSessionView>("The list."); notFound("No such session, or no todo (`todo_not_found`).") }
    }
    post("/api/v1/todos/session/items/{item}/link") {
        operationId = "linkTodoItem"; tag(ApiTags.TODOS)
        summary = "Bind a PR, a release, an artifact or a wait to an item"
        description = "`kind` is `pr`, `release` (their https address in `ref`), `artifact` or `wait` (their id). A link is frozen: the same `ref` may be completed with `number`, `sha` (a PR's merge commit) or `tag`; " +
            "another one of the same kind is refused, except to replace a PR closed without merge (`replaceClosed`), which a note records. `artifact_published` and `wait_answered` are verified by the server through these links. $TODO_TEXT"
        parameters { todoItem() }
        body<LinkTodoItemRequest>("What to bind.")
        responses {
            ok<TodoSessionView>("The list.")
            badRequest("An invalid link (`invalid_link`).")
            notFound("No such session, todo or item (`unknown_item`).")
            conflict("The item already has a link of this kind (`already_linked`), or the todo is closed (`todo_closed`).")
        }
    }
    post("/api/v1/todos/session/items/{item}/drop") {
        operationId = "requestTodoDrop"; tag(ApiTags.TODOS)
        summary = "Ask to drop an item"
        description = "Opens a wait for the session's owner and puts the item in `drop_requested`. Nothing is deleted: the item is dropped only on the owner's yes. $TODO_TEXT"
        parameters { todoItem() }
        body<DropTodoItemRequest>("Why the item no longer makes sense.")
        responses {
            ok<TodoSessionView>("The list.")
            notFound("No such session, todo or item (`unknown_item`).")
            conflict("The item is done or dropped (`item_closed`), or already waits (`drop_pending`).")
            error(HttpStatusCode.ServiceUnavailable, "Waits are not available (`waits_unavailable`).")
        }
    }
}
