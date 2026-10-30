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
/**
 * A remote session has no profile or image: it carries the reserved [REMOTE_PLACEHOLDER] in their place, so a client
 * from before remote sessions still decodes it, and a workspace deletion that does not apply (the server never touches
 * the member's directory).
 */
internal fun CloudSession.view() = CloudSessionView(
    id = id, name = name, ownerId = ownerId, createdBy = CloudAttributionView(createdBy.initiatorId, createdBy.credentialId, createdBy.effectiveMemberId),
    state = state.wire, phase = phase, desiredState = desiredState.wire, generation = generation, currentEpoch = currentEpoch,
    profile = if (kind == CloudSessionKind.REMOTE) CloudProfileRef(REMOTE_PLACEHOLDER, 0) else CloudProfileRef(profileId, profileVersion),
    image = if (kind == CloudSessionKind.REMOTE) CloudImageView(REMOTE_PLACEHOLDER) else CloudImageView(imageReference, imageDigest),
    agent = CloudAgentView(agentKind, agentVersion, agentModel, agentEffort), labels = labels,
    activity = CloudActivityView(lastActivityAt, idleTimeoutMinutes), resume = CloudResumeView(resumeWorkspace.wire, resumeConversation.wire),
    failure = failure?.view(), resumeBlocked = resumeBlocked?.view(),
    workspaceDeletion = if (kind == CloudSessionKind.REMOTE) CloudWorkspaceDeletionView(NOT_APPLICABLE)
        else CloudWorkspaceDeletionView(workspace.deletion.wire, workspace.claimDeletedAt, workspace.volumeDeletedAt, workspace.volumeHandle, workspace.deletionProblem?.view()),
    createdAt = createdAt, updatedAt = updatedAt, pausedAt = pausedAt, expiresAt = expiresAt, terminatedReason = terminatedReason, transferId = transferId,
    environment = environment.view(), kind = kind.wire,
    host = host?.let { CloudSessionHostView(it.deviceId, it.device?.name, it.device?.platform, it.device?.osVersion, it.directoryName, it.online, it.lastHeartbeatAt, it.permissionMode.wire, it.folderId) },
    waiting = waitingForHuman?.let { CloudWaitingView(it.reason.wire, it.requestId, it.since, it.eventSeq) },
    githubAccess = githubAccess.wire.takeIf { kind != CloudSessionKind.REMOTE },
)
/** The profile id and image reference of a remote session, which has neither. */
internal const val REMOTE_PLACEHOLDER = "remote"
/** The workspace deletion state of a remote session: its directory is the member's, never deleted by the server. */
internal const val NOT_APPLICABLE = "not_applicable"
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
private fun CloudProfile.view(clusterName: String? = null) = CloudProfileView(id, name, version, spec.view(), assignedMemberIds.sorted(), createdAt, updatedAt, clusterName)
private fun CloudSettings.view() = CloudSettingsView(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays,
    memberTransfers.wire, transferOfferDays, transferPendingInputs.wire, permissionDeadlineMinutes, remoteIdleTimeoutMinutes, fileMaxMebibytes, messageFileMebibytes,
    backgroundWorkMaxMinutes, maxAutomationsPerMember, maxRunningAutomationsPerMember)
