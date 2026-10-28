package me.nathanfallet.teamcode.domain.cloud

import java.util.UUID
import me.nathanfallet.teamcode.domain.team.*

/**
 * Management use cases. Routes only write the desired state here; the reconciler performs every cluster call.
 * Authorization uses the caller's own rights; `onBehalfOf` only selects the effective member.
 */
class CloudSessionService(
    private val store: CloudSessionStore,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(store),
    /** The team access policy; null (tests of the lifecycle alone) lets every member create their own sessions. */
    private val access: CloudAccessService? = null,
    /** Queues `initialMessage`; null where the server has no conversations (creation then refuses one). */
    private val initialMessages: CloudInitialMessages? = null,
    /** The daemons of members' devices; null where remote sessions cannot be started on another device. */
    private val daemons: DeviceDaemonStore? = null,
) {
    /** Whether a remote session can be started on one of the caller's devices by its daemon (`cloud.sessions.remote.daemon.v1`). */
    val startsOnDevices: Boolean get() = daemons != null

    /** Whether creation accepts `initialMessage`; the server advertises `cloud.sessions.initial-message.v1` from it. */
    val acceptsInitialMessage: Boolean get() = initialMessages != null

    /**
     * Resolves `TeamCode-On-Behalf-Of` for a write. Only an interactive administrator or an administrator's
     * `admin:cloud` key may name another member.
     */
    suspend fun actor(principal: Principal, onBehalfOf: String?): CloudActor {
        principal.requireCloudWrite()
        if (onBehalfOf == null || onBehalfOf == principal.member.id) return CloudActor(principal, principal.member.id)
        if (!principal.isCloudAdministrator)
            throw TeamFailure("on_behalf_forbidden", 403, "Only an administrator can act on behalf of another member.")
        val target = store.member(principal.teamId, onBehalfOf)
        if (target == null || target.disabled || target.passwordChangeRequired)
            throw TeamFailure("invalid_effective_member", 422, "The effective member must be an enabled team member who completed password setup.")
        return CloudActor(principal, target.id)
    }

    suspend fun create(principal: Principal, onBehalfOf: String?, draft: CloudSessionDraft, idempotency: IdempotencyRequest?): CloudCreateOutcome {
        val actor = actor(principal, onBehalfOf)
        validate(draft)
        // A profile id is random and only listed to the members who may use it: saying that an id exists but is not
        // assigned reveals nothing a member could guess. Names are resolved by clients among the profiles listed to
        // the caller, so an unassigned profile is never found by its name.
        val profile = store.profile(principal.teamId, draft.profileId) ?: throw missingProfile()
        val owner = store.member(principal.teamId, actor.effectiveMemberId)
            ?: throw TeamFailure("invalid_effective_member", 422, "The effective member must be an enabled team member who completed password setup.")
        val onBehalf = actor.effectiveMemberId != principal.member.id
        if (!owner.role.isAdministrator && owner.id !in profile.assignedMemberIds) throw unassignedProfile(owner.id == principal.member.id)
        // The owner must hold `create` under the access policy, also when an administrator creates on their behalf.
        access?.require(principal.teamId, owner, owner.id, CloudPermission.CREATE)
        draft.initialMessage?.let { text ->
            if (initialMessages == null) throw TeamFailure("initial_message_unsupported", 400, "This server does not accept an initial message.")
            CloudConversationRules.validateMessage(text)
            // The caller writes the message, as on the messages route: they need `message` on the owner's sessions.
            access?.require(principal.teamId, principal.member, owner.id, CloudPermission.MESSAGE, principal.isCloudAdministrator)
        }
        val spec = profile.spec
        val image = draft.image?.also { requested ->
            if (requested != spec.image && spec.allowedImageOverrides.none { matchesImagePattern(it, requested) })
                throw TeamFailure("image_not_allowed", 403, "This image does not match the profile's allowed image overrides.")
        } ?: spec.image
        val cluster = store.cluster(principal.teamId, spec.clusterId) ?: throw TeamFailure("cluster_unavailable", 409, "The profile's cluster is no longer configured.")
        if (spec.namespace !in cluster.allowedNamespaces) throw TeamFailure("namespace_not_allowed", 409, "The profile's namespace is no longer allowed on its cluster.")
        val settings = store.settings(principal.teamId)
        val now = store.now()
        val id = UUID.randomUUID().toString()
        val idle = (draft.idleTimeoutMinutes ?: spec.idleTimeoutMinutes).coerceIn(spec.minIdleTimeoutMinutes, spec.maxIdleTimeoutMinutes)
        val session = CloudSession(
            id = id, teamId = principal.teamId, ownerId = owner.id, name = draft.name, createdBy = actor.attribution,
            profileId = profile.id, profileVersion = profile.version, clusterId = cluster.id, namespace = spec.namespace,
            imageReference = image, imageDigest = null, agentKind = spec.agentKind, agentVersion = spec.agentVersion,
            agentModel = draft.agentModel, agentEffort = draft.agentEffort, labels = draft.labels,
            state = CloudSessionState.CREATING, phase = "scheduling", desiredState = DesiredState.RUNNING, generation = 0, currentEpoch = 0,
            idleTimeoutMinutes = idle, lastActivityAt = now, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
            failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null,
            terminatedReason = null, waitingSince = null,
            workspace = CloudWorkspace(claimName(id), spec.storageClass ?: cluster.defaultStorageClass, spec.workspaceSizeGibibytes),
            createdAt = now, updatedAt = now,
            environment = CloudEnvironmentRules.merge(spec.environment, draft.environment).also(CloudEnvironmentRules::validate),
            // Created on someone's behalf, the session runs the creator's instructions: no GitHub token until its owner allows it (audit M2).
            githubAccess = if (onBehalf) CloudGitHubAccess.NONE else CloudGitHubAccess.OWNER,
            automationId = draft.automationId,
        )
        if (onBehalf && session.environment.requireGitHub) throw TeamFailure("github_owner_consent", 422,
            "This profile requires GitHub, and a session created on someone's behalf has no GitHub access until its owner allows it. Ask the owner to create the session.")
        val outcome = store.insert(session, actor, idempotency, settings.maxRetainedSessionsPerMember)
        // Queued after the session commits, idempotently: if this step fails, the client's retry with the same
        // Idempotency-Key replays the creation and queues the message then.
        draft.initialMessage?.let { text ->
            val created = when (outcome) { is CloudCreateOutcome.Created -> outcome.session; is CloudCreateOutcome.Replayed -> outcome.session }
            initialMessages?.queue(principal, created, text)
        }
        return outcome
    }

    /**
     * Creates a session that runs on the caller's own device ([CloudSessionKind.REMOTE]): the device is the one of the
     * caller's login session, so a service key or a login without an installation is refused
     * (`422 remote_requires_device`), and nobody creates one on behalf of another member. No profile, cluster or
     * workspace is involved; the session waits for its host (`teamcode remote`), which starts its first execution.
     * Remote sessions count toward neither the retained nor the running cap of cloud sessions, and a device has no cap.
     *
     * With [RemoteSessionDraft.deviceId], the session runs on that device instead, started by its daemon: see [targetFolder].
     */
    suspend fun createRemote(principal: Principal, onBehalfOf: String?, draft: RemoteSessionDraft, idempotency: IdempotencyRequest?): CloudCreateOutcome {
        val actor = actor(principal, onBehalfOf)
        if (actor.effectiveMemberId != principal.member.id)
            throw TeamFailure("on_behalf_forbidden", 403, "A remote session runs on the caller's own device; it cannot be created on behalf of another member.")
        validateRemote(draft)
        val (device, folder) = if (draft.deviceId == null) deviceOf(principal) to null else targetFolder(principal, draft)
        val owner = store.member(principal.teamId, principal.member.id)
            ?: throw TeamFailure("invalid_effective_member", 422, "The effective member must be an enabled team member who completed password setup.")
        access?.require(principal.teamId, owner, owner.id, CloudPermission.CREATE)
        draft.initialMessage?.let { text ->
            if (initialMessages == null) throw TeamFailure("initial_message_unsupported", 400, "This server does not accept an initial message.")
            CloudConversationRules.validateMessage(text)
        }
        val settings = store.settings(principal.teamId)
        val now = store.now()
        val id = UUID.randomUUID().toString()
        val session = CloudSession(
            id = id, teamId = principal.teamId, ownerId = owner.id, name = draft.name, createdBy = actor.attribution,
            profileId = "", profileVersion = 0, clusterId = "", namespace = "", imageReference = "", imageDigest = null,
            agentKind = draft.agentKind, agentVersion = null, agentModel = draft.agentModel, agentEffort = draft.agentEffort, labels = draft.labels,
            state = CloudSessionState.CREATING, phase = WAITING_HOST, desiredState = DesiredState.RUNNING, generation = 0, currentEpoch = 0,
            idleTimeoutMinutes = settings.remoteIdleTimeoutMinutes, lastActivityAt = now, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
            failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null,
            terminatedReason = null, waitingSince = null, workspace = CloudWorkspace("", null, 0), createdAt = now, updatedAt = now,
            kind = CloudSessionKind.REMOTE, host = CloudSessionHost(device, folder?.name ?: draft.directoryName, permissionMode = draft.permissionMode, folderId = folder?.id,
                resumeSessionId = draft.resumeSessionId),
        )
        val outcome = store.insert(session, actor, idempotency, settings.maxRetainedSessionsPerMember)
        draft.initialMessage?.let { text ->
            val created = when (outcome) { is CloudCreateOutcome.Created -> outcome.session; is CloudCreateOutcome.Replayed -> outcome.session }
            initialMessages?.queue(principal, created, text)
        }
        return outcome
    }

    /**
     * Starts the next execution of a remote session on its host (`POST /cloud/sessions/{id}/host`, called by
     * `teamcode remote`): only the owner, from the session's device, with an interactive login. A new epoch fences
     * the execution it replaces (a host started twice: the first one gets `409 execution_fenced` and exits). A paused
     * session resumes; a creating one keeps preparing. Returns the session with its new epoch, which the host then
     * uses on the runner channel with the same login.
     */
    suspend fun host(principal: Principal, id: String): CloudSession {
        val actor = actor(principal, null)
        val found = store.find(principal.teamId, id)
        if (found == null || found.ownerId != principal.member.id) throw missing()
        if (found.kind != CloudSessionKind.REMOTE) throw TeamFailure("not_remote", 409, "This is a cloud session; it runs in a pod the server starts.")
        requireHost(principal, found)
        repeat(5) {
            val current = store.find(principal.teamId, id) ?: throw missing()
            if (current.state.terminal || current.desiredState == DesiredState.TERMINATED) throw notRunning()
            if (current.state == CloudSessionState.PAUSING)
                throw TeamFailure("session_pausing", 409, "The session is still pausing on its host. Try again once it is paused.", retryable = true)
            val now = store.now()
            val live = if (current.currentEpoch > 0) store.execution(current.id, current.currentEpoch)?.takeIf { it.state != ExecutionState.ENDED } else null
            val epoch = current.currentEpoch + 1
            val state = when (current.state) {
                CloudSessionState.CREATING, CloudSessionState.PREPARING -> current.state
                else -> CloudSessionState.RESUMING
            }
            // A remote session's failure only ever says why its last execution ended (its host went offline).
            val next = current.copy(state = state, phase = "starting_host", desiredState = DesiredState.RUNNING, currentEpoch = epoch, resumeBlocked = null, failure = null,
                pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, updatedAt = now)
            val execution = CloudExecution(current.id, epoch, hostExecutionName(epoch), ExecutionState.PENDING, now, ownerId = current.ownerId)
            val ended = live?.copy(state = ExecutionState.ENDED, endedAt = now, exitReason = "replaced")
            transition.startHost(current, next, execution, ended, actor)?.let { return it }
        }
        throw TeamFailure("session_busy", 409, "The cloud session changed concurrently. Retry the request.")
    }

    /**
     * The device and folder of a session started by a device's daemon. Only the device's owner, with an interactive
     * login, starts one: the device row's member decides, never the caller's role, so an administrator cannot start an
     * agent on another member's computer (`404 device_not_found`, as for an unknown device). The daemon must be online
     * (`409 device_offline`: nothing is queued for later) and the folder one it published (`422 folder_not_published`);
     * the daemon checks the folder again against its own configuration before it hosts the session.
     */
    private suspend fun targetFolder(principal: Principal, draft: RemoteSessionDraft): Pair<String, DeviceFolder> {
        val daemons = daemons ?: throw TeamFailure("device_target_unsupported", 400, "This server does not start remote sessions on another device.")
        DeviceDaemonUseCase.requireNotCloudSession(daemons, principal)
        if (principal.serviceScopes != null)
            throw TeamFailure("remote_requires_login", 422, "Only the device's owner, signed in interactively, starts a session on it; service keys cannot.")
        val deviceId = draft.deviceId!!
        val folderId = draft.folderId?.takeIf(DeviceDaemonRules::validFolderId)
            ?: throw TeamFailure("invalid_folder", 400, "Send the folderId of one of the device's published folders.")
        val device = daemons.owned(principal.teamId, principal.member.id, deviceId) ?: throw DeviceDaemonUseCase.deviceNotFound()
        if (device.daemon?.online != true)
            throw TeamFailure("device_offline", 409, "${device.name} is offline: its TeamCode daemon is not connected. Start it on the device, or choose another one.")
        // Only a daemon that lists conversations checks and forks one; an older one would start a new conversation instead.
        if (draft.resumeSessionId != null) DeviceDaemonUseCase.requireResume(device)
        val folder = device.folders.find { it.id == folderId } ?: throw DeviceDaemonUseCase.folderNotPublished(device.name)
        if (draft.directoryName != null && draft.directoryName != folder.name)
            throw TeamFailure("invalid_directory_name", 400, "The directory of a session started on a device is its folder's name; omit directoryName.")
        return device.id to folder
    }

    /**
     * The daemon refuses an order its own check rejected (the folder left its configuration, the agent is missing…): the
     * session ends with `failure.code = host_refused` and the daemon's [reason]. Only from the session's device, for a
     * session started through its daemon that still waits for a host; anything else is `404` or `409 not_waiting_host`.
     */
    suspend fun refuseHost(principal: Principal, id: String, reason: String): CloudSession {
        DeviceDaemonRules.validateReason(reason)
        val actor = actor(principal, null)
        val found = store.find(principal.teamId, id)
        if (found == null || found.ownerId != principal.member.id || found.kind != CloudSessionKind.REMOTE || found.host?.folderId == null) throw missing()
        requireHost(principal, found)
        return transition(principal.teamId, id) { current ->
            if (current.phase != WAITING_HOST || current.desiredState != DesiredState.RUNNING || current.state.terminal)
                throw TeamFailure("not_waiting_host", 409, "This session no longer waits for its host.")
            val now = store.now()
            CloudTransition(
                current.copy(desiredState = DesiredState.TERMINATED, terminatedReason = HOST_REFUSED,
                    failure = CloudProblem(HOST_REFUSED, "The device refused to start this session ($reason).", false), updatedAt = now),
                CloudWrite(actor = actor, auditAction = "cloud.session.host_refused", reconcileAt = now),
            )
        } ?: throw missing()
    }

    /** The caller's device, required for anything that runs a remote session. */
    private suspend fun deviceOf(principal: Principal): String {
        if (principal.serviceScopes != null) throw remoteRequiresDevice()
        return store.deviceOf(principal) ?: throw remoteRequiresDevice()
    }

    /** A remote session runs, and resumes, only on its own device. */
    private suspend fun requireHost(principal: Principal, session: CloudSession) {
        val device = deviceOf(principal)
        val host = session.host
        if (host?.deviceId == null) throw TeamFailure("remote_host_removed", 409, "The device of this remote session was removed; the session cannot run again.")
        if (host.deviceId != device)
            throw TeamFailure("remote_host_mismatch", 409, "This remote session runs on ${host.device?.name ?: "another device"}; resume it there.")
    }

    suspend fun list(principal: Principal, query: CloudSessionQuery): CloudSessionPage {
        principal.requireCloudRead()
        if (query.limit !in 1..50) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 50.")
        LabelRules.validateFilter(query.labels)
        val owner = query.owner ?: principal.member.id
        if (owner != principal.member.id) principal.requireCloudTeamRead()
        store.revalidate(principal)
        return store.list(principal.teamId, query.copy(owner = owner.takeUnless { it == "*" }))
    }

    suspend fun get(principal: Principal, id: String): CloudSession {
        principal.requireCloudRead()
        store.revalidate(principal)
        return visible(principal, store.find(principal.teamId, id), read = true)
    }

    suspend fun executions(principal: Principal, id: String): List<CloudExecution> {
        get(principal, id)
        return store.executions(principal.teamId, id)
    }

    suspend fun pause(principal: Principal, onBehalfOf: String?, id: String, force: Boolean): CloudSession {
        val actor = actor(principal, onBehalfOf)
        visible(principal, store.find(principal.teamId, id))
        return transition(principal.teamId, id) { current ->
            val now = store.now()
            when {
                current.state.terminal || current.desiredState == DesiredState.TERMINATED -> throw notRunning()
                current.state == CloudSessionState.CREATING || current.state == CloudSessionState.PREPARING ->
                    throw TeamFailure("session_preparing", 409, "The session is still being prepared. Delete it to cancel creation.")
                current.state == CloudSessionState.TRANSFERRING -> throw TeamFailure("session_transferring", 409, "The session is being transferred.")
                current.desiredState == DesiredState.PAUSED && (!force || current.pauseForce) -> null
                else -> CloudTransition(
                    current.copy(desiredState = DesiredState.PAUSED, pauseRequestedAt = current.pauseRequestedAt ?: now, pauseForce = force || current.pauseForce, updatedAt = now),
                    CloudWrite(actor = actor, auditAction = "cloud.session.paused", reconcileAt = now),
                )
            }
        } ?: throw missing()
    }

    /**
     * The owner chooses whose instructions an agent holding their GitHub token follows ([CloudGitHubAccess]). Only the
     * owner, signed in or through their own key, never on their behalf: the choice consents to the use of their token.
     * Turning the token off waits for a pause, because a running agent keeps a token it already received until it
     * expires; turning it on or sharing applies to the next renewal and the next message.
     */
    suspend fun setGitHubAccess(principal: Principal, onBehalfOf: String?, id: String, access: CloudGitHubAccess): CloudSession {
        if (onBehalfOf != null && onBehalfOf != principal.member.id) throw TeamFailure("github_owner_only", 403, "Only the session owner chooses who uses their GitHub access.")
        val actor = actor(principal, null)
        val found = store.find(principal.teamId, id)
        if (found == null || found.ownerId != principal.member.id) throw missing()
        if (found.kind == CloudSessionKind.REMOTE) throw TeamFailure("github_access_unsupported", 409, "A remote session uses the GitHub access of the device it runs on.")
        return transition(principal.teamId, id) { current ->
            val now = store.now()
            when {
                current.ownerId != principal.member.id -> throw missing()
                current.state.terminal || current.desiredState == DesiredState.TERMINATED -> throw notRunning()
                current.state == CloudSessionState.TRANSFERRING || current.transferId != null -> throw TeamFailure("session_transferring", 409, "The session is being transferred.")
                current.githubAccess == access -> null
                access == CloudGitHubAccess.NONE && current.environment.requireGitHub -> throw TeamFailure("github_required", 422,
                    "This session's profile requires GitHub: it cannot run without GitHub access.")
                access == CloudGitHubAccess.NONE && current.state != CloudSessionState.PAUSED -> throw TeamFailure("session_not_paused", 409,
                    "A running agent keeps the GitHub token it already received. Pause the session, then turn GitHub access off.")
                else -> CloudTransition(current.copy(githubAccess = access, updatedAt = now),
                    CloudWrite(actor = actor, auditAction = "cloud.session.github_access_changed", reconcileAt = null))
            }
        } ?: throw missing()
    }

    suspend fun resume(principal: Principal, onBehalfOf: String?, id: String): CloudSession {
        val actor = actor(principal, onBehalfOf)
        val session = visible(principal, store.find(principal.teamId, id))
        if (session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw notRunning()
        // Synchronous rechecks: the owner must still be enabled and assigned to the profile.
        val owner = store.member(principal.teamId, session.ownerId)
        if (owner == null || owner.disabled || owner.passwordChangeRequired)
            throw TeamFailure("owner_unavailable", 403, "The session owner is disabled or has not completed password setup.")
        // A remote session has no profile; it resumes only from its own device (elsewhere, a message with `wake` asks its host).
        if (session.kind == CloudSessionKind.REMOTE) requireHost(principal, session)
        else {
            val profile = store.profile(principal.teamId, session.profileId)
            if (profile == null || (!owner.role.isAdministrator && owner.id !in profile.assignedMemberIds))
                throw TeamFailure("profile_unassigned", 403, "The session owner is no longer assigned to this profile.")
        }
        return transition(principal.teamId, id) { current ->
            val now = store.now()
            when {
                current.state.terminal || current.desiredState == DesiredState.TERMINATED -> throw notRunning()
                // An accepted transfer pauses the session; only the transfer itself resumes it, for the recipient.
                current.state == CloudSessionState.TRANSFERRING || current.transferId != null -> throw TeamFailure("session_transferring", 409, "The session is being transferred to another member.")
                current.desiredState == DesiredState.RUNNING && current.resumeBlocked == null -> null
                // Still pausing: the reconciler cancels the pause when no checkpoint was taken yet, otherwise it resumes
                // the session as soon as the pause completes. The pause's request time stays, for its deadlines.
                current.state == CloudSessionState.PAUSING -> CloudTransition(
                    current.copy(desiredState = DesiredState.RUNNING, resumeBlocked = null, updatedAt = now),
                    CloudWrite(actor = actor, auditAction = "cloud.session.resumed", reconcileAt = now),
                )
                else -> CloudTransition(
                    current.copy(desiredState = DesiredState.RUNNING, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, updatedAt = now),
                    CloudWrite(actor = actor, auditAction = "cloud.session.resumed", reconcileAt = now),
                )
            }
        } ?: throw missing()
    }

    /** Idempotent: deleting a terminated or already-deleting session succeeds without another write. */
    suspend fun delete(principal: Principal, onBehalfOf: String?, id: String): CloudSession {
        val actor = actor(principal, onBehalfOf)
        visible(principal, store.find(principal.teamId, id))
        return transition(principal.teamId, id) { current ->
            if (current.state == CloudSessionState.TERMINATED || current.desiredState == DesiredState.TERMINATED) return@transition null
            val now = store.now()
            CloudTransition(
                current.copy(desiredState = DesiredState.TERMINATED, terminatedReason = "deleted", updatedAt = now),
                CloudWrite(actor = actor, auditAction = "cloud.session.deleted", reconcileAt = now),
            )
        } ?: throw missing()
    }

    /** [read] also opens every member's session to a `team:read` key; a write needs the owner or a cloud administrator. */
    private fun visible(principal: Principal, session: CloudSession?, read: Boolean = false): CloudSession {
        val administrator = if (read) principal.canReadTeamCloud else principal.isCloudAdministrator
        if (session == null || (session.ownerId != principal.member.id && !administrator)) throw missing()
        return session
    }

    companion object {
        /** The phase of a remote session waiting for its host to start an execution. */
        const val WAITING_HOST = "waiting_host"
        /** Why a session the device's daemon refused ended. */
        const val HOST_REFUSED = "host_refused"
        val REMOTE_AGENTS = setOf("claude", "codex")
        /** The execution name of a remote session's host, in place of a pod name. */
        fun hostExecutionName(epoch: Int) = "host-e$epoch"
        fun validateRemote(draft: RemoteSessionDraft) {
            validate(CloudSessionDraft(draft.name, "", null, draft.agentModel, draft.agentEffort, draft.labels, null))
            if (draft.agentKind !in REMOTE_AGENTS) throw TeamFailure("invalid_agent", 400, "Use the agent claude or codex.")
            val directory = draft.directoryName
            if (draft.deviceId == null && draft.folderId != null) throw TeamFailure("invalid_folder", 400, "A folderId needs the deviceId whose daemon published it.")
            draft.resumeSessionId?.let { resume ->
                if (draft.deviceId == null) throw TeamFailure("invalid_resume", 400, "A resumeSessionId needs the deviceId and folderId of the conversation it continues.")
                if (!DeviceDaemonRules.validConversationId(resume)) throw TeamFailure("invalid_resume", 400, "Send the id of a conversation the device listed (a lowercase UUID).")
                if (draft.agentKind != "claude") throw TeamFailure("resume_unsupported", 400, "Only a Claude Code session continues a conversation of the device.")
            }
            if ((draft.deviceId == null || directory != null) && (directory == null || !DeviceDaemonRules.validDirectoryName(directory)))
                throw TeamFailure("invalid_directory_name", 400, "Send the directory's name (its last segment, 1–255 characters), never its path.")
        }
        private fun remoteRequiresDevice() = TeamFailure("remote_requires_device", 422,
            "A remote session runs on the device of an interactive login. Sign in with teamcode login on this device; service keys cannot host one.")
        fun claimName(sessionId: String) = "tc-ws-$sessionId"
        fun podName(sessionId: String, epoch: Int) = "tc-$sessionId-e$epoch"
        fun validate(draft: CloudSessionDraft) {
            if (draft.name.isBlank() || draft.name.length > 120 || draft.name.any(Char::isISOControl))
                throw TeamFailure("invalid_session", 400, "Use a session name of 1–120 characters without control characters.")
            LabelRules.validate(draft.labels)
            if (draft.image != null && (draft.image.length !in 1..512 || draft.image.any { it.isWhitespace() || it.isISOControl() }))
                throw TeamFailure("invalid_image", 400, "Use a valid image reference.")
            for (value in listOfNotNull(draft.agentModel, draft.agentEffort))
                if (!Regex("[a-zA-Z0-9._:/-]{1,120}").matches(value)) throw TeamFailure("invalid_agent", 400, "Use a valid agent model and effort.")
            draft.environment?.let(CloudEnvironmentRules::validate)
        }
        /** `*` matches any run of characters, including `/` and `:`; everything else is literal. */
        fun matchesImagePattern(pattern: String, image: String) =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(image)
        private fun missingProfile() = TeamFailure("profile_not_found", 404, "No profile has this id. List the profiles you can use with GET /api/v1/cloud/profiles (teamcode cloud profiles).")
        private fun unassignedProfile(self: Boolean) = TeamFailure("profile_unassigned", 403,
            (if (self) "This profile is not assigned to you." else "This profile is not assigned to the effective member.") +
                " List the usable profiles with GET /api/v1/cloud/profiles (teamcode cloud profiles), or ask an administrator to assign it.")
        private fun missing() = TeamFailure("not_found", 404, "Cloud session not found.")
        private fun notRunning() = TeamFailure("session_not_running", 409, "The cloud session is terminated or being deleted.")
    }
}
