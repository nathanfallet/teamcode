package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*

private fun CloudProblem.view() = CloudProblemView(code, message, retryable)
internal fun CloudSession.view() = CloudSessionView(
    id = id, name = name, ownerId = ownerId, createdBy = CloudAttributionView(createdBy.initiatorId, createdBy.credentialId, createdBy.effectiveMemberId),
    state = state.wire, phase = phase, desiredState = desiredState.wire, generation = generation, currentEpoch = currentEpoch,
    profile = CloudProfileRef(profileId, profileVersion), image = CloudImageView(imageReference, imageDigest),
    agent = CloudAgentView(agentKind, agentVersion, agentModel, agentEffort), labels = labels,
    activity = CloudActivityView(lastActivityAt, idleTimeoutMinutes), resume = CloudResumeView(resumeWorkspace.wire, resumeConversation.wire),
    failure = failure?.view(), resumeBlocked = resumeBlocked?.view(),
    workspaceDeletion = CloudWorkspaceDeletionView(workspace.deletion.wire, workspace.claimDeletedAt, workspace.volumeDeletedAt, workspace.volumeHandle, workspace.deletionProblem?.view()),
    createdAt = createdAt, updatedAt = updatedAt, pausedAt = pausedAt, expiresAt = expiresAt, terminatedReason = terminatedReason,
)
private fun CloudExecution.view() = CloudExecutionView(epoch, podName, state.wire, createdAt, registeredAt, lastHeartbeatAt, nodeName, imageDigest, endedAt, exitReason, checkpoint)
private fun CloudCluster.view() = CloudClusterView(id, name, apiUrl, allowedNamespaces, maxRunningPods, defaultStorageClass, version, orphanedVolumes, lastGarbageCollectionAt, createdAt, updatedAt)
private fun CloudProfileSpec.view() = CloudProfileSpecView(clusterId, namespace, image, allowedImageOverrides, imagePullSecrets, CloudResourcesView(resources.cpuMillis, resources.memoryMebibytes),
    workspaceSizeGibibytes, storageClass, agentKind, agentVersion, idleTimeoutMinutes, minIdleTimeoutMinutes, maxIdleTimeoutMinutes)
private fun CloudProfileSpecView.domain() = CloudProfileSpec(clusterId, namespace, image, allowedImageOverrides, imagePullSecrets, CloudResources(resources.cpuMillis, resources.memoryMebibytes),
    workspaceSizeGibibytes, storageClass, agentKind, agentVersion, idleTimeoutMinutes, minIdleTimeoutMinutes, maxIdleTimeoutMinutes)
private fun CloudProfile.view() = CloudProfileView(id, name, version, spec.view(), assignedMemberIds.sorted(), createdAt, updatedAt)
private fun CloudSettings.view() = CloudSettingsView(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays)
private fun CloudSettingsView.domain() = CloudSettings(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays)

/** `TeamCode-On-Behalf-Of`: at most one value; the domain decides whether the caller may use it. */
private fun ApplicationCall.onBehalfOf(): String? {
    val values = request.headers.getAll(CloudHeaders.ON_BEHALF_OF) ?: return null
    return values.singleOrNull()?.takeIf { Regex("[a-zA-Z0-9-]{1,36}").matches(it) }
        ?: throw TeamFailure("invalid_on_behalf", 400, "Send one member identifier in ${CloudHeaders.ON_BEHALF_OF}.")
}
private fun ApplicationCall.idempotencyKey(required: Boolean): String? {
    val values = request.headers.getAll(CloudHeaders.IDEMPOTENCY_KEY)
    if (values == null) {
        if (required) throw TeamFailure("idempotency_key_required", 400, "Send an ${CloudHeaders.IDEMPOTENCY_KEY} header (1–128 characters from A-Z a-z 0-9 . _ : -).")
        return null
    }
    return values.singleOrNull()?.takeIf { CloudHeaders.idempotencyKey.matches(it) }
        ?: throw TeamFailure("invalid_idempotency_key", 400, "Use one ${CloudHeaders.IDEMPOTENCY_KEY} of 1–128 characters from A-Z a-z 0-9 . _ : -.")
}
private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
private fun ApplicationCall.id() = parameters["id"]!!.also { if (!Regex("[a-zA-Z0-9-]{1,36}").matches(it)) throw TeamFailure("not_found", 404, "Not found.") }

