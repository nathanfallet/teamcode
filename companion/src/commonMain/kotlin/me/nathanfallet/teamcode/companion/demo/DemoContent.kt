package me.nathanfallet.teamcode.companion.demo

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
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

    /** What the demo server announces: no transfers, devices, files, GitHub link or MCP gateway, so their screens stay quiet. */
    val features = setOf(
        Features.SESSIONS, Features.TRANSCRIPT, Features.MESSAGES, Features.EVENTS, Features.INITIAL_MESSAGE, Features.ARTIFACTS, Features.RENAME,
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