/** An absent transfer or permission deadline field keeps [current]'s value. */
private fun CloudSettingsView.domain(current: CloudSettings) = CloudSettings(pausedRetentionDays, failedWorkspaceRetentionDays, maxRunningSessionsPerMember, maxRetainedSessionsPerMember, transcriptRetentionDays,
    memberTransfers?.let { MemberTransfers.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Use memberTransfers offer or disabled.") } ?: current.memberTransfers,
    transferOfferDays ?: current.transferOfferDays,
    transferPendingInputs?.let { TransferPendingInputs.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Use transferPendingInputs cancel or keep.") } ?: current.transferPendingInputs,
    permissionDeadlineMinutes ?: current.permissionDeadlineMinutes, remoteIdleTimeoutMinutes ?: current.remoteIdleTimeoutMinutes,
    fileMaxMebibytes ?: current.fileMaxMebibytes, messageFileMebibytes ?: current.messageFileMebibytes,
    backgroundWorkMaxMinutes ?: current.backgroundWorkMaxMinutes,
    maxAutomationsPerMember ?: current.maxAutomationsPerMember, maxRunningAutomationsPerMember ?: current.maxRunningAutomationsPerMember)

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

/**
 * Requests that start new cloud work, refused with `404` while the stored runtime is off: creating, resuming, messaging,
 * attaching to and transferring sessions, and starting a GitHub link. Everything that reads, stops or removes stays
 * available (pause, interrupt, delete, transcripts, unlinking), as do configuration and the runner channel of pods that
 * already run, so turning cloud sessions off never strands a running agent. The path is read the way routing matches it
 * (empty segments skipped, each segment percent-decoded); a segment that cannot be decoded is refused.
 */
internal fun cloudStartsWork(method: String, path: String): Boolean {
    // Most requests (inference included) are outside /api and /dashboard: skip the segment parse for them.
    val first = path.trimStart('/').substringBefore('/')
    if (first != "api" && first != "dashboard" && '%' !in first) return false
    // null marks a segment that cannot be decoded: it matches anything, so a malformed spelling of a gated route is
    // refused, while malformed paths elsewhere are left to routing.
    val segments = path.split('/').filter { it.isNotEmpty() }.map { segment -> try { segment.decodeURLPart() } catch (_: Exception) { null } }
    fun matches(vararg pattern: String) = segments.size == pattern.size && pattern.indices.all { pattern[it] == "*" || segments[it] == null || segments[it] == pattern[it] }
    fun under(vararg prefix: String) = segments.size > prefix.size && prefix.indices.all { segments[it] == null || segments[it] == prefix[it] }
    return (method == "POST" && WORK_ROUTES.any { matches(*it) }) || (method == "POST" && matches("api", "v1", "github", "link", "device")) ||
        matches(*GITHUB_LINK_PATH.split('/').filter { it.isNotEmpty() }.toTypedArray())
}

/** Every `POST` that starts or keeps up new cloud work; reads, pauses, interrupts and deletions are not listed on purpose. */
private val WORK_ROUTES = listOf(
    arrayOf("api", "v1", "cloud", "sessions"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "resume"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "host"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "messages"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "files"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "attachments"),
    arrayOf("api", "v1", "cloud", "sessions", "*", "transfer"),
    arrayOf("api", "v1", "cloud", "transfers", "*", "accept"),
    // Arming an automation and a real test start runs; a dry run is refused too while sessions are off.
    arrayOf("api", "v1", "automations", "*", "enable"),
    arrayOf("api", "v1", "automations", "*", "run"),
)

private fun CloudRuntime.view(runtime: CloudRuntimeService) = CloudRuntimeView(enabled, runnerImage, runnerBinary, serverUrl, workspaceAccessMode, runtime.serverUrl(this), updatedAt)
private fun CloudRuntimeView.domain() = CloudRuntime(enabled, runnerImage, runnerBinary, serverUrl, workspaceAccessMode)

private fun ClusterListing<*>.wire() = when (this) { is ClusterListing.Listed -> "listed"; ClusterListing.Forbidden -> "forbidden"; ClusterListing.Unavailable -> "unavailable" }
internal fun ClusterChoices.view() = CloudClusterChoicesView(cluster.id, namespace, namespaces.map { CloudNamespaceChoiceView(it.name, it.status.wire) }, cluster.defaultStorageClass,
    CloudStorageClassChoicesView(storageClasses.wire(), (storageClasses as? ClusterListing.Listed)?.items.orEmpty().map {
        CloudStorageClassChoiceView(it.name, it.reclaimPolicy, it.isDefault, it.keepsVolumes, it.provisioner)
    }),
    CloudImagePullSecretChoicesView(imagePullSecrets.wire(), (imagePullSecrets as? ClusterListing.Listed)?.items.orEmpty()))

/** [team] lists the members a profile can be assigned to on the dashboard. */
internal fun Route.cloudRoutes(sessions: CloudSessionService, admin: CloudAdministrationService, origin: String, runtime: CloudRuntimeService? = null, team: TeamService? = null) {
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

        get("/clusters/{id}/choices") { call.respond(admin.choices(call.principal, call.id(), call.request.queryParameters["namespace"]).view()) }

        get("/profiles") {
            val member = call.request.queryParameters.getAll("member")?.let { it.singleOrNull() ?: throw TeamFailure("invalid_query", 400, "Send member at most once.") }
            val profiles = admin.profiles(call.principal, member)
            val clusters = admin.clusterNames(call.principal, profiles)
            call.respond(profiles.map { it.view(clusters[it.spec.clusterId]) })
        }
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
            val idempotency = IdempotencyRequest("POST /cloud/sessions", key, sha256(canonical))
            val outcome = when (input.kind ?: CloudSessionKind.CLOUD.wire) {
                CloudSessionKind.CLOUD.wire -> {
                    if (input.directoryName != null || input.permissionMode != null || input.agent?.kind != null || input.deviceId != null || input.folderId != null || input.resumeSessionId != null)
                        throw TeamFailure("invalid_session", 400, "A cloud session takes its agent from its profile and has no directory or permission mode.")
                    val draft = CloudSessionDraft(input.name, input.profileId ?: throw TeamFailure("invalid_session", 400, "Send the profileId of a cloud session."),
                        input.image, input.agent?.model, input.agent?.effort, input.labels, input.idleTimeoutMinutes,
                        input.environment?.let { CloudEnvironmentOverride(it.repositories?.map { repository -> repository.domain() }, it.workingDirectory) }, input.initialMessage)
                    sessions.create(call.principal, onBehalfOf, draft, idempotency)
                }
                CloudSessionKind.REMOTE.wire -> {
                    if (input.profileId != null || input.image != null || input.idleTimeoutMinutes != null || input.environment != null)
                        throw TeamFailure("invalid_session", 400, "A remote session has no profile, image, idle timeout or environment: it runs in a directory of the caller's device.")
                    val mode = input.permissionMode?.let { RemotePermissionMode.parse(it) ?: throw TeamFailure("invalid_permission_mode", 400, "Use the permission mode ask or bypass.") }
                        ?: RemotePermissionMode.ASK
                    if (input.deviceId == null && input.directoryName == null) throw TeamFailure("invalid_directory_name", 400, "Send the name of the session's directory.")
                    val draft = RemoteSessionDraft(input.name, input.agent?.kind ?: throw TeamFailure("invalid_agent", 400, "Send the agent of a remote session: claude or codex."),
                        input.agent?.model, input.agent?.effort, input.labels, input.directoryName, mode, input.initialMessage, input.deviceId, input.folderId, input.resumeSessionId)
                    sessions.createRemote(call.principal, onBehalfOf, draft, idempotency)
                }
                else -> throw TeamFailure("invalid_kind", 400, "Use kind cloud or remote.")
            }
            when (outcome) {
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
            val labels = LabelRules.parseFilter(q.getAll("label").orEmpty())
            val owner = single("owner")?.let { if (it == "all") "*" else it }
            val limit = single("limit")?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 50.") } ?: 50
            // Without `kind`, cloud sessions only: a client from before remote sessions never lists one it cannot show.
            val kinds = q.getAll("kind")?.map { value -> CloudSessionKind.entries.find { it.wire == value } ?: throw TeamFailure("invalid_kind", 400, "Use kind=cloud or kind=remote.") }?.toSet()
                ?: setOf(CloudSessionKind.CLOUD)
            val page = sessions.list(call.principal, CloudSessionQuery(owner, states, labels, single("before"), limit, kinds))
            call.respond(CloudSessionPageView(page.sessions.map { it.view() }, page.nextBeforeId))
        }
        get("/sessions/{id}") { call.respond(sessions.get(call.principal, call.id()).view()) }
        get("/sessions/{id}/executions") { call.respond(sessions.executions(call.principal, call.id()).map { it.view() }) }
        post("/sessions/{id}/pause") {
            val input = if (call.request.contentType().withoutParameters() == ContentType.Application.Json) call.jsonBody<PauseCloudSessionRequest>() else PauseCloudSessionRequest()
            call.respond(HttpStatusCode.Accepted, sessions.pause(call.principal, call.onBehalfOf(), call.id(), input.force).view())
        }
        put("/sessions/{id}/github") {
            val input = call.jsonBody<CloudGitHubAccessRequest>()
            val access = CloudGitHubAccess.parse(input.access) ?: throw TeamFailure("invalid_github_access", 400, "Use access owner, shared or none.")
            call.respond(sessions.setGitHubAccess(call.principal, call.onBehalfOf(), call.id(), access).view())
        }
        // A remote session's host (teamcode remote) starts its next execution here, from the session's device.
        post("/sessions/{id}/host") {
            val session = sessions.host(call.principal, call.id())
            call.respond(CloudHostView(session.teamId, session.currentEpoch, session.view()))
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
        val sections = runtime?.let { runtimeForm(actor, it.get(actor), it.publicOrigin) }.orEmpty() +
            clustersSection(actor, clusters, admin.kubeconfigImports != null) + profilesSection(admin.profiles(actor), clusters,
                call.request.queryParameters["published"]?.toIntOrNull()) +
            """<h2>GitHub</h2><p>The team's GitHub App, which gives each member's cloud sessions their own Git access, is configured on <a href="/dashboard/github">GitHub</a>.</p><h2>Retention, limits and transfers</h2>"""
        call.respondText(call.page("Cloud sessions", sections + """<details class='help'><summary>How this works</summary><p>Paused sessions keep their workspace volume until the retention below expires; then the workspace claim and its provider volume are deleted and the session is terminated. Inactivity only pauses a session; it never deletes it. A change applies to sessions paused afterwards.</p></details><form method="post">${csrf(actor)}<label>Paused retention in days (empty keeps paused workspaces indefinitely) <input type="number" name="pausedRetentionDays" min="1" max="3650" value="${settings.pausedRetentionDays ?: ""}"></label><label>Failed workspace retention in days <input type="number" name="failedWorkspaceRetentionDays" min="0" max="365" value="${settings.failedWorkspaceRetentionDays}" required></label><label>Running sessions per member <input type="number" name="maxRunning" min="1" max="100" value="${settings.maxRunningSessionsPerMember}" required></label><label>Retained sessions per member <input type="number" name="maxRetained" min="1" max="1000" value="${settings.maxRetainedSessionsPerMember}" required></label><label>Transcript retention in days (empty keeps transcripts indefinitely; counted from each event, independently of pods and workspaces) <input type="number" name="transcriptRetentionDays" min="1" max="3650" value="${settings.transcriptRetentionDays ?: ""}"></label><fieldset><legend>Transfers</legend><p>Administrators can transfer any session. A session changes owner only once it is paused: a running session drains and checkpoints first, its pod is deleted, and the next pod runs with the recipient's credentials.</p><label>Members may offer their own sessions <select name="memberTransfers">${option("offer", "Yes, the recipient accepts", settings.memberTransfers.wire)}${option("disabled", "No, administrators only", settings.memberTransfers.wire)}</select></label><label>Days to accept an offer <input type="number" name="transferOfferDays" min="1" max="30" value="${settings.transferOfferDays}" required></label><label>Unconsumed messages at the change of owner <select name="transferPendingInputs">${option("cancel", "Cancel them", settings.transferPendingInputs.wire)}${option("keep", "Keep them for the recipient's agent", settings.transferPendingInputs.wire)}</select></label></fieldset><fieldset><legend>Permission requests</legend><p>A cloud agent waits for a person when a tool use matches an <code>ask</code> permission rule or when it asks the user a question. Without a deadline it waits until someone answers, interrupts the turn or pauses the session. With one, the runner denies a request nobody answered in time and the agent reads why. A change applies to sessions started or resumed afterwards.</p><label>Deny unanswered requests after, in minutes (empty or 0: no deadline) <input type="number" name="permissionDeadlineMinutes" min="0" max="${CloudConversationRules.MAX_PERMISSION_DEADLINE_MINUTES}" value="${settings.permissionDeadlineMinutes.takeIf { it > 0 } ?: ""}"></label></fieldset><fieldset><legend>Remote sessions</legend><p>A remote session runs on a member's own device, in a directory they chose. With no turn, message or attached client for this long, it pauses: its host drains the turn, checkpoints and exits, and the next message wakes it on that device. Pausing never deletes anything.</p><label>Pause idle remote sessions after, in minutes (0: never) <input type="number" name="remoteIdleTimeoutMinutes" min="0" max="${CloudIdleRules.MAX_TIMEOUT_MINUTES}" value="${settings.remoteIdleTimeoutMinutes}" required></label></fieldset><fieldset><legend>Background work</legend><p>A session that is idle is paused after its idle timeout, which stops its pod. Background work of its agent (subagents, background commands, monitors and scheduled wake-ups) keeps it running, but only for this long after that work started, so work that never ends cannot keep a session running forever; the idle timeout then runs as usual.</p><label>Background work keeps a session running for at most, in minutes (0: never) <input type="number" name="backgroundWorkMaxMinutes" min="0" max="${CloudIdleRules.MAX_TIMEOUT_MINUTES}" value="${settings.backgroundWorkMaxMinutes}" required></label></fieldset><fieldset><legend>Automations</legend><p>An automation starts a session as its owner on a schedule or when its webhook is called. Its runs have their own cap, apart from the member's running sessions, and its sessions are outside the retained sessions above: each automation keeps those of its 10 latest runs.</p><label>Automations per member <input type="number" name="maxAutomationsPerMember" min="0" max="200" value="${settings.maxAutomationsPerMember}" required></label><label>Automation runs working at once per member <input type="number" name="maxRunningAutomationsPerMember" min="1" max="100" value="${settings.maxRunningAutomationsPerMember}" required></label></fieldset><fieldset><legend>Files</legend><p>Members can send images, PDFs and text files to their running sessions. Files wait in the team's artifact bucket until the session's host receives them, a day at most, and need that bucket set up and tested on the Artifacts page.</p><label>Largest file, in MiB <input type="number" name="fileMaxMebibytes" min="1" max="${CloudFileRules.MAX_MEBIBYTES}" value="${settings.fileMaxMebibytes}" required></label><label>Largest total per message, in MiB <input type="number" name="messageFileMebibytes" min="1" max="${CloudFileRules.MAX_MEBIBYTES}" value="${settings.messageFileMebibytes}" required></label></fieldset><button>Save settings</button></form>"""), ContentType.Text.Html)
    }
    get("/dashboard/assets/profile-editor.js") {
        call.principal.requireAdmin()
        call.respondText(PROFILE_SCRIPT, ContentType.parse("application/javascript"))
    }
    get("/dashboard/cloud/profiles/new") {
        val clusters = admin.clusters(call.principal)
        call.respondProfileEditor(admin, team, ProfileDraft.new(clusters.firstOrNull { it.id == call.request.queryParameters["cluster"] } ?: clusters.firstOrNull()), clusters, null)
    }
    get("/dashboard/cloud/profiles/{id}") {
        val profile = admin.profiles(call.principal).singleOrNull { it.id == call.id() } ?: throw TeamFailure("not_found", 404, "Profile not found.")
        call.respondProfileEditor(admin, team, ProfileDraft.of(profile), admin.clusters(call.principal), null)
    }
    // Every step of a draft posts the whole form: reloading the cluster's choices, checking, publishing. A refused
    // check or publication shows the form again with the reason, so nothing typed is lost.
    post("/dashboard/cloud/profiles/draft") {
        val actor = call.principal
        val input = call.form(origin.trimEnd('/'), limit = PROFILE_FORM_BYTES)
        val parsed = ProfileDraft.parse(input)
        // A storage class and pull secrets chosen on another cluster are not carried over to this one.
        val draft = if (input["choicesCluster"]?.let { it != parsed.spec.clusterId } == true)
            parsed.copy(spec = parsed.spec.copy(storageClass = null, imagePullSecrets = emptyList())) else parsed
        val members = draft.members
        val outcome = try {
            when (input["intent"]) {
                "publish" -> {
                    val published = admin.saveProfile(actor, draft.id, draft.version, draft.name, draft.spec, members)
                    return@post call.respondRedirect("/dashboard/cloud?published=${published.version}#profiles")
                }
                "check" -> {
                    admin.checkProfile(actor, draft.id, draft.version, draft.name, draft.spec, members)
                    DraftOutcome("The draft passes the profile checks (cluster, namespace, storage class, bounds, recipe). Publishing creates version ${draft.version + 1}.", problem = false)
                }
                else -> null
            }
        } catch (failure: TeamFailure) {
            if (failure.status == 401 || failure.status == 403) throw failure
            DraftOutcome(failure.message ?: "The draft was refused.", problem = true)
        }
        call.respondProfileEditor(admin, team, draft, admin.clusters(actor), outcome)
    }
    if (runtime != null) post("/dashboard/cloud/runtime") {
        val input = call.form(origin.trimEnd('/'))
        runtime.save(call.principal, CloudRuntime(enabled = input["enabled"] == "on", runnerImage = input["runnerImage"], runnerBinary = input["runnerBinary"].orEmpty(),
            serverUrl = input["serverUrl"], workspaceAccessMode = input["workspaceAccessMode"].orEmpty()),
            input["updatedAt"]?.takeIf { it.isNotBlank() }?.let { it.toLongOrNull() ?: -1 })
        call.respondRedirect("/dashboard/cloud")
    }
    admin.kubeconfigImports?.let { imports ->
        get("/dashboard/assets/kubeconfig-import.js") {
            call.principal.requireAdmin()
            call.respondText(KUBECONFIG_SCRIPT, ContentType.parse("application/javascript"))
        }
        // The server reads the kubeconfig: its CA and token go back to the browser only sealed, never in the page.
        post("/dashboard/cloud/clusters/import") {
            val actor = call.principal
            val offer = imports.offer(actor, call.form(origin.trimEnd('/'), limit = KUBECONFIG_FORM_BYTES)["kubeconfig"].orEmpty())
            val only = offer.contexts.singleOrNull()
            call.respondText(call.page("Import a kubeconfig", if (only != null) importedClusterForm(actor, offer.sealed, only) else contextPicker(actor, offer)), ContentType.Text.Html)
        }
        post("/dashboard/cloud/clusters/import/context") {
            val actor = call.principal
            val input = call.form(origin.trimEnd('/'), limit = KUBECONFIG_FORM_BYTES)
            val choice = imports.choose(actor, input["kubeconfigSealed"].orEmpty(), input["context"].orEmpty())
            call.respondText(call.page("Import a kubeconfig", importedClusterForm(actor, choice.sealed, choice.summary)), ContentType.Text.Html)
        }
    }
    post("/dashboard/cloud/clusters") {
        val input = call.form(origin.trimEnd('/'), limit = CLUSTER_FORM_BYTES)
        val imported = input["kubeconfigSealed"]?.let { sealed ->
            (admin.kubeconfigImports ?: throw TeamFailure("invalid_cluster", 400, "Kubeconfig import is not available.")).open(call.principal, sealed, input["kubeconfigContext"].orEmpty())
        }
        admin.saveCluster(call.principal, null, null, clusterInput(input, imported))
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
            input["transferPendingInputs"]?.let { TransferPendingInputs.parse(it) ?: throw TeamFailure("invalid_cloud_settings", 400, "Choose what happens to unconsumed messages.") } ?: current.transferPendingInputs,
            // Empty means no deadline; a form from before the deadline existed keeps the stored one.
            input["permissionDeadlineMinutes"]?.let { if (it.isBlank()) 0 else it.toIntOrNull() ?: -1 } ?: current.permissionDeadlineMinutes,
            input["remoteIdleTimeoutMinutes"]?.let { it.toIntOrNull() ?: -1 } ?: current.remoteIdleTimeoutMinutes,
            input["fileMaxMebibytes"]?.let { it.toIntOrNull() ?: -1 } ?: current.fileMaxMebibytes,
            input["messageFileMebibytes"]?.let { it.toIntOrNull() ?: -1 } ?: current.messageFileMebibytes,
            input["backgroundWorkMaxMinutes"]?.let { it.toIntOrNull() ?: -1 } ?: current.backgroundWorkMaxMinutes,
            input["maxAutomationsPerMember"]?.let { it.toIntOrNull() ?: -1 } ?: current.maxAutomationsPerMember,
            input["maxRunningAutomationsPerMember"]?.let { it.toIntOrNull() ?: -1 } ?: current.maxRunningAutomationsPerMember))
        call.respondRedirect("/dashboard/cloud")
    }
}
private fun option(value: String, label: String, selected: String) = """<option value="$value"${if (value == selected) " selected" else ""}>$label</option>"""
private fun SaveCloudClusterRequest.domain() = CloudClusterInput(name, apiUrl, caCertificate, serviceAccountToken, allowedNamespaces, maxRunningPods, defaultStorageClass)

