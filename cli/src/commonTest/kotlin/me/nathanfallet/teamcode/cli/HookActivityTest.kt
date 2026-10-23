package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.agents.HookActivity
import kotlin.test.*

class HookActivityTest {
    private fun state(json: String) = HookActivity.state(json.encodeToByteArray())

    @Test fun stopAndAskingNotificationsWaitForAPerson() {
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Stop","session_id":"s","last_assistant_message":"secret"}"""))
        for (type in listOf("permission_prompt", "idle_prompt", "elicitation_dialog"))
            assertEquals("waiting_for_human", state("""{"hook_event_name":"Notification","notification_type":"$type","message":"secret"}"""), type)
        // A client that does not say the type still asked for something.
        assertEquals("waiting_for_human", state("""{"hook_event_name":"Notification","message":"secret"}"""))
    }

    @Test fun promptsAndToolResultsMeanTheAgentWorks() {
        assertEquals("working", state("""{"hook_event_name":"UserPromptSubmit","prompt":"secret"}"""))
        assertEquals("working", state("""{"hook_event_name":"PostToolUse","tool_name":"Bash"}"""))
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

    @Test fun onlyACanonicalRunIdentifierIsUsed() {
        assertEquals("11111111-2222-3333-4444-555555555555", HookActivity.runId("11111111-2222-3333-4444-555555555555"))
        assertNull(HookActivity.runId("../x")); assertNull(HookActivity.runId(null)); assertNull(HookActivity.runId(""))
    }
}
