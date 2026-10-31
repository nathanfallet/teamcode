package me.nathanfallet.teamcode.companion.demo

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunVisit
import me.nathanfallet.teamcode.companion.domain.models.StepAction
import me.nathanfallet.teamcode.companion.domain.models.StepDeliverable
import me.nathanfallet.teamcode.companion.domain.models.StepKind
import me.nathanfallet.teamcode.companion.domain.models.StepTransition
import me.nathanfallet.teamcode.companion.domain.models.VisitStatus
import me.nathanfallet.teamcode.companion.domain.models.DefinitionAction
import me.nathanfallet.teamcode.companion.domain.models.DefinitionDeliverable
import me.nathanfallet.teamcode.companion.domain.models.DefinitionField
import me.nathanfallet.teamcode.companion.domain.models.DefinitionNode
import me.nathanfallet.teamcode.companion.domain.models.EditableWorkflow
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowDefinition
import me.nathanfallet.teamcode.companion.domain.models.toWorkflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowInput
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.models.WorkflowStep
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitOption
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.domain.repositories.Features

/**
 * What the built-in demo shows: one account on no server, nine sessions, one detailed transcript, eight artifacts. Times
 * are relative to [now] (when the demo started), so the lists read "2 min ago" whenever the app is opened. The store
 * screenshots and the app's whole-screen tests use the same data.
 */
object DemoContent {
    /** The demo account's id. Real accounts have drawn UUIDs (or `cli`), so it never collides with one. */
    const val ACCOUNT_ID = "demo"

    /** An origin under `.invalid`, a name that never resolves: nothing the demo shows can reach a network. */
    const val ORIGIN = "https://demo.teamcode.invalid"

    /** Where the demo sends the curious: star the project, deploy a server of their own. */
    const val REPOSITORY_URL = "https://github.com/nathanfallet/teamcode"

    const val MEMBER_ID = "m1"
    const val USERNAME = "ada"
    const val SERVER_VERSION = "demo"

    /** What the demo server announces: waits, workflows and one automation, but no transfers, devices, files, GitHub link or MCP gateway, so their screens stay quiet. */
    val features = setOf(
        Features.SESSIONS, Features.TRANSCRIPT, Features.MESSAGES, Features.EVENTS, Features.INITIAL_MESSAGE, Features.ARTIFACTS, Features.RENAME, Features.WAITS,
        Features.WORKFLOWS, Features.WORKFLOWS_LIVE, Features.AUTOMATIONS,
    )

    /**
     * The agent's only answer in the demo, to every message and every new session: no AI is connected, and nothing the
     * member types leaves the device.
     */
    const val SCRIPTED_REPLY =
        "This is the TeamCode demo: no AI agent is connected, so every message gets this same answer.\n\n" +
            "On a real TeamCode server, your message goes to the session's agent (Claude Code or Codex), which works in the " +
            "session's repositories and answers here, live.\n\n" +
            "To try it for real, star the project and deploy your own server: $REPOSITORY_URL"

    fun account(deviceName: String) = Account(
        id = ACCOUNT_ID, origin = ORIGIN, username = USERNAME, memberId = MEMBER_ID, role = "MEMBER", deviceName = deviceName,
        expiresAt = Long.MAX_VALUE, serverVersion = SERVER_VERSION,
    )

    val profiles = listOf(Profile("demo-claude", "Claude Code", "claude"), Profile("demo-codex", "Codex", "codex"))

    private const val MINUTE = 60_000L

    private val names = listOf(
        "Fix the flaky login test" to SessionState.ACTIVE, "Migrate billing to the new API" to SessionState.READY,
        "Review the release checklist" to SessionState.READY, "Speed up the CI cache" to SessionState.ACTIVE,
        "Write the onboarding guide" to SessionState.READY, "Upgrade Kotlin to 2.4" to SessionState.PREPARING,
        "Audit the payment webhooks" to SessionState.READY, "Prototype the search page" to SessionState.READY,
    )

