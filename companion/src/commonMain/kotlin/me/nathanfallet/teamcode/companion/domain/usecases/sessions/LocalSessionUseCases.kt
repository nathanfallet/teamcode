package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.requireInput
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.services.LocalAvailability
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException
import me.nathanfallet.teamcode.companion.domain.services.LocalSessionRequest
import me.nathanfallet.teamcode.companion.domain.services.LocalResumables
import me.nathanfallet.teamcode.companion.domain.services.LocalResumeBlock
import me.nathanfallet.teamcode.companion.domain.models.CompanionException

/** What starting a session on this device gave: its id, and the first message when one was sent after it. */
data class LocalSessionStart(val sessionId: String, val firstMessage: PendingMessage?)

/**
 * "On this device": checks what the CLI and the server allow, then starts the session through the CLI. The first
 * message is sent through the server once the session exists, never on the CLI's command line, where other processes
 * of the device could read it.
 */
class StartLocalSessionUseCase(
    private val host: LocalHost,
    private val features: FeaturesRepository,
    private val send: SendMessageUseCase,
) {
    suspend fun availability(accountId: String): LocalAvailability {
        if (Features.REMOTE_SESSIONS !in features.features(accountId)) return LocalAvailability.ServerUnsupported
        return host.availability(accountId)
    }

    /**
     * The Claude Code conversations of [directory] on this device that the session can continue
     * (`cloud.sessions.remote.resume.v1`), read by the CLI with its daemon's code: nothing of them goes through the server.
     */
    suspend fun resumable(accountId: String, directory: String, before: Long?): LocalResumables {
        if (Features.RESUME_SESSIONS !in features.features(accountId)) return LocalResumables.Unavailable(LocalResumeBlock.SERVER)
        return host.resumable(accountId, directory, before)
    }

    suspend operator fun invoke(accountId: String, request: LocalSessionRequest, message: String): LocalSessionStart {
        val name = request.name.trim()
        requireInput(request.directory.isNotBlank(), InvalidInput.Problem.CHOOSE_LOCAL_FOLDER, message = "Choose the folder the agent works in.")
        requireInput(request.agent in AGENTS, InvalidInput.Problem.CHOOSE_AGENT, message = "Choose Claude Code or Codex.")
        requireInput(name.isNotEmpty() && name.length <= MAX_NAME && name.none(Char::isISOControl), InvalidInput.Problem.SESSION_NAME, MAX_NAME, "Name the session (up to $MAX_NAME characters).")
        val first = message.trim().takeIf { it.isNotEmpty() }?.let(send::draft)
        if (availability(accountId) != LocalAvailability.Ready) throw LocalHostException("Sessions on this device are not available: see the explanation above.")
        // Codex continues no Claude Code conversation.
        val resume = request.resumeSessionId?.takeIf { request.agent == "claude" }
        if (resume != null && Features.RESUME_SESSIONS !in features.features(accountId)) throw CompanionException.Unsupported(Features.RESUME_SESSIONS)
        val id = host.start(accountId, request.copy(name = name, model = request.model.trim(), effort = request.effort.trim(), resumeSessionId = resume))
        return LocalSessionStart(id, first?.let { send(accountId, id, it) })
    }

    companion object {
        val AGENTS = listOf("claude", "codex")
        private const val MAX_NAME = 120

        /** The session's default name: the folder's own name, whichever separator the system uses. */
        fun defaultName(directory: String): String = directory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    }
}
