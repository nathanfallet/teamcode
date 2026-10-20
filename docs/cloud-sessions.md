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
| Runner channel rules (register, heartbeat, checkpoint) with epoch fencing | Implemented in the domain and persistence (PR 1); HTTP routes planned (PR 2) |
| MySQL tables and migration: clusters, versioned profiles, assignments, sessions, labels, executions, idempotency; audit attribution columns | Implemented (PR 1) |
| Management API: settings, clusters, profiles, sessions create/list/get/executions/pause/resume/delete, `TeamCode-On-Behalf-Of` for interactive administrators, `Idempotency-Key` | Implemented (PR 1), typed client included |
| Dashboard page for cloud settings (`/dashboard/cloud`) | Implemented (PR 1) |
| Server wiring, `cloud.sessions.v1` advertised by the server | Planned (PR 2): the server enables the cloud routes and the reconciler only together with the Kubernetes adapter |
| Kubernetes adapter (pods, claims, volumes, storage classes, TokenReview) | Planned (PR 2) |
| Runner skeleton `teamcode cloud runner` (register, heartbeat, checkpoint; no agent) | Planned (PR 2) |
| CLI `teamcode cloud create/list/show/pause/resume/delete` | Planned (PR 2) |
| Opt-in `kind` smoke test | Planned (PR 2) |
| Environment preparation, GitHub, transcript, messages, attach, transfer, personal and administrator keys | Planned (later PRs, see [sequence](#pull-request-sequence)) |

Until PR 2 merges, the server does not mount the cloud routes and does not advertise `cloud.sessions.v1`. Clients must check that capability before using any route below.

## Resources

| Resource | Table | Owner | Purpose |
| --- | --- | --- | --- |
| Cluster | `cloud_clusters` | Administrator | API URL, CA bundle, encrypted service-account credential, allowed namespaces, `maxRunningPods`, default storage class, garbage-collection report |
| Profile | `cloud_profiles`, `cloud_profile_versions`, `cloud_profile_assignments` | Administrator; assigned to members (yes/no) | Versioned, immutable once published: cluster, namespace, image, `allowedImageOverrides`, `imagePullSecrets` names, resources, workspace size and storage class, agent kind/version, idle timeout and bounds |
| Session | `cloud_sessions`, `cloud_session_labels` | Effective member (`ownerId`) | Pinned profile version, image and digest, desired state, state/phase, `generation`, `currentEpoch`, labels, activity, resume quality, expiry, workspace and its deletion proof |
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
| `creating`, `preparing` | `failed` | Missing or retaining storage class, namespace no longer allowed on the cluster, image or start failure, 15-minute start timeout, 30-minute preparation timeout, or execution lost during the first execution. The workspace is deleted right away. |
| `preparing`, `resuming` | `ready` | The runner reported ready on a heartbeat. The agent is never reported ready earlier. |
| `ready`, `active` | `pausing` | Desired state `paused` (explicit pause; idle timeout and transfer are planned), or the runner's checkpoint arriving before the reconciler's own move |
| `pausing` | `paused` | Checkpoint completed (`consistent`), or failed, missed heartbeats or 15-minute pausing deadline (`crash_consistent`) |
| `ready`, `active`, `pausing` | `paused` | Execution lost: no heartbeat for 90 s, the pod disappeared or stopped, or the execution row is missing (`execution_missing`, after deleting any pod of the session). `crash_consistent`, and the desired state becomes `paused`, so a lost execution never restarts by itself. |
| `paused` | `resuming` | Desired state `running` (explicit resume; wake by message is planned) |
| `resuming` | `paused` | Image unavailable, start failure or timeout, lost execution, namespace no longer allowed, or a pause requested during the resume. Sets `resumeBlocked`, keeps the workspace, never `failed`. |
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
- **Bare pods.** A Job controller could recreate a pod outside TeamCode's epoch control, so the adapter (PR 2) creates bare pods with `restartPolicy: Never`; the reconciler alone decides whether to start again.
- **Exclusive volume.** The workspace claim requests `ReadWriteOncePod` where the CSI driver supports it (adapter, PR 2).

Due times: about 2 s while a step is in progress, 5 s while waiting for capacity, 15 s while running (heartbeat watch), the expiry time while paused, 10 minutes while a deletion is blocked, and nothing once a deletion is proven.

## HTTP API under `/api/v1/cloud`

### Principals and the effective member

| Principal | Cloud rights in this release |
| --- | --- |
| Member (interactive login) | Own sessions; assigned profiles |
| Administrator (interactive login) | Every team session; clusters, profiles and settings |
| Service/CI key | None: every cloud path is classified interactive-only |
| Personal key `cloud:read`/`cloud:write`, administrator key `admin:cloud` | Planned with the V2 API keys |
| Runner | Planned (PR 2): `/api/v1/cloud/runner/*` only, for its own session and epoch |

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
| `GET/POST /cloud/clusters`, `PUT /cloud/clusters/{id}` | Clusters; `PUT` requires `version`; omitted CA/token keep the stored values, except when the API URL changes: the CA and a token for the new server are then required (`400 cluster_credentials_required`). The API URL is trusted administrator input: `https` only, loopback (`kind`) and private addresses allowed, link-local, unspecified and cloud metadata hosts refused (`400 cluster_host_not_allowed`). A namespace removed from a cluster is refused for new sessions (`409 namespace_not_allowed`) and blocks the start of existing ones | Administrator | Implemented |
| `GET /cloud/profiles` | Profiles assigned to the caller (all for administrators) | Member, administrator | Implemented |
| `POST /cloud/profiles`, `PUT /cloud/profiles/{id}` | Publish a new immutable version; `version` is the version being replaced | Administrator | Implemented |
| `POST /cloud/sessions` | Create; `202` | Member, administrator; owner = effective member | Implemented |
| `GET /cloud/sessions` | List: own sessions; `?owner=<id>` or `?owner=all` for administrators; `state` and `label=k=v` (repeatable) filters; `before=<sessionId>` cursor; `limit` ≤ 50 | Member, administrator | Implemented |
| `GET /cloud/sessions/{id}` | Read | Owner, administrator | Implemented |
| `GET /cloud/sessions/{id}/executions` | Pod history: epoch, node, digest, exit reason, checkpoint | Owner, administrator | Implemented |
| `POST /cloud/sessions/{id}/pause` | `{force?: false}`; `202`. `409 session_preparing` while creating or preparing; repeated pauses write nothing | Owner, administrator | Implemented |
| `POST /cloud/sessions/{id}/resume` | `202`; synchronous recheck that the owner is enabled and still assigned to the profile (`403 owner_unavailable`, `403 profile_unassigned`) | Owner, administrator | Implemented |
| `DELETE /cloud/sessions/{id}` | Terminate and delete the workspace; `204`, also when already terminated | Owner, administrator | Implemented |
| `/cloud/runner/*` | Runner channel | Execution credential | Planned (PR 2) |
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
  "idleTimeoutMinutes": 30
}
```

- `image` must equal the profile image or match one of its `allowedImageOverrides` (`*` matches any run of characters, everything else is literal); otherwise `403 image_not_allowed`.
- `labels`: at most 16 pairs, lowercase keys up to 63 characters, values up to 256, not secret, filterable.
- `idleTimeoutMinutes` is clamped to the profile bounds (stored; automatic idle pause is planned with activity signals).
- `agent.model` and `agent.effort` are recorded; they are checked against the member's model policy on every inference, as for local sessions.
- `repositories` and `initialMessage` are planned (environment preparation and messages). The server ignores unknown fields, so do not send them before the matching capability is advertised.

The response is the session view: `id`, `name`, `ownerId`, `createdBy`, `state`, `phase`, `desiredState`, `generation`, `currentEpoch`, `profile {id, version}`, `image {reference, digest}`, `agent {kind, version, model, effort}`, `labels`, `activity {lastActivityAt, idleTimeoutMinutes}`, `resume`, `failure`, `resumeBlocked`, `workspaceDeletion`, `createdAt`, `updatedAt`, `pausedAt`, `expiresAt`, `terminatedReason`. The image digest is recorded from the first running container; every later execution uses `reference@digest`, so a moved tag does not change a resumed session.

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
- **Garbage collection.** For each cluster, a pass lists TeamCode-labelled pods and claims (`app.kubernetes.io/managed-by=teamcode` and the cluster id label, so another cluster record or server sharing a namespace is never touched) in the allowed namespaces and in any namespace that still holds a session not yet proven deleted, and the released or failed volumes of TeamCode workspaces, before reading which claims and current-epoch pods the database still accounts for. Objects are created only after their row commits, so this order never mistakes a fresh object for an orphan. Orphaned pods and claims are deleted; orphaned volumes that would not be deleted by their provisioner are counted in the cluster's `orphanedVolumes`, with `lastGarbageCollectionAt`. The server's scheduled GC worker is wired with the adapter (PR 2).
- **Acceptance.** `CloudSessionPersistenceTest` verifies that no claim or volume remains after deletion with a fake cluster that models the provisioner. The opt-in cluster smoke (PR 2) will verify it on a real cluster, and on OpenStack can also check the recorded handle with `openstack volume show`.

## Pause and resume

1. `POST /pause` sets `desiredState = paused`. The reconciler moves `ready`/`active` to `pausing`; a checkpoint the runner sends first (it learns of the pause from its heartbeat) is accepted and makes the same move.
2. The runner's heartbeat response carries `desiredState` and `pauseDeadline` (10 minutes after the request, or immediate with `force: true`). The runner drains, stops its child processes, writes its checkpoint and calls `checkpoint {completed}`.
3. The reconciler deletes the pod, confirms its deletion, ends the execution and marks the session `paused` with `consistent`.
4. A failed checkpoint, missed heartbeats or the 15-minute pausing deadline force-delete the pod: `paused` with `crash_consistent`. It is never advertised as `consistent`.
5. `POST /resume` rechecks the owner and assignment, then sets `desiredState = running`. The reconciler moves the session to `resuming`, waits for capacity, starts epoch n+1 on the same workspace with the pinned digest, and the runner's ready heartbeat makes it `ready`.

Inactivity only pauses; it never deletes. Idle detection (turns, pending inputs, preparation), the idle warning and `maxTurnMinutes` are planned with the agent and messages.

## The runner and the pod (planned, PR 2)

The domain rules are implemented (`RunnerChannelUseCase`); the routes, the pod identity exchange and the runner binary are planned.

- **Delivery.** An init container from a TeamCode runner image copies the `teamcode` Linux binary (used as `teamcode cloud runner`) and pinned agent CLIs into an `emptyDir` at `/teamcode/bin`; the main container runs the runner as PID 1 with signal forwarding. The team image stays a pure toolchain (Linux `amd64`/`arm64` with the published binaries' glibc baseline, `/bin/sh`, `git`, non-root UID, writable `$HOME`). A profile option will let teams that pin agents in their image skip the bundled CLIs.
- **Identity.** A projected ServiceAccount token with audience `teamcode` and a 10-minute expiry. The server verifies it through `TokenReview` with the cluster credential and checks that the bound pod name and UID match the current execution. No TeamCode secret exists in the cluster. Where `TokenReview` is not permitted, a one-time Secret created with the pod is the fallback. A token in the pod environment is rejected.
- **Execution credential.** A hashed `tcex_` token in the shared token table, bound to `(session, epoch, owner)`, rotated hourly, revoked on pause, transfer, delete and fencing; charged to the owner's ledger. Agents never see it: they use the runner's loopback proxy, as with `teamcode run`.
- **Channel.** HTTPS only: register, heartbeat every 15 s (the response carries the desired state and pause deadline), checkpoint; later batched event upload and an input long-poll. It fits stateless replicas with MySQL as the only bus and needs no inbound connectivity to the pod.
- **Kubernetes client.** The adapter calls the Kubernetes REST API with the Ktor client the server already uses, rather than fabric8 or the official Java client. The surface is small (pods, claims, volumes, storage classes, TokenReview), the server stores each cluster's URL, CA and token instead of a kubeconfig, and the adapter is tested against recorded API fixtures with Ktor's mock engine. It adds no dependency, and nothing reaches the native CLI, which keeps `cli -> client -> api`.

## The agent in the pod (planned)

- **Permissions.** Agents run in bypass mode inside the sandboxed pod: `claude --dangerously-skip-permissions` and `codex --dangerously-bypass-approvals-and-sandbox`. Cluster prerequisites: a non-root pod, an egress NetworkPolicy, no cluster credentials in the pod. API approval round-trips are a later extension behind their own capability.
- **One long-lived process with streaming input**, not one process per turn: Claude Code with `--input-format stream-json` on stdin, Codex through `codex app-server` (JSON-RPC). Messages can arrive at any time and are injected while the agent works; an interrupt stops the current turn. After a new pod, the runner resumes the conversation by its id (`--resume <id>` for Claude, thread resume for Codex) from the agent state kept on the workspace volume. Exact flags are verified against the pinned agent versions in the adapter tests. Fallback, where an agent version cannot accept input mid-turn: queue the message for the next turn.
- **Resume quality.** Resume is attempted only when the native state exists and the agent version is unchanged, or after an explicit upgrade with the agent's own compatibility check; otherwise `resume.conversation` becomes `transcript_only` and a fresh conversation starts.
- **Credential files.** Agents authenticate only through the loopback proxy; the runner refuses to checkpoint a credential file found under the agent homes.

## Other planned parts

- **Environment preparation.** Repositories and refs cloned into `/workspace/<path>` on the first execution and only fetched on resume (never reset or re-cloned over work); `create` and `start` setup steps with completion markers; the owner's Git identity rewritten on every execution; variant values from the environment/MCP variants work, injected into processes only.
- **GitHub.** GitHub App user-to-server tokens per member (8-hour tokens, rotating refresh serialized with the existing `refresh_pending` pattern); installation tokens only for dedicated service members; personal access tokens refused. Members link GitHub in the dashboard or with `teamcode github link`. Tokens reach Git and `gh` through a credential helper over a tmpfs socket, never the workspace, a remote URL, an event or an image.
- **Transcript.** Normalized events (`message.user`, `message.agent`, `tool.call`, `tool.result`, `turn`, `status`, `error`) with per-session sequence numbers, bounded sizes, secret masking, no thinking blocks; cursor pagination in both directions; per-session SSE with `Last-Event-ID`; a caller-wide SSE feed of notices for orchestrators; webhooks later.
- **Messages.** `POST .../messages` with `Idempotency-Key` or `clientMessageId`; acknowledgement levels `queued`, `delivered`, `consumed`, `cancelled`, `rejected`; queue and wake by default (`wake: false` per message, `wakeOnMessage` per profile); `expectedLastEventSeq` against stale views; at most 100 pending messages.
- **Attach.** `teamcode cloud attach`: structured attach over the shared event stream and message routes, with an attachment lease; it may come after the other CLI commands if it proves more complex. A PTY attach is a possible later addition.
- **Transfer.** Administrator transfer is immediate; an owner's request is an offer the recipient accepts within 7 days. Transfer happens at a checkpoint, revokes execution and attachment credentials, rechecks the recipient, and charges future work to the recipient while historical usage stays with the original member. The policy follows the settings above.
- **Keys.** `cloud:read`/`cloud:write` personal scopes and `admin:cloud` administrator keys with `TeamCode-On-Behalf-Of`, with the V2 API keys. This enables the driving integration: an external orchestrator creating one session per task on behalf of a member, relaying messages from mobile through its own backend, without administrator keys ever reaching a device.

## CLI (planned, PR 2)

```sh
teamcode cloud create --profile ID [--name TEXT] [--label K=V]... [--model M] [--effort E] [--member MEMBER_ID] [--json]
teamcode cloud list [--all|--member ID] [--state STATE] [--label K=V] [--json]
teamcode cloud show SESSION [--json]
teamcode cloud pause SESSION [--force]
teamcode cloud resume SESSION
teamcode cloud delete SESSION [--yes]
teamcode cloud runner            # hidden; pod entrypoint only
```

`--member` sends `TeamCode-On-Behalf-Of`, `--json` prints the API DTOs unchanged, and `create` generates the idempotency key and reuses it on retry. `logs`, `send`, `attach`, `interrupt`, `transfer(s)`, the administration commands and `teamcode github ...` come with their features.

## Pull request sequence

| PR | Content | Capability |
| --- | --- | --- |
| 1 (this one) | Contract; domain, persistence, reconciler, deletion proof and GC over `ClusterPort`; management API and client; settings; audit columns | Not advertised by the server |
| 2 (stacked on 1) | Kubernetes adapter, runner routes and skeleton, server wiring and workers, CLI commands, opt-in `kind` smoke | `cloud.sessions.v1` |
| 3 | Environment preparation and GitHub | `cloud.environment.v1` |
| 4 | Agent adapters (long-lived streaming process), transcript, SSE | `cloud.transcript.v1` |
| 5 | Messages, interrupt, idle pause, attach | `cloud.messages.v1`, `cloud.attach.v1` |
| 6 | Transfer and its settings | `cloud.transfer.v1` |
| Cross-cutting | V2 keys (`cloud:*`, `admin:cloud`), MCP gateway and skills in the runner | - |

## Evidence

- `CloudSessionPersistenceTest` (real MySQL 8.4, two independent connection pools): the reviewed migration matches the Exposed tables; encrypted cluster credentials; profile versioning and the `Retain` refusal; administrator delegation with attribution in the session and the audit journal; concurrent creations with one idempotency key creating one session; the full create, prepare, ready, pause, checkpoint and paused cycle with no pod left; runner fencing by epoch and pod name; concurrent resumes from both pools producing one epoch, including two reconcilers holding the same lease; stale-owner fencing after lease expiry; lost-execution handling; FIFO capacity admission; proven deletion leaving no claim or volume; the blocked deletion and orphaned-volume alert for a retained volume; garbage collection of orphaned pods and claims; retention expiry; the retained cap and keyset pagination.
- `CloudRoutesTest`: service keys refused on cloud routes, `Idempotency-Key` validation and canonical replay, `TeamCode-On-Behalf-Of` refused for members, visibility as `404`, natural idempotency of pause and delete through the typed client, query validation, and the settings API and dashboard form with CSRF.
- `CloudLifecycleRulesTest`: the transition table, request validation, image override patterns and settings bounds.

Migration note: the cloud session migrations are numbered `V20261020100004` and `V20261020100004.1`, after the latest `main` migration at the time of the 0.1.0 release. Renumber again if another migration lands on `main` first.
