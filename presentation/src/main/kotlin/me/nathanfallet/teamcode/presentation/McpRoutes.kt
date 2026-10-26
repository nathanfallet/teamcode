package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.api.formatUnits
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.VariantKind
import me.nathanfallet.teamcode.domain.variants.VariantService

/** MCP protocol revisions the gateway speaks to agents, newest first. */
internal val MCP_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
/** Each assigned upstream server has its own endpoint, `$MCP_PATH/<server>`; sources are never aggregated. */
internal const val MCP_PATH = "/mcp"
internal const val MCP_SERVERS_PATH = "/api/v1/mcp/servers"

private object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
}

private fun McpToolPolicy.view() = McpToolPolicyView(mode.name, tools.sorted())
private fun McpUsage.view() = McpUsageView(memberId, username, server, calls, lists, errors, denied, responseBytes, totalDurationMillis)
private fun McpCallRecord.view() = McpCallView(id, memberId, server, variantId, operation.name, tool, outcome.name, startedAt, durationMillis, responseBytes)
private fun toolPolicy(view: McpToolPolicyView) = McpToolPolicy(
    McpToolMode.entries.find { it.name == view.mode } ?: throw TeamFailure("invalid_tool_policy", 400, "Use ALL or ALLOWLIST."), view.tools.toSet())