    /** Eight open sessions (`s0`..`s7`, the fourth on a laptop) and a paused one (`p1`), newest first. */
    fun sessions(now: Long): List<CloudSession> {
        val open = names.mapIndexed { index, (name, state) ->
            val created = now - (index * 17 + 2) * MINUTE
            session("s$index", name, state, created).copy(
                repositories = listOf(listOf("acme/web", "acme/api", "acme/infra")[index % 3]),
                effort = if (index % 2 == 0) "high" else null,
                kind = if (index == 3) SessionKind.REMOTE else SessionKind.CLOUD,
                host = if (index == 3) SessionHost("Ada's MacBook", "macos", "15.1", "app", true, false, SessionHost.ASK) else null,
            )
        }
        return open + session("p1", "Refactor the settings screen", SessionState.PAUSED, now - 300 * MINUTE)
    }

    /** A session as the demo server describes it. */
    fun session(id: String, name: String, state: SessionState, createdAt: Long, profile: Profile = profiles.first(), model: String? = "opus") = CloudSession(
        id = id, name = name, ownerId = MEMBER_ID, state = state, phase = null, agentKind = profile.agentKind, model = model, effort = null,
        profileId = profile.id, repositories = emptyList(), lastActivityAt = createdAt, createdAt = createdAt, updatedAt = createdAt, problemCode = null,
    )

    /** The transcript of [sessionId]: a full one with tools for the first session, a question and its answer for the others. */
    fun transcript(sessionId: String, now: Long): List<TranscriptEvent> {
        val at = now - 600_000
        if (sessionId == "s0") return listOf(
            TranscriptEvent.UserMessage(1, at, "The login test fails about once in ten runs on CI. Can you find out why and fix it?", "in-1", MEMBER_ID),
            TranscriptEvent.AgentMessage(2, at + 5_000, "I'll start by reading the test and running it in a loop to reproduce the failure.", null),
            TranscriptEvent.ToolCall(3, at + 6_000, "t1", "Bash", "{\n  \"command\": \"./gradlew :web:test --tests LoginTest --rerun\"\n}", "./gradlew :web:test --tests LoginTest --rerun", null),
            TranscriptEvent.ToolResult(4, at + 40_000, "t1", "BUILD SUCCESSFUL in 31s", false, 0, null),
            TranscriptEvent.ToolCall(5, at + 41_000, "t2", "Read", "{\n  \"file_path\": \"web/src/test/LoginTest.kt\"\n}", "web/src/test/LoginTest.kt", null),
            TranscriptEvent.ToolResult(6, at + 42_000, "t2", "class LoginTest { ... }", false, null, null),
            TranscriptEvent.AgentMessage(7, at + 60_000,
                "Found it: the test reads the session cookie before the redirect completes. **The fix** waits for the redirect, then asserts:\n\n" +
                    "```kotlin\nwaitUntil { page.url.endsWith(\"/home\") }\nassertNotNull(cookies[\"session\"])\n```\n\nI ran it 50 times locally without a failure.", null),
            TranscriptEvent.Turn(8, at + 61_000, TurnPhase.COMPLETED, 61_000, 6),
            TranscriptEvent.UserMessage(9, at + 120_000, "Great, open a pull request with that change.", "in-2", MEMBER_ID),
        )
        val (question, answer) = exchanges[sessionId] ?: return emptyList()
        return listOf(
            TranscriptEvent.UserMessage(1, at, question, "in-1", MEMBER_ID),
            TranscriptEvent.AgentMessage(2, at + 30_000, answer, null),
            TranscriptEvent.Turn(3, at + 31_000, TurnPhase.COMPLETED, 31_000, 4),
        )
    }

