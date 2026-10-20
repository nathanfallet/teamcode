package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** The runner channel, authenticated by the pod's projected token (see [AuthenticateRunnerUseCase]). */
class CloudRunnerChannel(
    val runner: RunnerChannelUseCase,
    val authenticate: AuthenticateRunnerUseCase,
    /** `tcex_` inference credentials for the agent's inference; null where the server does not issue them. */
    val inference: RunnerInferenceUseCase? = null,
)

internal const val RUNNER_PATH = "/api/v1/cloud/runner/"

private fun RunnerInstruction.view() = RunnerInstructionView(desiredState.wire, pauseDeadline, epoch)
private fun ApplicationCall.runnerToken() = request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()
    ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

internal fun Route.cloudRunnerRoutes(channel: CloudRunnerChannel) {
    post(RUNNER_PATH + "register") {
        val body = call.jsonBody<RunnerRegisterRequest>()
        val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
        call.respond(channel.runner.register(pod.teamId, pod.sessionId, pod.epoch, pod.podName).view())
    }
    post(RUNNER_PATH + "heartbeat") {
        val body = call.jsonBody<RunnerHeartbeatRequest>()
        val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
        call.respond(channel.runner.heartbeat(pod.teamId, pod.sessionId, pod.epoch, body.ready).view())
    }
    post(RUNNER_PATH + "environment") {
        val body = call.jsonBody<RunnerEnvironmentRequest>()
        val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
        val prepared = channel.runner.environment(pod.teamId, pod.sessionId, pod.epoch)
        val github = when (val access = prepared.github) {
            is GitHubAccess.Linked -> RunnerGitHubView("linked", access.token.token, access.token.expiresAt)
            GitHubAccess.NotLinked -> RunnerGitHubView("not_linked")
            GitHubAccess.Unavailable -> RunnerGitHubView("unavailable")
        }
        // The body holds the owner's variable values and GitHub token: no intermediary may keep it.
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(RunnerEnvironmentView(prepared.spec.view(), RunnerGitIdentityView(prepared.git.name, prepared.git.email),
            prepared.variables.values, prepared.variables.unavailable, github))
    }
    post(RUNNER_PATH + "preparation") {
        val body = call.jsonBody<RunnerPreparationRequest>()
        val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
        val failure = body.failure?.let {
            PreparationFailure(PreparationStep.parse(it.step) ?: throw TeamFailure("invalid_failure", 400, "Unknown preparation step."), it.index, it.exitCode)
        }
        call.respond(channel.runner.progress(pod.teamId, pod.sessionId, pod.epoch, body.phase, failure).view())
    }
    post(RUNNER_PATH + "checkpoint") {
        val body = call.jsonBody<RunnerCheckpointRequest>()
        val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
        call.respond(channel.runner.checkpoint(pod.teamId, pod.sessionId, pod.epoch, body.completed).view())
    }
    channel.inference?.let { inference ->
        // The credential is returned only to the registered runner of the current epoch, and never stored or logged.
        post(RUNNER_PATH + "inference") {
            val body = call.jsonBody<RunnerInferenceRequest>()
            val pod = channel.authenticate(call.runnerToken(), body.teamId, body.sessionId, body.epoch)
            val credential = inference.issue(pod.teamId, pod.sessionId, pod.epoch)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(RunnerInferenceCredentialView(credential.token, credential.expiresAt, credential.lifetimeMillis))
        }
    }
}
