package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.presentation.screens.ServerWait
import me.nathanfallet.teamcode.companion.presentation.screens.serverWait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerWaitTest {
    private fun wait(
        title: String, source: WaitSource = WaitSource.WAIT, kind: WaitKind = WaitKind.HUMAN_REQUEST,
        actions: List<String> = emptyList(), fields: List<WaitField> = emptyList(),
    ) = Wait(
        "w1", source, kind, true, "s1", "session", null, "m1", title, null, emptyList(), fields,
        actions.map { WaitAction(it, it) }, 1,
    )

    @Test
    fun session_permission_and_question_are_recognised() {
        assertEquals(ServerWait.PERMISSION, serverWait(wait("The agent needs a permission", WaitSource.SESSION, WaitKind.PERMISSION)))
        assertEquals(ServerWait.QUESTION, serverWait(wait("The agent asks a question", WaitSource.SESSION, WaitKind.QUESTION)))
    }

    @Test
    fun todo_waits_are_recognised_by_title_and_actions() {
        assertEquals(ServerWait.DROP_ITEM, serverWait(wait("Drop this item of the todo?", actions = listOf("yes", "no"))))
        assertEquals(ServerWait.BLOCKED, serverWait(wait("Blocked: Fix login", actions = listOf("no", "yes", "stop"))))
        assertEquals(ServerWait.BLOCKED, serverWait(wait("Blocked: the todo", actions = listOf("no", "stop"))))
    }

    @Test
    fun a_wait_written_by_an_agent_keeps_its_own_words() {
        assertNull(serverWait(wait("Drop this item of the todo?", actions = listOf("ok"))))
        assertNull(serverWait(wait("Blocked: Fix login")))
        assertNull(serverWait(wait("Which database?", fields = listOf(WaitField("db", FieldType.TEXT, "Database")), actions = listOf("yes", "no"))))
        assertNull(serverWait(wait("Deploy now?", actions = listOf("yes", "no"))))
    }
}
