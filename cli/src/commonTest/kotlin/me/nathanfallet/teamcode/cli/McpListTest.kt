package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.api.models.McpServerView
import me.nathanfallet.teamcode.cli.mcp.mcpServerLine
import kotlin.test.Test
import kotlin.test.assertEquals

class McpListTest {
    @Test fun eachServerShowsTheNameAgentsSeeItsTransportAndAHostedCommandsState() {
        assertEquals("teamcode-docs\tremote (HTTPS)\t-", mcpServerLine(McpServerView("docs", "/mcp/docs", "http")))
        assertEquals("teamcode-every_thing\thosted command\tready", mcpServerLine(McpServerView("every.thing", "/mcp/every.thing", "stdio", "ready")))
        assertEquals("teamcode-x\thosted command\tidle", mcpServerLine(McpServerView("x", "/mcp/x", "stdio", "idle", "crashed")))
        assertEquals("teamcode-x\thosted command\tunavailable (start_failed)", mcpServerLine(McpServerView("x", "/mcp/x", "stdio", "failed", "start_failed")))
        assertEquals("teamcode-x\thosted command\tunavailable (backoff)", mcpServerLine(McpServerView("x", "/mcp/x", "stdio", "backoff")))
        assertEquals("teamcode-x\thosted command\tunknown", mcpServerLine(McpServerView("x", "/mcp/x", "stdio")), "A server that could not read the state")
        assertEquals("teamcode-old\t-\t-", mcpServerLine(McpServerView("old", "/mcp/old")), "A server older than mcp.gateway.stdio.v1")
    }
}
