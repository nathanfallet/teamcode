package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.clikt.parameters.options.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.terminal.PickerOption
import me.nathanfallet.teamcode.cli.terminal.pickOne
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException

internal fun CloudTransferView.line() = listOf(id, state, sessionId, "$fromMemberId -> $toMemberId",
    expiresAt?.takeIf { state == "offered" }?.let { "expires ${Instant.fromEpochMilliseconds(it)}" } ?: "updated ${Instant.fromEpochMilliseconds(updatedAt)}",
).joinToString("\t") + (failure?.let { "\t${it.code}" } ?: "")

/**
 * `teamcode cloud transfer [SESSION] [--to MEMBER]`: an administrator transfers at once; an owner makes an offer. Without
 * SESSION or `--to`, the session (among the caller's own transferable cloud sessions) and the recipient (among the
 * members the server lists for a picker) are chosen in a picker: arrow keys on a terminal, a numbered list otherwise.
 */
internal class CloudTransfer : SuspendingCliktCommand(name = "transfer") {
    override fun help(context: Context) = "Transfer a session to another member. Administrators transfer any session at once; an owner offers their own session and the recipient accepts it. " +
        "The session pauses first (the running turn drains, unless --force) and changes owner once its pod is gone. Without SESSION or --to, pick them from a list."
    private val id by argument(help = "The session; omit it to pick one of your transferable sessions.").optional()
    private val to by option("--to", help = "Recipient member ID; omit it to pick a member from a list.")
    private val resume by option("--resume", help = "Resume the session for the recipient once the owner changed (administrator transfers).").flag()
    private val force by option("--force", help = "Pause without letting the running turn finish; the workspace becomes crash-consistent.").flag()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    private val idempotencyKey by option("--idempotency-key", help = "Defaults to a random key; reuse one to retry safely.")
    private val json by option("--json").flag()
    override suspend fun run() {
        val terminal by lazy { Terminal() }
        val transfer = sdk(loadConfig()).use { client ->
            // Without a kind, the list holds cloud sessions only: remote sessions are never transferred.
            val picked = if (id == null) pickTransferableSession(client.cloudSessions(states = TransferPicking.STATES).sessions, terminal) else null
            val session = id ?: picked!!.id
            val recipient = to ?: pickRecipient(recipients(client), (picked ?: client.cloudSession(session)).ownerId, terminal)
            client.transferCloudSession(session, CreateCloudTransferRequest(recipient, resume, force), idempotencyKey ?: Uuid.random().toString(), onBehalfOf)
        }
        echo(if (json) Serialization.json.encodeToString(transfer) else transfer.line())
    }

    private suspend fun recipients(client: TeamCodeClient): List<CloudTransferRecipientView> = try {
        client.cloudTransferRecipients()
    } catch (error: TeamCodeException) {
        // A server from before the picker's list: the recipient is named by ID, as before.
        if (error.statusCode == 404) throw UsageError("This server does not list members for transfers. Name the recipient with --to MEMBER_ID.") else throw error
    }
}

/** The pickers of `teamcode cloud transfer`, apart from the command so they are tested on a recorded terminal. */
internal object TransferPicking {
    /** States a transfer may start from (the server refuses sessions being created or prepared, ended or transferring). */
    val STATES = listOf("ready", "active", "pausing", "paused", "resuming")
}

/** One of [sessions] (the caller's own cloud sessions, newest first); fails when there is none or the member cancelled. */
internal fun pickTransferableSession(sessions: List<CloudSessionView>, terminal: Terminal): CloudSessionView {
    val transferable = sessions.filter { it.state in TransferPicking.STATES && it.kind == CloudSessionKinds.CLOUD && it.transferId == null }
    if (transferable.isEmpty()) throw UsageError("You have no cloud session that can be transferred now (ready, working, pausing, paused or resuming).")
    val index = pickOne("Which session do you transfer?", transferable.map { PickerOption(it.name, "${it.state} · ${it.agent.kind} · ${it.id}") }, terminal = terminal)
        ?: throw UsageError("No session chosen.")
    return transferable[index]
}

/** One of [recipients] other than [ownerId]; fails when nobody else can receive it or the member cancelled. */
internal fun pickRecipient(recipients: List<CloudTransferRecipientView>, ownerId: String, terminal: Terminal): String {
    val others = recipients.filter { it.id != ownerId }
    if (others.isEmpty()) throw UsageError("No other member can receive a session.")
    val index = pickOne("Who receives it?", others.map { PickerOption(it.username, (if (it.administrator) "administrator · " else "") + it.id) }, terminal = terminal)
        ?: throw UsageError("No member chosen.")
    return others[index].id
}

