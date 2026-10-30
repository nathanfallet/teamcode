package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

/** What a person reads when the server refuses an automation request. */
class AutomationErrorsTest {
    private fun message(status: Int, code: String) = describeFailure(TeamCodeException(status, "TeamCode returned HTTP $status.", code), "https://team.example").message

    @Test fun `a remote action says it is not available yet and what to use`() {
        val text = message(422, "not_available_yet")
        assertContains(text, "not available yet"); assertContains(text, "--action cloud"); assertContains(text, "HTTP 422, not_available_yet")
    }

    @Test fun `a stale version asks to run the command again`() = assertContains(message(409, "stale_version"), "Run the command again")

    @Test fun `the limit points at delete`() = assertContains(message(409, "automation_limit"), "automations delete")

    @Test fun `an invalid definition lists what to check`() {
        val text = message(400, "invalid_automation")
        assertContains(text, "five fields"); assertContains(text, "timezone"); assertContains(text, "--var")
    }

    @Test fun `payload refusals say which option to use`() {
        assertContains(message(422, "payload_required"), "--payload")
        assertContains(message(422, "payload_not_accepted"), "without --payload")
        assertContains(message(422, "invalid_payload"), "--dry-run")
    }
}