/** Two lists of 16 setup commands of 4,096 characters, percent-encoded, and the rest of a profile. */
private const val PROFILE_FORM_BYTES = 512 * 1024
/** A CA bundle (up to 64 KiB) and a token (up to 16 KiB), form-encoded. */
private const val CLUSTER_FORM_BYTES = 256 * 1024
/** A kubeconfig of 128 K characters, percent-encoded (up to three bytes per character), or its sealed copy. */
private const val KUBECONFIG_FORM_BYTES = 512 * 1024
private const val KEPT = " (defined; leave empty to keep it, or paste a replacement)"

private fun runtimeForm(actor: Principal, runtime: CloudRuntime, publicOrigin: String): String {
    val state = if (runtime.enabled) "<span class=\"chip\">on</span>" else "<span class=\"chip danger\">off</span>"
    return "<h2>Cloud sessions $state</h2>" + help("What this controls", "<p>While cloud sessions are off, members see no cloud page and clients are told the server has no cloud sessions, so none is created, resumed or messaged. Sessions that already exist keep being managed (idle pause, retention) and can still be paused, interrupted or deleted through the API; a running agent is never stopped by this switch itself. Every replica applies a change within a few seconds, without a restart. Turning them on needs a runner image: an image holding the <code>teamcode</code> Linux binary and <code>cp</code>, copied into each session pod. Pin it by digest (<code>image@sha256:...</code>).</p>") +
        """<form action="/dashboard/cloud/runtime" method="post">${csrf(actor)}<input type="hidden" name="updatedAt" value="${runtime.updatedAt ?: ""}"><label><input type="checkbox" name="enabled"${if (runtime.enabled) " checked" else ""}> Cloud sessions are on</label>""" +
        """<label>Runner image <input name="runnerImage" maxlength="512" placeholder="registry.example.com/teamcode-runner@sha256:..." value="${escape(runtime.runnerImage ?: "")}"></label>""" +
        """<label>Path of the binary in the runner image <input name="runnerBinary" maxlength="256" required value="${escape(runtime.runnerBinary)}"></label>""" +
        """<label>Server URL for pods (empty uses the public URL, ${escape(publicOrigin)}) <input name="serverUrl" type="url" maxlength="512" placeholder="${escape(publicOrigin)}" value="${escape(runtime.serverUrl ?: "")}"></label>""" +
        """<label>Workspace access mode <select name="workspaceAccessMode">${option("ReadWriteOncePod", "ReadWriteOncePod (one pod per workspace, enforced by the cluster)", runtime.workspaceAccessMode)}${option("ReadWriteOnce", "ReadWriteOnce (storage drivers without ReadWriteOncePod)", runtime.workspaceAccessMode)}</select></label><button>Save cloud sessions</button></form>"""
}

