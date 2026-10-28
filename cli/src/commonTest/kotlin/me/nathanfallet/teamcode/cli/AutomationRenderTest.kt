package me.nathanfallet.teamcode.cli

import com.github.ajalt.clikt.core.UsageError
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.automations.*
import kotlin.test.*

/** What `automations list|show|runs|create|rotate-secret` print: rows, the webhook address in each form, and the preview. */
class AutomationRenderTest {
    private val hook = AutomationWebhookView("/api/v1/automations/a1/trigger", "/api/v1/automations/a1/v/4/trigger", 1_000)
    private fun automation(webhook: AutomationWebhookView? = hook, trigger: AutomationTriggerView = AutomationTriggerView("webhook")) = AutomationView(
        "a1", "m1", "Nathan", "Fix tickets", true, null, null, 4, trigger, AutomationActionView("cloud", "p1", "sonnet"), "Fix {{ticket}}\nThen stop.",
        listOf(AutomationVariableView("ticket", "\$.ticket.number", "int", sensitive = true)), null, "skip", 24, webhook = webhook, createdAt = 0, updatedAt = 0)

    @Test fun `a list row shows state trigger version next slot and latest run`() {
        val row = AutomationSummaryView("a1", "m1", "Nathan", "Evening", true, null, 2, AutomationTriggerView("cron", "0 21 * * *", "Europe/Paris"), "cloud",
            nextRunAt = 0, lastRun = AutomationRunSummaryView("r1", "cron", "succeeded", 0), updatedAt = 0)
        assertEquals("a1\tEvening\tenabled\tcron \"0 21 * * *\" Europe/Paris\tv2\tnext 1970-01-01T00:00:00Z\tlast succeeded (cron)", row.line())
        val paused = row.copy(enabled = false, disabledReason = "owner_unavailable", nextRunAt = null, lastRun = null, trigger = AutomationTriggerView("webhook"), consecutiveFailures = 3)
        assertEquals("a1\tEvening\tpaused (owner_unavailable)\twebhook\tv2\tnext -\tlast -\t3 failed in a row", paused.line())
    }

    @Test fun `a run row shows status reason trigger version time and session`() {
        val run = AutomationRunView("r1", "a1", 3, "webhook", "failed", "session_failed", "s1", queuedAt = 0)
        assertEquals("r1\tfailed (session_failed)\twebhook\tv3\tqueued 1970-01-01T00:00:00Z\tsession s1", run.line())
        assertEquals("r2\tskipped_overlap\ttest\tv3\tqueued 1970-01-01T00:00:00Z\tsession -", run.copy(id = "r2", source = "test", status = "skipped_overlap", reason = null, sessionId = null).line())
        assertContains(run.copy(cancelRequested = true).line(), "cancel requested")
    }

    @Test fun `the signed address follows the latest version by default`() {
        assertEquals("https://team.example/api/v1/automations/a1/trigger", webhookUrl("https://team.example/", hook, 4, null, WebhookUrlMode.SIGNED, null))
    }

    @Test fun `a pinned address names the version and the secret form appends the secret`() {
        assertEquals("https://team.example/api/v1/automations/a1/v/4/trigger", webhookUrl("https://team.example", hook, 4, 4, WebhookUrlMode.SIGNED, null))
        assertEquals("https://team.example/api/v1/automations/a1/v/2/trigger/whsec_abc", webhookUrl("https://team.example", hook, 4, 2, WebhookUrlMode.SECRET, "whsec_abc"))
        assertEquals("https://team.example/api/v1/automations/a1/trigger/<secret>", webhookUrl("https://team.example", hook, 4, null, WebhookUrlMode.SECRET, null))
    }

    @Test fun `a pinned address comes from the server's versioned path`() {
        val odd = AutomationWebhookView("/hooks/a1/", "/hooks/a1/v/4/fire", 0)
        assertEquals("https://team.example/hooks/a1/v/9/fire", webhookUrl("https://team.example", odd, 4, 9, WebhookUrlMode.SIGNED, null))
    }

