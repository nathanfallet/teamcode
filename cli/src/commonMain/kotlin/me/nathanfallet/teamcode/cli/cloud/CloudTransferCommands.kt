package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

internal fun CloudTransferView.line() = listOf(id, state, sessionId, "$fromMemberId -> $toMemberId",
    expiresAt?.takeIf { state == "offered" }?.let { "expires ${Instant.fromEpochMilliseconds(it)}" } ?: "updated ${Instant.fromEpochMilliseconds(updatedAt)}",
).joinToString("\t") + (failure?.let { "\t${it.code}" } ?: "")

/** `teamcode cloud transfer SESSION --to MEMBER`: an administrator transfers at once; an owner makes an offer. */
internal class CloudTransfer : SuspendingCliktCommand(name = "transfer") {
    override fun help(context: Context) = "Transfer a session to another member. Administrators transfer any session at once; an owner offers their own session and the recipient accepts it. " +
        "The session pauses first (the running turn drains, unless --force) and changes owner once its pod is gone."
    private val id by argument()
    private val to by option("--to", help = "Recipient member ID.").required()
    private val resume by option("--resume", help = "Resume the session for the recipient once the owner changed (administrator transfers).").flag()
    private val force by option("--force", help = "Pause without letting the running turn finish; the workspace becomes crash-consistent.").flag()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    private val idempotencyKey by option("--idempotency-key", help = "Defaults to a random key; reuse one to retry safely.")
    private val json by option("--json").flag()
    override suspend fun run() {
        val transfer = sdk(loadConfig()).use {
            it.transferCloudSession(id, CreateCloudTransferRequest(to, resume, force), idempotencyKey ?: Uuid.random().toString(), onBehalfOf)
        }
        echo(if (json) Serialization.json.encodeToString(transfer) else transfer.line())
    }
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
        require(listOf(incoming, outgoing, all).count { it } <= 1) { "Use at most one of --incoming, --outgoing and --all." }
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
    override fun help(context: Context) = "Accept a session offered to you. The session pauses, then becomes yours."
    private val id by argument()
    private val resume by option("--resume", help = "Resume the session for you once it is yours.").flag()
    private val onBehalfOf by option("--member", help = "Act for this member (TeamCode-On-Behalf-Of); administrators only.")
    override suspend fun run() { echo(sdk(loadConfig()).use { it.acceptCloudTransfer(id, resume, onBehalfOf) }.line()) }
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