private fun clusterFields(cluster: CloudCluster?, imported: KubeconfigContextSummary? = null): String {
    val fromKubeconfig = " (from the kubeconfig; leave empty to use it, or paste a replacement)"
    fun hint(hasValue: Boolean) = if (cluster != null) KEPT else if (hasValue) fromKubeconfig else ""
    val caRequired = cluster == null && imported?.hasCaCertificate != true
    val tokenRequired = cluster == null && imported?.hasToken != true
    return """<label>Name <input name="name" maxlength="120" required value="${escape(cluster?.name ?: imported?.name ?: "")}"></label>""" +
        """<label>API URL <input name="apiUrl" type="url" maxlength="512" placeholder="https://kubernetes.example.com:6443" required value="${escape(cluster?.apiUrl ?: imported?.apiUrl ?: "")}"></label>""" +
        """<label>CA bundle (PEM)${hint(imported?.hasCaCertificate == true)}<br><textarea name="caCertificate" rows="4" cols="70" autocomplete="off"${if (caRequired) " required" else ""}></textarea></label>""" +
        """<label>Service-account token${hint(imported?.hasToken == true)} <input type="password" name="serviceAccountToken" autocomplete="off" maxlength="16384"${if (tokenRequired) " required" else ""}></label>""" +
        """<label>Allowed namespaces (comma-separated) <input name="allowedNamespaces" maxlength="2048" required value="${escape(cluster?.allowedNamespaces?.joinToString(", ") ?: imported?.namespace ?: "")}"></label>""" +
        """<label>Running pods at most <input type="number" name="maxRunningPods" min="1" max="1000" required value="${cluster?.maxRunningPods ?: 10}"></label>""" +
        """<label>Default storage class (empty uses the cluster default) <input name="defaultStorageClass" maxlength="253" value="${escape(cluster?.defaultStorageClass ?: "")}"></label>"""
}

