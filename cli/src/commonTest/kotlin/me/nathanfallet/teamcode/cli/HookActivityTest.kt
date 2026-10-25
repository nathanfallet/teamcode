package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.agents.HookActivity
import kotlin.test.*

class HookActivityTest {
    private fun state(json: String) = HookActivity.state(json.encodeToByteArray())

    @Test fun stopAndAskingNotificationsWaitForAPerson() {
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Stop","session_id":"s","last_assistant_message":"secret"}"""))
        for (type in listOf("permission_prompt", "idle_prompt", "elicitation_dialog"))
            assertEquals("waiting_for_human", state("""{"hook_event_name":"Notification","notification_type":"$type","message":"secret"}"""), type)
        // Only the types known to ask for a person: an untyped or unknown notice is information.
        assertNull(state("""{"hook_event_name":"Notification","message":"secret"}"""))
        assertNull(state("""{"hook_event_name":"Notification","notification_type":"progress"}"""))
    }

    @Test fun aStopWithBackgroundWorkStillWorks() {
        // Claude Code's Stop input lists the background tasks in flight and the session's scheduled wake-ups: the turn
        // ended, but the agent is not waiting for a person while they run (https://code.claude.com/docs/en/hooks#stop-input).
        val shell = """{"id":"task-001","type":"shell","status":"running","description":"tail logs","command":"tail -f /var/log/syslog"}"""
        assertEquals("working", state("""{"hook_event_name":"Stop","last_assistant_message":"secret","background_tasks":[$shell],"session_crons":[]}"""))
        assertEquals("working", state("""{"hook_event_name":"Stop","background_tasks":[],"session_crons":[{"id":"cron-001","schedule":"*/5 * * * *","recurring":true,"prompt":"check"}]}"""))
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Stop","background_tasks":[],"session_crons":[]}"""), "Nothing in flight or scheduled")
        val big = "x".repeat(100_000)
        assertEquals("working", state("""{"hook_event_name":"Stop","last_assistant_message":"$big","background_tasks":[$shell],"session_crons":[]}"""), "Read without parsing a large payload")
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Stop","last_assistant_message":"\"background_tasks\":[{$big","background_tasks":[]}"""),
            "A message that quotes the key does not count")
    }

    @Test fun promptsAndToolResultsMeanTheAgentWorks() {
        assertEquals("working", state("""{"hook_event_name":"UserPromptSubmit","prompt":"secret"}"""))
        assertEquals("working", state("""{"hook_event_name":"PostToolUse","tool_name":"Bash"}"""))
        assertEquals("working", state("""{"hook_event_name":"PostToolUseFailure","tool_name":"Bash"}"""))
    }

    @Test fun otherEventsAndBadPayloadsSayNothing() {
        assertNull(state("""{"hook_event_name":"Notification","notification_type":"auth_success"}"""))
        assertNull(state("""{"hook_event_name":"SubagentStop"}"""))
        assertNull(state("""{"hook_event_name":"PreToolUse"}"""))
        assertNull(state("""{"hook_event_name":7}"""))
        assertNull(state("""[]"""))
        assertNull(state("not json"))
        assertNull(HookActivity.state(ByteArray(65_537) { '{'.code.toByte() }))
    }

    @Test fun anOversizedEventStillReportsItsState() {
        val big = "x".repeat(100_000)
        assertEquals("working", state("""{"session_id":"s","hook_event_name":"PostToolUse","tool_response":"$big"}"""))
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Stop","last_assistant_message":"$big"}"""))
        assertNull(state("""{"hook_event_name":"Notification","notification_type":"auth_success","message":"$big"}"""))
    }

    @Test fun onlyACanonicalRunIdentifierIsUsed() {
        assertEquals("11111111-2222-3333-4444-555555555555", HookActivity.runId("11111111-2222-3333-4444-555555555555"))
        assertNull(HookActivity.runId("../x")); assertNull(HookActivity.runId(null)); assertNull(HookActivity.runId(""))
    }
}
