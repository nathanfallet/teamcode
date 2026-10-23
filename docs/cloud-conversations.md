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
| Idle pause with attachment leases, `initialMessage` on create (`cloud.sessions.initial-message.v1`) | Implemented, see [idle pause](cloud-sessions.md#idle-pause) and [create](cloud-sessions.md#create-request-and-view) |
| Caller-wide SSE feed: sessions and local runs, per member or team-wide, resumable (`cloud.events.v1`) | Implemented, see [the event feed](#event-feed) |
| Webhooks: team-wide subscriptions, HMAC signature, retries with backoff, delivery log, test button (`team.webhooks.v1`) | Implemented, see [webhooks](webhooks.md) |
| Transfer: leases removed, unconsumed messages cancelled or kept, `transfer` event | Implemented, see [transfer](cloud-sessions.md#transfer) |
| Permission requests relayed to members (`ask` rules, questions), answered through the API (`cloud.permissions.v1`) | Implemented for Claude Code, see [permission requests](#permission-requests) |
| Live agent text: `message.agent.delta` events replaced by their final `message.agent` (`cloud.transcript.live.v1`) | Implemented for Claude Code, see [live text](#live-text) |
| `waiting` on the session view while the agent waits for a person (`cloud.sessions.waiting.v1`) | Implemented, see [a session waiting for a person](#a-session-waiting-for-a-person) |

The capabilities are advertised with `cloud.sessions.v1`, while cloud sessions are on in the stored [runtime](cloud-sessions.md#runtime-on-off-runner-image-and-pod-callback).

## The agent in the pod

The runner (`teamcode cloud runner`) starts the session's agent with the first message, through the exec supervisor of the pod's agent container (its own UID, no runner token), as one long-lived process with streaming input, in bypass mode inside the sandboxed pod:

- **Claude Code**: `claude -p --input-format stream-json --output-format stream-json --verbose --dangerously-skip-permissions [--permission-prompt-tool stdio] [--include-partial-messages] [--model M] [--effort E] [--resume ID]` (the prompt tool when the server relays [permission requests](#permission-requests), partial messages when it takes [live text](#live-text)). Each message is a `user` line on stdin; Claude Code queues messages sent during a turn. An interrupt is a `control_request` with subtype `interrupt`.
- **Codex**: `codex app-server` (JSON-RPC lines without the `jsonrpc` member): `initialize`, `initialized`, then `thread/start` or `thread/resume` with `approvalPolicy: never` and `sandbox: danger-full-access` (the app-server form of `--dangerously-bypass-approvals-and-sandbox`). A message during a turn is `turn/steer`, otherwise `turn/start`; a steer that arrives after the turn ended starts the next turn instead of being lost. An interrupt is `turn/interrupt`. Server requests (approvals) are refused, since bypass mode should produce none.

The agent kind, version, model and effort come from the session (`POST /cloud/runner/agent`). Its inference goes through the runner's loopback proxy, authenticated to the gateway with the execution's `tcex_` credential (see [inference from the pod](cloud-sessions.md#inference-from-the-pod)); the agent only holds the proxy's local token. The conversation id is kept in `$HOME/.teamcode/cloud-agent.json` on the workspace volume; a new pod resumes it only when the agent kind and version are unchanged. Otherwise, or when the resume fails, a fresh conversation starts and `resume.conversation` becomes `transcript_only` (`resumable` once the agent reported its id). The agent runs in `TEAMCODE_CLOUD_WORKDIR` (default `/workspace`) and never inherits the runner's `TEAMCODE_*` variables.

Output is normalized in the runner (thinking and reasoning are dropped there) and uploaded in ordered batches (at most 200 events or 1 MB, buffer bounded to 5,000 events: further events are dropped without a sequence number and reported by a `transcript_overflow` error event). Each message is acknowledged once written to the agent's stdin; a redelivered input is acknowledged again without being written twice. Claude Code reports no turn start, so the runner emits `turn` `started` when a message reaches an idle agent. Agent stderr is discarded.

**Pause.** When a pause is requested, the runner drains beside its heartbeats: it lets the running turn finish until 60 s before the pause deadline, then interrupts it, waits 15 s, stops the process (10 s grace) and flushes the transcript for at most 30 s; an agent output stream still open 5 s after the process stopped (a descendant holding it) is given up rather than waited for. The checkpoint is sent as soon as the drain ends, not at the next heartbeat, and the runner exits once it is accepted, so the pod stops without waiting for its grace period. The runner and the agent supervisor are each PID 1 of their container, where a signal without a handler is ignored: both handle SIGTERM and SIGINT, stopping their processes and exiting at once (an eviction or a deletion no longer waits the 30-second grace period either). The pause itself reaches the runner through its open input long-poll, within half a second, instead of at its next heartbeat. The checkpoint reports `completed: false` when the turn had to be interrupted. Messages delivered during the drain are left unacknowledged, so the server offers them to the next execution. A pause cancelled during the drain lets the agent take messages again. A fenced execution (`409 execution_fenced`, or `401`/`403`/`404` on the agent channel) stops the agent at once.

### Runner routes

All take `{teamId, sessionId, epoch}` and the pod's projected token, like the lifecycle runner routes; another epoch gets `409 execution_fenced`.

| Route | Purpose |
| --- | --- |
| `POST /cloud/runner/agent` | `{kind, version, model, effort, conversation}` |
| `POST /cloud/runner/events` | `{events: [{sequence, type, data}]}`, the runner's own consecutive sequence per epoch (a retried batch is deduplicated); returns `{acceptedThrough, lastSeq}`. Body at most 2 MB, 200 events |
| `POST /cloud/runner/inputs` | Long-poll (`waitMillis` at most 20,000) for queued inputs, interrupts first; returns them `delivered`, with the session's `desiredState`. Nothing is delivered unless the session is `ready` or `active` and desired `running`. With `desiredState` in the request (the one the runner last knew, `running` or `paused`), the poll also ends as soon as the session's differs: that is how a pause, or a resume that cancels it, reaches the runner without waiting for a heartbeat |
| `POST /cloud/runner/inputs/ack` | `{acks: [{id, status: consumed or rejected, reason}]}` |
| `POST /cloud/runner/inference` | The execution's `tcex_` inference credential, see [inference from the pod](cloud-sessions.md#inference-from-the-pod) |

A `turn` event with phase `started` makes a `ready` session `active`; `completed`, `interrupted` or `failed` makes it `ready` again. Both refresh `lastActivityAt`. A `status` event with `conversation` updates `resume.conversation`.

## Transcript

Events, in `cloud_session_events`:

| Type | `data` |
| --- | --- |
| `message.user` | `{text, inputId, authorId}`, written by the server when the agent consumed the message |
| `message.agent` | `{text, messageId?, index?, parentToolUseId?}`; `messageId` is the agent's API message id (Claude Code), `index` the content block when it was streamed, see [live text](#live-text) |
| `message.agent.delta` | `{messageId, index, text, parentToolUseId?}`, the text a block gained since its previous delta, see [live text](#live-text) |
| `tool.call` | `{id, name, input, parentToolUseId?}` |
| `tool.result` | `{id, output, isError, exitCode?, parentToolUseId?}` |
| `turn` | `{phase: started, completed, interrupted, failed, durationMs?, steps?, costMicros?, totalCostMicros?, inputTokens?, outputTokens?, cacheCreationInputTokens?, cacheReadInputTokens?}` |
| `status` | `{agent, conversation?, conversationId?, model?, exitCode?}` |
| `error` | `{code, message}` |
| `permission.request` | `{requestId, toolName, input or inputTruncated and inputPreview, question, toolUseId?, description?, reason?}`, see [permission requests](#permission-requests) |
| `permission.expired` | `{requestId, deadlineMinutes, message}`, runner-written when it denied a request nobody answered within the team's deadline |
| `input` | `{inputId, kind, status, reason}`, server-written when an input is cancelled or rejected, or a permission answer consumed (then with `requestId`, `behavior` and `authorId`) |
| `transfer` | `{transferId, fromMemberId, toMemberId, atEpoch}`, server-written when the session changed owner |

A Claude Code turn that ends with a `result` line also carries its cost and tokens. `totalCostMicros` is Claude Code's own estimate of the conversation's cost so far, in micro-dollars (its `total_cost_usd`, computed from its price list, not what a subscription account pays), and `costMicros` the part of it spent by this turn. Claude Code keeps that total across `--resume`, so the first turn after the agent restarted in a new pod has `totalCostMicros` but no `costMicros` (a client may subtract the previous turn's total, which holds only within one `conversationId`: a fresh conversation restarts at zero). A turn whose `result` reports no cost is counted in the next priced turn, and a total lower than the previous one gives no `costMicros`. Costs and the four token counts (`usage` of the `result` line) belong to one `result` line, which may answer several messages that Claude Code queued during a turn. Codex turns carry none of these. Clients must tolerate unknown types and unknown fields. `seq` starts at 1 and increases by one per session; `epoch` is the producing execution (0 for server events).

`parentToolUseId` is set on the events of a Claude Code subagent: it is the `id` of the parent's `tool.call` that started it (the `Agent` tool, `Task` in older Claude Code versions), so a client can show a subagent's steps under that call, as `teamcode cloud logs` and `attach` do by indenting them; it is absent for the main agent, for Codex, and when the agent does not report it. Clients must tolerate unknown types and unknown fields. `seq` starts at 1 and increases by one per session; `epoch` is the producing execution (0 for server events).

**Bounds and masking.** The server, not only the runner, removes `thinking`, `signature`, `reasoning` and `redacted_thinking` keys, caps nesting at 16 levels and arrays at 200 items, cuts strings at 16,000 characters (marked), masks secrets (provider and GitHub tokens, TeamCode keys, AWS keys, Slack tokens, JWTs, private keys, `Authorization` values, secret-named assignments, URL credentials) and replaces an event larger than 60,000 bytes by `{truncated, originalBytes}`. The marker keeps the event's identity fields, from the masked event, so a client still links it: `id`, `parentToolUseId`, `toolUseId`, `requestId`, `messageId` and `inputId` when they are strings of at most 128 characters, `name` of at most 200, `isError` when it is a boolean and `index` when it is a non-negative integer; never any other field. A cut `tool.result` therefore stays under its `tool.call` (and a subagent's under its parent), and a cut `message.agent` still replaces its deltas.

### Live text

When the server advertises `cloud.transcript.live.v1` it sets `liveText` in `POST /cloud/runner/agent` for Claude Code sessions, and the runner starts Claude Code with `--include-partial-messages`. Each text block of the agent's answer then streams as `message.agent.delta` events while it is written, before the `message.agent` that holds the block's whole text:

- `messageId` is the agent's API message id, `index` the content block within that message: `(messageId, index)` names one block. `text` is what the block gained since the previous delta of the same block, so a client appends it.
- Deltas are coalesced in the runner: one goes out once 500 ms passed since the block's previous delta (or its start), once about 2,000 characters are held, or at the block's end. Only text is streamed: thinking and the input of a tool use never are.
- **Replacement.** The `message.agent` with the same `messageId` and `index` replaces the deltas of that block (Claude Code writes it once the block is complete); one with the `messageId` but no `index` replaces every block of that message. No delta of a block comes after its `message.agent`. A cut `message.agent` (`truncated`, above 60,000 bytes) keeps `messageId` and `index` and still ends its block, but holds no text: a client that showed the block's deltas may keep them as its text.
- A block that gets no `message.agent` (its turn was interrupted) sends no more deltas after its turn's `turn` event; its partial text is not in the final transcript, so a client drops it at that event.
- Clients that do not show live text ignore `message.agent.delta`, as they ignore any unknown type: `message.agent` carries the whole text as before. `teamcode cloud logs` and `attach` print the final message only (`logs --json` prints every event, deltas included), and the companion app shows final messages only.
- Deltas are stored like other events (same bounds, masking and retention), so a client that reconnects replays them in order. Codex sessions are unchanged: they send whole messages and no deltas.

**Routes.**

| Route | Purpose |
| --- | --- |
| `GET /cloud/sessions/{id}/events?after=&before=&limit=` | Page in ascending order (`limit` 1 to 200). `firstSeq` is the oldest retained event, `lastSeq` the newest; continue with `after=nextAfter` or `before=nextBefore` |
| `GET /cloud/sessions/{id}/events/stream` | SSE: `id: <seq>`, `event: <type>`, `data: <event JSON>`; resumes after `Last-Event-ID` (or `?after=`). Keep-alive comments every 15 s; `retry: 2000`. Ends with `event: end` when the session ended, or `event: denied` when access was revoked; access is rechecked every 15 s |

**Retention.** `transcriptRetentionDays` in the cloud settings (1 to 3,650, default 30, `null` keeps forever) deletes older events every 10 minutes, in bounded batches.

## Messages and interrupts

`POST /cloud/sessions/{id}/messages` with `{text, wake, clientMessageId, expectedLastEventSeq}` (text 1 to 32,000 characters, body at most 256 KB) returns `202` with the input view. `Idempotency-Key` or `clientMessageId` (one, not both, `400` otherwise) makes a retry return the first acknowledgement with `Idempotency-Replayed: true`; the same key with another text is `422 idempotency_conflict`. `expectedLastEventSeq` refuses the message with `409 stale_view` when newer events exist.

Acknowledgement levels: `queued`, `delivered` (handed to the runner), `consumed` (written to the agent; `eventSeq` points to its `message.user` event), and the final `cancelled` or `rejected` with a `reason`. A delivery not acknowledged within 60 s, or whose execution ended, is offered again, up to 3 attempts (`rejected`, `delivery_failed`). Inputs of a terminated session are `cancelled`; at a change of owner, unconsumed inputs are `cancelled` with reason `transferred` or kept, per the team's `transferPendingInputs`. At most 100 inputs may be pending per session (`429 too_many_pending_inputs`).

**Wake.** A message to a paused session resumes it (`woke: true`) unless `wake: false`; it waits in the queue for the next resume. A message received while the session is pausing wakes it like a [resume during a pause](cloud-sessions.md#pause-and-resume): the pause is cancelled when the runner has not checkpointed yet, otherwise the session resumes as soon as the pause completes. The wake rechecks the owner and the profile assignment and is audited (`cloud.session.woken`).

`POST /cloud/sessions/{id}/interrupt` stops the current turn; interrupts are delivered before queued messages, never wake a session and are refused with `409 nothing_to_interrupt` unless the session is `ready` or `active`. `GET .../messages` lists inputs (newest first, `before` cursor) and `GET .../messages/{input}` reads one.

`TeamCode-On-Behalf-Of` is refused on these routes (`400 on_behalf_unsupported`): a message is always written by the authenticated member.

## Permission requests

Bypass mode skips Claude Code's ordinary permission checks, but two things still wait for a person: a tool use matched by an `ask` permission rule (from the settings in the image or the repository, or a hook that answers `ask`), and a question to the user (`AskUserQuestion`). Without a prompt tool, Claude Code in print mode refuses the first ("Claude requested permissions to use Bash, but you haven't granted it yet") and does not offer the second. When the server advertises `cloud.permissions.v1` it sets `permissionRelay` in `POST /cloud/runner/agent` for Claude Code sessions, and the runner starts Claude Code with `--permission-prompt-tool stdio`: each `can_use_tool` control request becomes a `permission.request` event and the agent waits.

`POST /cloud/sessions/{id}/permissions` with `{requestId, behavior: allow or deny, message?, answers?}` (body at most 64 KiB, `Idempotency-Key` accepted; a retry with the same key replays the first acknowledgement even after the answer was consumed) answers it and returns `202` with the input view (kind `permission`). `deny` passes `message` to the agent. `allow` with `answers` answers a question: each answer is `{question, options}` or `{question, text}`, where `question` is the index of the question in the request's `input.questions` and `options` the indexes of the chosen options in its `options` (from 0; several only for a `multiSelect` question), or `text` a free answer; at most 16. The runner turns the indexes into Claude Code's format (question text to the chosen label, labels joined with `, ` for several). Indexes never depend on how the transcript shows a question, so a question masked or cut there is still answered exactly.

The answer is checked under the session lock, atomically with its insert (so of two members answering at once the second sees the first), against the request found among the session's latest 200 requests however many events followed it (index `cloud_event_type`), and only while the session is `ready` or `active` and not being paused (`409 nothing_to_answer`): no such request is `409 unknown_permission_request`; one already closed is `409` too, `permission_request_answered` when an answer to it was consumed or another one is still on its way to the agent (checked under the session lock, so two members cannot both be told their conflicting answers were accepted), `permission_request_expired` when the runner denied it at the deadline, `permission_request_ended` when its turn ended or it belongs to an execution that ended (a pause, a new pod); and an index the request does not have, more than one option for a single-choice question, two answers to questions with the same text (Claude Code keys answers by text), an `allow` of a question that does not answer each of its questions, or answers to a tool use `400 invalid_permission_answer` (a request whose input was left out of the event can only be denied, below). **Who may answer**: anyone with the `message` permission answers or declines a question, as a message could already steer the agent; a tool use gated by an `ask` rule is allowed or denied only by the session owner or a cloud administrator (an interactive administrator, or an administrator's `admin:cloud` key), `403 permission_answer_denied` otherwise, since the rule exists to keep that decision with them.

An answer never wakes a session, is refused with `409 nothing_to_answer` unless the session is `ready` or `active`, and is delivered before queued messages. Pending answers have their own bound of 16 per session, apart from the 100 pending inputs, so messages queued behind a waiting turn never block the answer that lets it end. Only the agent process that asked can take it: an answer to a request that is no longer open is `rejected` with `unknown_permission_request`, one whose indexes do not fit the request with `invalid_answer` (the request stays open), and one delivered to an execution that ended with `execution_ended`. A request is open until its answer is consumed, its turn ends (a `turn` event) or its deadline passes. While it waits the turn is running, so the session stays `active`; a pause interrupts it like any long turn.

**Deadline.** `permissionDeadlineMinutes` in the [cloud settings](cloud-sessions.md#persistent-settings) (dashboard or `PUT /cloud/settings`) is 0 by default: a request waits until an answer, an interrupt or a pause. With 1 to 1,440 minutes, the runner denies a request still open after that long with the message "No answer within N minutes: the request was denied.", which the model reads as the tool result, and records a `permission.expired` event. The runner reads it once per execution (pod), so a change applies to sessions started or resumed afterwards.

Codex sessions keep refusing approval requests: their bypass mode produces none.

`question` is true only for `AskUserQuestion`; any other tool that reaches the prompt tool, interactive or not, is a tool use to approve. Limits of this first version: a tool input above 15,000 UTF-8 bytes, or one the server would cut (more than 200 entries in an object or array, nesting deeper than 16, keys above 64 characters, or keys the transcript drops), is left out of the event (`inputTruncated`, with its first 2,000 characters in `inputPreview`), the request id kept, and such a request can only be denied (`allow` is `400 permission_input_unseen`: nobody approves what nobody could read); a kept input is returned to Claude Code as is on `allow` (secrets in it are masked for the approver as everywhere in the transcript, never in what Claude Code gets back); a request id the server would not accept back (not 1 to 128 printable ASCII characters) is denied at once; a request the runner cannot record (its upload buffer is full while the server is unreachable, or the server refused the event) is denied at once rather than left waiting unseen; any other control request from Claude Code is answered with an error, so it never waits on one; an answer not acknowledged within 60 s is offered again to the same execution (the runner acknowledges a repeat without writing it twice), up to 3 attempts, and rejected (`execution_ended`) once its execution ended; a pausing session takes no answer, so when it starts draining the runner denies its open requests ("The session is pausing: the request was denied.") and the turn waiting on one can end; one the runner could not write to the agent is rejected (`agent_unavailable`) and leaves the request open, its deadline still running. A transfer cancels pending answers (`transferred`) even when the team keeps pending messages: the process that asked is gone. Every replica must run this version before members answer: an older replica reads a `permission` input as a message.

## A session waiting for a person

`GET /cloud/sessions` and `GET /cloud/sessions/{id}` return `waiting` (`cloud.sessions.waiting.v1`) while the session's agent waits for a [permission request](#permission-requests) to be decided, and no field otherwise:

| Field | Meaning |
| --- | --- |
| `reason` | `permission` (a tool use to approve) or `question` (a question to answer) |
| `requestId` | What `POST /permissions` takes; always present in this version, optional for later reasons to wait |
| `since` | When the request was stored (server time, milliseconds) |
| `eventSeq` | The `permission.request` event in the transcript |

It is set when a `permission.request` is stored, and ends exactly when the request stops being answerable: its answer is consumed, it expires (`permission.expired`), its turn ends (a `turn` end event), or the session stops taking answers (a pause is asked, the session pauses, ends or is transferred, which always goes through a new execution). When several requests are open at once (parallel tool uses), it shows the newest; when that one is answered or expires, the newest earlier request of the same execution still open shows instead. A request stored while the session takes no answer (a pause asked: the runner denies it), or with an id `POST /permissions` would refuse (the runner denies it at once), never shows.

It is written in the same transaction as the transcript event, or the acknowledgement of the answer, that opens or closes it, under the session row lock already held there, and the session store clears it in the write that makes the session stop taking answers; reads return it with the session row, so listing sessions costs no extra query. It agrees with the [event feed](#event-feed)'s `cloud.session.waiting` and `cloud.session.answered`, which tell a client when to read it again.

Limits: the search for the next open request reads at most 16 earlier requests, from their stored head only; with more than 16 requests open at once in one turn, an older one stays open without showing until its turn ends. A request Claude Code withdrew on its own (`control_cancel_request`, only seen with an interrupted turn) shows until that turn's end, which follows at once.

## Attach

`POST /cloud/sessions/{id}/attachments` takes a 60-second lease (`201`), renewed with `POST .../attachments/{attachment}/renew` (`409 attachment_expired` once lost) and released with `DELETE .../attachments/{attachment}`. Attaching needs the `attach` and `transcript` permissions; the lease lists what the member may do (`transcript`, `message`). An ended session cannot be attached (`409`). A change of owner removes every lease of the session; the former owner's `teamcode cloud attach` then stops with "Access to this session was revoked." A live lease keeps the session from the [idle pause](cloud-sessions.md#idle-pause), and the end of a lease (detach or expiry) counts as its last activity; ended leases are kept a day for that, then purged.

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
teamcode cloud answer SESSION REQUEST_ID allow|deny [--message TEXT] [--answer QUESTION=OPTION[,OPTION...]]... [--answer-text QUESTION=TEXT]...
teamcode cloud attach SESSION [--tail N] [--read-only] [--no-wake]
teamcode attach SESSION [--tail N] [--read-only] [--no-wake]   # the same command, for cloud and remote sessions
```

`send` retries network failures with the same idempotency key, so a retry never sends twice; `--wait` waits until the message was consumed or rejected. `logs --follow` reconnects like `attach`. `logs` and `attach` skip `message.agent.delta` and print the final message.

`logs` and `attach` print a tool use as `[permission ID]` with the tool's whole input (or its preview, marked cut), and a question as `[question ID]` followed by one line per question (or by its preview when its input was left out; control, format and separator characters, including invisible ones outside the basic plane, shown as spaces, so agent text cannot rewrite or hide part of the line) with its index and its options' indexes, agent texts quoted so a label cannot pass for more options, for example `? 0: "Red or blue?" (0: "Red" / 1: "Blue")` (`several` when more than one option may be chosen), an expiry as `[expired ID]`, and a consumed answer as `[permission INPUT consumed] allow REQUEST by MEMBER`. `answer` takes those indexes: `--answer 0=1` picks `Blue`, `--answer 1=0,2` two options of a multi-select question, `--answer-text 0=Green` a free answer.

## Event feed

`GET /api/v1/cloud/events/stream` is one SSE stream for everything a credential may see happen, instead of one connection per session. It is advertised as `cloud.events.v1`, and needs an interactive login or a key with `cloud:read` (or `cloud:write`, `admin:cloud`).

| Event (`event:` field) | When | `data` (besides `sessionId` or `runId`) |
| --- | --- | --- |
| `cloud.session.created` | A session was created | `name`, `state`, `kind` (`cloud` or `remote`), `hostDeviceId` for a remote session; every session event carries the last two, `cloud.turn`, `.waiting` and `.answered` included |
| `cloud.session.paused` | It reached `paused` | `state`, `previousState`, `reason` (`paused`, `idle_paused`, ...) |
| `cloud.session.woken` | A message asked a paused session to wake up | `state`, `reason` |
| `cloud.session.resumed` | A resuming session is `ready` again | `state`, `previousState` |
| `cloud.session.ended` | It reached `terminated` or `failed` | `state`, `reason` (`terminated`, `failed`, `expired`, ...) |
| `cloud.turn` | The agent started, completed, interrupted or failed a turn | `phase`, `eventSeq` (the sequence in the session's transcript) |
| `cloud.session.waiting` | The agent waits for a person: a [permission request](#permission-requests) | `reason` (`permission` or `question`), `requestId`, `eventSeq` (the `permission.request` event) |
| `cloud.session.answered` | The request was decided: a member's answer reached the agent (`reason` `answered`), or the runner denied it at the team's [deadline](#permission-requests) (`reason` `expired`, `behavior` `deny`) | `requestId`, `behavior` (`allow` or `deny`), `reason`, `eventSeq` (the `input` event, or the `permission.expired` event) |
| `run.started`, `run.ended` | A local launch (`teamcode launch`) started or ended | `agent`, `workload`; `exitCode` when ended |
| `run.stale` | No heartbeat for 90 s | `agent`, `workload`, `lastHeartbeatAt` |

Each frame is `id: <seq>`, `event: <type>`, `data: <TeamEventView>` (`seq`, `type`, `subject` = `session` or `run`, `subjectId`, `memberId`, `at`, `data`). Transcript text never appears in it: follow `eventSeq` into `GET /cloud/sessions/{id}/events`. A run that beats again and goes silent again produces another `run.stale`. The state of a session after `cloud.session.created`, `.paused`, `.woken`, `.resumed` and `.ended` is in `data.state`; the types are an open list, so a client ignores types it does not know. A session can wait for several requests at once; one stops waiting at `cloud.session.answered` for it (an answer, or the deadline's denial, so with a team deadline every wait ends with this event unless its turn ends first), at a later `cloud.turn` (the turn it belonged to ended), or when the session pauses or ends. `cloud.session.answered` can come after that turn's end, since the runner acknowledges the answer and uploads its events separately: a client ignores an answer to a request it no longer tracks. A rejected or cancelled answer publishes nothing (the request may still be open). `requestId` is left out when the runner sent one that `POST /permissions` would refuse.

**Who sees what.** The caller's own sessions and runs, as `memberId`. The whole team for an interactive administrator or an administrator's `team:read` key; for sessions also an administrator's `admin:cloud` key. A key acts as its owner otherwise, even an administrator's. Run events additionally need an interactive login or a `usage:read` or `team:read` key (a `cloud:read` key sees sessions only), and the whole team's runs only an interactive administrator or a `team:read` key, as for `GET /runs?all=true`. A session keeps the owner it had when the event happened, so after a transfer the former owner keeps the earlier events and the new owner gets the later ones. Access granted by the [access policy](#access) to other members is not part of the feed: it is the owner's and the administrators' view.

**Resuming.** `seq` is the team's own sequence, gap-free and allocated in the transaction that changes the state, so an event exists exactly when its change committed and a reader never skips one. Send `Last-Event-ID` (an `EventSource` does it by itself) or `?after=`; the header wins. Without either the stream starts at the newest event; `after=0` starts at the oldest retained one. A cursor ahead of the team's events is `400 invalid_cursor` (a cursor from another server). Events are kept seven days: when the cursor is older than the oldest kept event, `event: gap` with `{"oldestSeq": n}` comes first, then the stream continues from there. `?types=cloud.turn,run.stale` keeps only those types (`400 invalid_event_type` for an unknown one). `?kind=cloud&kind=remote` chooses the session kinds whose events the stream carries, as for the session list: without it only cloud sessions', so a client written before remote sessions never hears of one (`400 invalid_kind` otherwise); run events always pass. Keep-alive comments every 15 s; the credential is rechecked every 15 s and the stream ends with `event: denied` when it stopped being valid. The stream never ends otherwise: reconnect from the last `id`. The typed client follows it with `followTeamEvents` ([contract](companion.md#client-contract)).

**Storage.** Migrations `V20261021210000*`: `team_events` (primary key `(team_id, seq)`), `team_event_cursors` (the last sequence per team; its row lock serializes publishers of a team) and `agent_runs.stale_notified_at`. Session and run writes publish in their own transaction (session store, transfer store, transcript turn events, run start and end); a scheduled job (`team-events/stale, every 30 s under a database lease) reports silent runs, only those whose last heartbeat is less than a day old,; a second job (`team-events/purge`) deletes events older than seven days. The stream reads the journal once a second per open stream (a primary-key read on the team's cursor while nothing changes).

## Storage

Migrations `V20261020100009*`: `cloud_settings.transcript_retention_days`, `cloud_access_policies`, `cloud_session_inputs` (text as `MEDIUMTEXT`, unique `(session, author, client key)`), `cloud_session_events` (primary key `(session_id, seq)`, unique `(session_id, epoch, runner_seq)` for deduplication) and `cloud_session_attachments`, and `cloud_session_transcripts`, the per-session cursors (last sequence, runner sequence of the latest execution) that the retention purge never deletes, so numbering never restarts. Writes take the session row lock, so sequences stay dense across replicas. Migration `V20261023230000.2` adds the `awaiting_*` columns of `cloud_sessions` that hold [`waiting`](#a-session-waiting-for-a-person).

## Known limits

- The agent runs in its own container under its own UID with no projected token, started by the supervisor there for the runner (see [the runner and the pod](cloud-sessions.md#the-runner-and-the-pod)). Its conversation files live under `/workspace/.agent-home`; a workspace from before this split keeps them in `.home`, so such a session starts a fresh conversation (the transcript is kept).
- The event feed keeps seven days of history; a service that cannot hold a stream open uses [webhooks](webhooks.md).
- Masking works on values that look like credentials and on secret-named assignments; a secret split across a name/value object (`{"name": "API_KEY", "value": ...}`) or of an unknown shape is not masked.
- The SSE stream polls the database once a second per open stream (one primary-key read while nothing changes).
- An event replaced by `{truncated, originalBytes}` (above 60,000 bytes) keeps only its identity fields (see [bounds and masking](#transcript)): its text, input or output is gone.
- Live text adds about two events per second of agent writing to the transcript; coalescing bounds it, nothing else does.
- When a Claude resume fails, the in-flight message that started the process may be lost; the next one starts a fresh conversation.

## Evidence

- `CloudConversationPersistenceTest` (real MySQL 8.4, two connection pools): migration drift; idempotent messages and replay; interrupts delivered first; acknowledgements and transcript order; runner event deduplication and gaps; turn markers moving `ready` and `active`; conversation resumability; backward paging and `stale_view`; follow; masking; the access policy (default, member and group rules, creation denied including on behalf, audit); wake while pausing through the sweeper; redelivery on a new epoch and within the same execution; numbering kept after a full purge; `wake: false` and synchronous wake; the pending cap; cancellation of a terminated session's inputs and the end of the stream; retention purge and validation.
- `CloudConversationRoutesTest` (a real local server for the stream): capabilities; service keys refused; another member's session invisible; on-behalf refused; idempotency header and body alias, replay header and conflict; the runner agent channel through the projected token (configuration, delivery, consumed acknowledgement, sanitized and deduplicated events, fencing, invalid types); SSE resume after `Last-Event-ID` with each event once and in order, ending with the session; access policy validation and administrator-only routes; read-only attachments from a group rule; renew, detach and expiry.
- `CloudAgentTest`: Claude and Codex adapter lines (flags, bypass, resume, thinking dropped, subagent events keeping their parent tool call, turn cost and tokens with their resume rule, permission requests relayed, answered once by index, withdrawn, ended with their turn or denied at the team deadline; answers delivered only to the process that asked, steer retry, resume fallback, approvals refused); the host starting lazily, acknowledging (and re-acknowledging a redelivered input without writing it twice), uploading in order, storing and resuming the conversation, leaving messages unacknowledged while draining, interrupting a long turn before the deadline, never resuming another agent version, and stopping when fenced; the runner loop heartbeating during the drain and reporting an interrupted drain.
- `CloudConversationPersistenceTest` also covers `waiting`: set by a stored request and read with the session (and the list), ended by the consumed answer, by its turn's end in the same batch, by an expiry that shows the earlier request still open, by a pause asked, never set by a request raised while pausing, kept through a session write (a turn marker); live text deltas stored like other events.
- `CloudAgentTest` (live text): coalesced `text_delta` lines from a Claude Code 2.1.309-shaped stream, the final `message.agent` with `messageId` and `index` dropping held text, no delta after it, thinking and tool input never streamed, the 2,000-character flush, a subagent's `parentToolUseId`, held text dropped with an interrupted turn, the flag only when the server takes deltas, the host's clock; `logs` and `attach` skipping deltas. `CloudTranscriptSanitizerTest`: the identity fields a cut event keeps. `CloudHumanWaitTest`: when a stored wait shows.
- `CloudConversationRulesTest`: credential shapes masked; ordinary text (`task-management-…`, `max_tokens=4096`) kept; bounds.
- `AgentProcessTest` (JVM and native macOS): line pipes to a real `/bin/sh` child, removed variables, bounded long lines that never eat the next line, exit code.