private fun importedClusterForm(actor: Principal, sealed: String, context: KubeconfigContextSummary) =
    """<p>Context <code>${escape(context.name)}</code> of your kubeconfig. Review the fields, then connect the cluster. The CA bundle and the token stay on the server, encrypted, and are not shown here.</p>""" +
        """<form action="/dashboard/cloud/clusters" method="post">${csrf(actor)}<input type="hidden" name="kubeconfigSealed" value="${escape(sealed)}"><input type="hidden" name="kubeconfigContext" value="${escape(context.name)}">${clusterFields(null, context)}<button>Connect cluster</button></form>"""

private fun contextPicker(actor: Principal, offer: KubeconfigOffer): String {
    val choices = offer.contexts.joinToString("") { context ->
        val usable = context.unsupported == null
        val detail = if (usable) escape(context.apiUrl.orEmpty()) + (context.namespace?.let { " · namespace ${escape(it)}" } ?: "") else "<span class=\"hint\">Cannot be imported: ${escape(context.unsupported.orEmpty())}</span>"
        """<label class="choice"><input type="radio" name="context" value="${escape(context.name)}"${if (context.name == offer.defaultContext) " checked" else ""}${if (usable) "" else " disabled"}><span><strong>${escape(context.name)}</strong>${if (context.name == offer.defaultContext) " <span class=\"chip\">current context</span>" else ""}<br><small>$detail</small></span></label>"""
    }
    return """<p>This kubeconfig has several contexts. Choose the one to connect; the current context of the file is selected.</p><form action="/dashboard/cloud/clusters/import/context" method="post">${csrf(actor)}<input type="hidden" name="kubeconfigSealed" value="${escape(offer.sealed)}">$choices<button>Continue</button></form>"""
}

