package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class CloudLifecycleRulesTest {
    @Test fun transitionsFollowTheDocumentedTable() {
        assertTrue(CloudStateMachine.permits(CREATING, PREPARING))
        assertTrue(CloudStateMachine.permits(RESUMING, PAUSED), "An asynchronous resume failure returns to paused")
        assertTrue(CloudStateMachine.permits(READY, PAUSED), "A lost execution pauses as crash-consistent")
        assertFalse(CloudStateMachine.permits(PAUSED, READY), "A paused session resumes through a new execution")
        assertFalse(CloudStateMachine.permits(CREATING, READY), "The agent is never reported ready before preparation")
        assertFalse(CloudStateMachine.permits(TERMINATED, PAUSED))
        for (state in CloudSessionState.entries.filter { it != TERMINATED }) assertTrue(CloudStateMachine.permits(state, TERMINATED))
        assertEquals("invalid_transition", assertFailsWith<TeamFailure> { CloudStateMachine.require(FAILED, READY) }.code)
    }

    @Test fun requestValidationAndImageOverrides() {
        val draft = CloudSessionDraft("Task", "profile", null, null, null, mapOf("orchestrator.task" to "1234"), null)
        CloudSessionService.validate(draft)
        for (bad in listOf(draft.copy(name = ""), draft.copy(labels = mapOf("Upper" to "x")), draft.copy(labels = (1..17).associate { "k$it" to "v" }),
            draft.copy(image = "two words"), draft.copy(agentModel = "opus; rm -rf"))) {
            assertFailsWith<TeamFailure> { CloudSessionService.validate(bad) }
        }
        assertTrue(CloudSessionService.matchesImagePattern("ghcr.io/acme/*", "ghcr.io/acme/toolchain:2026-10"))
        assertFalse(CloudSessionService.matchesImagePattern("ghcr.io/acme/*", "ghcr.io/evil/toolchain:1"))
        assertFalse(CloudSessionService.matchesImagePattern("ghcr.io/acme/tool.chain", "ghcr.io/acme/toolXchain"), "Dots are literal")
        assertEquals("tc-0a6e-e2", CloudSessionService.podName("0a6e", 2))
    }

    @Test fun clusterApiHosts() {
        for (url in listOf("https://10.0.0.1:6443", "https://127.0.0.1:41234", "https://k8s.example.test", "https://fe80.example.test", "https://[fd00::1]:6443"))
            assertTrue(CloudAdministrationService.allowedApiHost(url), url)
        for (url in listOf("https://169.254.169.254/", "https://metadata.google.internal", "https://[fe80::1]:6443", "https://0.0.0.0:6443", "https://[::ffff:169.254.169.254]", "https://user@169.254.1.1"))
            assertFalse(CloudAdministrationService.allowedApiHost(url), url)
    }

    @Test fun lifecycleTransitionsUsedByTheReconciler() {
        // A running session without its execution row, and a lost execution, pause crash-consistent.
        for (state in listOf(READY, ACTIVE, PAUSING)) assertTrue(CloudStateMachine.permits(state, PAUSED))
        // An early checkpoint moves a ready or active session to pausing; a failed session can still be deleted.
        assertTrue(CloudStateMachine.permits(ACTIVE, PAUSING))
        assertTrue(CloudStateMachine.permits(FAILED, TERMINATED))
    }

    @Test fun settingsBounds() {
        CloudAdministrationService.validate(CloudSettings())
        CloudAdministrationService.validate(CloudSettings(pausedRetentionDays = null))
        for (bad in listOf(CloudSettings(pausedRetentionDays = 0), CloudSettings(failedWorkspaceRetentionDays = -1), CloudSettings(maxRunningSessionsPerMember = 30, maxRetainedSessionsPerMember = 20)))
            assertFailsWith<TeamFailure> { CloudAdministrationService.validate(bad) }
    }
}