internal fun Route.cloudRoutes(sessions: CloudSessionService, admin: CloudAdministrationService, origin: String) {
    route("/api/v1/cloud") {
        get("/settings") { call.respond(admin.settings(call.principal).view()) }
        put("/settings") { call.respond(admin.saveSettings(call.principal, call.jsonBody<CloudSettingsView>().domain()).view()) }

        get("/clusters") { call.respond(admin.clusters(call.principal).map { it.view() }) }
        post("/clusters") {
            val input = call.jsonBody<SaveCloudClusterRequest>()
            call.respond(HttpStatusCode.Created, admin.saveCluster(call.principal, null, null, input.domain()).view())
        }
        put("/clusters/{id}") {
            val input = call.jsonBody<SaveCloudClusterRequest>()
            call.respond(admin.saveCluster(call.principal, call.id(), input.version ?: throw TeamFailure("invalid_cluster", 400, "Send the current cluster version."), input.domain()).view())
        }

        get("/profiles") { call.respond(admin.profiles(call.principal).map { it.view() }) }
        post("/profiles") {
            val input = call.jsonBody<SaveCloudProfileRequest>()
            call.respond(HttpStatusCode.Created, admin.saveProfile(call.principal, null, 0, input.name, input.spec.domain(), input.assignedMemberIds.toSet()).view())
        }
        put("/profiles/{id}") {
            val input = call.jsonBody<SaveCloudProfileRequest>()
            call.respond(admin.saveProfile(call.principal, call.id(), input.version, input.name, input.spec.domain(), input.assignedMemberIds.toSet()).view())
        }

        post("/sessions") {
            val key = call.idempotencyKey(required = true)!!
            val onBehalfOf = call.onBehalfOf()
            val input = call.jsonBody<CreateCloudSessionRequest>()
            val canonical = Serialization.json.encodeToString(input.copy(labels = input.labels.toSortedMap())) + "\n" + (onBehalfOf ?: call.principal.member.id)
            val draft = CloudSessionDraft(input.name, input.profileId, input.image, input.agent?.model, input.agent?.effort, input.labels, input.idleTimeoutMinutes)
            when (val outcome = sessions.create(call.principal, onBehalfOf, draft, IdempotencyRequest("POST /cloud/sessions", key, sha256(canonical)))) {
                is CloudCreateOutcome.Created -> call.respond(HttpStatusCode.Accepted, outcome.session.view())
                is CloudCreateOutcome.Replayed -> {
                    call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
                    call.respond(HttpStatusCode.Accepted, outcome.session.view())
                }
            }
        }
        get("/sessions") {
            val q = call.request.queryParameters
            fun single(name: String) = q.getAll(name)?.let { it.singleOrNull() ?: throw TeamFailure("invalid_query", 400, "Send $name at most once.") }
            val states = q.getAll("state").orEmpty().map { value -> CloudSessionState.entries.find { it.wire == value } ?: throw TeamFailure("invalid_state", 400, "Unknown session state.") }.toSet()
            val labels = q.getAll("label").orEmpty().associate { value ->
                val (key, label) = value.split('=', limit = 2).takeIf { it.size == 2 } ?: throw TeamFailure("invalid_label", 400, "Filter labels as key=value.")
                key to label
            }
            val owner = single("owner")?.let { if (it == "all") "*" else it }
            val limit = single("limit")?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 50.") } ?: 50
            val page = sessions.list(call.principal, CloudSessionQuery(owner, states, labels, single("before"), limit))
            call.respond(CloudSessionPageView(page.sessions.map { it.view() }, page.nextBeforeId))
        }
        get("/sessions/{id}") { call.respond(sessions.get(call.principal, call.id()).view()) }
        get("/sessions/{id}/executions") { call.respond(sessions.executions(call.principal, call.id()).map { it.view() }) }
        post("/sessions/{id}/pause") {
            val input = if (call.request.contentType().withoutParameters() == ContentType.Application.Json) call.jsonBody<PauseCloudSessionRequest>() else PauseCloudSessionRequest()
            call.respond(HttpStatusCode.Accepted, sessions.pause(call.principal, call.onBehalfOf(), call.id(), input.force).view())
        }
        post("/sessions/{id}/resume") { call.respond(HttpStatusCode.Accepted, sessions.resume(call.principal, call.onBehalfOf(), call.id()).view()) }
        delete("/sessions/{id}") {
            sessions.delete(call.principal, call.onBehalfOf(), call.id())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/dashboard/cloud") {
        val actor = call.principal
        val settings = admin.settings(actor)
        call.respondText(page("Cloud session settings", """<p>Paused sessions keep their workspace volume until the retention below expires; then the workspace claim and its provider volume are deleted and the session is terminated. Inactivity only pauses a session; it never deletes it. A change applies to sessions paused afterwards.</p><form method="post">${csrf(actor)}<label>Paused retention in days (empty keeps paused workspaces indefinitely) <input type="number" name="pausedRetentionDays" min="1" max="3650" value="${settings.pausedRetentionDays ?: ""}"></label><label>Failed workspace retention in days <input type="number" name="failedWorkspaceRetentionDays" min="0" max="365" value="${settings.failedWorkspaceRetentionDays}" required></label><label>Running sessions per member <input type="number" name="maxRunning" min="1" max="100" value="${settings.maxRunningSessionsPerMember}" required></label><label>Retained sessions per member <input type="number" name="maxRetained" min="1" max="1000" value="${settings.maxRetainedSessionsPerMember}" required></label><label>Transcript retention in days (empty keeps transcripts indefinitely; counted from each event, independently of pods and workspaces) <input type="number" name="transcriptRetentionDays" min="1" max="3650" value="${settings.transcriptRetentionDays ?: ""}"></label><button>Save settings</button></form>"""), ContentType.Text.Html)
    }
    post("/dashboard/cloud") {
        val input = call.form(origin.trimEnd('/'))
        fun number(name: String) = input[name]?.toIntOrNull() ?: -1
        admin.saveSettings(call.principal, CloudSettings(input["pausedRetentionDays"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: -1 },
            number("failedWorkspaceRetentionDays"), number("maxRunning"), number("maxRetained"),
            input["transcriptRetentionDays"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: -1 }))
        call.respondRedirect("/dashboard/cloud")
    }
}
private fun SaveCloudClusterRequest.domain() = CloudClusterInput(name, apiUrl, caCertificate, serviceAccountToken, allowedNamespaces, maxRunningPods, defaultStorageClass)
