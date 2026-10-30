package me.nathanfallet.teamcode.cli.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.VERSION
import me.nathanfallet.teamcode.cli.mcp.McpTools.schema
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * The `teamcode-workflow` MCP server: the agent of a workflow step hands in what the step asked for (docs/workflows.md,
 * "Delivery"), once, for the session it runs in ([sessionId]). The one tool, `deliver`, goes through the launcher's
 * loopback relay ([client], with the local token), which relays that call and nothing else; the server checks the output
 * against the step's deliverable and refuses what does not match.
 */
class WorkflowMcpServer(
    private val client: TeamCodeClient,
    private val sessionId: String,
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
                if (name != TOOL) McpTools.error(id, -32602, "Unknown tool: ${name ?: "no name"}")
                else McpTools.result(id, call(params))
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
            putJsonObject("serverInfo") { put("name", "teamcode-workflow"); put("version", VERSION) }
            put("instructions", INSTRUCTIONS)
        }
    }

    private suspend fun call(params: JsonObject): JsonObject {
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val output = arguments["output"]?.takeIf { it !is JsonNull } ?: throw CliUserException("output is required: hand in what the step asked for.")
            val run = client.deliverWorkflowSession(sessionId, output)
            McpTools.text(buildJsonObject {
                put("delivered", true); put("run", run.id); put("status", run.status)
                put("instruction", "Your delivery is recorded. End your turn now without doing anything else on this step; do not deliver again.")
            }.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            McpTools.text(McpTools.describe(error) { failure -> hint(failure) }, isError = true)
        }
    }

    /** The server's refusals, each with what to do about it. */
    private fun hint(error: TeamCodeException): String? = when (error.code) {
        "invalid_deliverable" -> "Deliver exactly what the step asked for, in the form its instruction names, as the message says; then call deliver again."
        "nothing_to_deliver" -> "This session is not running a workflow step that waits for a delivery, or the step already has its delivery: end your turn."
        "credential_scope" -> "This session's credential cannot deliver a workflow step; say what you produced in your reply instead."
        "not_found" -> "No workflow run is bound to this session for you."
        else -> null
    }

    companion object {
        private const val TOOL = "deliver"
        private const val INSTRUCTIONS = "You are working on a step of a TeamCode workflow. When the task of the step is done, call `deliver` once with exactly what the " +
            "step asked for (its instruction names the form), then end your turn. Do not call it for anything else."

        private val TOOLS = buildJsonArray {
            addJsonObject {
                put("name", TOOL)
                put("description", "Hand in the result of the workflow step you are working on. Call it once, when the task of the current step is done, with exactly " +
                    "what the step's instruction asked for: a string for text, an https address or an artifact id, a JSON value for structured data. The server checks " +
                    "it against the step and refuses what does not match (then fix it and call again). After it succeeds, end your turn: the workflow moves on.")
                put("inputSchema", schema(listOf("output")) {
                    putJsonObject("output") {
                        put("description", "What the step asked for: a string (text, https address or artifact id) or, when the step asks for JSON, a JSON value (not a string holding JSON).")
                    }
                })
            }
        }
    }
}