private fun importSection(actor: Principal): String =
    """<details><summary>Import a kubeconfig</summary><p>Drop a kubeconfig file, choose one, or paste its contents. TeamCode reads it on the server and prefills the cluster form from the chosen context: API URL, CA bundle, token and namespace. The CA bundle and the token are encrypted like a manual entry and are never sent back to the browser. Kubeconfigs that use an <code>exec</code> or <code>auth-provider</code> plugin, a client certificate, or files referenced by path are refused with the reason; embed the data with <code>kubectl config view --raw --flatten --minify</code>, or connect the cluster by hand.</p>""" +
        """<form action="/dashboard/cloud/clusters/import" method="post" id="kubeconfig-import">${csrf(actor)}<div id="kubeconfig-drop" class="drop" hidden><strong>Drop a kubeconfig here</strong> <label class="pick">or choose a file <input type="file" id="kubeconfig-file" hidden></label><br><small id="kubeconfig-status" role="status"></small></div>""" +
        """<label>Kubeconfig contents <textarea name="kubeconfig" id="kubeconfig-text" rows="6" cols="70" maxlength="${Kubeconfig.MAX_CHARACTERS}" autocomplete="off" spellcheck="false" required></textarea></label><button>Read kubeconfig</button></form><script src="/dashboard/assets/kubeconfig-import.js"></script></details>"""

