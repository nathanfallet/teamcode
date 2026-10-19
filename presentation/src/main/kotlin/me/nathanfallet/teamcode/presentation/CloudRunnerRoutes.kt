package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*

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
