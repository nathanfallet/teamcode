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
| Ownership transfer: offers and administrator transfers, recipient rechecks, owner change at a paused boundary, revocation, settings (`cloud.transfer.v1`) | Implemented, see [transfer](#transfer) |
| Environment preparation, GitHub, personal and administrator keys | Planned (later PRs, see [sequence](#pull-request-sequence)) |

The server mounts the cloud routes and advertises `cloud.sessions.v1` only when cloud sessions are enabled (see [deployment](#deployment)). Clients must check that capability before using any route below.

## Resources

| Resource | Table | Owner | Purpose |
| --- | --- | --- | --- |
| Cluster | `cloud_clusters` | Administrator | API URL, CA bundle, encrypted service-account credential, allowed namespaces, `maxRunningPods`, default storage class, garbage-collection report |
| Profile | `cloud_profiles`, `cloud_profile_versions`, `cloud_profile_assignments` | Administrator; assigned to members (yes/no) | Versioned, immutable once published: cluster, namespace, image, `allowedImageOverrides`, `imagePullSecrets` names, resources, workspace size and storage class, agent kind/version, idle timeout and bounds |
| Session | `cloud_sessions`, `cloud_session_labels` | Effective member (`ownerId`) | Pinned profile version, image and digest, desired state, state/phase, `generation`, `currentEpoch`, labels, activity, resume quality, expiry, workspace and its deletion proof |
| Execution | `cloud_executions` | Session | One pod lifetime, unique on `(session_id, epoch)`: pod name, node, image digest, registration, heartbeats, exit reason, checkpoint result |
| Idempotency record | `cloud_idempotency` | Team + initiating member | The session created by a keyed request, kept 24 hours |
| Transfer | `cloud_transfers` | Session; former owner and recipient | From, to, initiator and decider, state, resume and pending-input policy, the last epoch of the former owner and the transcript event of the change. Kept after completion |

Planned tables: GitHub grants. Each execution row also records the owner it ran for (`owner_id`), which is how history stays attributed after a transfer.

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
| `transferring` | Changing owner at a paused boundary: no pod, recipient rechecked, then one transaction changes the owner | `transferring`, `stopping_pod` |
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
| `ready`, `active` | `pausing` | Desired state `paused` (explicit pause, or an accepted [transfer](#transfer); idle timeout is planned), or the runner's checkpoint arriving before the reconciler's own move |
| `pausing` | `paused` | Checkpoint completed (`consistent`), or failed, missed heartbeats or 15-minute pausing deadline (`crash_consistent`) |
| `ready`, `active`, `pausing` | `paused` | Execution lost: no heartbeat for 90 s, the pod disappeared or stopped, or the execution row is missing (`execution_missing`, after deleting any pod of the session). `crash_consistent`, and the desired state becomes `paused`, so a lost execution never restarts by itself. |
| `paused` | `resuming` | Desired state `running` (explicit resume, or a [message](cloud-conversations.md#messages-and-interrupts) with `wake`) |
| `resuming` | `paused` | Image unavailable, start failure or timeout, lost execution, namespace no longer allowed, or a pause requested during the resume. Sets `resumeBlocked`, keeps the workspace, never `failed`. |
| `resuming` | `failed` | The workspace claim is lost |
| `paused` | `transferring` | An accepted transfer (`transferId` set) once the session is paused |
| `transferring` | `paused` | Owner changed without `resume`, or the transfer failed or was dropped (the session keeps its former owner) |
| `transferring` | `resuming` | Owner changed with `resume`: the next execution starts for the recipient |
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

Sessions record `createdBy {initiatorId, credentialId, effectiveMemberId}`; `credentialId` is the id of the login session or key, never its token. The audit journal gained two nullable columns, `effective_member_id` and `credential_id`, exposed as optional fields of `GET /api/v1/audit` events. Journaled actions: `cloud.session.created`, `.paused`, `.resumed`, `.deleted` (by the caller), and `.pausing`, `.paused_checkpoint`, `.ready`, `.ready_resumed`, `.execution_lost`, `.resume_blocked`, `.failed`, `.expired`, `.terminated`, `.workspace_deleted`, `.transferring` (by `system`, with the owner as effective member); `cloud.cluster.saved`, `cloud.profile.saved` and `cloud.settings.updated`. Transfers add a third nullable column, `related_member_id` (`relatedMemberId` in the API): `cloud.transfer.offered`, `.requested`, `.accepted`, `.declined`, `.cancelled` (by the caller, subject the transfer), `.expired`, `.failed` (by `system`, with the recipient as effective member and the former owner as related member), and `cloud.session.transferred` (subject the session, actor the transfer's initiator, effective member the recipient, related member the former owner).

### Idempotency

`POST /cloud/sessions` requires `Idempotency-Key` (1–128 characters from `[A-Za-z0-9._:-]`). Scope: team + initiating member + route. The server stores a SHA-256 of the canonical request (the decoded body with sorted labels, plus the effective member) in the same transaction as the session:

- same key, same request: `202` with the session and `Idempotency-Replayed: true`;
- same key, different request or effective member: `422 idempotency_conflict`;
- concurrent requests with one key are serialized on the member row; one creates, the others replay.

The replay returns the **current** view of the created session rather than a byte copy of the first response. Records expire after 24 hours (bounded purge).

Pause, resume and delete are naturally idempotent through the desired state. Messages take `Idempotency-Key` or its `clientMessageId` alias ([messages](cloud-conversations.md#messages-and-interrupts)). `POST /cloud/sessions/{id}/transfer` requires `Idempotency-Key`, with the same scope and digest rules as creation (the digest covers the session, the body and the effective member); accept, decline and cancel are idempotent through the transfer's state.

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
| `/cloud/sessions/{id}/events`, `.../events/stream`, `.../messages`, `.../interrupt`, `.../attachments`, `/cloud/access`, `/cloud/runner/agent`, `.../events`, `.../inputs` | Transcript, messages, interrupt, attach lease, access policy, agent channel: see [transcript, messages and attach](cloud-conversations.md) | Access policy | Implemented |
| `/cloud/events/stream` | Caller-wide SSE feed | | Planned |
| `POST /cloud/sessions/{id}/transfer` | `{toMemberId, resume?, force?}` with `Idempotency-Key`; `202`. An administrator's transfer is `accepted` at once, an owner's is `offered` | Owner (when `memberTransfers` is `offer`), administrator | Implemented |
| `GET /cloud/transfers` | `direction=incoming\|outgoing\|all`, `scope=team` (administrators), `state` (repeatable), `session`, `before`, `limit` ≤ 50 | Both members, administrator | Implemented |
| `GET /cloud/transfers/{id}`; `POST .../accept` (`{resume?}`), `.../decline`, `.../cancel` | Read and decide | Recipient (accept, decline), former owner (cancel an offer), administrator (all) | Implemented |

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
| `transcriptRetentionDays` | 30 | Days transcript events are kept (1 to 3,650). `null` keeps them indefinitely. |
| `memberTransfers` | `offer` | `offer`: an owner may offer their own session and the recipient accepts; `disabled`: only administrators transfer. |
| `transferOfferDays` | 7 | Days a recipient has to accept an offer (1 to 30). |
| `transferPendingInputs` | `cancel` | Messages not yet consumed when the owner changes: `cancel` (reason `transferred`) or `keep` (delivered to the recipient's next execution). |

`expiresAt` is computed when the session pauses, from the setting in force at that moment; changing the setting applies to sessions paused afterwards, so shortening it never deletes existing work by surprise. Resuming clears it. At expiry the session is terminated with reason `expired` and its workspace deleted as below. The 7-day and 1-day expiry notices need the transcript and feed, and are planned.

The pause before a change of owner and the end of the former owner's access are not settings: they are what makes a transfer safe (see [transfer](#transfer)). The pending-input policy is copied onto the transfer when it is accepted, so a later change of the setting does not alter a transfer in progress.

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

Inactivity only pauses; it never deletes. Idle detection (turns, pending inputs, preparation), the idle warning and `maxTurnMinutes` are planned.

## Transfer

A session can change owner and keep its identity, workspace, transcript and executions. Capability `cloud.transfer.v1`.

### Who may transfer

- **Administrators** transfer any team session to any eligible member at once: the transfer is created `accepted`. With `TeamCode-On-Behalf-Of`, the delegated member is recorded as the transfer's effective member; the call is still authorized with the administrator's rights, as everywhere else. An administrator's transfer cancels a pending offer for the same session (`superseded`).
- **An owner who is not an administrator** can only offer their own session (`offered`), and only while `memberTransfers` is `offer`. The recipient, or an administrator, accepts or declines it within `transferOfferDays`; the owner can withdraw it until it is accepted. Nobody can take a session they do not own: a member asking to transfer someone else's session gets `404`, and only the recipient or an administrator can accept.
- A session has at most one open transfer (`409 transfer_pending`). A session being created or prepared cannot be transferred (`409 session_preparing`): its preparation runs with the owner's credentials and recipe. An ended session cannot either (`409 session_not_running`).
- `POST /transfer` requires `Idempotency-Key`; a retry replays the transfer even after the session changed (`Idempotency-Replayed: true`).

Transfer states: `offered` → `accepted` → `completed`, or `declined`, `cancelled` (by a member, `superseded`, or because the session ended), `expired` (offers past their validity; a maintenance worker and every read of an offer apply it), `failed` (the recipient was no longer eligible at the boundary).

### Recipient checks

The recipient is checked when the transfer is requested, when it is accepted, and again by the reconciler right before the owner changes, on fresh data. A refusal at request or acceptance is `422 recipient_ineligible`, naming every problem code; a refusal at the boundary fails the transfer with that code and returns the session to `paused` for its former owner.

| Check | Code |
| --- | --- |
| A team member, enabled, password setup completed | `recipient_not_found`, `recipient_unavailable` |
| Assigned to the session's profile (administrators use every profile) | `profile_unassigned` |
| `create` under the [access policy](cloud-conversations.md#access), as for a creation on their behalf | `cloud_permission_denied` |
| The session's recorded agent model and effort against the team's model policy, exactly as inference will check them | `model_not_allowed`, `effort_not_allowed` |
| An inference allowance at all (a pool weight or daily allowance of zero refuses). Today's consumption is not checked: a transfer neither resets nor borrows allowances | `recipient_no_quota` |
| The retained-session cap, in the owner-change transaction under the recipient's member row lock | `cloud_session_limit` |

The checks are a list of `CloudRecipientCheck` ports. Environment variables, MCP servers and skills are delivered to cloud sessions by other branches (environment preparation, the MCP gateway and the skill catalog); their assignment checks (for example the profile's `requiredVariables` for the recipient) join this list when they are merged, without changing the transfer flow.

### The boundary: pause first, then change owner

**Decision.** The owner changes only at a paused boundary, never in a running pod. Accepting a transfer sets the desired state to `paused` (keeping `transferId` on the session); a running session drains and checkpoints exactly like an explicit pause (the running turn finishes, or is interrupted 60 s before the deadline; `force` pauses at once and leaves a crash-consistent workspace), the reconciler deletes the pod and confirms it is gone, and only a `paused` session moves to `transferring`. There, the reconciler deletes any pod of the session still listed by the cluster, ends any execution still open, rechecks the recipient, and commits the change in one transaction. The next execution is a new epoch, started for the recipient, by `resume` on the transfer (or the acceptance) or later by the recipient.

**Why.** Everything that acts for the owner inside a pod is bound to that pod or was resolved for the owner when it started: the agent process and its memory, the Git and GitHub credentials and the variable values the runner fetched for the owner, MCP credentials, and the pod's projected token. None of it can be proven revoked while the process lives, and the agent may have copied a credential into a child process. Ending the execution is the only revocation that holds, and the epoch fence already guarantees that nothing of an ended epoch can call the server again. The other options were set aside:

- *Live hand-off in the running pod* (rotate credentials, keep the process): the former owner's credentials stay in the process and its children, and the work in progress would be billed partly to one member and partly to the other within one turn.
- *Cancel the turn and transfer at once*: loses the running turn's work for no gain over `force`, which administrators can already ask for.
- *Refuse transfers of running sessions*: pushes the pause onto every caller (an orchestrator would have to pause, poll and transfer), with a race between the pause and someone resuming it.

The cost is the drain (bounded by the pause deadline) and one pod restart. The workspace, the transcript and, for agents that support it, the conversation (resumed by id) are kept, so the recipient continues the same session.

While a transfer is accepted and not finished, an explicit resume is refused (`409 session_transferring`), a message never wakes the session, and a new transfer is refused. Deleting the session cancels the transfer.

### What changes at the boundary

In the owner-change transaction:

- `ownerId` becomes the recipient; the transfer becomes `completed` with `atEpoch` (the last epoch run for the former owner) and `eventSeq`.
- Every attachment lease of the session is removed. The former owner's `attach` stops at its next renewal, and their transcript stream ends with `event: denied` at its next access check.
- Messages not yet consumed follow the transfer's `pendingInputs`: `cancel` marks them `cancelled` with reason `transferred` (each with its `input` transcript event), `keep` queues them for the recipient's next execution. Wake requests are cleared either way, so a former owner's message never resumes the recipient's session.
- A server-written `transfer` event `{transferId, fromMemberId, toMemberId, atEpoch}` is appended to the transcript.
- `cloud.session.transferred` is journaled with the initiator, the recipient and the former owner.
- Without `resume`, the session stays `paused` and its expiry restarts from the recipient's full paused retention. With `resume`, it moves to `resuming` and starts for the recipient.

After it, the former owner has no owner rights on the session (it is a `404` for them), unless the team's access policy grants them access to other members' sessions. The recipient sees the whole history.

### Former-owner credentials

- **Pod and execution.** No pod of the session exists after the boundary, and every execution of the former owner ended; their projected tokens are fenced by epoch. The planned `tcex_` inference credential is bound to the execution, so it dies with it.
- **Git, GitHub and variables.** The environment branch resolves the Git identity, the GitHub user token and the variable values for the session's current owner on every runner `environment` call, which only the registered runner of the current epoch can make. After a transfer, that is the recipient's first execution. Nothing of the former owner's is stored with the session or in the pod spec. The runner's credential helper and `GH_TOKEN` live in memory and in child-process environments of the ended pod only.
- **Attachments.** Removed in the transaction.
- **Workspace content.** The workspace is transferred as it is: that is the point of a transfer. Files the former owner (or their agent) wrote there, including the agent's conversation state under `$HOME`, belong to the session. Credentials never reach the workspace through TeamCode, but a secret that a member typed into a file goes with the session.

### Attribution and billing

Each execution records the owner it ran for (`ownerId` in `GET /cloud/sessions/{id}/executions`). Work of an execution (inference through its execution credential, pod time) is attributed to that member, so the former owner's executions stay theirs and the recipient's start at `atEpoch + 1`. Transcript events carry their epoch, and `message.user` events their author, so history remains attributable after the change. Usage already recorded in the ledger is never moved, and allowances are neither reset nor transferred.

## The runner and the pod

- **Pod.** One bare pod per execution, `tc-<session>-e<epoch>`, `restartPolicy: Never`, labelled `app.kubernetes.io/managed-by=teamcode` with the session, cluster and epoch. Non-root (UID/GID/fsGroup 1000, `runAsNonRoot`), `seccompProfile: RuntimeDefault`, no privilege escalation, all capabilities dropped, `automountServiceAccountToken: false`, no service links. CPU and memory requests equal limits from the profile. The workspace claim is mounted at `/workspace`, `HOME` is `/workspace/.home` so agent state survives pauses.
- **Delivery.** An init container from the runner image (`TEAMCODE_CLOUD_RUNNER_IMAGE`) copies the `teamcode` Linux binary (`TEAMCODE_CLOUD_RUNNER_BINARY`, default `/usr/local/bin/teamcode`) into an `emptyDir` at `/opt/teamcode`, with `cp`; the session container runs `/opt/teamcode/teamcode cloud runner`. The team image stays a pure toolchain (Linux with the published binaries' glibc baseline, `/bin/sh`, `git`, writable by UID 1000). Bundled agent CLIs come with the agent PR.
- **Identity.** A projected ServiceAccount token, audience `teamcode`, 600 s expiry (the API minimum; the kubelet rotates the file, and the runner reads it for every call). The server reviews it with `TokenReview` using the cluster credential and requires the bound namespace to be the session's and the bound pod name to be the current execution's pod; the pod name always comes from the review, never from the request. A pod of the session with another epoch gets `409 execution_fenced` and exits. Reviews are cached 60 s per token (refusals 10 s, in a separate cache so bad tokens never evict accepted ones), so a heartbeat every 15 s costs one review a minute per pod. No TeamCode secret exists in the cluster, and the pod environment holds only the server URL, the token file path and the team, session and epoch identifiers.
- **Channel.** HTTPS only (the client refuses plain HTTP except on loopback): register, heartbeat every 15 s (the response carries the desired state and pause deadline), checkpoint; later batched event upload and an input long-poll. It fits stateless replicas with MySQL as the only bus and needs no inbound connectivity to the pod. Network failures are retried with backoff; the server marks the execution lost after 90 s without heartbeats.
- **Runner skeleton.** This release's runner starts no agent: it registers, reports ready, heartbeats, and checkpoints immediately when a pause is requested (there is nothing to drain yet). It exits on `409 execution_fenced` or when the session is being deleted, retries any other `409` (for example a checkpoint refused because the pause was cancelled), and exits with code 2 on `401`. The client reads only the error code of a failed runner call, never its message.
- **Token exposure (planned fix).** The projected token is readable by the pod's UID. With no agent in this release only the runner runs there; before the agent lands, the agent must run under a separate UID that cannot read the token, so it cannot drive the runner channel (for example report a clean checkpoint).
- **Planned.** A `tcex_` execution credential (hashed, bound to session, epoch and owner, revoked on pause, transfer, delete and fencing) for the inference the runner's loopback proxy forwards, with the agent PR; a one-time Secret fallback where `TokenReview` is not permitted.
- **Kubernetes client.** The adapter (`KubernetesClusterPort`) calls the Kubernetes REST API with the Ktor client the server already uses, rather than fabric8 or the official Java client. The surface is small (pods, claims, volumes, storage classes, TokenReview), the server stores each cluster's URL, CA and token instead of a kubeconfig, and the adapter is tested with Ktor's mock engine. Each cluster gets its own client whose trust store holds only that cluster's CA bundle; the connection is reloaded from the database at most every 30 s, so a rotated token applies quickly. Lists are paged (500 per page, at most 20 pages, 200 for the cluster-wide PersistentVolume list); a page missing mid-list fails the call rather than passing for a complete list. Concurrent first uses of a cluster build one client, and a client replaced after a rotation stays open until shutdown for requests in flight. API error bodies are never kept or logged. It adds no dependency, and nothing reaches the native CLI, which keeps `cli -> client -> api`.

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

## The agent in the pod

See [transcript, messages and attach](cloud-conversations.md#the-agent-in-the-pod): Claude Code and Codex run in bypass mode as one long-lived process with streaming input, resumed by conversation id after a new pod. Agents authenticate only through the loopback proxy; refusing to checkpoint a credential file found under the agent homes is planned.

## Other planned parts

- **Environment preparation.** Repositories and refs cloned into `/workspace/<path>` on the first execution and only fetched on resume (never reset or re-cloned over work); `create` and `start` setup steps with completion markers; the owner's Git identity rewritten on every execution; variant values from the environment/MCP variants work, injected into processes only.
- **GitHub.** GitHub App user-to-server tokens per member (8-hour tokens, rotating refresh serialized with the existing `refresh_pending` pattern); installation tokens only for dedicated service members; personal access tokens refused. Members link GitHub in the dashboard or with `teamcode github link`. Tokens reach Git and `gh` through a credential helper over a tmpfs socket, never the workspace, a remote URL, an event or an image.
- **Keys.** `cloud:read`/`cloud:write` personal scopes and `admin:cloud` administrator keys with `TeamCode-On-Behalf-Of`, with the V2 API keys. This enables the driving integration: an external orchestrator creating one session per task on behalf of a member, relaying messages from mobile through its own backend, without administrator keys ever reaching a device.

## CLI

```sh
teamcode cloud create --name TEXT --profile ID [--label K=V]... [--image REF] [--model M] [--effort E] [--idle-minutes N] [--member MEMBER_ID] [--idempotency-key KEY] [--json]
teamcode cloud list [--all|--member ID] [--state STATE]... [--label K=V]... [--json]
teamcode cloud show SESSION [--executions] [--json]
teamcode cloud pause SESSION [--force] [--member ID]
teamcode cloud resume SESSION [--member ID]
teamcode cloud delete SESSION --yes [--member ID]
teamcode cloud transfer SESSION --to MEMBER_ID [--resume] [--force] [--member ID] [--idempotency-key KEY] [--json]
teamcode cloud transfers list [--incoming|--outgoing|--all] [--state STATE]... [--session ID] [--json]
teamcode cloud transfers show TRANSFER [--json]
teamcode cloud transfers accept TRANSFER [--resume] [--member ID]
teamcode cloud transfers decline|cancel TRANSFER [--member ID]
teamcode cloud runner            # hidden; pod entrypoint only
```

`--member` sends `TeamCode-On-Behalf-Of` (and, for `list`, filters by owner), `--json` prints the API DTOs unchanged, and `create` sends a random idempotency key unless one is given, so a script can retry with its own. `list` follows pages up to 500 sessions. `delete` refuses without `--yes`. `logs`, `send`, `attach` and `interrupt` are described in [transcript, messages and attach](cloud-conversations.md#cli); the administration commands and `teamcode github ...` come with their features.

## Pull request sequence

| PR | Content | Capability |
| --- | --- | --- |
| 1 | Contract; domain, persistence, reconciler, deletion proof and GC over `ClusterPort`; management API and client; settings; audit columns | Not advertised by the server |
| 2 (stacked on 1) | Kubernetes adapter, runner routes and skeleton, server wiring and workers, CLI commands, opt-in `kind` smoke | `cloud.sessions.v1` |
| 3 | Environment preparation and GitHub | `cloud.environment.v1` |
| 4 | Agent adapters (long-lived streaming process), transcript, SSE | `cloud.transcript.v1` |
| 5 | Messages, interrupt, idle pause, attach | `cloud.messages.v1`, `cloud.attach.v1` |
| 6 | Transfer and its settings (implemented) | `cloud.transfer.v1` |
| Cross-cutting | V2 keys (`cloud:*`, `admin:cloud`), MCP gateway and skills in the runner | - |

## Evidence

- `CloudSessionPersistenceTest` (real MySQL 8.4, two independent connection pools): the reviewed migration matches the Exposed tables; encrypted cluster credentials; profile versioning and the `Retain` refusal; administrator delegation with attribution in the session and the audit journal; concurrent creations with one idempotency key creating one session; the full create, prepare, ready, pause, checkpoint and paused cycle with no pod left; runner fencing by epoch and pod name; concurrent resumes from both pools producing one epoch, including two reconcilers holding the same lease; stale-owner fencing after lease expiry; lost-execution handling; FIFO capacity admission; proven deletion leaving no claim or volume; the blocked deletion and orphaned-volume alert for a retained volume; garbage collection of orphaned pods and claims; retention expiry; the retained cap and keyset pagination.
- `CloudRoutesTest`: service keys refused on cloud routes, `Idempotency-Key` validation and canonical replay, `TeamCode-On-Behalf-Of` refused for members, visibility as `404`, natural idempotency of pause and delete through the typed client, query validation, and the settings API and dashboard form with CSRF.
- `CloudLifecycleRulesTest`: the transition table, request validation, image override patterns, cluster API hosts and settings bounds.
- `CloudRoutesTest` (runner): only the current pod's projected token is accepted; member credentials, another namespace and unbound tokens get `401`, another epoch `409`; reviews are cached.
- `KubernetesClusterPortTest` (Ktor mock engine): claim, volume, storage-class and pod parsing; idempotent create and delete; the pod manifest (bare, non-root, no API token, projected token, resources, pull secrets); `TokenReview` parsing and refusals; label-selected paging; error bodies never surfaced.
- `CloudRunnerTest`: the runner skeleton registers with backoff, checkpoints once when paused, exits when fenced or deleted, and stops on `401`.
- `CloudTransferPersistenceTest` (real MySQL 8.4, two pools): migration drift; offers refused for another member's session, for oneself, on behalf by a member, for an unassigned recipient or one without allowance, and when the team disabled them; one offer from concurrent identical requests on both replicas, replay and conflict; visibility and decision rights; decline, withdrawal, expiry on read and by the worker; concurrent acceptances on both replicas; the drain, checkpoint and pod deletion before `transferring`; no wake and no resume meanwhile; the owner change with attachment leases removed, unconsumed messages cancelled, the `transfer` event, the journal entry with initiator and both members, the former owner locked out, and the next epoch run for the recipient while epoch 1 stays the former owner's; an administrator transfer on behalf of a member superseding an offer, with `keep`; the recipient unassigned before the boundary failing the transfer (session kept by its owner); the retained cap at the boundary; deletion cancelling an accepted transfer; no transfer while creating.
- `CloudRoutesTest` (transfers): `cloud.transfer.v1`, service keys refused, `Idempotency-Key` required with replay and conflict, on-behalf refused for members, offers through the typed client, query validation, acceptance pausing the session, cancellation rights, an administrator's direct transfer on behalf of a member, the transfer settings in the API and the dashboard form.
- `CloudTransferRulesTest`: only `paused` moves to `transferring`; the owner never changes through an ordinary compare-and-set; acceptance toward a pause; refused states; model, effort and quota checks; settings bounds.
- `KindSmokeTest` through `scripts/smoke-cloud-kind.py` (opt-in): on a throwaway `kind` cluster with the least-privilege roles above, the real reconciler, stores and adapter create the claim and pod, a pod-bound token passes `TokenReview` (and one for another audience does not), the session pauses with a checkpoint and keeps its workspace, and deletion is proven with no claim or PersistentVolume left.

Migration note: this branch's migration is numbered after its base (`V20261019153908`). The transfer migrations (`V20261019230000*`: transfer settings, `cloud_sessions.transfer_id`, `cloud_executions.owner_id`, `cloud_transfers`, `audit_events.related_member_id`) are generated from the Exposed tables and numbered after the transcript and GitHub branches' `V20261019210000*`. V1 work on `main` adds migrations too; the files are renumbered after the latest `main` migration when this branch is updated for merge.
