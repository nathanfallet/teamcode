package me.nathanfallet.teamcode.cli.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.cli.mcp.McpTools.boolean
import me.nathanfallet.teamcode.cli.mcp.McpTools.schema
import me.nathanfallet.teamcode.cli.mcp.McpTools.string
import me.nathanfallet.teamcode.cli.mcp.McpTools.text
import me.nathanfallet.teamcode.cli.todo.TodoChecks
import me.nathanfallet.teamcode.cli.todo.TodoDocs
import me.nathanfallet.teamcode.cli.todo.TodoRender
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * The `teamcode_todo` MCP server: the agent keeps its session's todo through the launcher's loopback relay (docs/todos.md). It
 * creates the list once, starts and notes items, and asks for an item to be done: then this process runs the item's stored checks
 * from the agent's environment ([checks]), reports what they observed, and the server decides. Nothing here deletes an item,
 * rewrites a condition or marks an item done by itself.
 */
internal class TodoMcpServer(
    private val client: TeamCodeClient,
    private val sessionId: String,
    private val checks: TodoChecks,
) {
    /** Handles one JSON-RPC line; null for a notification, which gets no answer. */
    suspend fun handle(line: String): String? {
        val message = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
            ?: return McpTools.error(JsonNull, -32700, "Parse error").toString()
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null || id is JsonNull) return null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> McpTools.result(id, initialize(params))
            "ping" -> McpTools.result(id, JsonObject(emptyMap()))
            "tools/list" -> McpTools.result(id, buildJsonObject { put("tools", TOOLS) })
            "tools/call" -> (params["name"] as? JsonPrimitive)?.contentOrNull.let { name ->
                if (name == null || name !in TOOL_NAMES) McpTools.error(id, -32602, "Unknown tool: ${name ?: "no name"}")
                else McpTools.result(id, call(name, params["arguments"] as? JsonObject ?: JsonObject(emptyMap())))
            }
            null -> McpTools.error(id, -32600, "Invalid request")
            else -> McpTools.error(id, -32601, "Method not found")
        }.toString()
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") { put("name", "teamcode-todo"); put("version", VERSION) }
            put("instructions", TodoDocs.INSTRUCTIONS)
        }
    }

    private suspend fun call(name: String, arguments: JsonObject): JsonObject = try {
        when (name) {
            "todo_help" -> McpTools.text(TodoDocs.HELP)
            "todo_create" -> create(arguments)
            "todo_show" -> McpTools.text(show())
            "todo_start" -> start(arguments)
            "todo_complete" -> complete(arguments)
            "todo_link" -> link(arguments)
            "todo_add" -> add(arguments)
            "todo_note" -> note(arguments)
            else -> drop(arguments)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        McpTools.text(McpTools.describe(error) { hint(it) }, isError = true)
    }

    private fun required(arguments: JsonObject, name: String) = arguments.string(name) ?: throw CliUserException("$name is required.")

    private suspend fun show(): String = client.sessionTodo(sessionId).todo?.let(TodoRender::show) ?: "This session has no todo yet. Call todo_create (todo_help shows the format)."

    private inline fun <reified T> decode(element: JsonElement?, what: String): T = try {
        Serialization.json.decodeFromJsonElement<T>(element ?: throw CliUserException("$what is required."))
    } catch (error: kotlinx.serialization.SerializationException) {
        throw CliUserException("$what is not in the expected form: ${error.message?.lineSequence()?.firstOrNull()?.take(200)}. Call todo_help for the format.")
    }

    private suspend fun create(arguments: JsonObject): JsonObject {
        val reference = required(arguments, "reference")
        val items = decode<List<TodoItemInput>>(arguments["items"], "items")
        val refs = arguments["refs"]?.takeIf { it !is JsonNull }?.let { decode<List<TodoRefInput>>(it, "refs") }.orEmpty()
        val todo = client.createSessionTodo(CreateTodoRequest(sessionId, reference, refs, items)).todo!!
        return McpTools.text("Todo created with ${todo.items.size} items.\n\n" + TodoRender.show(todo))
    }

    private suspend fun start(arguments: JsonObject): JsonObject {
        val id = required(arguments, "id")
        val started = client.startTodoItem(id, StartTodoItemRequest(sessionId, arguments.boolean("background") ?: false, arguments.string("agent")))
        val item = started.todo.items.first { it.id == id }
        val hasPr = item.doneWhen.checks.any { it.type == "pr_merged" || it.type == "pr_closed" }
        return McpTools.text(buildString {
            appendLine("Item $id is in progress.")
            appendLine(item.description)
            appendLine("Done when: ${item.doneWhen.text}")
            if (hasPr) {
                appendLine("Put this marker, as is, in the body of the pull request you open for it (the PR is found and linked by it):")
                append(started.marker)
            }
        }.trimEnd())
    }

    private suspend fun complete(arguments: JsonObject): JsonObject {
        val id = required(arguments, "id")
        val proof = arguments["proof"] as? JsonObject
        val todo = client.sessionTodo(sessionId).todo ?: throw CliUserException("This session has no todo yet.")
        val item = todo.items.firstOrNull { it.id == id } ?: throw CliUserException("No item '$id' in this todo.")
        val urls = (proof?.get("urls") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().filter { it.startsWith("https://") }.take(10)
        val outcomes = checks.run(todo, item, proof?.string("text"), urls)
        // What a check found (the PR by its marker, the release) is bound to the item first, so the server and the next check can cite it.
        for (link in outcomes.flatMap { it.second.links }.distinctBy { it.kind to it.ref }) {
            try { client.linkTodoItem(id, link) } catch (error: CancellationException) { throw error } catch (error: TeamCodeException) {
                if (error.code != "already_linked") throw error
            }
        }
        val reports = outcomes.map { (index, outcome) -> TodoCheckReportInput(index, outcome.passed, outcome.observed.take(2_000), outcome.urls.take(10)) }
        val after = try {
            client.completeTodoItem(id, CompleteTodoItemRequest(sessionId, reports)).todo!!
        } catch (error: TeamCodeException) {
            if (error.code != "not_done") throw error
            // The client keeps only the server's error code; what was seen is the proof the server stored for the refusal.
            val seen = client.sessionTodo(sessionId).todo?.items?.firstOrNull { it.id == id }?.proof?.text ?: outcomes.joinToString("; ") { it.second.observed }
            return McpTools.text("Item $id is not done: $seen. ${hint(error)}", isError = true)
        }
        return McpTools.text("Item $id is done.\n\n" + TodoRender.show(after))
    }

    private suspend fun link(arguments: JsonObject): JsonObject {
        val id = required(arguments, "id")
        val ref = required(arguments, "ref")
        val kind = arguments.string("kind") ?: when {
            Regex("https://github\\.com/[^/]+/[^/]+/pull/\\d+").matches(ref) -> "pr"
            Regex("https://github\\.com/[^/]+/[^/]+/releases/tag/.+").matches(ref) -> "release"
            else -> throw CliUserException("Name the kind of link: pr, release, artifact or wait.")
        }
        val number = if (kind == "pr") Regex("/pull/(\\d+)").find(ref)?.groupValues?.get(1)?.toIntOrNull() else null
        val tag = if (kind == "release") Regex("/releases/tag/(.+)").find(ref)?.groupValues?.get(1) else null
        val after = client.linkTodoItem(id, LinkTodoItemRequest(sessionId, kind, ref, number = number, tag = tag, replaceClosed = arguments.boolean("replaceClosed") ?: false)).todo!!
        return McpTools.text("Item $id is linked to $ref.\n\n" + TodoRender.show(after))
    }

    private suspend fun add(arguments: JsonObject): JsonObject {
        val item = decode<TodoItemInput>(arguments["item"], "item")
        val after = client.addTodoItem(AddTodoItemRequest(sessionId, item, required(arguments, "reason"))).todo!!
        return McpTools.text("Item ${item.id} added.\n\n" + TodoRender.show(after))
    }

    private suspend fun note(arguments: JsonObject): JsonObject {
        val id = required(arguments, "id")
        client.noteTodoItem(id, NoteTodoItemRequest(sessionId, required(arguments, "text")))
        return McpTools.text("Noted on $id.")
    }

    private suspend fun drop(arguments: JsonObject): JsonObject {
        val id = required(arguments, "id")
        client.requestTodoDrop(id, DropTodoItemRequest(sessionId, required(arguments, "reason")))
        return McpTools.text("The session's owner is asked whether to drop $id (a wait is open). The item stays until they say yes. End your turn: the answer wakes you.")
    }

    /** The server's refusals, each with what to do about it. */
    private fun hint(error: TeamCodeException): String? = when (error.code) {
        "not_done" -> "Fix what is missing (merge the PR, wait for CI, publish the release), then call todo_complete again."
        "todo_exists" -> "Use todo_add to add an item to the existing todo."
        "todo_not_found" -> "Create the todo first with todo_create (todo_help shows the format)."
        "already_linked" -> "A link does not change. If the PR was closed without merge, pass replaceClosed: true."
        "credential_scope" -> "This session's credential cannot keep a todo; carry on without it."
        "dependency_open" -> "Finish the item it depends on first (todo_show lists them)."
        else -> null
    }

    companion object {
        private val TOOL_NAMES = setOf("todo_create", "todo_show", "todo_start", "todo_complete", "todo_link", "todo_help", "todo_add", "todo_note", "todo_drop")

        private val ITEM_SCHEMA = buildJsonObject {
            put("type", "object"); put("description", "A complete item: id (slug), name (one line), description (80+ characters: what, where, why, out of scope), dependsOn (ids), doneWhen {text, checks[]}.")
        }

        private val TOOLS = buildJsonArray {
            tool("todo_help", "The rules, the schema of an item and three examples (a PR, a release, a research ending on an artifact). Call it if you are unsure how to write an item.", schema(emptyList()) {})
            tool("todo_create", "Create this session's todo, once, before you start a task with several steps. `reference` is one paragraph that points to the full plan (an artifact link, or the request copied as is); " +
                "`items` are every step in order, each with a complete description and a typed doneWhen. Refused: a duplicate id, an unknown or circular dependency, an item without a typed check, a description under 80 characters, a PR number or version written in advance. " +
                "Example item: {\"id\":\"fix-login\",\"name\":\"Fix the login redirect\",\"description\":\"...80+ characters...\",\"dependsOn\":[],\"doneWhen\":{\"text\":\"The PR is merged\",\"checks\":[{\"type\":\"pr_merged\",\"repo\":\"acme/web\"}]}}.", schema(listOf("reference", "items")) {
                text("reference", "One paragraph in Markdown, at most 4000 characters: a link to the plan artifact, or the person's request as written.")
                putJsonObject("refs") { put("type", "array"); put("description", "Optional structured references: {kind: artifact|session|url, id|url, title}.") }
                putJsonObject("items") { put("type", "array"); put("items", ITEM_SCHEMA); put("description", "Every step of the plan, in order, at most 50.") }
            })
            tool("todo_show", "The todo as it stands: the reference, the items with their last proof, and the next item. Call it after a pause or when you lose track.", schema(emptyList()) {})
            tool("todo_start", "Put an item in progress before you work on it. For an item whose condition is a pull request it returns the marker to put, as is, in the PR body: the PR is found and linked by it, whatever its number. Refused while a dependency is not done.", schema(listOf("id")) {
                text("id", "The item's id.")
                putJsonObject("background") { put("type", "boolean"); put("description", "True when a background agent does the item: other items may stay in progress.") }
                text("agent", "With background: the name of the agent.")
            })
            tool("todo_complete", "Ask for an item to be done once you think its checks pass. This process runs the item's stored checks (gh, the shell) and the server decides: you cannot choose or skip a check, or mark it done yourself. " +
                "A refusal says what is missing (for example `PR #1310 is OPEN`): do that, then call again. For an `attested` check, give proof.text and proof.urls.", schema(listOf("id")) {
                text("id", "The item's id.")
                putJsonObject("proof") { put("type", "object"); put("description", "Only for attested checks: {text, urls[]} of what you did (https URLs).") }
            })
            tool("todo_link", "Bind an artifact or a wait (their id), or a PR or release that already exists (its https address), to an item. A link is frozen: another of the same kind is refused, except a PR that was closed without merge (replaceClosed).", schema(listOf("id", "ref")) {
                text("id", "The item's id.")
                text("ref", "The https address of a pull request or release, or the id of an artifact or a wait.")
                text("kind", "pr, release, artifact or wait; deduced from a GitHub address.")
                putJsonObject("replaceClosed") { put("type", "boolean"); put("description", "Replace a PR that was closed without merge.") }
            })
            tool("todo_add", "Add an item you found the plan needs, with a reason. It goes after the item in progress. Write it as complete as in todo_create.", schema(listOf("item", "reason")) {
                putJsonObject("item") { put("type", "object"); put("description", "A complete item, as in todo_create.") }
                text("reason", "Why the plan needs this item.")
            })
            tool("todo_note", "Add a dated note to an item: where you are, what you tried. Notes are never rewritten and count as progress.", schema(listOf("id", "text")) {
                text("id", "The item's id."); text("text", "The note.")
            })
            tool("todo_drop", "Ask the session's owner to drop an item that no longer makes sense. Nothing is deleted: a wait asks them, and the item stays until they say yes. End your turn after calling it.", schema(listOf("id", "reason")) {
                text("id", "The item's id."); text("reason", "Why it no longer makes sense.")
            })
        }

        private fun JsonArrayBuilder.tool(name: String, description: String, inputSchema: JsonObject) = addJsonObject {
            put("name", name); put("description", description); put("inputSchema", inputSchema)
        }
    }
}
