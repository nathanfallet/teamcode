# Cloud sessions: transcript, messages and attach

This page is the contract for talking to a [cloud session](cloud-sessions.md): the agent process in the pod, the transcript, messages and interrupts, `teamcode cloud attach`, and who may do what. It extends the session contract and uses its principals, errors and idempotency rules.

## Status

| Part | State |
| --- | --- |
| Agent in the pod: Claude Code (stream-json) and Codex (`app-server`) adapters, one long-lived process, resume by conversation id | Implemented |
| Transcript: normalized, bounded, secret-masked events; pages in both directions; per-session SSE with `Last-Event-ID` | Implemented (`cloud.transcript.v1`) |
| Messages and interrupts with acknowledgement levels, idempotency, queue and wake | Implemented (`cloud.messages.v1`) |
| Attachment leases and `teamcode cloud attach` | Implemented (`cloud.attach.v1`) |
| Access policy for members and groups (create, attach, message, transcript) | Implemented (`cloud.access.v1`); groups need a group directory, see [access](#access) |
| Transcript retention setting (`transcriptRetentionDays`, 30 days by default) | Implemented |
| Idle pause, `initialMessage` on create, caller-wide SSE feed, webhooks, transfer | Planned |

The capabilities are advertised with `cloud.sessions.v1`, when cloud sessions are enabled.

## The agent in the pod

The runner (`teamcode cloud runner`) starts the session's agent with the first message, as one long-lived process with streaming input, in bypass mode inside the sandboxed pod:

- **Claude Code**: `claude -p --input-format stream-json --output-format stream-json --verbose --dangerously-skip-permissions [--model M] [--effort E] [--resume ID]`. Each message is a `user` line on stdin; Claude Code queues messages sent during a turn. An interrupt is a `control_request` with subtype `interrupt`.
- **Codex**: `codex app-server` (JSON-RPC lines without the `jsonrpc` member): `initialize`, `initialized`, then `thread/start` or `thread/resume` with `approvalPolicy: never` and `sandbox: danger-full-access` (the app-server form of `--dangerously-bypass-approvals-and-sandbox`). A message during a turn is `turn/steer`, otherwise `turn/start`; a steer that arrives after the turn ended starts the next turn instead of being lost. An interrupt is `turn/interrupt`. Server requests (approvals) are refused, since bypass mode should produce none.

The agent kind, version, model and effort come from the session (`POST /cloud/runner/agent`). The conversation id is kept in `$HOME/.teamcode/cloud-agent.json` on the workspace volume; a new pod resumes it only when the agent kind and version are unchanged. Otherwise, or when the resume fails, a fresh conversation starts and `resume.conversation` becomes `transcript_only` (`resumable` once the agent reported its id). The agent runs in `TEAMCODE_CLOUD_WORKDIR` (default `/workspace`) and never inherits the runner's `TEAMCODE_*` variables.

Output is normalized in the runner (thinking and reasoning are dropped there) and uploaded in ordered batches (at most 200 events or 1 MB, buffer bounded to 5,000 events: further events are dropped without a sequence number and reported by a `transcript_overflow` error event). Each message is acknowledged once written to the agent's stdin; a redelivered input is acknowledged again without being written twice. Claude Code reports no turn start, so the runner emits `turn` `started` when a message reaches an idle agent. Agent stderr is discarded.

**Pause.** When a pause is requested, the runner drains beside its heartbeats: it lets the running turn finish until 60 s before the pause deadline, then interrupts it, waits 15 s, stops the process (10 s grace) and flushes the transcript for at most 30 s. The checkpoint reports `completed: false` when the turn had to be interrupted. Messages delivered during the drain are left unacknowledged, so the server offers them to the next execution. A pause cancelled during the drain lets the agent take messages again. A fenced execution (`409 execution_fenced`, or `401`/`403`/`404` on the agent channel) stops the agent at once.

### Runner routes

All take `{teamId, sessionId, epoch}` and the pod's projected token, like the lifecycle runner routes; another epoch gets `409 execution_fenced`.

| Route | Purpose |
| --- | --- |
| `POST /cloud/runner/agent` | `{kind, version, model, effort, conversation}` |
| `POST /cloud/runner/events` | `{events: [{sequence, type, data}]}`, the runner's own consecutive sequence per epoch (a retried batch is deduplicated); returns `{acceptedThrough, lastSeq}`. Body at most 2 MB, 200 events |
| `POST /cloud/runner/inputs` | Long-poll (`waitMillis` at most 20,000) for queued inputs, interrupts first; returns them `delivered`. Nothing is delivered unless the session is `ready` or `active` and desired `running` |
| `POST /cloud/runner/inputs/ack` | `{acks: [{id, status: consumed or rejected, reason}]}` |

A `turn` event with phase `started` makes a `ready` session `active`; `completed`, `interrupted` or `failed` makes it `ready` again. Both refresh `lastActivityAt`. A `status` event with `conversation` updates `resume.conversation`.

## Transcript

Events, in `cloud_session_events`:

| Type | `data` |
| --- | --- |
| `message.user` | `{text, inputId, authorId}`, written by the server when the agent consumed the message |
| `message.agent` | `{text}` |
| `tool.call` | `{id, name, input}` |
| `tool.result` | `{id, output, isError, exitCode?}` |
| `turn` | `{phase: started, completed, interrupted, failed, durationMs?, steps?}` |
| `status` | `{agent, conversation?, conversationId?, model?, exitCode?}` |
| `error` | `{code, message}` |
| `input` | `{inputId, kind, status, reason}`, server-written when an input is cancelled or rejected |

Clients must tolerate unknown types and unknown fields. `seq` starts at 1 and increases by one per session; `epoch` is the producing execution (0 for server events).

**Bounds and masking.** The server, not only the runner, removes `thinking`, `signature`, `reasoning` and `redacted_thinking` keys, caps nesting at 16 levels and arrays at 200 items, cuts strings at 16,000 characters (marked), masks secrets (provider and GitHub tokens, TeamCode keys, AWS keys, Slack tokens, JWTs, private keys, `Authorization` values, secret-named assignments, URL credentials) and replaces an event larger than 60,000 bytes by `{truncated, originalBytes}`.

**Routes.**

| Route | Purpose |
| --- | --- |
| `GET /cloud/sessions/{id}/events?after=&before=&limit=` | Page in ascending order (`limit` 1 to 200). `firstSeq` is the oldest retained event, `lastSeq` the newest; continue with `after=nextAfter` or `before=nextBefore` |
| `GET /cloud/sessions/{id}/events/stream` | SSE: `id: <seq>`, `event: <type>`, `data: <event JSON>`; resumes after `Last-Event-ID` (or `?after=`). Keep-alive comments every 15 s; `retry: 2000`. Ends with `event: end` when the session ended, or `event: denied` when access was revoked; access is rechecked every 15 s |

**Retention.** `transcriptRetentionDays` in the cloud settings (1 to 3,650, default 30, `null` keeps forever) deletes older events every 10 minutes, in bounded batches.

## Messages and interrupts

`POST /cloud/sessions/{id}/messages` with `{text, wake, clientMessageId, expectedLastEventSeq}` (text 1 to 32,000 characters, body at most 256 KB) returns `202` with the input view. `Idempotency-Key` or `clientMessageId` (one, not both, `400` otherwise) makes a retry return the first acknowledgement with `Idempotency-Replayed: true`; the same key with another text is `422 idempotency_conflict`. `expectedLastEventSeq` refuses the message with `409 stale_view` when newer events exist.

Acknowledgement levels: `queued`, `delivered` (handed to the runner), `consumed` (written to the agent; `eventSeq` points to its `message.user` event), and the final `cancelled` or `rejected` with a `reason`. A delivery not acknowledged within 60 s, or whose execution ended, is offered again, up to 3 attempts (`rejected`, `delivery_failed`). Inputs of a terminated session are `cancelled`. At most 100 inputs may be pending per session (`429 too_many_pending_inputs`).

**Wake.** A message to a paused session resumes it (`woke: true`) unless `wake: false`; it waits in the queue for the next resume. A message received while the session is pausing is queued and wakes the session as soon as the pause completes. The wake rechecks the owner and the profile assignment and is audited (`cloud.session.woken`).

`POST /cloud/sessions/{id}/interrupt` stops the current turn; interrupts are delivered before queued messages, never wake a session and are refused with `409 nothing_to_interrupt` unless the session is `ready` or `active`. `GET .../messages` lists inputs (newest first, `before` cursor) and `GET .../messages/{input}` reads one.

`TeamCode-On-Behalf-Of` is refused on these routes (`400 on_behalf_unsupported`): a message is always written by the authenticated member.

## Attach

`POST /cloud/sessions/{id}/attachments` takes a 60-second lease (`201`), renewed with `POST .../attachments/{attachment}/renew` (`409 attachment_expired` once lost) and released with `DELETE .../attachments/{attachment}`. Attaching needs the `attach` and `transcript` permissions; the lease lists what the member may do (`transcript`, `message`). An ended session cannot be attached (`409`).

`teamcode cloud attach SESSION [--tail N] [--read-only] [--no-wake]` holds a lease (renewed every 20 s, taken again if it expired), prints the last events and then the stream, and sends each input line as a message with its own idempotency key. `/interrupt` stops the current turn; `/detach` or the end of input detaches, and the session keeps running. A network failure reconnects from the last printed event, so nothing is printed twice or skipped.

## Access

Administrators hold every permission on every session. For members, the team's policy (`GET /cloud/access`, `PUT /cloud/access` with the current `version`; administrators only) lists rules `{subject, permissions, scope}`:

- `subject`: `everyone`, `member:<id>` or `group:<id>`;
- `permissions`: `create`, `attach`, `message`, `transcript`;
- `scope`: `own` (the member's own sessions, and creation) or `team` (every team session; `create` is not allowed there).

The default policy (version 0) gives everyone all four permissions on their own sessions, which is the behavior before this feature. A member without `create` gets `403 cloud_permission_denied` on creation, including when an administrator creates on the member's behalf. A session that a member cannot reach at all stays a `404`; a reachable one without the needed permission is `403 cloud_permission_denied`. Policy rules never widen lifecycle routes (pause, resume, delete stay owner and administrator only). Saving checks that members and groups exist (`422 member_not_found`, `group_not_found`) and is audited.

Groups come from a `CloudGroupDirectory`. TeamCode has no groups yet, so group rules are refused until the directory is wired to them; the rules and their evaluation are tested with a directory fake.

## CLI

```sh
teamcode cloud logs SESSION [--after SEQ] [--tail N] [--follow] [--json]
teamcode cloud send SESSION TEXT|- [--no-wake] [--idempotency-key KEY] [--wait] [--json]
teamcode cloud interrupt SESSION
teamcode cloud attach SESSION [--tail N] [--read-only] [--no-wake]
```

`send` retries network failures with the same idempotency key, so a retry never sends twice; `--wait` waits until the message was consumed or rejected. `logs --follow` reconnects like `attach`.

## Storage

Migrations `V20261019210000*`: `cloud_settings.transcript_retention_days`, `cloud_access_policies`, `cloud_session_inputs` (text as `MEDIUMTEXT`, unique `(session, author, client key)`), `cloud_session_events` (primary key `(session_id, seq)`, unique `(session_id, epoch, runner_seq)` for deduplication) and `cloud_session_attachments`, and `cloud_session_transcripts`, the per-session cursors (last sequence, runner sequence of the latest execution) that the retention purge never deletes, so numbering never restarts. Writes take the session row lock, so sequences stay dense across replicas.

## Known limits

- The agent runs with the pod UID like the runner, so it can read the projected token file; a separate UID or a token that the runner drops after reading is a follow-up.
- No `tcex_` inference credential is issued to the pod yet, so the agent cannot authenticate to the TeamCode gateway in a real cluster until that lands.
- Idle pause, the attachment lease in idle detection, `initialMessage`, the caller-wide feed, webhooks and transfer are not implemented.
- Masking works on values that look like credentials and on secret-named assignments; a secret split across a name/value object (`{"name": "API_KEY", "value": ...}`) or of an unknown shape is not masked.
- The SSE stream polls the database once a second per open stream (one primary-key read while nothing changes).
- When a Claude resume fails, the in-flight message that started the process may be lost; the next one starts a fresh conversation.

## Evidence

- `CloudConversationPersistenceTest` (real MySQL 8.4, two connection pools): migration drift; idempotent messages and replay; interrupts delivered first; acknowledgements and transcript order; runner event deduplication and gaps; turn markers moving `ready` and `active`; conversation resumability; backward paging and `stale_view`; follow; masking; the access policy (default, member and group rules, creation denied including on behalf, audit); wake while pausing through the sweeper; redelivery on a new epoch and within the same execution; numbering kept after a full purge; `wake: false` and synchronous wake; the pending cap; cancellation of a terminated session's inputs and the end of the stream; retention purge and validation.
- `CloudConversationRoutesTest` (a real local server for the stream): capabilities; service keys refused; another member's session invisible; on-behalf refused; idempotency header and body alias, replay header and conflict; the runner agent channel through the projected token (configuration, delivery, consumed acknowledgement, sanitized and deduplicated events, fencing, invalid types); SSE resume after `Last-Event-ID` with each event once and in order, ending with the session; access policy validation and administrator-only routes; read-only attachments from a group rule; renew, detach and expiry.
- `CloudAgentTest`: Claude and Codex adapter lines (flags, bypass, resume, thinking dropped, steer retry, resume fallback, approvals refused); the host starting lazily, acknowledging (and re-acknowledging a redelivered input without writing it twice), uploading in order, storing and resuming the conversation, leaving messages unacknowledged while draining, interrupting a long turn before the deadline, never resuming another agent version, and stopping when fenced; the runner loop heartbeating during the drain and reporting an interrupted drain.
- `CloudConversationRulesTest`: credential shapes masked; ordinary text (`task-management-…`, `max_tokens=4096`) kept; bounds.
- `AgentProcessTest` (JVM and native macOS): line pipes to a real `/bin/sh` child, removed variables, bounded long lines that never eat the next line, exit code.
