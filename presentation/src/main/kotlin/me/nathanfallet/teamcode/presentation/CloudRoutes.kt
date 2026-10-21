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

internal fun CloudProblem.view() = CloudProblemView(code, message, retryable)
internal fun CloudSession.view() = CloudSessionView(
    id = id, name = name, ownerId = ownerId, createdBy = CloudAttributionView(createdBy.initiatorId, createdBy.credentialId, createdBy.effectiveMemberId),
    state = state.wire, phase = phase, desiredState = desiredState.wire, generation = generation, currentEpoch = currentEpoch,
    profile = CloudProfileRef(profileId, profileVersion), image = CloudImageView(imageReference, imageDigest),
    agent = CloudAgentView(agentKind, agentVersion, agentModel, agentEffort), labels = labels,
    activity = CloudActivityView(lastActivityAt, idleTimeoutMinutes), resume = CloudResumeView(resumeWorkspace.wire, resumeConversation.wire),
    failure = failure?.view(), resumeBlocked = resumeBlocked?.view(),
    workspaceDeletion = CloudWorkspaceDeletionView(workspace.deletion.wire, workspace.claimDeletedAt, workspace.volumeDeletedAt, workspace.volumeHandle, workspace.deletionProblem?.view()),
    createdAt = createdAt, updatedAt = updatedAt, pausedAt = pausedAt, expiresAt = expiresAt, terminatedReason = terminatedReason, transferId = transferId,
    environment = environment.view(),
)
internal fun CloudEnvironmentSpec.view() = CloudEnvironmentView(repositories.map { CloudRepositoryView(it.url, it.path, it.ref, it.branch) }, workingDirectory,
    CloudSetupView(setup.create, setup.start), requiredVariables, requireGitHub, gitEmailDomain, repositoryHosts)
private fun CloudRepositoryView.domain() = CloudRepository(url, path, ref, branch)
private fun CloudEnvironmentView.domain() = CloudEnvironmentSpec(repositories.map { it.domain() }, workingDirectory, CloudSetup(setup.create, setup.start), requiredVariables, requireGitHub, gitEmailDomain, repositoryHosts)
private fun CloudExecution.view() = CloudExecutionView(epoch, podName, state.wire, createdAt, registeredAt, lastHeartbeatAt, nodeName, imageDigest, endedAt, exitReason, checkpoint, ownerId)
private fun CloudCluster.view() = CloudClusterView(id, name, apiUrl, allowedNamespaces, maxRunningPods, defaultStorageClass, version, orphanedVolumes, lastGarbageCollectionAt, createdAt, updatedAt)
private fun CloudProfileSpec.view() = CloudProfileSpecView(clusterId, namespace, image, allowedImageOverrides, imagePullSecrets, CloudResourcesView(resources.cpuMillis, resources.memoryMebibytes),
    workspaceSizeGibibytes, storageClass, agentKind, agentVersion, idleTimeoutMinutes, minIdleTimeoutMinutes, maxIdleTimeoutMinutes, environment.view())
private fun CloudProfileSpecView.domain() = CloudProfileSpec(clusterId, namespace, image, allowedImageOverrides, imagePullSecrets, CloudResources(resources.cpuMillis, resources.memoryMebibytes),
    workspaceSizeGibibytes, storageClass, agentKind, agentVersion, idleTimeoutMinutes, minIdleTimeoutMinutes, maxIdleTimeoutMinutes, environment.domain())
private fun CloudProfile.view() = CloudProfileView(id, name, version, spec.view(), assignedMemberIds.sorted(), createdAt, updatedAt)
private fun CloudSettings.view() = CloudSettingsView(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays,
    memberTransfers.wire, transferOfferDays, transferPendingInputs.wire)
