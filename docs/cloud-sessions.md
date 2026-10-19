# Cloud agent sessions (V2)

TeamCode V2 runs agent sessions on a Kubernetes cluster that an administrator connects to the team. A session is a durable, logical identity: it survives pauses, pod replacements and server restarts, and keeps one workspace volume for its whole life. The CLI and the API share the same contract; the CLI never talks to Kubernetes.

This page is the contract and the record of what is implemented. It follows the [roadmap](roadmap.md) section on Kubernetes cloud sessions and builds on [stateless replicas](replicas.md), [audit](audit.md) and [service credentials](service-keys.md).

## Status

| Part | State |
| --- | --- |
| Domain model, states and transition table | Implemented (PR 1) |
| `TransitionCloudSessionUseCase`: compare-and-set on `generation`, epoch advance with its execution row | Implemented (PR 1) |
| Leased reconciler, capacity admission (FIFO), pause/checkpoint, lost-execution handling, retention expiry | Implemented (PR 1), against the `ClusterPort` interface |
| Proven workspace deletion (claim and provider volume), `Retain` refusal and alert, orphan garbage collection | Implemented (PR 1), against the `ClusterPort` interface |
| Runner channel (register, heartbeat, checkpoint) with epoch fencing, authenticated by `TokenReview` | Implemented (rules in PR 1, routes in PR 2) |
| MySQL tables and migration: clusters, versioned profiles, assignments, sessions, labels, executions, idempotency; audit attribution columns | Implemented (PR 1) |
| Management API: settings, clusters, profiles, sessions create/list/get/executions/pause/resume/delete, `TeamCode-On-Behalf-Of` for interactive administrators, `Idempotency-Key` | Implemented (PR 1), typed client included |
| Dashboard page for cloud settings (`/dashboard/cloud`) | Implemented (PR 1) |
| Server wiring (reconciler, garbage collection and maintenance workers), `cloud.sessions.v1` advertised when `TEAMCODE_CLOUD_RUNNER_IMAGE` is set | Implemented (PR 2) |
| Kubernetes adapter (pods, claims, volumes, storage classes, TokenReview) | Implemented (PR 2) |
| Runner skeleton `teamcode cloud runner` (register, heartbeat, checkpoint; no agent) | Implemented (PR 2) |
| CLI `teamcode cloud create/list/show/pause/resume/delete` | Implemented (PR 2) |
| Opt-in `kind` smoke (`scripts/smoke-cloud-kind.py`) | Implemented (PR 2), not part of pull-request CI |
| Environment preparation: recipe on the profile version and the session, repositories, working directory, Git identity, setup commands, progress and failures in the lifecycle, `cloud.environment.v1` | Implemented (PR 3) |
| Environment variables from environment variants, GitHub access through GitHub App user tokens | Interfaces and delivery implemented (PR 3); sources planned (variants work, GitHub linking PR) |
| Transcript, messages, attach, transfer, personal and administrator keys | Planned (later PRs, see [sequence](#pull-request-sequence)) |

The server mounts the cloud routes and advertises `cloud.sessions.v1` only when cloud sessions are enabled (see [deployment](#deployment)). Clients must check that capability before using any route below.

## Resources

| Resource | Table | Owner | Purpose |
| --- | --- | --- | --- |
| Cluster | `cloud_clusters` | Administrator | API URL, CA bundle, encrypted service-account credential, allowed namespaces, `maxRunningPods`, default storage class, garbage-collection report |
| Profile | `cloud_profiles`, `cloud_profile_versions`, `cloud_profile_assignments` | Administrator; assigned to members (yes/no) | Versioned, immutable once published: cluster, namespace, image, `allowedImageOverrides`, `imagePullSecrets` names, resources, workspace size and storage class, agent kind/version, idle timeout and bounds, [environment recipe](#environment-preparation) |
| Session | `cloud_sessions`, `cloud_session_labels` | Effective member (`ownerId`) | Pinned profile version, image and digest, resolved environment recipe, desired state, state/phase, `generation`, `currentEpoch`, labels, activity, resume quality, expiry, workspace and its deletion proof |
| Execution | `cloud_executions` | Session | One pod lifetime, unique on `(session_id, epoch)`: pod name, node, image digest, registration, heartbeats, exit reason, checkpoint result |
| Idempotency record | `cloud_idempotency` | Team + initiating member | The session created by a keyed request, kept 24 hours |

Planned tables: `cloud_session_events` (transcript), `cloud_session_inputs` (messages and interrupts), `cloud_transfers`, GitHub grants.

Credentials are never stored in these tables, except the cluster's service-account token, encrypted with the server's credential key and bound to the cluster id. No API returns it.

Groups do not exist in TeamCode yet, so profiles are assigned to members. Administrators may use every profile.

## Session states

`state` is a closed, stable enum. `phase` is an open, informational string; clients must tolerate unknown values.

| State | Meaning | Typical `phase` values |
| --- | --- | --- |
| `creating` | Accepted; waiting for capacity, provisioning the workspace and pod | `scheduling`, `waiting_capacity`, `starting_runner` |
| `preparing` | The runner registered for the first execution and is preparing the environment | `starting_runner` |
| `ready` | Prepared; accepts work | - |
| `active` | An agent turn is running (planned with the agent) | - |
| `pausing` | Draining and checkpointing | `draining`, `checkpointing`, `stopping_pod` |
| `paused` | No pod; workspace retained | - |
| `resuming` | A new execution is starting on the same workspace | `waiting_capacity`, `scheduling`, `starting_runner`, `restoring` |
| `transferring` | Changing owner at a paused boundary (planned) | - |
| `terminated` | Deleted or expired; no pod; workspace being deleted or deleted | `cleanup_pending`, `deleting_volume`, `cleaned` |
| `failed` | Unusable; the workspace never held work or is lost | - |

Other fields: `desiredState` (`running`, `paused`, `terminated`), `failure` and `resumeBlocked` (`{code, message, retryable}`), `resume` (`workspace`: `consistent`, `crash_consistent` or `lost`; `conversation`: `resumable`, `transcript_only` or `none`; always `none` until the agent lands), and `workspaceDeletion` (see [deletion](#workspace-and-proven-deletion)).

### Transitions

| From | To | Trigger |
| --- | --- | --- |
| (new) | `creating` | `POST /cloud/sessions` |
| `creating` | `preparing` | The runner registered for the current epoch |
| `creating`, `preparing` | `failed` | Missing or retaining storage class, namespace no longer allowed on the cluster, image or start failure, 15-minute start timeout, failed environment preparation (`preparation_failed`), 30-minute preparation timeout, or execution lost during the first execution. The workspace is deleted right away. |
| `preparing`, `resuming` | `ready` | The runner reported ready on a heartbeat. The agent is never reported ready earlier. |
| `ready`, `active` | `pausing` | Desired state `paused` (explicit pause; idle timeout and transfer are planned), or the runner's checkpoint arriving before the reconciler's own move |
| `pausing` | `paused` | Checkpoint completed (`consistent`), or failed, missed heartbeats or 15-minute pausing deadline (`crash_consistent`) |
| `ready`, `active`, `pausing` | `paused` | Execution lost: no heartbeat for 90 s, the pod disappeared or stopped, or the execution row is missing (`execution_missing`, after deleting any pod of the session). `crash_consistent`, and the desired state becomes `paused`, so a lost execution never restarts by itself. |
| `paused` | `resuming` | Desired state `running` (explicit resume; wake by message is planned) |
| `resuming` | `paused` | Image unavailable, start failure or timeout, failed environment preparation or 30-minute preparation timeout once the runner registered, lost execution, namespace no longer allowed, or a pause requested during the resume. Sets `resumeBlocked`, keeps the workspace, never `failed`. |
| `resuming` | `failed` | The workspace claim is lost |
| any | `terminated` | `DELETE`, or retention expiry while paused. A `failed` session keeps its deletion progress (a workspace already proven deleted stays proven). |

The table lives in `CloudStateMachine`; every write is checked against it.

## Coordination across replicas

API routes only write the desired state. A **leased reconciler** running on any replica does all cluster work, outside any database transaction:

- **Compare-and-set.** Every change is an Exposed conditional update `WHERE id = ? AND generation = ?` that increments `generation`. A lost race re-reads the row and evaluates the decision again (`TransitionCloudSessionUseCase`).
- **Lease per session.** `reconcile_owner`/`reconcile_until` use the database clock. A pass claims up to 8 due sessions with a 60 s lease; a session not reached within half the lease is released due immediately instead of being worked on with a lease about to expire. Reconciler writes also require the lease to still be held, so a stale owner whose lease expired cannot commit, and cannot release its successor's lease. A write made during a pass (for example a pause request) sets `reconcile_requested`, and the lease release schedules another pass immediately.
- **Epoch fencing.** Starting an execution advances `currentEpoch` by one and inserts the `cloud_executions` row in the same transaction, serialized on the cluster row with the capacity check. The `(session_id, epoch)` key and the generation check make a duplicate start impossible, even with two replicas holding the same stale view. The pod name is deterministic (`tc-<session>-e<epoch>`); creating an existing pod succeeds. Pods of older epochs are deleted and confirmed gone before the next epoch starts.
- **Runner fencing.** Every runner call is bound to `(session, epoch)` and to the pod name of the execution. Anything else returns `409 execution_fenced`; the runner must then stop its agent and exit without checkpointing.
- **Image pulls.** `InvalidImageName` and `ErrImageNeverPull` fail the start at once. `ErrImagePull` and `ImagePullBackOff` are retried by the kubelet (registry outage, rate limit, tag pushed late), so they fail the start only after 5 minutes.
- **Errors.** A step that throws (cluster or database unavailable, or a defect) is retried after 30 s; the reconciler takes an error callback so the server logs and counts it, and the raw error never reaches the session or its API view.
- **Bare pods.** A Job controller could recreate a pod outside TeamCode's epoch control, so the adapter creates bare pods with `restartPolicy: Never`; the reconciler alone decides whether to start again.
- **Exclusive volume.** The workspace claim requests `ReadWriteOncePod` by default, so the cluster itself refuses a second pod on the workspace. `TEAMCODE_CLOUD_WORKSPACE_ACCESS_MODE=ReadWriteOnce` is for drivers without it (the local `kind` provisioner); the epoch rule above still applies.

Due times: about 2 s while a step is in progress, 5 s while waiting for capacity, 15 s while running (heartbeat watch), the expiry time while paused, 10 minutes while a deletion is blocked, and nothing once a deletion is proven.

## HTTP API under `/api/v1/cloud`

### Principals and the effective member

| Principal | Cloud rights in this release |
| --- | --- |
| Member (interactive login) | Own sessions; assigned profiles |
| Administrator (interactive login) | Every team session; clusters, profiles and settings |
| Service/CI key | None: every cloud path is classified interactive-only |
| Personal key `cloud:read`/`cloud:write`, administrator key `admin:cloud` | Planned with the V2 API keys |
| Runner | `/api/v1/cloud/runner/*` only, for its own session and epoch, with its projected pod token |

`TeamCode-On-Behalf-Of: <memberId>` sets the effective member. It is accepted only from an interactive administrator (administrator keys follow with the V2 keys). On any other credential the header must be absent or equal to the caller's own id; otherwise `403 on_behalf_forbidden`. The target must be an enabled team member who completed password setup (`422 invalid_effective_member`), and for creation it must hold the profile assignment. The header never grants rights: the call is authorized with the caller's own rights.

For creation, the effective member becomes the owner; the retained-session cap and profile assignment resolve for that member.

### Attribution and audit

Sessions record `createdBy {initiatorId, credentialId, effectiveMemberId}`; `credentialId` is the id of the login session or key, never its token. The audit journal gained two nullable columns, `effective_member_id` and `credential_id`, exposed as optional fields of `GET /api/v1/audit` events. Journaled actions: `cloud.session.created`, `.paused`, `.resumed`, `.deleted` (by the caller), and `.pausing`, `.paused_checkpoint`, `.ready`, `.ready_resumed`, `.execution_lost`, `.resume_blocked`, `.failed`, `.expired`, `.terminated`, `.workspace_deleted` (by `system`, with the owner as effective member); `cloud.cluster.saved`, `cloud.profile.saved` and `cloud.settings.updated`.

### Idempotency

`POST /cloud/sessions` requires `Idempotency-Key` (1–128 characters from `[A-Za-z0-9._:-]`). Scope: team + initiating member + route. The server stores a SHA-256 of the canonical request (the decoded body with sorted labels, plus the effective member) in the same transaction as the session:

- same key, same request: `202` with the session and `Idempotency-Replayed: true`;
- same key, different request or effective member: `422 idempotency_conflict`;
- concurrent requests with one key are serialized on the member row; one creates, the others replay.

The replay returns the **current** view of the created session rather than a byte copy of the first response. Records expire after 24 hours (bounded purge).

Pause, resume and delete are naturally idempotent through the desired state. `Idempotency-Key` for messages and transfers, and the `clientMessageId` alias, are planned with those routes.

### Routes

| Route | Purpose | Authorization | State |
| --- | --- | --- | --- |
| `GET/PUT /cloud/settings` | Persistent settings | Administrator | Implemented |
| `GET/POST /cloud/clusters`, `PUT /cloud/clusters/{id}` | Clusters; `PUT` requires `version`; omitted CA/token keep the stored values. The API URL is trusted administrator input: `https` only, loopback (`kind`) and private addresses allowed, link-local, unspecified and cloud metadata hosts refused (`400 cluster_host_not_allowed`). A namespace removed from a cluster is refused for new sessions (`409 namespace_not_allowed`) and blocks the start of existing ones | Administrator | Implemented |
| `GET /cloud/profiles` | Profiles assigned to the caller (all for administrators) | Member, administrator | Implemented |
| `POST /cloud/profiles`, `PUT /cloud/profiles/{id}` | Publish a new immutable version; `version` is the version being replaced | Administrator | Implemented |
| `POST /cloud/sessions` | Create; `202` | Member, administrator; owner = effective member | Implemented |
| `GET /cloud/sessions` | List: own sessions; `?owner=<id>` or `?owner=all` for administrators; `state` and `label=k=v` (repeatable) filters; `before=<sessionId>` cursor; `limit` ≤ 50 | Member, administrator | Implemented |
| `GET /cloud/sessions/{id}` | Read | Owner, administrator | Implemented |
| `GET /cloud/sessions/{id}/executions` | Pod history: epoch, node, digest, exit reason, checkpoint | Owner, administrator | Implemented |
| `POST /cloud/sessions/{id}/pause` | `{force?: false}`; `202`. `409 session_preparing` while creating or preparing; repeated pauses write nothing | Owner, administrator | Implemented |
| `POST /cloud/sessions/{id}/resume` | `202`; synchronous recheck that the owner is enabled and still assigned to the profile (`403 owner_unavailable`, `403 profile_unassigned`) | Owner, administrator | Implemented |
| `DELETE /cloud/sessions/{id}` | Terminate and delete the workspace; `204`, also when already terminated | Owner, administrator | Implemented |
| `POST /cloud/runner/register`, `.../heartbeat`, `.../checkpoint` | Runner channel: `{teamId, sessionId, epoch}` (plus `ready` or `completed`); returns `{desiredState, pauseDeadline, epoch}`. `401 runner_unauthenticated`, `409 execution_fenced` | Pod token (`TokenReview`) | Implemented |
| `POST /cloud/runner/environment` | The recipe, the owner's Git identity, variable values and GitHub token for this execution (`Cache-Control: no-store`). Only the registered runner of the current epoch, while `preparing` or `resuming`; otherwise `409 not_preparing`. Missing required variables or GitHub access: `409 preparation_failed` | Pod token | Implemented |
| `POST /cloud/runner/preparation` | `{phase}` progress, or `{phase, failure: {step, index, exitCode}}`; returns the instruction | Pod token | Implemented |
| `/cloud/sessions/{id}/events`, `.../events/stream`, `/cloud/events/stream` | Transcript pages, per-session SSE, caller-wide SSE feed | | Planned |
| `/cloud/sessions/{id}/messages`, `.../interrupt`, `.../attachments` | Messages, interrupt, CLI attach lease | | Planned |
| `/cloud/sessions/{id}/transfer`, `/cloud/transfers...` | Ownership transfer | | Planned |

A session the caller cannot see returns `404`. Errors use the existing `ApiError {error: {type, message}}`.

### Create request and view

```json
POST /api/v1/cloud/sessions
Idempotency-Key: task-1234-create
TeamCode-On-Behalf-Of: 6f1c...member-uuid

{
  "name": "Task 1234: fix relist retry",
  "profileId": "b2d0...uuid",
  "image": null,
  "agent": { "model": "opus", "effort": "high" },
  "labels": { "orchestrator.task": "1234" },
  "idleTimeoutMinutes": 30,
  "environment": {
    "repositories": [{ "url": "https://github.com/example/api.git", "path": "api", "ref": "main", "branch": "task-1234" }],
    "workingDirectory": "api"
  }
}
```

- `image` must equal the profile image or match one of its `allowedImageOverrides` (`*` matches any run of characters, everything else is literal); otherwise `403 image_not_allowed`.
- `labels`: at most 16 pairs, lowercase keys up to 63 characters, values up to 256, not secret, filterable.
- `idleTimeoutMinutes` is clamped to the profile bounds (stored; automatic idle pause is planned with activity signals).
- `agent.model` and `agent.effort` are recorded; they are checked against the member's model policy on every inference, as for local sessions.
- `environment` (with `cloud.environment.v1`) replaces the profile's `repositories` and `workingDirectory` for this session; setup commands, required variables and the other recipe fields come from the profile only. Null fields keep the profile's values.
- `initialMessage` is planned with messages. The server ignores unknown fields, so do not send them before the matching capability is advertised.

The response is the session view: `id`, `name`, `ownerId`, `createdBy`, `state`, `phase`, `desiredState`, `generation`, `currentEpoch`, `profile {id, version}`, `image {reference, digest}`, `agent {kind, version, model, effort}`, `labels`, `activity {lastActivityAt, idleTimeoutMinutes}`, `resume`, `failure`, `resumeBlocked`, `workspaceDeletion`, `createdAt`, `updatedAt`, `pausedAt`, `expiresAt`, `terminatedReason`, `environment` (the resolved recipe). The image digest is recorded from the first running container; every later execution uses `reference@digest`, so a moved tag does not change a resumed session.

### Bounds and capacity

- Settings `maxRunningSessionsPerMember` (default 2) and `maxRetainedSessionsPerMember` (default 20) apply to every member; per-member overrides in member policy are planned.
- Each cluster has `maxRunningPods`. Executions not yet ended count, for all sessions on the cluster.
- A creation or resume beyond the running caps waits in `creating`/`resuming` with `phase = waiting_capacity`. Admission is FIFO per cluster: an older waiting session goes first unless its owner is at their own cap.
- The retained cap (sessions neither terminated nor failed) is enforced synchronously: `409 cloud_session_limit`.
- Inference from a pod will use the existing ledger, routing and pacing. Pod-hours are not metered as quota.

## Persistent settings

Administrators edit them through `PUT /api/v1/cloud/settings` or **Dashboard → Cloud** (`/dashboard/cloud`), like the `experimentalCrossProviderTranslation` model setting. They live in `cloud_settings`; a team without a row uses the defaults.

| Setting | Default | Meaning |
| --- | --- | --- |
| `pausedRetentionDays` | 30 | Days a paused session keeps its workspace. `null` keeps it indefinitely. |
| `failedWorkspaceRetentionDays` | 7 | Days a session that failed after its first execution keeps its workspace. |
| `maxRunningSessionsPerMember` | 2 | Running cap per member. |
| `maxRetainedSessionsPerMember` | 20 | Retained cap per member. |

`expiresAt` is computed when the session pauses, from the setting in force at that moment; changing the setting applies to sessions paused afterwards, so shortening it never deletes existing work by surprise. Resuming clears it. At expiry the session is terminated with reason `expired` and its workspace deleted as below. The 7-day and 1-day expiry notices need the transcript and feed, and are planned.

The transfer policy will be a setting too, with these defaults: pause first, undelivered messages cancelled, former owner without access. It ships with the transfer PR; nothing in this release reads it.

## Workspace and proven deletion

Each session has one PersistentVolumeClaim, `tc-ws-<session>`, created before its first pod, kept while paused, and deleted at the end. Deleting a claim is not enough: the provider volume behind it (for example an OpenStack Cinder volume) must be gone too.

- **Refusing retention.** Publishing a profile checks the workspace storage class on the cluster and refuses one whose `reclaimPolicy` is not `Delete` (`422 storage_class_retains_volumes`), or a missing one (`422 storage_class_missing`). The reconciler checks again before creating a claim and fails the session with the same code, since the class can change after publication.
- **Proof.** When a session ends, the reconciler records the bound PersistentVolume name and the provider volume handle (for CSI volumes, `spec.csi.volumeHandle`), deletes the claim, waits until the claim is gone, then waits until the PersistentVolume is gone. With `reclaimPolicy: Delete`, the external provisioner removes the PersistentVolume only after the driver's `DeleteVolume` call succeeded on the provider, so its disappearance is the proof that the provider volume was deleted. `workspaceDeletion.state` becomes `proven` with `claimDeletedAt` and `volumeDeletedAt`; the handle stays on the session as evidence, and `cloud.session.workspace_deleted` is journaled.
- **Alerts.** A volume found with a non-`Delete` policy, in phase `Failed`, or still present one hour after its claim was deleted marks the deletion `blocked` with `volume_retained` or `volume_deletion_stalled`, rechecked every 10 minutes. The administrator deletes the provider volume and the PersistentVolume; the next check then proves the deletion.
- **Garbage collection.** For each cluster, a pass lists TeamCode-labelled pods and claims (`app.kubernetes.io/managed-by=teamcode` and the cluster id label, so another cluster record or server sharing a namespace is never touched) in the allowed namespaces and in any namespace that still holds a session not yet proven deleted, and the released or failed volumes of TeamCode workspaces, before reading which claims and current-epoch pods the database still accounts for. Objects are created only after their row commits, so this order never mistakes a fresh object for an orphan. Orphaned pods and claims are deleted; orphaned volumes (claim in one of those namespaces) that would not be deleted by their provisioner are counted in the cluster's `orphanedVolumes`, with `lastGarbageCollectionAt`. A scheduled worker runs it every 10 minutes on one replica.
- **Acceptance.** `CloudSessionPersistenceTest` verifies that no claim or volume remains after deletion with a fake cluster that models the provisioner. `KindSmokeTest` verifies it on a real cluster through the Kubernetes adapter; on OpenStack, the recorded handle can also be checked with `openstack volume show`.

## Pause and resume

1. `POST /pause` sets `desiredState = paused`. The reconciler moves `ready`/`active` to `pausing`; a checkpoint the runner sends first (it learns of the pause from its heartbeat) is accepted and makes the same move.
2. The runner's heartbeat response carries `desiredState` and `pauseDeadline` (10 minutes after the request, or immediate with `force: true`). The runner drains, stops its child processes, writes its checkpoint and calls `checkpoint {completed}`.
3. The reconciler deletes the pod, confirms its deletion, ends the execution and marks the session `paused` with `consistent`.
4. A failed checkpoint, missed heartbeats or the 15-minute pausing deadline force-delete the pod: `paused` with `crash_consistent`. It is never advertised as `consistent`.
5. `POST /resume` rechecks the owner and assignment, then sets `desiredState = running`. The reconciler moves the session to `resuming`, waits for capacity, starts epoch n+1 on the same workspace with the pinned digest, and the runner's ready heartbeat makes it `ready`.

Inactivity only pauses; it never deletes. Idle detection (turns, pending inputs, preparation), the idle warning and `maxTurnMinutes` are planned with the agent and messages.

## The runner and the pod

- **Pod.** One bare pod per execution, `tc-<session>-e<epoch>`, `restartPolicy: Never`, labelled `app.kubernetes.io/managed-by=teamcode` with the session, cluster and epoch. Non-root (UID/GID/fsGroup 1000, `runAsNonRoot`), `seccompProfile: RuntimeDefault`, no privilege escalation, all capabilities dropped, `automountServiceAccountToken: false`, no service links. CPU and memory requests equal limits from the profile. The workspace claim is mounted at `/workspace`, `HOME` is `/workspace/.home` so agent state survives pauses.
- **Delivery.** An init container from the runner image (`TEAMCODE_CLOUD_RUNNER_IMAGE`) copies the `teamcode` Linux binary (`TEAMCODE_CLOUD_RUNNER_BINARY`, default `/usr/local/bin/teamcode`) into an `emptyDir` at `/opt/teamcode`, with `cp`; the session container runs `/opt/teamcode/teamcode cloud runner`. The team image stays a pure toolchain (Linux with the published binaries' glibc baseline, `/bin/sh`, `git`, writable by UID 1000). Bundled agent CLIs come with the agent PR.
- **Identity.** A projected ServiceAccount token, audience `teamcode`, 600 s expiry (the API minimum; the kubelet rotates the file, and the runner reads it for every call). The server reviews it with `TokenReview` using the cluster credential and requires the bound namespace to be the session's and the bound pod name to be the current execution's pod; the pod name always comes from the review, never from the request. A pod of the session with another epoch gets `409 execution_fenced` and exits. Reviews are cached 60 s per token (refusals 10 s, in a separate cache so bad tokens never evict accepted ones), so a heartbeat every 15 s costs one review a minute per pod. No TeamCode secret exists in the cluster, and the pod environment holds only the server URL, the token file path and the team, session and epoch identifiers.
- **Channel.** HTTPS only (the client refuses plain HTTP except on loopback): register, heartbeat every 15 s (the response carries the desired state and pause deadline), checkpoint; later batched event upload and an input long-poll. It fits stateless replicas with MySQL as the only bus and needs no inbound connectivity to the pod. Network failures are retried with backoff; the server marks the execution lost after 90 s without heartbeats.
- **Runner.** This release's runner starts no agent: it registers, [prepares the environment](#environment-preparation) while heartbeating, reports ready once prepared, and checkpoints immediately when a pause is requested (there is nothing to drain yet). It exits on `409 execution_fenced` or when the session is being deleted, retries any other `409` (for example a checkpoint refused because the pause was cancelled), and exits with code 2 on `401`. The client reads only the error code of a failed runner call, never its message.
- **Token exposure (planned fix).** The projected token is readable by the pod's UID. With no agent in this release only the runner runs there; before the agent lands, the agent must run under a separate UID that cannot read the token, so it cannot drive the runner channel (for example report a clean checkpoint).
- **Planned.** A `tcex_` execution credential (hashed, bound to session, epoch and owner, revoked on pause, transfer, delete and fencing) for the inference the runner's loopback proxy forwards, with the agent PR; a one-time Secret fallback where `TokenReview` is not permitted.
- **Kubernetes client.** The adapter (`KubernetesClusterPort`) calls the Kubernetes REST API with the Ktor client the server already uses, rather than fabric8 or the official Java client. The surface is small (pods, claims, volumes, storage classes, TokenReview), the server stores each cluster's URL, CA and token instead of a kubeconfig, and the adapter is tested with Ktor's mock engine. Each cluster gets its own client whose trust store holds only that cluster's CA bundle; the connection is reloaded from the database at most every 30 s, so a rotated token applies quickly. Lists are paged (500 per page, at most 20 pages, 200 for the cluster-wide PersistentVolume list); a page missing mid-list fails the call rather than passing for a complete list. Concurrent first uses of a cluster build one client, and a client replaced after a rotation stays open until shutdown for requests in flight. API error bodies are never kept or logged. It adds no dependency, and nothing reaches the native CLI, which keeps `cli -> client -> api`.

## Environment preparation

Toolchain and preparation are separate. The **image** carries what is installed (for example Node.js, a JDK, Gradle); the **recipe** says what to do with it in each session. Neither contains a TeamCode or provider credential.

### Image

- **Per profile, per session.** The profile names the image; a session may name another one only if it matches the profile's `allowedImageOverrides`. Private registries authenticate through the profile's `imagePullSecrets`, Kubernetes Secrets the administrator manages in the namespace; TeamCode never stores registry credentials.
- **Digest.** The digest of the first running container is recorded on the session (`image.digest`) and on the execution; every later execution starts `reference@digest`, so a resume prepares on the same bits even if the tag moved.
- **Contract.** Linux with the published binaries' glibc baseline, `/bin/sh` and POSIX `mkdir`, `rm`, `mv`, `rmdir`, `dirname`, `touch`, `test`; `git` 2.31 or later (credentials are configured through `GIT_CONFIG_COUNT`); writable by UID 1000. `gh` is optional. Nothing from TeamCode: the runner binary is copied in by the init container, and the agent CLIs come with the agent PR.
- **No baked identity.** Nothing in the pod spec identifies a member or a provider account: the pod environment holds only the server URL, the token file path and the team, session and epoch identifiers. Credentials reach the pod only through the runner channel, at preparation time, for the current owner.

### Recipe

The recipe is part of the immutable profile version (`spec.environment`). At creation, the session's `environment` overrides are merged in and the result is stored on the session (`cloud_sessions.environment`, JSON without any secret), so a resume prepares exactly what the first execution did, even after the profile changed. It is never edited afterwards.

| Field | Meaning |
| --- | --- |
| `repositories` | Up to 16 `{url, path, ref?, branch?}`. `https://` URLs without credentials, query or fragment; `path` relative to `/workspace` (not under `.teamcode` or `.home`, never nested in another repository); `ref` is a branch, tag or commit; `branch` is a local branch created at `ref`. Option-like and malformed refs are refused |
| `workingDirectory` | Relative to `/workspace`; must exist after cloning. Setup commands run there |
| `setup.create` | Up to 16 commands run once per workspace, in order, with `/bin/sh -c` |
| `setup.start` | Up to 16 commands run on every execution, after `create` |
| `requiredVariables` | Variable names the owner must be assigned; preparation fails without them |
| `requireGitHub` | Preparation fails when the owner has no usable GitHub access |
| `gitEmailDomain` | Email domain of the Git identity for owners without a linked GitHub account |
| `repositoryHosts` | Hosts repositories may be cloned from, `["github.com"]` by default; session repositories are checked against the profile's list, so a request cannot point the pod at metadata or cluster-internal endpoints |

A session that replaces the repositories while the profile's working directory is not inside any of them must set `workingDirectory` too (`400 invalid_environment`), rather than failing in the pod after a full clone.

### What the runner does

After registering, the runner prepares while it keeps heartbeating (`ready: false`), so a long clone or dependency install never looks like a lost pod:

1. `POST /cloud/runner/environment` returns the recipe and, resolved for the **current owner** on every call: the Git identity, the variable values and the GitHub token. A transferred session therefore never keeps its former owner's values.
2. **Git identity** (`configuring_git`): `git config --global user.name/user.email` under `HOME=/workspace/.home`, rewritten on every execution. A linked GitHub account gives `<login>` and `<id>+<login>@users.noreply.github.com`; otherwise the member name and `<name>@<gitEmailDomain>` (`users.noreply.teamcode.invalid` by default).
3. **Repositories.** If `/workspace/<path>/.git` exists, only `git fetch --prune origin` (`fetching_repositories`): work on the workspace is never reset or re-cloned. Otherwise (`cloning_repositories`) clone into `/workspace/.teamcode/clone-<n>`, check out `ref`, create `branch`, then move it into place; an interrupted clone is cleaned and retried, and a non-empty directory at the path is never overwritten.
4. The **working directory** must exist.
5. **Setup** (`running_setup`): each `create` command whose marker `/workspace/.teamcode/setup/create-<n>-<hash>.done` is missing (the hash is the command's, so an edited command runs again on a new session), then every `start` command.
6. The next heartbeat reports `ready: true`, and only then does the session become `ready`.

Command output goes to `/workspace/.teamcode/logs/prepare-e<epoch>.log` on the session's own workspace and never reaches the server, the pod log or the transcript. After each command the runner replaces known variable values and the token by `[REDACTED]` and keeps the last 4 MiB, rewriting the file atomically. Masking is best effort: a command that prints a credential (`env`, `set -x`) writes it to the log until the command ends, or for good if the pod dies first, and transformed values (base64, URL-encoded) are not recognized. Setup commands must not print credentials.

A runner error (unwritable or full workspace) is reported as step `runner` right away instead of waiting for the preparation timeout, and a runner that stops heartbeating while it prepares a resume is lost after 90 seconds, as during a first preparation.

### Credentials in child processes only

Variable values and the GitHub token live in the runner's memory and in the environment of the preparation's child processes. The runner never writes them to the pod spec, a Kubernetes Secret, a remote URL, a Git config file, the workspace or the server's database (see the log caveat above):

- `git` gets the token from a credential helper defined through `GIT_CONFIG_COUNT` for `https://github.com` only (helpers from the image are reset first), which prints `x-access-token` and the token from its own environment; other hosts never see it.
- `gh` reads `GH_TOKEN`.
- `GIT_TERMINAL_PROMPT=0`, and children do not inherit the runner's token file path.
- Owner variables never steer the runner's own commands: names that are not shell identifiers, and `PATH`, `HOME`, `SHELL`, `IFS`, `ENV`, `BASH_ENV`, `XDG_CONFIG_HOME` and the `LD_`, `DYLD_`, `GIT_`, `GH_`, `SSH_`, `TEAMCODE_` prefixes, are dropped (by name, in the pod log). The variants work refuses most of them at definition already.

The child processes run with the pod UID, like the planned agent: see the token exposure note in [the runner and the pod](#the-runner-and-the-pod).

### Variables and GitHub sources

- **Variables** come from `CloudVariableSource`. Its real source is the environment/MCP variants work (`ResolveEffectiveVariantsUseCase`, environment entries only, `unavailable` slots by name); the adapter is a few lines in `CloudServer` once that work is merged. Until then members receive no variables, and a profile with `requiredVariables` fails preparation with the missing names.
- **GitHub** comes from `GitHubAccessSource`: GitHub App user-to-server tokens of the effective member (8-hour tokens refreshed on the server, scoped to what both the App installation and the user can reach). Installation tokens are reserved for dedicated service members and personal access tokens are refused. The member linking flow (`teamcode github link`, dashboard), the encrypted token store and the serialized refresh come in their own PR; until then every owner counts as not linked, public repositories clone anonymously and `requireGitHub` profiles fail preparation.

### Progress and failures

`phase` shows `configuring_git`, `cloning_repositories`, `fetching_repositories` or `running_setup`. A failed step is reported as `{step, index, exitCode}` only; the server writes the message (for example "Cloning https://github.com/example/api.git into api failed (exit code 128). Private repositories need the owner's GitHub access."), so no command output or runner text reaches the API. The session gets `failure.code = preparation_failed`, is never reported ready, and the reconciler then stops the pod and:

- on the first execution, fails the session and deletes its workspace;
- on a resume, returns the session to `paused` with `resumeBlocked = preparation_failed` and keeps the workspace.

Clone, fetch and GitHub failures are `retryable`; setup and path failures are not. The 30-minute preparation timeout applies to resumes too once the runner registered.

## Deployment

Cloud sessions are enabled by setting `TEAMCODE_CLOUD_RUNNER_IMAGE` on the server; without it the routes stay unmounted and the capability is not advertised.

| Variable | Default | Purpose |
| --- | --- | --- |
| `TEAMCODE_CLOUD_RUNNER_IMAGE` | (unset: disabled) | Image holding the `teamcode` Linux binary and `cp`, copied into each session pod. Pin it by digest (`image@sha256:...`); it is pulled with the profile's `imagePullSecrets`, so host it publicly or where those secrets reach |
| `TEAMCODE_CLOUD_RUNNER_BINARY` | `/usr/local/bin/teamcode` | Path of the binary in that image |
| `TEAMCODE_CLOUD_SERVER_URL` | `TEAMCODE_PUBLIC_URL` | HTTPS origin the runner calls, reachable from the cluster. The server refuses to start with cloud enabled if it is not HTTPS or is loopback |
| `TEAMCODE_CLOUD_WORKSPACE_ACCESS_MODE` | `ReadWriteOncePod` | `ReadWriteOnce` for drivers without `ReadWriteOncePod` |

The cluster's service account (its token is saved with the cluster) needs, in each allowed namespace, `get`, `list`, `create` and `delete` on `pods` and `persistentvolumeclaims`; cluster-wide `get` and `list` on `persistentvolumes` and `storageclasses`; and `system:auth-delegator` for `TokenReview`. Nothing else: TeamCode never deletes a PersistentVolume itself. `scripts/smoke-cloud-kind.py` creates exactly these roles. Egress from the session namespace should be limited by a NetworkPolicy to the TeamCode server, the registries and the repositories the team uses.

Each server replica runs the reconciler continuously (leased per session) and, through scheduled work, garbage collection every 10 minutes and the idempotency-record purge every minute. A failed reconciliation step is logged with the session id and the error class or Kubernetes status only.

## The agent in the pod (planned)

- **Permissions.** Agents run in bypass mode inside the sandboxed pod: `claude --dangerously-skip-permissions` and `codex --dangerously-bypass-approvals-and-sandbox`. Cluster prerequisites: a non-root pod, an egress NetworkPolicy, no cluster credentials in the pod. API approval round-trips are a later extension behind their own capability.
- **One long-lived process with streaming input**, not one process per turn: Claude Code with `--input-format stream-json` on stdin, Codex through `codex app-server` (JSON-RPC). Messages can arrive at any time and are injected while the agent works; an interrupt stops the current turn. After a new pod, the runner resumes the conversation by its id (`--resume <id>` for Claude, thread resume for Codex) from the agent state kept on the workspace volume. Exact flags are verified against the pinned agent versions in the adapter tests. Fallback, where an agent version cannot accept input mid-turn: queue the message for the next turn.
- **Resume quality.** Resume is attempted only when the native state exists and the agent version is unchanged, or after an explicit upgrade with the agent's own compatibility check; otherwise `resume.conversation` becomes `transcript_only` and a fresh conversation starts.
- **Credential files.** Agents authenticate only through the loopback proxy; the runner refuses to checkpoint a credential file found under the agent homes.

## Other planned parts

- **GitHub.** The linking flow and token store for GitHub App user-to-server tokens (see [variables and GitHub sources](#variables-and-github-sources)): 8-hour tokens, rotating refresh serialized with the existing `refresh_pending` pattern, members link in the dashboard or with `teamcode github link`. Preparation already passes the token to Git and `gh` through child-process environments only; for the long-lived agent, the token moves to a credential helper over a tmpfs socket, never the workspace, a remote URL, an event or an image.
- **Transcript.** Normalized events (`message.user`, `message.agent`, `tool.call`, `tool.result`, `turn`, `status`, `error`) with per-session sequence numbers, bounded sizes, secret masking, no thinking blocks; cursor pagination in both directions; per-session SSE with `Last-Event-ID`; a caller-wide SSE feed of notices for orchestrators; webhooks later.
- **Messages.** `POST .../messages` with `Idempotency-Key` or `clientMessageId`; acknowledgement levels `queued`, `delivered`, `consumed`, `cancelled`, `rejected`; queue and wake by default (`wake: false` per message, `wakeOnMessage` per profile); `expectedLastEventSeq` against stale views; at most 100 pending messages.
- **Attach.** `teamcode cloud attach`: structured attach over the shared event stream and message routes, with an attachment lease; it may come after the other CLI commands if it proves more complex. A PTY attach is a possible later addition.
- **Transfer.** Administrator transfer is immediate; an owner's request is an offer the recipient accepts within 7 days. Transfer happens at a checkpoint, revokes execution and attachment credentials, rechecks the recipient, and charges future work to the recipient while historical usage stays with the original member. The policy follows the settings above.
- **Keys.** `cloud:read`/`cloud:write` personal scopes and `admin:cloud` administrator keys with `TeamCode-On-Behalf-Of`, with the V2 API keys. This enables the driving integration: an external orchestrator creating one session per task on behalf of a member, relaying messages from mobile through its own backend, without administrator keys ever reaching a device.

## CLI

```sh
teamcode cloud create --name TEXT --profile ID [--label K=V]... [--image REF] [--repo [PATH=]URL[#REF]]... [--branch NAME] [--workdir PATH] [--model M] [--effort E] [--idle-minutes N] [--member MEMBER_ID] [--idempotency-key KEY] [--json]
teamcode cloud list [--all|--member ID] [--state STATE]... [--label K=V]... [--json]
teamcode cloud show SESSION [--executions] [--json]
teamcode cloud pause SESSION [--force] [--member ID]
teamcode cloud resume SESSION [--member ID]
teamcode cloud delete SESSION --yes [--member ID]
teamcode cloud runner            # hidden; pod entrypoint only
```

`--member` sends `TeamCode-On-Behalf-Of` (and, for `list`, filters by owner), `--json` prints the API DTOs unchanged, and `create` sends a random idempotency key unless one is given, so a script can retry with its own. `list` follows pages up to 500 sessions. `delete` refuses without `--yes`. `--repo` (repeatable) replaces the profile's repositories, the path defaulting to the repository name; `--branch` creates that local branch in each of them. `logs`, `send`, `attach`, `interrupt`, `transfer(s)`, the administration commands and `teamcode github ...` come with their features.

## Pull request sequence

| PR | Content | Capability |
| --- | --- | --- |
| 1 | Contract; domain, persistence, reconciler, deletion proof and GC over `ClusterPort`; management API and client; settings; audit columns | Not advertised by the server |
| 2 (stacked on 1) | Kubernetes adapter, runner routes and skeleton, server wiring and workers, CLI commands, opt-in `kind` smoke | `cloud.sessions.v1` |
| 3 | Environment preparation (image contract, recipe, repositories, Git identity, setup, progress and failures), variable and GitHub interfaces | `cloud.environment.v1` |
| 3b | GitHub App linking, encrypted user-token store and refresh; variants source wired once the variants work is merged | - |
| 4 | Agent adapters (long-lived streaming process), transcript, SSE | `cloud.transcript.v1` |
| 5 | Messages, interrupt, idle pause, attach | `cloud.messages.v1`, `cloud.attach.v1` |
| 6 | Transfer and its settings | `cloud.transfer.v1` |
| Cross-cutting | V2 keys (`cloud:*`, `admin:cloud`), MCP gateway and skills in the runner | - |

## Evidence

- `CloudSessionPersistenceTest` (real MySQL 8.4, two independent connection pools): the reviewed migration matches the Exposed tables; encrypted cluster credentials; profile versioning and the `Retain` refusal; administrator delegation with attribution in the session and the audit journal; concurrent creations with one idempotency key creating one session; the full create, prepare, ready, pause, checkpoint and paused cycle with no pod left; runner fencing by epoch and pod name; concurrent resumes from both pools producing one epoch, including two reconcilers holding the same lease; stale-owner fencing after lease expiry; lost-execution handling; FIFO capacity admission; proven deletion leaving no claim or volume; the blocked deletion and orphaned-volume alert for a retained volume; garbage collection of orphaned pods and claims; retention expiry; the retained cap and keyset pagination.
- `CloudRoutesTest`: service keys refused on cloud routes, `Idempotency-Key` validation and canonical replay, `TeamCode-On-Behalf-Of` refused for members, visibility as `404`, natural idempotency of pause and delete through the typed client, query validation, and the settings API and dashboard form with CSRF.
- `CloudLifecycleRulesTest`: the transition table, request validation, image override patterns, cluster API hosts and settings bounds.
- `CloudRoutesTest` (runner): only the current pod's projected token is accepted; member credentials, another namespace and unbound tokens get `401`, another epoch `409`; reviews are cached.
- `KubernetesClusterPortTest` (Ktor mock engine): claim, volume, storage-class and pod parsing; idempotent create and delete; the pod manifest (bare, non-root, no API token, projected token, resources, pull secrets); `TokenReview` parsing and refusals; label-selected paging; error bodies never surfaced.
- `CloudRunnerTest`: the runner skeleton registers with backoff, checkpoints once when paused, exits when fenced or deleted, and stops on `401`.
- `CloudSessionPersistenceTest` (environment): the recipe is stored in the immutable profile version and resolved onto the session with its overrides; missing required variables refuse the environment and fail the first execution with the names only, freeing the workspace; the owner's variables and Git identity reach the runner and no value is stored with the session; a failed preparation is never reported ready; a failed setup command on a resume returns the session to `paused` with `resumeBlocked` and keeps the workspace. Removing the reconciler's preparation check makes it fail.
- `CloudRoutesTest` (environment): `cloud.environment.v1`; overrides on creation; credentials in a clone URL refused; only the registered runner reads its environment, with `no-store`; progress phases; unknown phases and step indexes refused; a reported failure blocks readiness.
- `CloudEnvironmentRulesTest`: URL, path, ref, setup and variable validation, merge, Git identity, server-written messages, redacted diagnostics.
- `CloudPreparationTest` and `CloudPreparationProcessTest` (real `/bin/sh` and `git` against a local repository): clone into staging, checkout, branch and move into place; fetch only on a second preparation, with uncommitted work intact; `create` steps once and `start` steps every time; values masked in the log; the token only in child environments and a github.com-scoped helper; failures reported as fields; heartbeats continue during preparation and report ready only after it.
- `KindSmokeTest` through `scripts/smoke-cloud-kind.py` (opt-in): on a throwaway `kind` cluster with the least-privilege roles above, the real reconciler, stores and adapter create the claim and pod, a pod-bound token passes `TokenReview` (and one for another audience does not), the session pauses with a checkpoint and keeps its workspace, and deletion is proven with no claim or PersistentVolume left.

Migration note: this branch's migration is numbered after its base (`V20261019153908`). V1 work on `main` adds migrations too; the files are renumbered after the latest `main` migration when this branch is updated for merge.