private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
    put("jsonrpc", "2.0"); put("id", id)
    putJsonObject("error") { put("code", code); put("message", message) }
}
private fun rpcResult(id: JsonElement, result: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
private fun toolError(message: String) = buildJsonObject {
    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", message) } }
    put("isError", true)
}
private suspend fun ApplicationCall.respondJson(value: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(value.toString(), ContentType.Application.Json, status)

/**
 * One agent-facing MCP endpoint per assigned upstream server (Streamable HTTP, stateless): every POST to
 * `/mcp/<server>` carries one JSON-RPC message and receives one JSON response, without an `Mcp-Session-Id`. Tools keep
 * their upstream names. Any replica can serve any message, and authorization is evaluated on each of them from the
 * bearer credential: an unassigned or revoked server answers 404 for every method.
 */
internal fun Route.mcpGatewayRoutes(gateway: McpGateway, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    get(MCP_SERVERS_PATH) {
        call.respond(gateway.assigned(call.principal).map {
            McpServerView(it.name, "$MCP_PATH/${it.name}", if (it.hosted) "stdio" else "http", it.state, it.failure)
        })
    }
    route("$MCP_PATH/{server}") {
        install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
        post {
            // Browsers send Origin; a foreign page must not drive a member's gateway session (DNS rebinding).
            call.request.headers[HttpHeaders.Origin]?.let { if (it.trimEnd('/') != origin) throw TeamFailure("forbidden_origin", 403, "This origin may not use the MCP gateway.") }
            call.request.headers["MCP-Protocol-Version"]?.let { if (it !in MCP_PROTOCOL_VERSIONS) return@post call.respondJson(rpcError(JsonNull, JsonRpc.INVALID_REQUEST, "Unsupported MCP protocol version."), HttpStatusCode.BadRequest) }
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json)
                return@post call.respondJson(rpcError(JsonNull, JsonRpc.INVALID_REQUEST, "Send one JSON-RPC message as application/json."), HttpStatusCode.UnsupportedMediaType)
            val bytes = call.receiveChannel().readBounded(gateway.limits.maxRequestBytes)
            val message = try { Serialization.json.parseToJsonElement(bytes.decodeToString()) } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
                ?: return@post call.respondJson(rpcError(JsonNull, JsonRpc.PARSE_ERROR, "Invalid JSON."), HttpStatusCode.BadRequest)
            if (message !is JsonObject) return@post call.respondJson(rpcError(JsonNull, JsonRpc.INVALID_REQUEST, "Send one JSON-RPC message per request; batches are not supported."), HttpStatusCode.BadRequest)
            val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = message["id"]
            val server = call.parameters["server"].orEmpty()
            // Notifications and responses to the server need no answer, but only on a server the member can use.
            if (method == null || id == null) {
                try { gateway.requireServer(call.principal, server) } catch (_: McpServerDenied) { return@post call.respond(HttpStatusCode.NotFound) }
                return@post call.respond(HttpStatusCode.Accepted)
            }
            if (id !is JsonPrimitive || id is JsonNull || (!id.isString && id.booleanOrNull != null)) return@post call.respondJson(rpcError(JsonNull, JsonRpc.INVALID_REQUEST, "Use a string or number request id."), HttpStatusCode.BadRequest)
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            val response = try {
                when (method) {
                    "initialize" -> { gateway.requireServer(call.principal, server); rpcResult(id, buildJsonObject {
                        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
                        put("protocolVersion", requested?.takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSIONS.first())
                        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                        putJsonObject("serverInfo") { put("name", "teamcode"); put("version", me.nathanfallet.teamcode.api.BuildInfo.VERSION) }
                        put("instructions", "TeamCode relays the $server MCP server. Only the tools assigned to you in TeamCode are listed and callable.")
                    }) }
                    "ping" -> { gateway.requireServer(call.principal, server); rpcResult(id, JsonObject(emptyMap())) }
                    "tools/list" -> {
                        val tools = gateway.listTools(call.principal, server).map { tool ->
                            JsonObject(Serialization.json.parseToJsonElement(tool.definition).jsonObject + ("name" to JsonPrimitive(tool.name)))
                        }
                        rpcResult(id, buildJsonObject { put("tools", JsonArray(tools)) })
                    }
                    "tools/call" -> {
                        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val arguments = params["arguments"]
                        if (name == null || (arguments != null && arguments !is JsonObject && arguments !is JsonNull)) rpcError(id, JsonRpc.INVALID_PARAMS, "tools/call needs a tool name and object arguments.")
                        else try {
                            when (val outcome = gateway.callTool(call.principal, server, name, (arguments as? JsonObject)?.toString())) {
                                is McpCallOutcome.Completed -> rpcResult(id, Serialization.json.parseToJsonElement(outcome.result.result).jsonObject)
                                is McpCallOutcome.Failed -> rpcResult(id, toolError(outcome.message))
                            }
                        } catch (_: McpToolDenied) {
                            rpcError(id, JsonRpc.INVALID_PARAMS, "Unknown tool.")
                        }
                    }
                    else -> rpcError(id, JsonRpc.METHOD_NOT_FOUND, "Method not supported by the TeamCode MCP gateway.")
                }
            } catch (_: McpServerDenied) {
                return@post call.respondJson(rpcError(id, JsonRpc.INVALID_REQUEST, "Unknown MCP server."), HttpStatusCode.NotFound)
            }
            call.respondJson(response)
        }
        // No server-initiated stream and no session to delete: the gateway is stateless.
        get { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
        delete { call.response.header(HttpHeaders.Allow, "POST"); call.respond(HttpStatusCode.MethodNotAllowed) }
    }
}

/** The dashboard's "Hosted commands": each hosted process's state on the MCP host, its scrubbed stderr and "Restart". */
private fun hostedSection(processes: HostedMcpProcesses, actor: Principal): String {
    val rows = processes.processes.joinToString("") { process ->
        val status = process.status
        val who = process.username?.let { "member <strong>${escape(it)}</strong>" } ?: "shared by the team"
        val chip = when { HostedProcessStates.failing(process.state) -> "danger"; process.state == HostedProcessStates.READY -> "ok"; else -> "warn" }
        val state = "<span class='chip $chip'>${escape(process.state)}</span>" + (process.failure?.let { " <small>${escape(it)}</small>" } ?: "")
        val figures = if (status == null) "—" else buildString {
            append("${status.starts} start(s), ${status.failures} failure(s)")
            status.coldStartMillis?.let { append("<br><small>cold start ${it} ms</small>") }
            status.rssBytes?.let { append("<br><small>memory ${formatUnits(it)}B</small>") }
            status.lastExitCode?.let { append("<br><small>last exit $it</small>") }
        }
        // The tail only: up to 64 KiB per process would make a page of many processes heavy.
        val stderr = status?.stderr?.takeIf { it.isNotBlank() }?.let { "<details><summary>stderr (last lines)</summary><pre>${escape(it.takeLast(STDERR_SHOWN_CHARS))}</pre></details>" } ?: ""
        val memberField = process.assignment.processMemberId.takeIf { it.isNotEmpty() }?.let { """<input type="hidden" name="memberId" value="${escape(it)}">""" } ?: ""
        """<tr><td><code>${escape(process.assignment.target)}</code> · ${escape(process.variantName)}<br><small>$who</small></td><td><code>${escape(process.launcher)} ${escape(process.pkg)}</code></td><td>$state</td><td>$figures$stderr</td><td><form method="post" action="/dashboard/mcp/hosted/restart">${csrf(actor)}<input type="hidden" name="variantId" value="${escape(process.assignment.variantId)}">$memberField<button>Restart</button></form></td></tr>"""
    }
    val host = if (processes.hostReachable) "<p>MCP host ${escape(processes.hostVersion.orEmpty())} reachable. States refresh about once a minute; figures are live.</p>"
        else "<div class='notice warn'><strong>The MCP host did not answer.</strong> States below are the last ones recorded; live figures and stderr are unavailable.</div>"
    return """<h2 id="hosted">Hosted commands</h2>$host<table><thead><tr><th>Server</th><th>Command</th><th>State</th><th>Process</th><th></th></tr></thead><tbody>$rows</tbody></table>${if (processes.processes.isEmpty()) "<div class='empty'><strong>No hosted command is assigned yet.</strong></div>" else ""}"""
}

/** Administrator tool policies, usage and the call journal. */
internal fun Route.mcpAdminRoutes(access: McpAccessStore, variants: VariantService?, publicOrigin: String, hosted: HostedMcpAdministration? = null) {
    val origin = publicOrigin.trimEnd('/')
    fun ApplicationCall.days() = request.queryParameters["days"]?.let { it.toIntOrNull() ?: 0 } ?: 7
    fun ApplicationCall.before(): Long? {
        val values = request.queryParameters.getAll("before") ?: return null
        return values.singleOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: throw TeamFailure("invalid_cursor", 400, "Use one positive call identifier.")
    }
    get("/api/v1/variants/{id}/tool-policy") { call.respond(access.toolPolicy(call.principal, call.parameters["id"]!!).view()) }
    put("/api/v1/variants/{id}/tool-policy") {
        val input = call.jsonBody<McpToolPolicyView>()
        call.respond(access.setToolPolicy(call.principal, call.parameters["id"]!!, toolPolicy(input)).view())
    }
    get("/api/v1/mcp/usage") { call.respond(access.usage(call.principal, call.days()).map { it.view() }) }
    get("/api/v1/mcp/calls") {
        val page = access.calls(call.principal, call.before())
        call.respond(McpCallPageView(page.calls.map { it.view() }, page.nextBeforeId))
    }

    post("/dashboard/mcp/hosted/restart") {
        val service = hosted ?: throw TeamFailure("mcp_disabled", 409, "No MCP host is configured on this server (Helm: mcp.enabled).")
        val input = call.form(origin)
        service.restart(call.principal, input["variantId"].orEmpty(), input["memberId"]?.takeIf { it.isNotEmpty() })
        call.respondRedirect("/dashboard/mcp#hosted")
    }

    get("/dashboard/mcp") {
        val actor = call.principal
        actor.requireAdmin()
        // The rest of the page stays usable when the hosted processes cannot be listed (more than the page shows).
        val hostedSection = hosted?.let {
            try { hostedSection(it.processes(actor), actor) }
            catch (failure: TeamFailure) { if (failure.status == 403 || failure.status == 401) throw failure else """<h2 id="hosted">Hosted commands</h2><div class='notice warn'>${escape(failure.message.orEmpty())}</div>""" }
        }.orEmpty()
        val usage = access.usage(actor, call.days())
        val page = access.calls(actor, call.before())
        val servers = variants?.list(actor).orEmpty().filter { it.kind == VariantKind.MCP }
        val policies = servers.associate { it.id to access.toolPolicy(actor, it.id) }
        val usageRows = usage.joinToString("") {
            """<tr><td>${escape(it.username)}</td><td><code>${escape(it.server.ifEmpty { "(unknown)" })}</code></td><td>${it.calls}</td><td>${it.lists}</td><td>${it.errors}</td><td>${it.denied}</td><td class="num">${formatUnits(it.responseBytes)}</td><td>${if (it.calls + it.lists > 0) it.totalDurationMillis / (it.calls + it.lists) else 0} ms</td></tr>"""
        }
        val callRows = page.calls.joinToString("") {
            """<tr><td>${it.id}<br><small>${Instant.ofEpochMilli(it.startedAt)}</small></td><td>${escape(it.memberId)}</td><td><code>${escape(it.server)}</code></td><td>${it.operation}${it.tool?.let { tool -> " <code>${escape(tool)}</code>" } ?: ""}</td><td>${it.outcome}</td><td>${it.durationMillis ?: ""}</td></tr>"""
        }
        val policyRows = servers.joinToString("") { variant ->
            val policy = policies.getValue(variant.id)
            """<tr><td><code>${escape(variant.target)}</code> · ${escape(variant.name)}</td><td>${if (policy.mode == McpToolMode.ALL) "All tools" else escape(policy.tools.sorted().joinToString(", "))}</td><td><form method="post" action="/dashboard/mcp/${escape(variant.id)}/tools">${csrf(actor)}<label>Allowed tools (one per line, empty for all) <textarea name="tools" rows="3">${escape(policy.tools.sorted().joinToString("\n"))}</textarea></label><button>Save</button></form></td></tr>"""
        }
        val older = page.nextBeforeId?.let { """<a href="/dashboard/mcp?before=$it">Older calls</a>""" } ?: ""
        call.respondText(call.page("MCP gateway", """<details class='help'><summary>How this works</summary><p>Agents reach each assigned MCP server on its own endpoint, <code>$MCP_PATH/&lt;server&gt;</code>. Discovery and every call are checked against current assignments and tool policies; credentials stay on the server. The journal keeps metadata only: never arguments or results.</p></details>
$hostedSection
<h2>Tool policies</h2><table><thead><tr><th>MCP variant</th><th>Exposed tools</th><th>Change</th></tr></thead><tbody>$policyRows</tbody></table>${if (servers.isEmpty()) "<div class='empty'><strong>No MCP variants yet. Create them on the variants page.</strong></div>" else ""}
<h2>Usage, last ${call.days()} days</h2><table><thead><tr><th>Member</th><th>Server</th><th>Calls</th><th>Lists</th><th>Errors</th><th>Denied</th><th>Bytes</th><th>Average</th></tr></thead><tbody>$usageRows</tbody></table>${if (usage.isEmpty()) "<div class='empty'><strong>No MCP traffic in this period.</strong></div>" else ""}
<h2>Recent operations</h2><p><a href="/dashboard/mcp">Latest</a> $older</p><table><thead><tr><th>Call / UTC</th><th>Member ID</th><th>Server</th><th>Operation</th><th>Outcome</th><th>ms</th></tr></thead><tbody>$callRows</tbody></table>${if (page.calls.isEmpty()) "<div class='empty'><strong>No MCP operation recorded yet.</strong></div>" else ""}"""), ContentType.Text.Html)
    }
    post("/dashboard/mcp/{id}/tools") {
        val input = call.form(origin)
        val tools = input["tools"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        access.setToolPolicy(call.principal, call.parameters["id"]!!, if (tools.isEmpty()) McpToolPolicy.ALL else McpToolPolicy(McpToolMode.ALLOWLIST, tools))
        call.respondRedirect("/dashboard/mcp")
    }
}

/** The stderr shown per hosted process on the dashboard: its last 4 KiB. */
private const val STDERR_SHOWN_CHARS = 4_096
