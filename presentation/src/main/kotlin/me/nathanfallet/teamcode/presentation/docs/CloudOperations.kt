package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import me.nathanfallet.teamcode.api.models.*

/** Cloud administration, sessions, conversations, transfers and GitHub links. */
internal fun ApiCatalog.cloudOperations() {
    cloudAdministration()
    cloudSessions()
    cloudConversation()
    cloudTransfers()
    githubOperations()
}

private const val ON_BEHALF_TEXT = "Act for another member (administrators, or an `admin:cloud` key): the member's identifier."
private const val IDEMPOTENCY_TEXT = "1 to 128 characters from `A-Z a-z 0-9 . _ : -`. Retrying with the same key and " +
    "body returns the first result with `Idempotency-Replayed: true`."

private fun Parameters.Builder.sessionId() = pathId("id", "The cloud session's identifier.")
private fun Parameters.Builder.onBehalfOf() = headerText("TeamCode-On-Behalf-Of", ON_BEHALF_TEXT)

private fun ApiCatalog.cloudAdministration() {
    get("/api/v1/cloud/runtime") {
        operationId = "getCloudRuntime"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Whether cloud sessions are on, and how their pods are built"
        description = "On or off, runner image and binary, the origin pods call back (`effectiveServerUrl` is the public URL " +
            "when `serverUrl` is null) and the workspace access mode. Stored in the database and reachable while cloud " +
            "sessions are off; no field is a credential. Administrators only."
        responses { ok<CloudRuntimeView>("The runtime; `updatedAt` is null while nothing is stored.") }
    }
    put("/api/v1/cloud/runtime") {
        operationId = "saveCloudRuntime"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Turn cloud sessions on or off, or change how their pods are built"
        description = "Replaces the whole runtime: every field is required, plus the `updatedAt` it was based on (null when " +
            "nothing was stored yet). Turning cloud sessions on needs a runner image and an HTTPS, non-loopback callback " +
            "origin; turned off, the runtime may stay incomplete. Every replica applies it within seconds. " +
            "Administrators only."
        body<CloudRuntimeView>("The runtime; `effectiveServerUrl` is ignored.")
        responses {
            ok<CloudRuntimeView>("The saved runtime.")
            badRequest("An invalid image, binary path, origin or access mode, or turning on without an image or a usable origin (`invalid_cloud_runtime`).")
            conflict("Another administrator saved the runtime since `updatedAt` (`cloud_runtime_conflict`).")
        }
    }
    get("/api/v1/cloud/settings") {
        operationId = "getCloudSettings"; tag(ApiTags.CLOUD_ADMIN)
        summary = "The cloud settings"
        description = "Retention of paused and failed workspaces and of transcripts, per-member session limits, the " +
            "transfer policy and the deadline of cloud agents' permission requests. Administrators only."
        responses { ok<CloudSettingsView>("The settings.") }
    }
    put("/api/v1/cloud/settings") {
        operationId = "saveCloudSettings"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Change the cloud settings"
        description = "The retention and limit fields are required; an absent transfer field or `permissionDeadlineMinutes` keeps its stored value. " +
            "`pausedRetentionDays` or `transcriptRetentionDays` set to null keep data indefinitely. `permissionDeadlineMinutes` (1 to 1440) denies a " +
            "cloud agent's permission request nobody answered in time; 0 waits without a deadline. Administrators only."
        body<CloudSettingsView>("The settings.")
        responses {
            ok<CloudSettingsView>("The saved settings.")
            badRequest("A value out of range (`invalid_cloud_settings`).")
        }
    }
    get("/api/v1/cloud/clusters") {
        operationId = "listCloudClusters"; tag(ApiTags.CLOUD_ADMIN)
        summary = "List the Kubernetes clusters"
        description = "The clusters sessions run in, with their namespaces, pod limit and orphaned volumes. The service " +
            "account token and CA are never returned. Administrators only."
        responses { ok<List<CloudClusterView>>("The clusters.") }
    }
    post("/api/v1/cloud/clusters") {
        operationId = "createCloudCluster"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Connect a cluster"
        description = "The API URL, its CA certificate and a service account token TeamCode uses to create session " +
            "pods, limited to `allowedNamespaces`. At most 16 clusters. Administrators only."
        body<SaveCloudClusterRequest>("The cluster, its credentials and limits.")
        responses {
            ok<CloudClusterView>("Connected.", HttpStatusCode.Created)
            badRequest("Invalid URL, credentials, namespaces or limits (`invalid_cluster`, `cluster_credentials_required`).")
            conflict("The name is used (`cluster_conflict`) or 16 clusters exist (`cluster_limit`).")
        }
    }
    put("/api/v1/cloud/clusters/{id}") {
        operationId = "updateCloudCluster"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Change a cluster"
        description = "`version` must be the current version. Null credentials keep the stored ones; changing the API " +
            "URL requires both again. Administrators only."
        parameters { pathId("id", "The cluster's identifier.") }
        body<SaveCloudClusterRequest>("The cluster, with its current `version`.")
        responses {
            ok<CloudClusterView>("The updated cluster.")
            badRequest("A missing version, or invalid settings (`invalid_cluster`, `cluster_credentials_required`).")
            notFound("No such cluster (`cluster_not_found`).")
            conflict("The cluster changed since it was read, or the name is used (`cluster_conflict`).")
        }
    }
    get("/api/v1/cloud/clusters/{id}/choices") {
        operationId = "getCloudClusterChoices"; tag(ApiTags.CLOUD_ADMIN)
        summary = "What a profile on a cluster can choose from"
        description = "Read from the cluster's API with its service account: the allowed namespaces (`found` or " +
            "`missing` with the optional `get` right on `namespaces`, `unknown` without it), the storage classes with " +
            "their reclaim policy and the default marked, and the names of the `kubernetes.io/dockerconfigjson` secrets " +
            "of the namespace, read as metadata only (the optional `list` right on `secrets`); secret contents are never " +
            "read. A list the service account may not read is `forbidden`, one the cluster could not answer " +
            "`unavailable`; neither is an error. Creating or publishing a profile still checks every value. Administrators only."
        parameters {
            pathId("id", "The cluster's identifier.")
            queryText("namespace", "An allowed namespace to read storage classes and secrets for; the first allowed one otherwise.")
        }
        responses {
            ok<CloudClusterChoicesView>("The choices.")
            notFound("No such cluster (`cluster_not_found`).")
        }
    }
    get("/api/v1/cloud/profiles") {
        operationId = "listCloudProfiles"; tag(ApiTags.CLOUD_ADMIN)
        summary = "List the session profiles"
        description = "A profile is what a session is created from: cluster and namespace, image, resources, workspace " +
            "size, agent, idle timeouts and the environment recipe (repositories, setup commands, required variables), " +
            "with the members allowed to use it. Members see the profiles assigned to them; administrators see all. " +
            "A cloud administrator passes `member` to list the profiles that member can create sessions from " +
            "(capability `cloud.profiles.member.v1`). `clusterName` names the profile's cluster."
        parameters { queryText("member", "Another member's identifier; cloud administrators only.") }
        responses {
            ok<List<CloudProfileView>>("The profiles.")
            error(HttpStatusCode.UnprocessableEntity, "The member does not exist, is disabled or has not completed password setup (`invalid_effective_member`).")
        }
    }
    post("/api/v1/cloud/profiles") {
        operationId = "createCloudProfile"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Create a session profile"
        description = "`version` must be 0. At most 200 profiles. Administrators only."
        body<SaveCloudProfileRequest>("The profile and the members allowed to use it.")
        responses {
            ok<CloudProfileView>("Created.", HttpStatusCode.Created)
            badRequest("Invalid specification (`type` names the field).")
            conflict("The name is used (`profile_conflict`) or 200 profiles exist (`profile_limit`).")
        }
    }
    put("/api/v1/cloud/profiles/{id}") {
        operationId = "updateCloudProfile"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Publish a new version of a profile"
        description = "Each save publishes an immutable version; running sessions keep the version they started with. " +
            "`version` must be the current version. Administrators only."
        parameters { pathId("id", "The profile's identifier.") }
        body<SaveCloudProfileRequest>("The profile, with its current `version`.")
        responses {
            ok<CloudProfileView>("The new version.")
            badRequest("Invalid specification (`type` names the field).")
            notFound("No such profile (`not_found`).")
            conflict("The profile changed since it was read, or the name is used (`profile_conflict`).")
        }
    }
    get("/api/v1/cloud/access") {
        operationId = "getCloudAccessPolicy"; tag(ApiTags.CLOUD_ADMIN)
        summary = "The cloud access policy"
        description = "Rules granting `create`, `attach`, `message` and `transcript` to `everyone`, `member:<id>` or " +
            "`group:<id>`, on their own sessions (`own`) or every team session (`team`). Administrators hold every " +
            "permission. A `message` grant does not reach the agent of an owner who linked GitHub, unless the owner shared " +
            "the session (`PUT /cloud/sessions/{id}/github`). Administrators only."
        responses { ok<CloudAccessPolicyView>("The policy.") }
    }
    put("/api/v1/cloud/access") {
        operationId = "saveCloudAccessPolicy"; tag(ApiTags.CLOUD_ADMIN)
        summary = "Replace the cloud access policy"
        description = "`version` must be the current version (0 for a team that never saved one). Administrators only."
        body<CloudAccessPolicyView>("The policy, with its current `version`.")
        responses {
            ok<CloudAccessPolicyView>("The saved policy.")
            badRequest("An unknown subject, permission or scope (`invalid_access_policy`).")
            conflict("The policy changed since it was read (`access_policy_conflict`).")
            error(HttpStatusCode.PayloadTooLarge, "The body exceeds 64 KiB (`payload_too_large`).")
        }
    }
}