/** `teamcode cloud transfers`: list, show and decide transfers. */
internal class CloudTransfers : SuspendingNoOpCliktCommand(name = "transfers") {
    override fun help(context: Context) = "List, accept, decline or cancel session transfers."
    init { subcommands(CloudTransfersList(), CloudTransfersShow(), CloudTransfersAccept(), CloudTransfersDecline(), CloudTransfersCancel()) }
}

private class CloudTransfersList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "Your incoming and outgoing transfers, newest first (at most $MAX_LISTED)."
    private val incoming by option("--incoming", help = "Only transfers to you.").flag()
    private val outgoing by option("--outgoing", help = "Only transfers of your sessions.").flag()
    private val all by option("--all", help = "Every team transfer; administrators only.").flag()
    private val states by option("--state", help = "Repeatable: ${CloudTransferStates.all.joinToString(", ")}.").multiple()
    private val session by option("--session", help = "Only transfers of this session.")
    private val json by option("--json").flag()
    override suspend fun run() {
        if (listOf(incoming, outgoing, all).count { it } > 1) throw UsageError("Use at most one of --incoming, --outgoing and --all.")
        val direction = if (incoming) "incoming" else if (outgoing) "outgoing" else null
        sdk(loadConfig()).use { client ->
            var before: String? = null
            val transfers = mutableListOf<CloudTransferView>()
            do {
                val page = client.cloudTransfers(direction, all, states, session, before, 50)
                transfers += page.transfers
                before = page.nextBefore
            } while (before != null && transfers.size < MAX_LISTED)
            if (json) echo(Serialization.json.encodeToString(transfers)) else transfers.forEach { echo(it.line()) }
        }
    }
    private companion object { const val MAX_LISTED = 500 }
}

private class CloudTransfersShow : SuspendingCliktCommand(name = "show") {
    private val id by argument()
    private val json by option("--json").flag()
    override suspend fun run() {
        val t = sdk(loadConfig()).use { it.cloudTransfer(id) }
        if (json) { echo(Serialization.json.encodeToString(t)); return }
        echo(buildString {
            appendLine("Transfer:     ${t.id}")
            appendLine("Session:      ${t.sessionId}")
            appendLine("State:        ${t.state}")
            appendLine("From:         ${t.fromMemberId}")
            appendLine("To:           ${t.toMemberId}")
            appendLine("Requested by: ${t.initiatedBy.initiatorId}" + if (t.initiatedBy.effectiveMemberId != t.initiatedBy.initiatorId) " for ${t.initiatedBy.effectiveMemberId}" else "")
            t.decidedBy?.let { appendLine("Decided by:   ${it.initiatorId}") }
            appendLine("Then:         " + (if (t.resume) "resume for the recipient" else "stay paused") + (if (t.force) ", forced pause" else "") + ", unconsumed messages ${t.pendingInputs}")
            t.expiresAt?.takeIf { t.state == "offered" }?.let { appendLine("Expires:      ${Instant.fromEpochMilliseconds(it)}") }
            t.completedAt?.let { appendLine("Completed:    ${Instant.fromEpochMilliseconds(it)} after epoch ${t.atEpoch}") }
            t.failure?.let { appendLine("Failure:      ${it.code}: ${it.message}") }
        }.trimEnd())
    }
}

private class CloudTransfersAccept : SuspendingCliktCommand(name = "accept") {
    override fun help(context: Context) = "Accept a session offered to you. The session pauses, then becomes yours. Its workspace is the former owner's work: " +
        "TeamCode resets the agent's settings and the repositories' Git configuration, but scripts and project files in the repositories run with your credentials, so review them."
    private val id by argument()
    private val resume by option(help = "Resume the session for you once it is yours, or not (default: what the offer asked for).").switch("--resume" to true, "--no-resume" to false)
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() {
        echo(sdk(loadConfig()).use { it.acceptCloudTransfer(id, resume, onBehalfOf) }.line())
        echo(ACCEPTED_WORKSPACE_NOTICE, err = true)
    }
}

private class CloudTransfersDecline : SuspendingCliktCommand(name = "decline") {
    private val id by argument()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() { echo(sdk(loadConfig()).use { it.declineCloudTransfer(id, onBehalfOf) }.line()) }
}

private class CloudTransfersCancel : SuspendingCliktCommand(name = "cancel") {
    override fun help(context: Context) = "Withdraw your offer, or (administrators) cancel a transfer before the owner change starts."
    private val id by argument()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() { echo(sdk(loadConfig()).use { it.cancelCloudTransfer(id, onBehalfOf) }.line()) }
}

/** Said on every acceptance: what the recipient takes over runs with the recipient's credentials. */
internal const val ACCEPTED_WORKSPACE_NOTICE = "The workspace is the former owner's work. Before it runs as you, TeamCode resets the agent's settings and " +
    "each repository's Git configuration and hooks; scripts and project files in the repositories are not reviewed: check them before you run them."