    private val exchanges = mapOf(
        "s1" to ("Move the invoices endpoint to the billing API v2 and keep the old one answering until the clients are updated." to
            "Done: `/v2/invoices` now serves the new format, and `/v1/invoices` maps it back to the old one. Both are covered by the contract tests."),
        "s2" to ("Go through the release checklist for 2.4 and tell me what is still missing." to
            "Two items are open: the changelog lacks the billing migration, and the Android build is not signed yet. Everything else is checked."),
        "s3" to ("The CI spends four minutes restoring caches. Can we make it faster?" to
            "The Gradle cache key changes on every commit because it hashes the whole repository. Keyed on the build files only, the restore takes 40 seconds."),
        "s4" to ("Write a first version of the onboarding guide for new developers." to
            "I drafted `docs/onboarding.md`: setting up the tools, running the app locally, the review process, and who to ask about what."),
        "s5" to ("Upgrade the project to Kotlin 2.4." to "Preparing the workspace: I'll update the Kotlin plugin first, then fix what the compiler reports."),
        "s6" to ("Check that every payment webhook verifies its signature." to
            "Three of the four handlers verify the signature. The refund handler does not: I added the check and a test that sends an unsigned event."),
        "s7" to ("Prototype a search page with filters by status and date." to
            "The prototype is on the `search-prototype` branch: a search field, two filters and the results list, on mock data for now."),
        "p1" to ("Split the settings screen into smaller components." to
            "The screen is now four components: account, device, theme and about. The behavior is unchanged and the screenshots match."),
    )

    private val artifactTitles = listOf(
        "Q3 latency report", "Login flakiness analysis", "Release 2.4 checklist", "Billing API migration plan", "CI cache benchmark",
        "Onboarding guide draft", "Webhook audit findings", "Search page prototype",
    )

    /** The artifact ids of the gallery, as the server draws them (UUIDs). */
    fun artifactId(index: Int) = "00000000-0000-4000-8000-${(0xa00 + index).toString(16).padStart(12, '0')}"

    /**
     * What waits for the member, for the waiting screen: four open (a plan to review, a tool use to approve in a session,
     * a failed turn, a choice), one answered and one withdrawn. The permission request is a session's own and is
     * answered in the session; the others are answered in place.
     */
    fun waits(now: Long): List<Wait> {
        fun wait(id: String, sessionId: String, sessionName: String, kind: WaitKind, title: String, body: String?, minutesAgo: Int, fields: List<WaitField> = emptyList(), actions: List<WaitAction> = emptyList(),
            source: WaitSource = WaitSource.WAIT) = Wait(
            id, source, kind, true, sessionId, sessionName, if (source == WaitSource.SESSION) "req-$id" else null, MEMBER_ID, title, body, emptyList(), fields, actions, now - minutesAgo * MINUTE,
        )
        val review = listOf(WaitAction("approve", "Approve", style = "primary"), WaitAction("request_changes", "Request changes", requires = listOf("feedback")))
        val feedback = WaitField("feedback", FieldType.TEXT, "Feedback")
        return listOf(
            wait("w1", "s1", "Migrate billing to the new API", WaitKind.HUMAN_REQUEST, "Review the migration plan",
                "Three steps: add `/v2/invoices`, map `/v1` onto it, then remove `/v1` after the clients update. Does the order suit you?", 4, listOf(feedback), review),
            wait("w2", "s3", "Speed up the CI cache", WaitKind.PERMISSION, "Run `rm -rf ~/.gradle/caches`?", "The agent wants to clear the Gradle cache before measuring the restore time again.", 9, source = WaitSource.SESSION),
            wait("w3", "s6", "Audit the payment webhooks", WaitKind.TURN_FAILED, "The last turn failed",
                "The agent stopped after the provider rejected the request. You can ask it to try again.", 21,
                listOf(WaitField("note", FieldType.TEXT, "Note for the agent")), listOf(WaitAction("retry", "Try again", style = "primary"), WaitAction("dismiss", "Dismiss"))),
            wait("w4", "s2", "Review the release checklist", WaitKind.HUMAN_REQUEST, "Which store do we ship first?", null, 35,
                listOf(WaitField("store", FieldType.CHOICE, "Store", required = true, options = listOf(WaitOption("ios", "App Store"), WaitOption("android", "Google Play")))),
                listOf(WaitAction("done", "Send"))),
            wait("w5", "s4", "Write the onboarding guide", WaitKind.HUMAN_REQUEST, "Is the tone right for new developers?", "I drafted the first page in a friendly, direct voice.", 180,
                listOf(feedback), review).copy(open = false, resolvedAt = now - 150 * MINUTE,
                answer = WaitAnswer("approve", mapOf("feedback" to JsonPrimitive("Yes, keep it short.")), MEMBER_ID, now - 150 * MINUTE)),
            wait("w6", "s7", "Prototype the search page", WaitKind.HUMAN_REQUEST, "Pick the filter layout", null, 240, actions = listOf(WaitAction("done", "Done")))
                .copy(open = false, cancelled = true, resolvedAt = now - 230 * MINUTE),
            // What the server watches and wakes the session for: nobody is asked, so these are "in progress", not "waiting for you".
            wait("w7", "s1", "Migrate billing to the new API", WaitKind.TIMED, "Check the nightly deploy", null, 12)
                .copy(resolver = WaitResolver.TIME, dueAt = now + 3 * 60 * MINUTE),
            wait("w8", "s3", "Speed up the CI cache", WaitKind.TIMED, "CI of the cache pull request", null, 26)
                .copy(resolver = WaitResolver.EVENT, eventSource = "webhook", onTimeout = "wake", timeoutAt = now + 20 * 60 * MINUTE),
            wait("w9", "s2", "Review the release checklist", WaitKind.TIMED, "Store review of build 2.4", null, 55)
                .copy(resolver = WaitResolver.EVENT, eventSource = "webhook", onTimeout = "turn_failed", timeoutAt = now + 46 * 60 * MINUTE),
        )
    }