private fun ApiCatalog.cloudSessions() {
    post("/api/v1/cloud/sessions") {
        operationId = "createCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Start a cloud session"
        description = "Creates a session from a profile: TeamCode prepares a workspace and starts the agent in the " +
            "cluster. The answer comes at once (202) with the session `creating`; follow `state` with " +
            "`GET /api/v1/cloud/sessions/{id}` or the transcript. `initialMessage` is delivered once the session is " +
            "ready (`cloud.sessions.initial-message.v1`). Requires the `create` permission. With `kind: remote` " +
            "(`cloud.sessions.remote.v1`), the session runs on the caller's own device instead: no profile, image, idle " +
            "timeout or environment; `agent.kind` (`claude` or `codex`), `directoryName` (the directory's last segment, " +
            "never its path) and `permissionMode` (`ask`, the default, or `bypass`). Its device is the one of the caller's " +
            "login session; its host (`teamcode remote`) then starts it with `POST /api/v1/cloud/sessions/{id}/host`. With " +
            "`deviceId` and `folderId` (`cloud.sessions.remote.daemon.v1`), it runs on that device instead, one of the " +
            "caller's own, started by its daemon in one of the folders it published (`GET /api/v1/devices/{id}/folders`); " +
            "omit `directoryName`, which is the folder's name. Only the device's owner, signed in interactively, starts " +
            "one, and only while its daemon is online: nothing is queued for a device that is offline. With " +
            "`resumeSessionId` as well (`cloud.sessions.remote.resume.v1`, Claude Code only), the session continues that " +
            "conversation of the folder, one the daemon listed (`GET /api/v1/devices/{id}/folders/{folderId}/resumable`): " +
            "the daemon checks it again and the agent forks it at its first start, leaving the original as it was. Refused " +
            "`invalid_resume` (not a lowercase UUID, or without `deviceId`), `resume_unsupported` (another agent) and " +
            "`daemon_resume_unsupported` (a daemon too old)."
        parameters {
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT, required = true)
            onBehalfOf()
        }
        body<CreateCloudSessionRequest>("The session's name, profile and optional overrides.")
        responses {
            ok<CloudSessionView>("Accepted: the session is being created.", HttpStatusCode.Accepted)
            badRequest("A missing or invalid `Idempotency-Key`, or an invalid field (`idempotency_key_required`, `invalid_idempotency_key`, `invalid_session`, `invalid_kind`, `invalid_agent`, `invalid_directory_name`, `invalid_permission_mode`, `invalid_folder`, `device_target_unsupported`).")
            error(HttpStatusCode.Forbidden, "The profile exists but is not assigned to the effective member (`profile_unassigned`), or a remote session was asked on behalf of another member (`on_behalf_forbidden`).")
            notFound("No profile has this id (`profile_not_found`). `profileId` is an id: list the usable ones with `GET /api/v1/cloud/profiles`. Or `deviceId` is not one of the caller's devices (`device_not_found`), whoever the caller is.")
            conflict("The member's session limit is reached (`cloud_session_limit`), the profile's cluster or namespace is gone (`cluster_unavailable`, `namespace_not_allowed`), or the device's daemon is offline (`device_offline`).")
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another body (`idempotency_conflict`), the effective member cannot own sessions (`invalid_effective_member`), a remote session was asked with a service key or a login without a device (`remote_requires_device`), a session on a device was asked with a service key (`remote_requires_login`), or the device did not publish the folder (`folder_not_published`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/host") {
        operationId = "hostCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Start a remote session's host"
        description = "Called by `teamcode remote` on the session's own device, with its owner's interactive login " +
            "(`cloud.sessions.remote.v1`). Starts the session's next execution: a paused session resumes, a new one " +
            "starts, and an execution still live (a host started twice) is replaced and fenced. The host then registers, " +
            "heartbeats and checkpoints on the runner channel (`/api/v1/cloud/runner/*`) with the same login and the " +
            "returned epoch. Nothing is deleted when the session pauses or ends: the directory is the member's."
        parameters { sessionId() }
        responses {
            ok<CloudHostView>("The new execution's epoch and the session.")
            notFound("No such session among the caller's own (`not_found`).")
            conflict("A cloud session (`not_remote`), another device (`remote_host_mismatch`), a removed device (`remote_host_removed`), a pause still draining (`session_pausing`), or a session that ended (`session_not_running`, `session_busy`).")
            error(HttpStatusCode.UnprocessableEntity, "A service key or a login without a device (`remote_requires_device`).")
        }
    }
    get("/api/v1/cloud/sessions") {
        operationId = "listCloudSessions"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "List cloud sessions"
        description = "The caller's sessions, newest first, 50 per page by default. `owner` reads another member's " +
            "(or `all`) where the caller may: an administrator, or a `team:read` key (read-only). Page with `before` = `nextBefore`."
        parameters {
            queryText("owner", "A member's identifier, or `all`.")
            queryList("state", "Only these states: `creating`, `preparing`, `ready`, `active`, `pausing`, `paused`, `resuming`, `transferring`, `terminated`, `failed`.")
            queryList("label", "Only sessions with these labels, each as `key=value`.")
            queryList("kind", "Only these kinds: `cloud` (a pod) or `remote` (a member's device). Without it, `cloud` only, so a client that predates remote sessions never receives one.")
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "The page size, 1 to 50.")
        }
        responses {
            ok<CloudSessionPageView>("A page of sessions.")
            badRequest("An invalid filter or limit (`invalid_query`, `invalid_state`, `invalid_label`, `invalid_kind`, `invalid_limit`).")
        }
    }
    get("/api/v1/cloud/sessions/{id}") {
        operationId = "getCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "One cloud session"
        description = "Its state and phase, profile, image, agent, activity and idle timeout, failure, workspace " +
            "deletion proof and environment, and `waiting` while its agent waits for a person (the open permission " +
            "request's `reason`, `requestId`, `since` and `eventSeq`)."
        parameters { sessionId() }
        responses {
            ok<CloudSessionView>("The session.")
            notFound("No such session visible to the caller (`not_found`).")
        }
    }
    get("/api/v1/cloud/sessions/{id}/executions") {
        operationId = "listCloudExecutions"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "A session's executions"
        description = "Each pod that ran the session (one per epoch), with its state, node, image digest and exit reason."
        parameters { sessionId() }
        responses {
            ok<List<CloudExecutionView>>("The executions.")
            notFound("No such session (`not_found`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/pause") {
        operationId = "pauseCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Pause a session"
        description = "Stops the pod and keeps the workspace. Without `force`, the running turn finishes first."
        parameters {
            sessionId()
            onBehalfOf()
        }
        body<PauseCloudSessionRequest>("Optional: `force` pauses at once.", required = false)
        responses {
            ok<CloudSessionView>("Accepted: the session is pausing.", HttpStatusCode.Accepted)
            notFound("No such session (`not_found`).")
            conflict("The session cannot pause now (`invalid_transition`, `session_preparing`, `session_transferring`, `session_busy`).")
        }
    }
    patch("/api/v1/cloud/sessions/{id}") {
        operationId = "renameCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Rename a session"
        description = "Changes the session's name (1-120 characters, no control characters, as at creation). The owner or a cloud " +
            "administrator, or a `cloud:write` key; audited as `cloud.session.renamed` and published on the event feed as " +
            "`cloud.session.renamed` with the new `name` (`cloud.sessions.rename.v1`). Renaming to the current name changes nothing."
        parameters {
            sessionId()
            onBehalfOf()
        }
        body<RenameCloudSessionRequest>("`name`: the new name.")
        responses {
            ok<CloudSessionView>("The session with its new `name`.")
            badRequest("An invalid name (`invalid_session`).")
            notFound("No such session (`not_found`).")
            conflict("The session is terminated or being deleted (`session_not_running`), or changed concurrently (`session_busy`).")
        }
    }
    put("/api/v1/cloud/sessions/{id}/github") {
        operationId = "setCloudSessionGitHubAccess"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Choose who instructs an agent that holds your GitHub token"
        description = "`owner`: the owner's token, instructed by the owner only while they have linked GitHub (others get " +
            "`403 github_owner_only` on messages, files and permission answers). `shared`: the owner's token, instructed by everyone " +
            "the access policy lets message the session. `none`: no GitHub token; a session created on someone's behalf starts " +
            "here. Only the owner, never on their behalf (`cloud.sessions.github-access.v1`). Turning access off waits for a pause."
        parameters { sessionId() }
        body<CloudGitHubAccessRequest>("`access`: `owner`, `shared` or `none`.")
        responses {
            ok<CloudSessionView>("The session with its new `githubAccess`.")
            badRequest("An unknown mode (`invalid_github_access`).")
            notFound("No such session among the caller's own (`not_found`).")
            conflict("A running session cannot turn access off (`session_not_paused`), or the session is ending, transferring or remote (`session_not_running`, `session_transferring`, `github_access_unsupported`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/resume") {
        operationId = "resumeCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Resume a paused session"
        description = "Starts a new execution on the kept workspace and conversation. During a pause, cancels it when the runner has not checkpointed yet, otherwise resumes as soon as the pause completes."
        parameters {
            sessionId()
            onBehalfOf()
        }
        responses {
            ok<CloudSessionView>("Accepted: the session is resuming.", HttpStatusCode.Accepted)
            notFound("No such session (`not_found`).")
            conflict("The session cannot resume now (`invalid_transition`, `session_transferring`, `session_busy`), or it is a remote session of another device (`remote_host_mismatch`, `remote_host_removed`); a message with `wake` asks its host instead.")
        }
    }
    delete("/api/v1/cloud/sessions/{id}") {
        operationId = "deleteCloudSession"; tag(ApiTags.CLOUD_SESSIONS)
        summary = "Delete a session"
        description = "Terminates the session and deletes its workspace; `workspaceDeletion` proves when the volume is gone."
        parameters {
            sessionId()
            onBehalfOf()
        }
        responses {
            empty("Deleted.")
            notFound("No such session (`not_found`).")
            conflict("The session is being transferred (`session_transferring`) or changed concurrently (`session_busy`).")
        }
    }
}

private fun ApiCatalog.cloudConversation() {
    get("/api/v1/cloud/sessions/{id}/events") {
        operationId = "listCloudEvents"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Read a session's transcript"
        description = "Events in ascending `seq` order: user and agent messages, tool calls and results, turns, " +
            "statuses, errors and input changes, and `message.agent.delta` (live text, replaced by the `message.agent` " +
            "with the same `messageId` and `index`; ignore it to show final messages only). An event above 60,000 bytes " +
            "is replaced by `{truncated, originalBytes}` with its identity fields. Continue with `after` = `nextAfter`, " +
            "or go back with `before` = `nextBefore`. Clients must tolerate unknown event types. Requires the `transcript` permission."
        parameters {
            sessionId()
            queryNumber("after", "Events after this sequence number.")
            queryNumber("before", "Events before this sequence number.")
            queryNumber("limit", "The page size (100 by default).")
        }
        responses {
            ok<CloudEventPageView>("A page of events.")
            badRequest("An invalid cursor or limit (`invalid_query`, `invalid_cursor`).")
            notFound("No such session (`not_found`).")
        }
    }
    get("/api/v1/cloud/sessions/{id}/events/stream") {
        operationId = "streamCloudEvents"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Follow a session's transcript live"
        description = "A server-sent event stream (`text/event-stream`): each event has `id` = `seq`, `event` = its " +
            "type and `data` = the event as JSON. A reconnecting `EventSource` resumes from `Last-Event-ID`. The stream " +
            "ends with `event: end`, or `event: denied` if access is lost. Requires the `transcript` permission."
        parameters {
            sessionId()
            queryNumber("after", "Start after this sequence number (0 by default).")
            headerText("Last-Event-ID", "Resume after this sequence number; wins over `after`.")
        }
        responses {
            HttpStatusCode.OK { description = "The event stream." }
            badRequest("A negative cursor (`invalid_cursor`).")
            notFound("No such session (`not_found`).")
        }
    }
    get("/api/v1/cloud/events/stream") {
        operationId = "streamTeamEvents"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Follow the caller-wide event feed live"
        description = "A server-sent event stream (`text/event-stream`) of what happens to the caller's cloud sessions " +
            "and agent runs: `cloud.session.created`, `.paused`, `.woken`, `.resumed`, `.ended`, `cloud.turn` " +
            "(`phase`: `started`, `completed`, `interrupted`, `failed`), `cloud.session.waiting` and `.answered` " +
            "(a permission request, and its answer or its denial at the team's deadline), `run.started`, `run.stale` and `run.ended`. " +
            "Each event has `id` = `seq` (the team's own gap-free sequence), `event` = its type and `data` = a " +
            "`TeamEventView`. Without `Last-Event-ID` or `after` the stream starts at the newest event; `after=0` " +
            "starts at the oldest retained one (7 days). A reconnecting `EventSource` resumes after the last `id`; if " +
            "events after that cursor were purged, `event: gap` with `{oldestSeq}` comes first. Sessions and runs are " +
            "the caller's own; an administrator, or an administrator's `admin:cloud` key for sessions, sees the whole " +
            "team. Runs need an interactive login or a `usage:read` key. The stream ends with `event: denied` if " +
            "access is lost, and otherwise only when the client leaves. Transcript text never appears in it."
        parameters {
            queryNumber("after", "Start after this sequence number; 0 for the oldest retained event.")
            headerText("Last-Event-ID", "Resume after this sequence number; wins over `after`.")
            queryText("types", "Comma-separated event types to keep, for example `cloud.turn,run.stale`.")
            queryText("kind", "Session kinds whose events to carry, repeatable: `cloud`, `remote`. Without it, only cloud sessions'; run events always pass.")
        }
        responses {
            HttpStatusCode.OK { description = "The event stream." }
            badRequest("A negative cursor, a cursor ahead of the team's events (`invalid_cursor`) or an unknown type (`invalid_event_type`) or kind (`invalid_kind`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/messages") {
        operationId = "sendCloudMessage"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Send a message to the agent"
        description = "Queues a message, authored by the caller. Its `status` moves from `queued` to `delivered` and " +
            "`consumed`, or ends `cancelled` or `rejected`. `wake: false` leaves a paused session paused. " +
            "`expectedLastEventSeq` refuses the message if newer events exist. Send an `Idempotency-Key` (or " +
            "`clientMessageId`) to retry safely. Requires the `message` permission. With `cloud.files.v1`, `fileIds` names up to 5 files " +
            "the caller uploaded to this session and has not sent yet; the text may then be empty. A host that does not download files " +
            "rejects such a message (`files_unsupported`)."
        parameters {
            sessionId()
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT)
        }
        body<SendCloudMessageRequest>("The message text and options (at most 256 KiB).")
        responses {
            ok<CloudInputView>("Accepted.", HttpStatusCode.Accepted)
            badRequest("An invalid message, file list or key, or `TeamCode-On-Behalf-Of` was sent (`invalid_message`, `invalid_files`, `invalid_idempotency_key`, " +
                "`on_behalf_unsupported`); files named on a server without `cloud.files.v1` (`files_unsupported`).")
            notFound("No such session (`not_found`).")
            conflict("Newer events exist (`stale_view`), the session cannot take messages (`session_not_running`, `session_transferring`), or a file " +
                "is unknown, expired, another member's or already sent (`file_unavailable`).")
            error(HttpStatusCode.PayloadTooLarge, "The body exceeds 256 KiB (`payload_too_large`), or the files exceed the team's total per message (`files_too_large`).")
        }
    }
    get("/api/v1/cloud/files/policy") {
        operationId = "getCloudFilePolicy"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Whether files can be sent"
        description = "Whether the caller's team can send files to sessions now, and its limits. `available` is false with `reason` " +
            "`file_storage_not_configured` while the team has no tested artifact bucket: hide or explain file sending then. Capability `cloud.files.v1`."
        responses { ok<CloudFilePolicyView>("The policy and limits.") }
    }
    post("/api/v1/cloud/sessions/{id}/files") {
        operationId = "uploadCloudFile"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Upload a file for a session"
        description = "The raw bytes of one file, sent before the message that names it in `fileIds`. Authorized exactly like a message " +
            "(the `message` permission, a `cloud:write` key, never on behalf of another member). The type is recognized from the bytes, never " +
            "from the request: PNG, JPEG, WebP, GIF, PDF, or UTF-8 text (plain, Markdown, CSV or JSON by extension). The name is cleaned into a " +
            "single safe file name (no path), and a binary file gets its type's extension. The file waits in the team's artifact bucket until " +
            "the session's host received it, 24 hours at most. Limits: the team's size per file (20 MiB by default), 20 files not sent yet and " +
            "100 MiB not received per session, and a daily amount per member. Each upload is audited (`cloud.file.uploaded`: name, size, type). " +
            "Capability `cloud.files.v1`."
        parameters {
            sessionId()
            queryText("name", "The file's name, at most 1,024 characters; only its last segment is kept, cleaned.")
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT)
        }
        responses {
            ok<CloudFileView>("Stored.", HttpStatusCode.Created)
            ok<CloudFileView>("The same file was already uploaded with this key (`Idempotency-Replayed: true`).")
            badRequest("An empty file (`empty_file`), a name too long (`invalid_file_name`), an invalid key or `TeamCode-On-Behalf-Of` (`invalid_idempotency_key`, `on_behalf_unsupported`).")
            notFound("No such session (`not_found`).")
            conflict("The team has no tested bucket (`file_storage_not_configured`), the session is ending (`session_not_running`), or a file with this key is still being uploaded (`file_upload_in_progress`).")
            error(HttpStatusCode.PayloadTooLarge, "The file exceeds the team's size per file (`file_too_large`).")
            error(HttpStatusCode.UnsupportedMediaType, "The bytes are not an accepted type (`file_type_not_allowed`).")
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another file (`idempotency_conflict`).")
            error(HttpStatusCode.TooManyRequests, "The session holds too many files not sent or not received, or the member reached today's amount (`upload_quota`, with `Retry-After` for the daily amount).")
            error(HttpStatusCode.ServiceUnavailable, "The bucket did not answer (`file_storage_unavailable`): send the file again.")
        }
    }
    get("/api/v1/cloud/sessions/{id}/messages") {
        operationId = "listCloudMessages"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "List a session's messages"
        description = "Messages and interrupts with their acknowledgement status, newest first. Page with `before` = `nextBefore`."
        parameters {
            sessionId()
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "The page size (50 by default).")
        }
        responses {
            ok<CloudInputPageView>("A page of messages.")
            badRequest("An invalid cursor or limit (`invalid_cursor`, `invalid_query`).")
            notFound("No such session (`not_found`).")
        }
    }
    get("/api/v1/cloud/sessions/{id}/messages/{input}") {
        operationId = "getCloudMessage"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "One message"
        description = "A message or interrupt and its acknowledgement."
        parameters {
            sessionId()
            pathId("input", "The message's identifier.")
        }
        responses {
            ok<CloudInputView>("The message.")
            notFound("No such message (`not_found`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/interrupt") {
        operationId = "interruptCloudSession"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Interrupt the agent"
        description = "Stops the agent's current turn, like Escape in a terminal. Requires the `message` permission."
        parameters {
            sessionId()
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT)
        }
        responses {
            ok<CloudInputView>("Accepted.", HttpStatusCode.Accepted)
            badRequest("An invalid key, or `TeamCode-On-Behalf-Of` was sent (`invalid_idempotency_key`, `on_behalf_unsupported`).")
            notFound("No such session (`not_found`).")
            conflict("No agent is running (`nothing_to_interrupt`), or the session is not running (`session_not_running`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/tasks/stop") {
        operationId = "stopCloudTask"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Stop a background task"
        description = "Stops one background task of the running agent (a subagent, a background command, a monitor), named by the `id` " +
            "of a `tasks` event (`cloud.sessions.background-tasks.v1`). Requires the `message` permission, like an interrupt; delivered " +
            "before queued messages and never wakes a session. Consumed when the runner handed it to the agent; rejected with " +
            "`unknown_task` when the task ended meanwhile, or `task_stop_unsupported` when the session's runner cannot stop a task."
        parameters {
            sessionId()
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT)
        }
        body<StopCloudTaskRequest>("The task to stop.")
        responses {
            ok<CloudInputView>("Accepted.", HttpStatusCode.Accepted)
            badRequest("A task id that is not 1 to 64 letters, digits, `-` or `_` (`invalid_task`), an invalid key, or `TeamCode-On-Behalf-Of` was sent.")
            notFound("No such session (`not_found`).")
            conflict("No agent is running (`nothing_to_stop`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/permissions") {
        operationId = "answerCloudPermission"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Answer a permission request"
        description = "Answers a `permission.request` event of the running agent: `allow` or `deny` the tool use (with a `message` for the agent), " +
            "or answer a question (`AskUserQuestion`) with `answers`, each a question index and the chosen option indexes or a free text. " +
            "Delivered before queued messages; never wakes a session. Consumed when the agent took it; rejected with `unknown_permission_request` " +
            "when that request is no longer open, or `execution_ended` when the agent process that asked has stopped. A question needs the " +
            "`message` permission; a tool use gated by an `ask` rule is approved or denied by the session owner or a cloud administrator only. " +
            "Capability `cloud.permissions.v1`."
        parameters {
            sessionId()
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT)
        }
        body<AnswerCloudPermissionRequest>("The request id, the behavior and an optional message or answers (at most 64 KiB).")
        responses {
            ok<CloudInputView>("Accepted.", HttpStatusCode.Accepted)
            badRequest("An invalid answer, key, or `TeamCode-On-Behalf-Of` was sent (`invalid_permission_answer`, `invalid_idempotency_key`, `on_behalf_unsupported`); " +
                "an index the request does not have, or answers to a tool use, are `invalid_permission_answer` too; `allow` of a request whose input was " +
                "too large to show is `permission_input_unseen` (it can only be denied).")
            error(HttpStatusCode.Forbidden, "The caller may not send messages (`cloud_permission_denied`), or a tool use is answered by someone other than the owner or an administrator (`permission_answer_denied`).")
            notFound("No such session (`not_found`).")
            conflict("No agent is running (`nothing_to_answer`), the session is not running (`session_not_running`), no such request is in the recent " +
                "transcript (`unknown_permission_request`), or it is closed: answered (`permission_request_answered`), expired without an answer " +
                "(`permission_request_expired`) or ended with its turn (`permission_request_ended`).")
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another answer (`idempotency_conflict`).")
            error(HttpStatusCode.PayloadTooLarge, "The body exceeds 64 KiB (`payload_too_large`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/attachments") {
        operationId = "attachCloudSession"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Attach to a session"
        description = "Takes a lease saying someone is watching (`teamcode cloud attach`): idle detection and transfers " +
            "take it into account. Renew it before `leaseUntil`. Requires the `attach` permission."
        parameters { sessionId() }
        responses {
            ok<CloudAttachmentView>("Attached.", HttpStatusCode.Created)
            notFound("No such session (`not_found`).")
        }
    }
    post("/api/v1/cloud/sessions/{id}/attachments/{attachment}/renew") {
        operationId = "renewCloudAttachment"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Renew an attachment"
        description = "Extends the lease."
        parameters {
            sessionId()
            pathId("attachment", "The attachment's identifier.")
        }
        responses {
            ok<CloudAttachmentView>("Renewed.")
            notFound("No such attachment (`not_found`).")
            conflict("The lease expired or was released: attach again (`attachment_expired`).")
        }
    }
    delete("/api/v1/cloud/sessions/{id}/attachments/{attachment}") {
        operationId = "detachCloudSession"; tag(ApiTags.CLOUD_CONVERSATION)
        summary = "Detach from a session"
        description = "Releases the lease."
        parameters {
            sessionId()
            pathId("attachment", "The attachment's identifier.")
        }
        responses {
            empty("Detached.")
            notFound("No such attachment (`not_found`).")
        }
    }
}

private fun ApiCatalog.cloudTransfers() {
    post("/api/v1/cloud/sessions/{id}/transfer") {
        operationId = "transferCloudSession"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "Give a session to another member"
        description = "An owner offers the session (the recipient accepts within the offer delay); an administrator " +
            "transfers it at once. The session pauses, changes owner, then resumes for the recipient if `resume`."
        parameters {
            sessionId()
            headerText("Idempotency-Key", IDEMPOTENCY_TEXT, required = true)
            onBehalfOf()
        }
        body<CreateCloudTransferRequest>("The recipient and options.")
        responses {
            ok<CloudTransferView>("Accepted: the transfer is open.", HttpStatusCode.Accepted)
            badRequest("A missing or invalid `Idempotency-Key`, or an invalid recipient (`idempotency_key_required`, `invalid_transfer`).")
            notFound("No such session (`not_found`).")
            conflict("The session already has an open transfer (`transfer_pending`) or is still prepared (`session_preparing`).")
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another body (`idempotency_conflict`).")
        }
    }
    get("/api/v1/cloud/transfer-recipients") {
        operationId = "listCloudTransferRecipients"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "Members a session can be given to"
        description = "For a recipient picker: the team's enabled members who completed password setup, by username, " +
            "at most 500, the caller included. Only their identifier, username and whether they are an administrator. Open " +
            "to administrators, and to members while the team lets them offer sessions. A listed member can still be " +
            "refused for a given session (`422 recipient_ineligible` on the transfer)."
        responses {
            ok<CloudTransferRecipientsView>("The members, by username.")
            error(HttpStatusCode.Forbidden, "Members may not offer sessions in this team (`transfer_disabled`).")
        }
    }
    get("/api/v1/cloud/transfers") {
        operationId = "listCloudTransfers"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "List transfers"
        description = "The caller's incoming and outgoing transfers, newest first; administrators can read the team's " +
            "(`scope=team`). Page with `before` = `nextBefore`."
        parameters {
            queryText("direction", "`incoming`, `outgoing` or `all` (default).")
            queryText("scope", "`mine` (default) or `team`.")
            queryList("state", "Only these states: `offered`, `accepted`, `completed`, `declined`, `cancelled`, `expired`, `failed`.")
            queryText("session", "Only this session's transfers.")
            queryText("before", "The `nextBefore` of the previous page.")
            queryNumber("limit", "The page size, 1 to 50.")
        }
        responses {
            ok<CloudTransferPageView>("A page of transfers.")
            badRequest("An invalid filter or limit (`invalid_query`, `invalid_state`, `invalid_limit`).")
        }
    }
    get("/api/v1/cloud/transfers/{transfer}") {
        operationId = "getCloudTransfer"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "One transfer"
        description = "The transfer and who decided it."
        parameters { pathId("transfer", "The transfer's identifier.") }
        responses {
            ok<CloudTransferView>("The transfer.")
            notFound("No such transfer (`not_found`).")
        }
    }
    post("/api/v1/cloud/transfers/{transfer}/accept") {
        operationId = "acceptCloudTransfer"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "Accept an offered session"
        description = "The recipient accepts; `resume` overrides what the offer asked for. The workspace comes as the former owner " +
            "left it: on the recipient's first execution the agent's home and each repository's Git configuration and hooks are " +
            "reset, but files in the working tree (package scripts, project settings) run with the recipient's credentials."
        parameters {
            pathId("transfer", "The transfer's identifier.")
            onBehalfOf()
        }
        body<AcceptCloudTransferRequest>("Optional: whether to resume.", required = false)
        responses {
            ok<CloudTransferView>("Accepted.", HttpStatusCode.Accepted)
            notFound("No such transfer (`not_found`).")
            conflict("The offer is no longer open or expired (`transfer_not_open`, `transfer_expired`).")
        }
    }
    post("/api/v1/cloud/transfers/{transfer}/decline") {
        operationId = "declineCloudTransfer"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "Decline an offered session"
        description = "The recipient refuses; the session stays with its owner."
        parameters {
            pathId("transfer", "The transfer's identifier.")
            onBehalfOf()
        }
        responses {
            ok<CloudTransferView>("Declined.")
            notFound("No such transfer (`not_found`).")
            conflict("The offer is no longer open (`transfer_not_open`).")
        }
    }
    post("/api/v1/cloud/transfers/{transfer}/cancel") {
        operationId = "cancelCloudTransfer"; tag(ApiTags.CLOUD_TRANSFERS)
        summary = "Cancel a transfer"
        description = "The owner withdraws an offer, before the owner changes."
        parameters {
            pathId("transfer", "The transfer's identifier.")
            onBehalfOf()
        }
        responses {
            ok<CloudTransferView>("Cancelled.")
            notFound("No such transfer (`not_found`).")
            conflict("The transfer is no longer open, or the owner is already changing (`transfer_not_open`, `transfer_in_progress`).")
        }
    }
}

private fun ApiCatalog.githubOperations() {
    get("/api/v1/github/app") {
        operationId = "getGitHubApp"; tag(ApiTags.GITHUB)
        summary = "The team's GitHub App"
        description = "Whether a GitHub App is configured, and the callback URL to register in it. The client secret " +
            "and private key are never returned. Administrators only."
        responses { ok<GitHubAppView>("The App.") }
    }
    put("/api/v1/github/app") {
        operationId = "saveGitHubApp"; tag(ApiTags.GITHUB)
        summary = "Configure the GitHub App"
        description = "Cloud sessions clone and push with each member's own GitHub permissions through this App. " +
            "`version` is required to update; a null secret or key keeps the stored one. Administrators only."
        body<SaveGitHubAppRequest>("The App's identifiers and secrets.")
        responses {
            ok<GitHubAppView>("The saved App.")
            badRequest("Invalid identifiers, secret or key (`invalid_github_app`).")
            conflict("The App changed since it was read (`github_app_conflict`).")
        }
    }
    get("/api/v1/github/link") {
        operationId = "getGitHubLink"; tag(ApiTags.GITHUB)
        summary = "The caller's GitHub link"
        description = "`state` is `not_linked`, `linked` or `reconnect_required`. No token is ever returned: it only " +
            "reaches the runners of the caller's own cloud sessions."
        responses { ok<GitHubLinkView>("The link.") }
    }
    delete("/api/v1/github/link") {
        operationId = "unlinkGitHub"; tag(ApiTags.GITHUB)
        summary = "Unlink the caller's GitHub account"
        description = "Forgets the caller's GitHub tokens."
        responses { empty("Unlinked.") }
    }
    post("/api/v1/github/link/device") {
        operationId = "startGitHubDeviceLink"; tag(ApiTags.GITHUB)
        summary = "Start linking GitHub from a terminal"
        description = "GitHub's device flow: open `verificationUri`, enter `userCode`, then poll " +
            "`POST /api/v1/github/link/device/{id}` every `interval` seconds until `expiresAt`."
        responses {
            ok<GitHubDeviceLinkView>("The code to enter.", HttpStatusCode.Created)
            conflict("No App is configured (`github_app_not_configured`), the App disables the device flow " +
                "(`github_device_flow_disabled`), or it is misconfigured (`github_app_misconfigured`).")
        }
    }
    post("/api/v1/github/link/device/{id}") {
        operationId = "pollGitHubDeviceLink"; tag(ApiTags.GITHUB)
        summary = "Check a device link"
        description = "`status` is `pending` (poll again after `interval`), `linked` (with the link), `expired` or `denied`."
        parameters { pathId("id", "The device link's identifier.") }
        responses {
            ok<GitHubDeviceLinkPollView>("The progress.")
            notFound("No such device link (`github_link_not_found`).")
            conflict("The GitHub account is already linked to another member (`github_account_in_use`).")
        }
    }
    get("/api/v1/github/links") {
        operationId = "listGitHubLinks"; tag(ApiTags.GITHUB)
        summary = "Every member's GitHub link"
        description = "Administrators only."
        responses { ok<List<GitHubLinkView>>("The links.") }
    }
    get("/api/v1/github/links/{memberId}") {
        operationId = "getMemberGitHubLink"; tag(ApiTags.GITHUB)
        summary = "A member's GitHub link"
        description = "Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            ok<GitHubLinkView>("The link.")
            notFound("No such member (`member_not_found`).")
        }
    }
    delete("/api/v1/github/links/{memberId}") {
        operationId = "unlinkMemberGitHub"; tag(ApiTags.GITHUB)
        summary = "Unlink a member's GitHub account"
        description = "Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            empty("Unlinked.")
            notFound("No such member (`member_not_found`).")
        }
    }
}