    @Test fun `a former secret is mentioned only while it is still accepted`() {
        val rotated = automation(webhook = hook.copy(previousSecretValidUntil = 5_000))
        assertContains(webhookInstructions("https://team.example", rotated, null, WebhookUrlMode.SIGNED, null, now = 4_000).joinToString("\n"), "former secret is accepted until")
        assertFalse("former secret" in webhookInstructions("https://team.example", rotated, null, WebhookUrlMode.SIGNED, null, now = 6_000).joinToString("\n"))
    }

    @Test fun `an enabled automation is shown as a list row`() {
        assertEquals("a1\tFix tickets\tenabled\twebhook\tv4\tnext -\tlast -", automation().toSummary().line())
    }

    @Test fun `the url version is latest current or vN`() {
        assertNull(parseUrlVersion("latest", 4)); assertNull(parseUrlVersion("LATEST", 4))
        assertEquals(4, parseUrlVersion("current", 4)); assertEquals(3, parseUrlVersion("v3", 4)); assertEquals(3, parseUrlVersion("3", 4))
        listOf("v0", "x", "v", "-1").forEach { assertFailsWith<UsageError>(it) { parseUrlVersion(it, 4) } }
    }

    @Test fun `instructions explain the signature or warn about the secret url`() {
        val signed = webhookInstructions("https://team.example", automation(), null, WebhookUrlMode.SIGNED, "whsec_abc").joinToString("\n")
        assertContains(signed, "TeamCode-Signature"); assertContains(signed, "Follows the current version"); assertFalse("whsec_abc" in signed, "A signed address never holds the secret")
        val secret = webhookInstructions("https://team.example", automation(), 3, WebhookUrlMode.SECRET, "whsec_abc").joinToString("\n")
        assertContains(secret, "trigger/whsec_abc"); assertContains(secret, "Anyone who reads this URL"); assertContains(secret, "automation_version_changed")
        val unknown = webhookInstructions("https://team.example", automation(), null, WebhookUrlMode.SECRET, null).joinToString("\n")
        assertContains(unknown, "<secret>"); assertContains(unknown, "shown at creation and rotation only")
        assertTrue(webhookInstructions("https://team.example", automation(webhook = null, trigger = AutomationTriggerView("cron", "* * * * *", "UTC")), null, WebhookUrlMode.SIGNED, null).isEmpty())
    }

    @Test fun `details show the definition variables webhook and prompt`() {
        val text = automation().details("https://team.example")
        listOf("Fix tickets", "Version:      4", "{{ticket}} <- \$.ticket.number (int, max 256, required, sensitive)", "POST https://team.example/api/v1/automations/a1/trigger", "  Fix {{ticket}}", "  Then stop.")
            .forEach { assertContains(text, it) }
    }

    @Test fun `a preview shows the rendered prompt with sensitive values masked`() {
        val preview = AutomationPreviewView("Fix 12", listOf(AutomationVariableValueView("ticket", "int", "12"), AutomationVariableValueView("token", "string", null, sensitive = true),
            AutomationVariableValueView("note", "string", present = false)), profileVersion = 5, wouldSkip = "overlap", nextRuns = listOf(0))
        val text = preview.text()
        listOf("{{ticket}} (int) = 12", "{{token}} (string) = (masked)", "(missing, renders empty)", "version 5", "would be skipped now: overlap", "Rendered prompt:\n  Fix 12")
            .forEach { assertContains(text, it) }
    }

    @Test fun `a preview warns that the prompt holds sensitive values`() {
        val text = AutomationPreviewView("Use secret-value", listOf(AutomationVariableValueView("token", "string", null, sensitive = true))).text()
        assertContains(text, "holds the values of the sensitive variables")
        assertFalse("holds the values" in AutomationPreviewView("Fix 12", listOf(AutomationVariableValueView("ticket", "int", "12"))).text())
    }

    @Test fun `a preview with problems lists paths and rules and no prompt`() {
        val text = AutomationPreviewView(problems = listOf(AutomationProblemView("\$.ticket.number", "type:int"))).text()
        assertContains(text, "\$.ticket.number\ttype:int"); assertFalse("Rendered prompt" in text)
    }
}