    /**
     * The demo's saved workflows, whole, so the editor has something to change: a ticket that an agent works on and a person reviews
     * (with a typed feedback field and a loop back), and a release that ends with a check by the first workflow.
     */
    fun definitions(now: Long): List<EditableWorkflow> {
        fun node(id: String, kind: StepKind, title: String, vararg transitions: StepTransition, instruction: String = "", deliverable: DefinitionDeliverable? = null,
                 fields: List<DefinitionField> = emptyList(), actions: List<DefinitionAction> = emptyList(), workflowId: String? = null, arguments: Map<String, String> = emptyMap()) =
            DefinitionNode(id, kind, title, instruction, deliverable = deliverable, fields = fields, actions = actions, workflowId = workflowId, arguments = arguments, transitions = transitions.toList())
        return listOf(
            EditableWorkflow(
                "wf-ticket", 2,
                WorkflowDefinition(
                    "Fix a ticket", "An agent fixes the ticket and opens a pull request, you review it, and it loops back until you approve.", "work", "same",
                    listOf(WorkflowInput("ticket", "Ticket", true, null, 64), WorkflowInput("notes", "Notes for the agent", false, "Keep the change small.", 500)),
                    listOf(
                        node("work", StepKind.AGENT, "Work on the ticket", StepTransition("delivered", "review"), instruction = "Fix {{inputs.ticket}}. {{inputs.notes}}", deliverable = DefinitionDeliverable("url", domain = "github.com")),
                        node("review", StepKind.HUMAN, "Review the pull request", StepTransition("approve", null), StepTransition("request_changes", "work"),
                            instruction = "Look at {{nodes.work.output}}",
                            fields = listOf(DefinitionField("feedback", "text", "What should change?", help = "Needed to request changes.")),
                            actions = listOf(DefinitionAction("approve", "Approve", style = "primary"), DefinitionAction("request_changes", "Request changes", requires = listOf("feedback")))),
                    ),
                ),
                now - 3 * 24 * 60 * MINUTE,
            ),
            EditableWorkflow(
                "wf-release", 1,
                WorkflowDefinition(
                    "Ship a release", "Writes the release notes, then checks them with a second workflow.", "notes", "handoff",
                    listOf(WorkflowInput("version", "Version", true, null, 32)),
                    listOf(
                        node("notes", StepKind.AGENT, "Write the release notes", StepTransition("delivered", "check"), instruction = "Write the notes of {{inputs.version}}.", deliverable = DefinitionDeliverable("text")),
                        node("check", StepKind.WORKFLOW, "Check the notes", StepTransition("delivered", null), workflowId = "wf-ticket", arguments = mapOf("ticket" to "{{inputs.version}}")),
                    ),
                ),
                now - 9 * 24 * 60 * MINUTE,
            ),
        )
    }