/** An absent transfer field keeps [current]'s value. */
private fun CloudSettingsView.domain(current: CloudSettings) = CloudSettings(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays,
    memberTransfers?.let { MemberTransfers.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Use memberTransfers offer or disabled.") } ?: current.memberTransfers,
    transferOfferDays ?: current.transferOfferDays,
    transferPendingInputs?.let { TransferPendingInputs.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Use transferPendingInputs cancel or keep.") } ?: current.transferPendingInputs)

/** `TeamCode-On-Behalf-Of`: at most one value; the domain decides whether the caller may use it. */
internal fun ApplicationCall.onBehalfOf(): String? {
    val values = request.headers.getAll(CloudHeaders.ON_BEHALF_OF) ?: return null
    return values.singleOrNull()?.takeIf(CloudIdentifier::valid)
        ?: throw TeamFailure("invalid_on_behalf", 400, "Send one member identifier in ${CloudHeaders.ON_BEHALF_OF}.")
}
internal fun ApplicationCall.idempotencyKey(required: Boolean): String? {
    val values = request.headers.getAll(CloudHeaders.IDEMPOTENCY_KEY)
    if (values == null) {
        if (required) throw TeamFailure("idempotency_key_required", 400, "Send an ${CloudHeaders.IDEMPOTENCY_KEY} header (1–128 characters from A-Z a-z 0-9 . _ : -).")
        return null
    }
    return values.singleOrNull()?.takeIf { CloudHeaders.idempotencyKey.matches(it) }
        ?: throw TeamFailure("invalid_idempotency_key", 400, "Use one ${CloudHeaders.IDEMPOTENCY_KEY} of 1–128 characters from A-Z a-z 0-9 . _ : -.")
}
internal fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
private fun ApplicationCall.id() = parameters["id"]!!.also { if (!CloudIdentifier.valid(it)) throw TeamFailure("not_found", 404, "Not found.") }

/** Cloud sub-trees that stay reachable while cloud sessions are off: configuration, and the runner channel of pods that already run. */
private val CLOUD_UNGATED = setOf("settings", "runtime", "clusters", "profiles", "runner")

/**
 * Paths members use to run or link cloud sessions, answered `404` while the stored runtime is off: sessions, transfers,
 * transcripts, access and new GitHub links. The path is read the way routing matches it (empty segments skipped,
 * each segment percent-decoded), so no other spelling of a gated route passes; a segment that cannot be decoded is gated.
 */
internal fun cloudOperationalPath(path: String): Boolean {
    val segments = path.split('/').filter { it.isNotEmpty() }.map { segment ->
        try { segment.decodeURLPart() } catch (_: Exception) { return true }
    }
    fun under(vararg prefix: String) = segments.size >= prefix.size && prefix.indices.all { segments[it] == prefix[it] }
    return when {
        under("api", "v1", "cloud") -> segments.getOrNull(3) !in CLOUD_UNGATED
        // Reading and removing one's own link stay available, so a member can always revoke it; only new links are gated.
        under("api", "v1", "github", "link") -> segments.size > 4
        under("dashboard", "github") -> segments.getOrNull(2) in setOf("link", "callback")
        else -> false
    }
}

private fun CloudRuntime.view(runtime: CloudRuntimeService) = CloudRuntimeView(enabled, runnerImage, runnerBinary, serverUrl, workspaceAccessMode, runtime.serverUrl(this), updatedAt)
private fun CloudRuntimeView.domain() = CloudRuntime(enabled, runnerImage, runnerBinary, serverUrl, workspaceAccessMode)

internal fun Route.cloudRoutes(sessions: CloudSessionService, admin: CloudAdministrationService, origin: String, runtime: CloudRuntimeService? = null) {
    route("/api/v1/cloud") {
        if (runtime != null) {
            get("/runtime") { call.respond(runtime.get(call.principal).view(runtime)) }
            put("/runtime") {
                val input = call.jsonBody<CloudRuntimeView>()
                call.respond(runtime.save(call.principal, input.domain(), input.updatedAt).view(runtime))
            }
        }
        get("/settings") { call.respond(admin.settings(call.principal).view()) }
        put("/settings") {
            val input = call.jsonBody<CloudSettingsView>()
            call.respond(admin.saveSettings(call.principal, input.domain(admin.settings(call.principal))).view())
        }

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
            // Room for an initial message of 32,000 characters.
            val input = call.jsonBody<CreateCloudSessionRequest>(256 * 1024)
            val canonical = Serialization.json.encodeToString(input.copy(labels = input.labels.toSortedMap())) + "\n" + (onBehalfOf ?: call.principal.member.id)
            val draft = CloudSessionDraft(input.name, input.profileId, input.image, input.agent?.model, input.agent?.effort, input.labels, input.idleTimeoutMinutes,
                input.environment?.let { CloudEnvironmentOverride(it.repositories?.map { repository -> repository.domain() }, it.workingDirectory) }, input.initialMessage)
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
        val clusters = admin.clusters(actor)
        val sections = runtime?.let { runtimeForm(actor, it.get(actor), it.serverUrl(CloudRuntime())) }.orEmpty() +
            clustersSection(actor, clusters) + profilesSection(admin.profiles(actor), clusters) +
            """<h2>GitHub</h2><p>The team's GitHub App, which gives each member's cloud sessions their own Git access, is configured on <a href="/dashboard/github">GitHub</a>.</p><h2>Retention, limits and transfers</h2>"""
        call.respondText(call.page("Cloud sessions", sections + """<details class='help'><summary>How this works</summary><p>Paused sessions keep their workspace volume until the retention below expires; then the workspace claim and its provider volume are deleted and the session is terminated. Inactivity only pauses a session; it never deletes it. A change applies to sessions paused afterwards.</p></details><form method="post">${csrf(actor)}<label>Paused retention in days (empty keeps paused workspaces indefinitely) <input type="number" name="pausedRetentionDays" min="1" max="3650" value="${settings.pausedRetentionDays ?: ""}"></label><label>Failed workspace retention in days <input type="number" name="failedWorkspaceRetentionDays" min="0" max="365" value="${settings.failedWorkspaceRetentionDays}" required></label><label>Running sessions per member <input type="number" name="maxRunning" min="1" max="100" value="${settings.maxRunningSessionsPerMember}" required></label><label>Retained sessions per member <input type="number" name="maxRetained" min="1" max="1000" value="${settings.maxRetainedSessionsPerMember}" required></label><label>Transcript retention in days (empty keeps transcripts indefinitely; counted from each event, independently of pods and workspaces) <input type="number" name="transcriptRetentionDays" min="1" max="3650" value="${settings.transcriptRetentionDays ?: ""}"></label><fieldset><legend>Transfers</legend><p>Administrators can transfer any session. A session changes owner only once it is paused: a running session drains and checkpoints first, its pod is deleted, and the next pod runs with the recipient's credentials.</p><label>Members may offer their own sessions <select name="memberTransfers">${option("offer", "Yes, the recipient accepts", settings.memberTransfers.wire)}${option("disabled", "No, administrators only", settings.memberTransfers.wire)}</select></label><label>Days to accept an offer <input type="number" name="transferOfferDays" min="1" max="30" value="${settings.transferOfferDays}" required></label><label>Unconsumed messages at the change of owner <select name="transferPendingInputs">${option("cancel", "Cancel them", settings.transferPendingInputs.wire)}${option("keep", "Keep them for the recipient's agent", settings.transferPendingInputs.wire)}</select></label></fieldset><button>Save settings</button></form>"""), ContentType.Text.Html)
    }
    if (runtime != null) post("/dashboard/cloud/runtime") {
        val input = call.form(origin.trimEnd('/'))
        runtime.save(call.principal, CloudRuntime(enabled = input["enabled"] == "on", runnerImage = input["runnerImage"], runnerBinary = input["runnerBinary"].orEmpty(),
            serverUrl = input["serverUrl"], workspaceAccessMode = input["workspaceAccessMode"].orEmpty()),
            input["updatedAt"]?.takeIf { it.isNotBlank() }?.let { it.toLongOrNull() ?: -1 })
        call.respondRedirect("/dashboard/cloud")
    }
    post("/dashboard/cloud/clusters") {
        val input = call.form(origin.trimEnd('/'), limit = CLUSTER_FORM_BYTES)
        admin.saveCluster(call.principal, null, null, clusterInput(input))
        call.respondRedirect("/dashboard/cloud")
    }
    post("/dashboard/cloud/clusters/{id}") {
        val input = call.form(origin.trimEnd('/'), limit = CLUSTER_FORM_BYTES)
        admin.saveCluster(call.principal, call.id(), input["version"]?.toIntOrNull() ?: throw TeamFailure("invalid_cluster", 400, "Send the current cluster version."), clusterInput(input))
        call.respondRedirect("/dashboard/cloud")
    }
    post("/dashboard/cloud") {
        val input = call.form(origin.trimEnd('/'))
        fun number(name: String) = input[name]?.toIntOrNull() ?: -1
        // A form from before the transfer settings existed keeps the stored transfer policy.
        val current = admin.settings(call.principal)
        admin.saveSettings(call.principal, CloudSettings(input["pausedRetentionDays"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: -1 },
            number("failedWorkspaceRetentionDays"), number("maxRunning"), number("maxRetained"),
            input["transcriptRetentionDays"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: -1 },
            input["memberTransfers"]?.let { MemberTransfers.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Choose whether members may offer sessions.") } ?: current.memberTransfers,
            input["transferOfferDays"]?.let { it.toIntOrNull() ?: -1 } ?: current.transferOfferDays,
            input["transferPendingInputs"]?.let { TransferPendingInputs.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Choose what happens to unconsumed messages.") } ?: current.transferPendingInputs))
        call.respondRedirect("/dashboard/cloud")
    }
}
private fun option(value: String, label: String, selected: String) = """<option value="$value"${if (value == selected) " selected" else ""}>$label</option>"""
private fun SaveCloudClusterRequest.domain() = CloudClusterInput(name, apiUrl, caCertificate, serviceAccountToken, allowedNamespaces, maxRunningPods, defaultStorageClass)

/** A CA bundle (up to 64 KiB) and a token (up to 16 KiB), form-encoded. */
private const val CLUSTER_FORM_BYTES = 256 * 1024
private const val KEPT = " (defined; leave empty to keep it, or paste a replacement)"

private fun runtimeForm(actor: Principal, runtime: CloudRuntime, publicOrigin: String): String {
    val state = if (runtime.enabled) "<span class=\"chip\">on</span>" else "<span class=\"chip danger\">off</span>"
    return "<h2>Cloud sessions $state</h2>" + help("What this controls", "<p>While cloud sessions are off, members see no cloud page and clients are told the server has no cloud sessions, so none is created, resumed or messaged. Sessions that already exist keep being managed (idle pause, retention), and a running agent is never stopped by this switch. Every replica applies a change within a few seconds, without a restart. Turning them on needs a runner image: an image holding the <code>teamcode</code> Linux binary and <code>cp</code>, copied into each session pod. Pin it by digest (<code>image@sha256:...</code>).</p>") +
        """<form action="/dashboard/cloud/runtime" method="post">${csrf(actor)}<input type="hidden" name="updatedAt" value="${runtime.updatedAt ?: ""}"><label><input type="checkbox" name="enabled"${if (runtime.enabled) " checked" else ""}> Cloud sessions are on</label>""" +
        """<label>Runner image <input name="runnerImage" maxlength="512" placeholder="registry.example.com/teamcode-runner@sha256:..." value="${escape(runtime.runnerImage ?: "")}"></label>""" +
        """<label>Path of the binary in the runner image <input name="runnerBinary" maxlength="256" required value="${escape(runtime.runnerBinary)}"></label>""" +
        """<label>Server URL for pods (empty uses the public URL, ${escape(publicOrigin)}) <input name="serverUrl" type="url" maxlength="512" placeholder="${escape(publicOrigin)}" value="${escape(runtime.serverUrl ?: "")}"></label>""" +
        """<label>Workspace access mode <select name="workspaceAccessMode">${option("ReadWriteOncePod", "ReadWriteOncePod (one pod per workspace, enforced by the cluster)", runtime.workspaceAccessMode)}${option("ReadWriteOnce", "ReadWriteOnce (storage drivers without ReadWriteOncePod)", runtime.workspaceAccessMode)}</select></label><button>Save cloud sessions</button></form>"""
}

private fun clusterFields(cluster: CloudCluster?): String =
    """<label>Name <input name="name" maxlength="120" required value="${escape(cluster?.name ?: "")}"></label>""" +
        """<label>API URL <input name="apiUrl" type="url" maxlength="512" placeholder="https://kubernetes.example.com:6443" required value="${escape(cluster?.apiUrl ?: "")}"></label>""" +
        """<label>CA bundle (PEM)${if (cluster != null) KEPT else ""}<br><textarea name="caCertificate" rows="4" cols="70" autocomplete="off"${if (cluster == null) " required" else ""}></textarea></label>""" +
        """<label>Service-account token${if (cluster != null) KEPT else ""} <input type="password" name="serviceAccountToken" autocomplete="off" maxlength="16384"${if (cluster == null) " required" else ""}></label>""" +
        """<label>Allowed namespaces (comma-separated) <input name="allowedNamespaces" maxlength="2048" required value="${escape(cluster?.allowedNamespaces?.joinToString(", ") ?: "")}"></label>""" +
        """<label>Running pods at most <input type="number" name="maxRunningPods" min="1" max="1000" required value="${cluster?.maxRunningPods ?: 10}"></label>""" +
        """<label>Default storage class (empty uses the cluster default) <input name="defaultStorageClass" maxlength="253" value="${escape(cluster?.defaultStorageClass ?: "")}"></label>"""

private fun clustersSection(actor: Principal, clusters: List<CloudCluster>): String {
    val rows = clusters.joinToString("") { cluster ->
        """<tr><td><strong>${escape(cluster.name)}</strong><br><small>${escape(cluster.apiUrl)}</small></td><td>${escape(cluster.allowedNamespaces.joinToString(", "))}</td><td>${cluster.maxRunningPods}</td><td>Token and CA bundle defined</td><td><details><summary>Edit</summary><form action="/dashboard/cloud/clusters/${escape(cluster.id)}" method="post">${csrf(actor)}<input type="hidden" name="version" value="${cluster.version}">${clusterFields(cluster)}<button>Save cluster</button></form></details></td></tr>"""
    }
    val table = if (clusters.isEmpty()) "<div class='empty'><strong>No cluster is connected.</strong></div>"
        else "<table><thead><tr><th>Cluster</th><th>Namespaces</th><th>Pods</th><th>Credentials</th><th></th></tr></thead><tbody>$rows</tbody></table>"
    return "<h2>Kubernetes clusters</h2>" + help("What the service account needs", "<p>In each allowed namespace: <code>get</code>, <code>list</code>, <code>create</code> and <code>delete</code> on <code>pods</code> and <code>persistentvolumeclaims</code>; cluster-wide <code>get</code> and <code>list</code> on <code>persistentvolumes</code> and <code>storageclasses</code>; and <code>system:auth-delegator</code> for <code>TokenReview</code>. The token is stored encrypted; neither it nor the CA bundle is shown again.</p>") +
        table + """<details><summary>Connect a cluster</summary><form action="/dashboard/cloud/clusters" method="post">${csrf(actor)}${clusterFields(null)}<button>Connect cluster</button></form></details>"""
}

private fun profilesSection(profiles: List<CloudProfile>, clusters: List<CloudCluster>): String {
    val names = clusters.associate { it.id to it.name }
    val rows = profiles.joinToString("") { profile ->
        """<tr><td><strong>${escape(profile.name)}</strong> <small>v${profile.version}</small></td><td>${escape(names[profile.spec.clusterId] ?: profile.spec.clusterId)} / ${escape(profile.spec.namespace)}</td><td><code>${escape(profile.spec.image)}</code></td><td>${profile.assignedMemberIds.size}</td></tr>"""
    }
    return "<h2>Profiles</h2><p>A profile says where and how a session runs: cluster, namespace, image, resources and environment. Profiles are versioned and edited with <code>POST</code> and <code>PUT /api/v1/cloud/profiles</code>.</p>" +
        (if (profiles.isEmpty()) "<div class='empty'><strong>No profile yet.</strong></div>" else "<table><thead><tr><th>Profile</th><th>Cluster / namespace</th><th>Image</th><th>Members</th></tr></thead><tbody>$rows</tbody></table>")
}

/** Empty credential fields keep the stored ones. */
private fun clusterInput(input: Parameters) = CloudClusterInput(input["name"].orEmpty().trim(), input["apiUrl"].orEmpty().trim(),
    input["caCertificate"]?.trim()?.takeIf { it.isNotEmpty() }, input["serviceAccountToken"]?.trim()?.takeIf { it.isNotEmpty() },
    input["allowedNamespaces"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }, input["maxRunningPods"]?.trim()?.toIntOrNull() ?: -1,
    input["defaultStorageClass"]?.trim()?.takeIf { it.isNotEmpty() })