private fun clustersSection(actor: Principal, clusters: List<CloudCluster>, canImport: Boolean): String {
    val rows = clusters.joinToString("") { cluster ->
        """<tr><td><strong>${escape(cluster.name)}</strong><br><small>${escape(cluster.apiUrl)}</small></td><td>${escape(cluster.allowedNamespaces.joinToString(", "))}</td><td>${cluster.maxRunningPods}</td><td>Token and CA bundle defined</td><td><details><summary>Edit</summary><form action="/dashboard/cloud/clusters/${escape(cluster.id)}" method="post">${csrf(actor)}<input type="hidden" name="version" value="${cluster.version}">${clusterFields(cluster)}<button>Save cluster</button></form></details></td></tr>"""
    }
    val table = if (clusters.isEmpty()) "<div class='empty'><strong>No cluster is connected.</strong></div>"
        else "<table><thead><tr><th>Cluster</th><th>Namespaces</th><th>Pods</th><th>Credentials</th><th></th></tr></thead><tbody>$rows</tbody></table>"
    return "<h2>Kubernetes clusters</h2>" + help("What the service account needs", "<p>In each allowed namespace: <code>get</code>, <code>list</code>, <code>create</code> and <code>delete</code> on <code>pods</code> and <code>persistentvolumeclaims</code>; cluster-wide <code>get</code> and <code>list</code> on <code>persistentvolumes</code> and <code>storageclasses</code>; and <code>system:auth-delegator</code> for <code>TokenReview</code>. The token is stored encrypted; neither it nor the CA bundle is shown again.</p><p>Optional, to choose from lists in the profile form: <code>get</code> on the allowed <code>namespaces</code>, and <code>list</code> on <code>secrets</code> in each allowed namespace for the image pull secrets. Listing secrets lets the service account read them; TeamCode asks for their names only. Without these rights the form asks for the names.</p>") +
        table + """<details><summary>Connect a cluster</summary><form action="/dashboard/cloud/clusters" method="post">${csrf(actor)}${clusterFields(null)}<button>Connect cluster</button></form></details>""" + (if (canImport) importSection(actor) else "")
}