    /** The read model of [definitions]. */
    fun workflows(now: Long): List<Workflow> = definitions(now).map { it.toWorkflow() }

    /** The demo's runs, one in each state; the waiting one is blocked on the first open wait, which is the member's. */
    fun workflowRuns(now: Long): List<WorkflowRun> {
        fun visit(seq: Int, node: String, title: String, kind: StepKind, status: VisitStatus, startedAgo: Int, endedAgo: Int? = null, outcome: String? = null, output: String? = null, session: String? = null, wait: String? = null) =
            RunVisit(seq, node, title, kind, status, outcome, output, session, wait, null, now - startedAgo * MINUTE, endedAgo?.let { now - it * MINUTE })
        fun run(id: String, workflow: Workflow, status: RunStatus, agoMinutes: Int, visits: List<RunVisit>, session: String?, failure: String? = null, output: String? = null, endedAgo: Int? = null, ticket: String = "ACME-210") = WorkflowRun(
            id, workflow.id, workflow.name, workflow.version, status, status.wire, MEMBER_ID, mapOf("ticket" to ticket), session,
            visits.lastOrNull { it.status == VisitStatus.ACTIVE }?.nodeId, visits.lastOrNull { it.status == VisitStatus.ACTIVE }?.kind, null, failure, output, visits, now - agoMinutes * MINUTE,
            now - (endedAgo ?: (agoMinutes - 1)) * MINUTE, endedAgo?.let { now - it * MINUTE },
        )
        val (ticket, release) = workflows(now)
        return listOf(
            run("run1", ticket, RunStatus.RUNNING, 6, listOf(visit(1, "work", "Work on the ticket", StepKind.AGENT, VisitStatus.ACTIVE, 6, session = "s0")), "s0", ticket = "ACME-231"),
            run("run2", ticket, RunStatus.WAITING, 25, listOf(
                visit(1, "work", "Work on the ticket", StepKind.AGENT, VisitStatus.DONE, 25, 5, "delivered", "https://github.com/acme/web/pull/412", "s1"),
                visit(2, "review", "Review the pull request", StepKind.HUMAN, VisitStatus.ACTIVE, 5, session = "s1", wait = "w1"),
            ), "s1", ticket = "ACME-228"),
            run("run3", release, RunStatus.SUCCEEDED, 130, listOf(
                visit(1, "notes", "Write the release notes", StepKind.AGENT, VisitStatus.DONE, 130, 95, "delivered", "Release 4.2: faster sync, fewer crashes.", "s2"),
                visit(2, "check", "Check the notes", StepKind.WORKFLOW, VisitStatus.DONE, 95, 80, "delivered", "approve: ok"),
            ), "s2", output = "approve: ok", endedAgo = 80, ticket = "4.2"),
            run("run4", ticket, RunStatus.FAILED, 300, listOf(
                visit(1, "work", "Work on the ticket", StepKind.AGENT, VisitStatus.FAILED, 300, 280, session = "s5"),
            ), "s5", failure = "session_failed", endedAgo = 280, ticket = "ACME-199"),
            run("run5", ticket, RunStatus.CANCELLED, 500, listOf(
                visit(1, "work", "Work on the ticket", StepKind.AGENT, VisitStatus.CANCELLED, 500, 490, session = "s4"),
            ), "s4", endedAgo = 490, ticket = "ACME-150"),
        )
    }

    fun artifacts(now: Long): List<Artifact> = artifactTitles.mapIndexed { index, title ->
        val id = artifactId(index)
        Artifact(
            id = id, url = "$ORIGIN/artifact/$id", title = title, description = "Published by a session: $title.", icon = "chart", access = "team",
            ownerName = USERNAME, currentVersion = index % 3 + 1, originKind = "cloud_session", originSessionId = null, updatedAt = now - (index * 45 + 5) * MINUTE,
        )
    }
}

/** The built-in demo's account: no server behind it, everything in memory. */
val Account.isDemo: Boolean get() = id == DemoContent.ACCOUNT_ID