private fun profilesSection(profiles: List<CloudProfile>, clusters: List<CloudCluster>, published: Int?): String {
    val names = clusters.associate { it.id to it.name }
    val rows = profiles.joinToString("") { profile ->
        """<tr><td><strong>${escape(profile.name)}</strong> <small>v${profile.version}</small></td><td>${escape(names[profile.spec.clusterId] ?: profile.spec.clusterId)} / ${escape(profile.spec.namespace)}</td><td><code>${escape(profile.spec.image)}</code></td><td>${profile.assignedMemberIds.size}</td><td><a href="/dashboard/cloud/profiles/${escape(profile.id)}">Edit (draft of v${profile.version + 1})</a></td></tr>"""
    }
    val create = if (clusters.isEmpty()) "<p class=\"muted\">Connect a cluster to create a profile.</p>" else """<p><a href="/dashboard/cloud/profiles/new">New profile</a></p>"""
    return "<h2 id=\"profiles\">Profiles</h2>" + (published?.let { "<div class=\"notice\" role=\"status\">Version $it published.</div>" }.orEmpty()) + "<p>A profile says where and how a session runs: cluster, namespace, image, resources, agent, environment and the members who may use it. Each publication is a new immutable version; sessions keep the version they were created with. The form offers the namespaces, storage classes and image pull secrets read from the chosen cluster. The API (<code>POST</code> and <code>PUT /api/v1/cloud/profiles</code>) accepts the same fields.</p>" +
        (if (profiles.isEmpty()) "<div class='empty'><strong>No profile yet.</strong></div>" else "<table><thead><tr><th>Profile</th><th>Cluster / namespace</th><th>Image</th><th>Members</th><th></th></tr></thead><tbody>$rows</tbody></table>") + create
}

/** A draft with choices read from its cluster; a cluster that no longer exists is replaced by the first one. */
private suspend fun ApplicationCall.respondProfileEditor(admin: CloudAdministrationService, team: TeamService?, start: ProfileDraft, clusters: List<CloudCluster>, outcome: DraftOutcome?) {
    val actor = principal
    val cluster = clusters.firstOrNull { it.id == start.spec.clusterId } ?: clusters.firstOrNull()
    val choices = cluster?.let { admin.choices(actor, it.id, start.spec.namespace.takeIf(String::isNotEmpty)) }
    val draft = if (choices == null) start else start.copy(spec = start.spec.copy(clusterId = choices.cluster.id, namespace = choices.namespace))
    val profiles = admin.profiles(actor)
    val images = profiles.flatMap { listOf(it.spec.image) + it.spec.allowedImageOverrides }
    val title = if (draft.id == null) "New cloud profile" else "Profile ${profiles.firstOrNull { it.id == draft.id }?.name ?: draft.name}"
    respondText(page(title, profileEditor(actor, draft, clusters, choices, team?.members(actor).orEmpty(), images, outcome)), ContentType.Text.Html,
        if (outcome?.problem == true) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK)
}

/** Empty credential fields keep the stored ones; on an import they take the kubeconfig context's. */
private fun clusterInput(input: Parameters, imported: KubeconfigContext? = null) = CloudClusterInput(input["name"].orEmpty().trim(), input["apiUrl"].orEmpty().trim(),
    input["caCertificate"]?.trim()?.takeIf { it.isNotEmpty() } ?: imported?.caCertificate, input["serviceAccountToken"]?.trim()?.takeIf { it.isNotEmpty() } ?: imported?.token,
    input["allowedNamespaces"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }, input["maxRunningPods"]?.trim()?.toIntOrNull() ?: -1,
    input["defaultStorageClass"]?.trim()?.takeIf { it.isNotEmpty() })

private val PROFILE_SCRIPT = requireNotNull(object {}.javaClass.getResourceAsStream("/me/nathanfallet/teamcode/presentation/profile-editor.js")) { "Missing profile editor resource" }
    .bufferedReader().use { it.readText() }
private val KUBECONFIG_SCRIPT = requireNotNull(object {}.javaClass.getResourceAsStream("/me/nathanfallet/teamcode/presentation/kubeconfig-import.js")) { "Missing kubeconfig import resource" }
    .bufferedReader().use { it.readText() }
