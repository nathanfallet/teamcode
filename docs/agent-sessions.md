# Agent launches and native sessions

Current `teamcode run claude -- ...` and `teamcode run codex -- ...` register a durable launch when the server advertises `team.runs.v1`. Inspect it in **Dashboard → Agents and MCP → Agent sessions**, through the API, or with:

```sh
teamcode sessions list
teamcode sessions list --json
teamcode sessions list --all --json
teamcode sessions list --member MEMBER_UUID --before RUN_UUID --json
teamcode sessions show RUN_UUID
```

```sh
teamcode run claude --label task=1234 --label team=payments -- -p "Review the test coverage"
teamcode sessions list --label task=1234 --json
```

These are local agent-launch records. `teamcode auth sessions` manages login credentials. Kubernetes cloud sessions, attachment, transcripts and transfers remain V2. This registry neither starts remote processes nor exposes a process-kill API.

## Lifecycle and identity

Each wrapper invocation generates one UUID and registers its agent kind and workload before launching the child. The creating TeamCode credential and optional registered-device ID are captured server-side. An identical start retry returns the same record; conflicting reuse fails and an ended record cannot be reopened. Command arguments, working directories, process IDs, prompts, transcripts and raw native session IDs are not stored.

The wrapper renews its heartbeat every 30 seconds. Successful child exit reports its actual code; cancellation or an unsuccessful launch can report an unknown code. Completion cleanup is bounded to three seconds and also attempts to close an uncertain start response. Failed heartbeats do not terminate an agent. Permanent authentication/identity/lifecycle refusals stop heartbeat retries; temporary failures produce sparse, redacted stderr warnings. Existing invocation arguments/environment isolation and exit-code propagation remain in place.

| State | Meaning |
| --- | --- |
| `RUNNING` | A valid creating credential and a wrapper heartbeat within 90 seconds |
| `STALE` | No recent heartbeat; process state is unknown |
| `ENDED` | The wrapper reported completion; exit code may be unknown |
| `REVOKED` | An unended launch's creating credential/member is no longer usable |

Active and queued gateway requests are separate observations. A stale wrapper can still have an unexpired reservation; lease expiry releases concurrency without refunding uncertain credits. Heartbeat staleness itself is informational and does not stop otherwise authorized inference. Ended records retain that confirmed state after later credential revocation. Ending or pausing a run prevents **new** admissions; active streams finish normally. TeamCode does not infer that every descendant process has stopped.

Lifecycle writes require the creating credential. Another login by the same member can read the launch but cannot heartbeat, end or dispatch requests under that old launch ID. Password, role, device or key revocation does not refund its spend. A shared local service that reloads another credential cannot silently attach it to an existing launch; restart the wrapper using the current login.

## Labels and the run identifier

`teamcode run claude|codex --label key=value` (repeatable, before `--`) attaches labels to the launch, with the model of [cloud session labels](cloud-sessions.md): at most 16 non-secret pairs, lowercase keys of 1–63 characters (`[a-z0-9]`, then `[a-z0-9._/-]`), values up to 256 characters without control characters. The CLI parses `--label` exactly like `teamcode cloud create --label` (the key is everything before the first `=`; the value may contain `=`; the last value of a repeated key wins) and the server validates with the same rules (`invalid_label`). Labels are sent with the start request, stored in `agent_run_labels` (additive migration, removed with the launch) and returned as `labels` by `GET /api/v1/runs` and `GET /api/v1/runs/{id}`. They are fixed at start: an identical retry is idempotent, a retry with other labels is `run_conflict`.

Filter with `GET /api/v1/runs?label=key=value` (repeatable, every filter must match, at most 16) or `teamcode sessions list --label key=value`, which shows each launch's labels as trailing columns. The filter composes with `member`, `all` and `before`, and never widens what the caller may read. Do not put secrets in labels. The server advertises `team.runs.labels.v1`; against an older server, `teamcode run --label` and `teamcode sessions list --label` stop with an error instead of dropping the labels or returning an unfiltered list.

Both `teamcode run claude` and `teamcode run codex` export `TEAMCODE_RUN_ID`, the launch UUID, in the agent's environment, so a hook, tool or script run by the agent can correlate its work with `teamcode sessions show "$TEAMCODE_RUN_ID"` or the API. It is metadata, not a credential. Without a registered launch (an older server) the variable is removed, so an outer launch's identifier is never inherited by mistake. The name contains none of `KEY`, `SECRET` or `TOKEN`, so Codex's default shell environment policy does not filter it from the commands it runs. Cloud sessions keep their session identifier out of the agent container by design, so it is not exported there.

## Waiting for a person

A launch says whether its agent works or waits for you, so a dashboard can tell a launch that needs attention from one that is busy. The state is `working` or `waiting_for_human`, and the launch view (`GET /api/v1/runs`, `sessions list`, **Dashboard → Agent sessions**) shows it, with when it began (`activity`, `activitySinceMillis`), **only while the launch is `RUNNING`**: after `STALE`, `ENDED` or `REVOKED` it would be a guess, so it is left out. A heartbeat that ends a silence clears a state reported before it; a state reported during the silence is kept.

| Agent event | Reported | Agents |
| --- | --- | --- |
| `Stop` | `waiting_for_human`: the turn ended and the agent waits for your next message | Claude Code, Codex |
| `Notification` of type `permission_prompt`, `idle_prompt` or `elicitation_dialog` (other or missing types say nothing) | `waiting_for_human`: a permission request, an idle prompt or a question | Claude Code (Codex has no `Notification` event) |
| `UserPromptSubmit` | `working` | Claude Code, Codex |
| `PostToolUse` | `working`: the tool ran after an approval, so the permission wait is over | Claude Code |

Nothing else leaves the computer: the hook reads the event name and, for a notification, its type, and drops the prompt, the message, the tool, the paths and the session identifier ([guidance](agent-guidance.md) keeps its own, separate use of the session identifier). A tool the member approved after a permission prompt keeps the launch at `waiting_for_human` until it ends (`PostToolUse` fires after it). Events whose payload exceeds 64 KiB (a large tool result) are not reported. An interrupt (Esc) fires no `Stop`, so the launch keeps its previous state until the next prompt or tool use. A permission the member denies produces no `PostToolUse`, so the launch keeps showing `waiting_for_human` until the agent's next tool use or prompt. A `Stop` that another hook refuses (a team hook that makes the agent continue) is corrected by the next tool use or prompt. Subagent events (`SubagentStop`) say nothing about the launch.

- **Claude Code.** The session plugin that carries guidance also registers HTTP hooks for `Stop`, `Notification` and `PostToolUse` on the local TeamCode service, and names the launch in the `X-TeamCode-Run` header. A reused shared service that does not advertise `activity-relay-v1` is replaced by a dedicated one for the launch. The service answers `{}` first, then reports one at a time in arrival order; it remembers the last state it sent per launch, so a turn with many tools costs one request. A temporary failure is retried at the next event; a refusal the server repeats (an ended or foreign launch: 403, 404, 409) is not. A state reported before a silence of the wrapper is cleared by the heartbeat that ends the silence.
- **Codex.** The guidance command (`teamcode agents hook`) is also registered on `Stop`, and reports from `UserPromptSubmit` and `Stop` with the launch identifier it reads from `TEAMCODE_RUN_ID`, only when the launcher set `TEAMCODE_ACTIVITY=1` for the launch (so `--no-activity`, an older server and custom hooks report nothing). The process waits for the report, at most 1.5 s, after printing its output. Codex asks you to trust the new `Stop` definition in `/hooks`, like the other TeamCode hooks. With no `Notification` event and no per-tool hook, a Codex launch waiting for a permission shows `working` until its turn ends.
- **Opting out.** `teamcode run --no-activity` reports nothing for that launch; `--no-guidance` and `--bare` also remove these hooks, which travel with guidance. A server without `team.runs.activity.v1`, a launch that is not registered, or custom Codex `-c hooks...` overrides get none, and never block the launch.
- **API.** `POST /api/v1/runs/{id}/activity` with `{ "state": "waiting_for_human" | "working" }`, through the credential that created the launch (an inference key may, like heartbeats). A repeated state writes nothing and keeps its time. `409 run_ended` after the end; `400 invalid_run_activity` for another state.
- **Cloud sessions.** They need no hook for this: the runner already sees the agent's turns (`ready` between turns, `active` during one) and a permission or a question is the [`cloud.session.waiting`](cloud-conversations.md) event. The team hooks of [cloud sessions](hooks.md#cloud-sessions) are separate and unchanged.

## Proxy association and compatibility

Launch-specific loopback paths carry the run UUID and workload. The local proxy validates their syntax and conflicts, attaches `x-teamcode-run-id`/`x-teamcode-workload`, and forwards only canonical inference endpoints. Central admission verifies the creating team/member/token and registered workload. UUIDs are metadata, not bearer capabilities. Both team headers are removed before a provider transport is invoked; provider authentication still comes exclusively from the server's encrypted pool.

The user service advertises `run-routing-v1`. A registered launch uses a separate ephemeral proxy if an older service lacks that capability, preserving the existing service. An older server without `team.runs.v1` retains the existing launcher behavior and has no fabricated registry entry. Upgrade all server replicas before relying on run attribution or limits: old binaries do not understand them. Upgrade the native CLI/service to register new launches. Already running legacy clients are not reconstructed into launches.

Direct API clients can register a UUID and attach it to inference. Requests without a run ID continue using ordinary member/group/pool policy. **A run cap is an additional limit for attributed requests, not a substitute for the member's team-wide fairness allowance.** Starting another launch or using a legacy client cannot reset member/group/pool spend. Use those hard shared policies to protect against a member consuming the team's allocation.

## Accounting, limits and guidance

Administrators can set a launch's lifetime credit cap, concurrent request cap and admission pause in its detail page or policy API. Zero adds no extra cap; it never grants more member/pool capacity. Valid credit caps are 0–100,000,000,000, and concurrency caps are 0–32. An administrator can lower a cap below already charged usage; this blocks later admissions without rewriting history.

Run admission, current policy/authentication checks, queue-turn consumption, reservation creation and launch accounting share the team transaction. Every reservation snapshots its optional run ID. Atomic counters track admitted attempts, streams completed with terminal usage, and charged/reserved credits. Native retry/fallback attempts can create several reservations for one client request; they are counted as admissions, not distinct user turns. Actual usage adjusts the run balance only on the first settlement; missing usage retains the reservation, and duplicate settlement cannot refund it. Lifetime totals survive midnight and deletion of resolved detailed reservation rows after their normal retention period. [Operator-attested reconciliation](usage-reconciliation.md) adjusts an unknown charge and its original run total atomically without inventing a completed stream; unresolved rows remain reviewable past normal detail retention.

`completed` counts provider streams with valid terminal usage observed by the gateway, including a native client closing immediately after its final SSE event before HTTP EOF. It is not a delivery/read acknowledgement. Cancellation still propagates and never replays committed output; cancellation without verified terminal usage keeps the conservative charge and does not increment completion. Historical counters from older binaries are not reconstructed.

Run concurrency waits in the existing bounded queue, yielding while blocked. Budget exhaustion (`run_allowance`), pause (`run_paused`), closure (`run_ended`) and credential mismatch are hard refusals. Account/model/provider fallback cannot bypass an attached run's constraints. Observation-only mode disables predictive throttling/delivery while preserving every run limit.

One wrapper can have several native sessions and conversations. TeamClaude's session tracker distinguishes the session header from individual conversation openings so subagents do not all pin to one account. TeamCode keeps that existing distinction: stored SHA-256 session/conversation keys link the launch to the latest requested/resolved model/provider. Up to 128 conversations are retained per launch; overflow is explicitly marked, while accounting continues. No first-message bytes or raw session identifiers enter this registry.

The controller projects recent open, credential-valid runs onto their native-session directives. Caps, remaining run budget and pauses constrain advice alongside member/group/pool limits. Changing run policy/bindings invalidates old recovery context; each billed token does not. Shared native session IDs combine recent associated launch limits conservatively. Incomplete bounded metadata produces `UNKNOWN` guidance instead of blind acceleration. Admission remains authoritative. Controller reads are bounded to 1,000 recent launches and 4,096 distinct bindings per member, with explicit incomplete-state handling.

Detail views show current native-session guidance and matching bridge/hook emission timestamps. They do not claim that the model read or obeyed the message. Ended/revoked launches cannot inherit a later directive through a reused native session ID. Observation-mode advice is labeled informational and automatic delivery remains suppressed.

## API and permissions

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/api/v1/runs` | Interactive login or `inference` key; body `{"id":"UUID","agent":"claude","workload":"background","labels":{"task":"1234"}}` (`labels` optional) |
| POST | `/api/v1/runs/{id}/heartbeat` | Creating credential with inference authority |
| POST | `/api/v1/runs/{id}/end` | Creating credential; body `{"exitCode":0}` or `{"exitCode":null}` |
| GET | `/api/v1/runs` | Own records with interactive login or `usage:read`; `?all=true` and `?member=` require an interactive administrator or a `team:read` key |
| GET | `/api/v1/runs/{id}` | Own detail with `usage:read`; another member requires an interactive administrator or a `team:read` key |
| PUT | `/api/v1/runs/{id}/policy` | Interactive owner/admin; `{"budgetUnits":100000,"maxConcurrent":2,"paused":false}` |

Lifecycle replies contain only UUID, timestamps and state. Inference-only keys do not gain usage-read access through heartbeat/end responses. List filters are `member=UUID`, `all=true`, `before=UUID` and repeatable `label=key=value`; cross-member/all-team reads require an interactive administrator or an administrator's `team:read` key; a `usage:read` key stays limited to its owner, including an administrator's. Group management authority grants no access to another member's launch registry. Pages contain at most 50 records ordered by immutable start time/UUID, with `nextBeforeId`; concurrent appends do not shift older pages. A deleted/inaccessible cursor requires refreshing the list.

The typed client exposes `startRun`, `heartbeatRun`, `endRun`, `runs`, `run` and `saveRunPolicy`. Its narrow `RunLifecycleClient` interface lets a wrapper own heartbeat/cancellation behavior without depending on server modules. Browser forms validate the exact origin and CSRF token; all metadata is escaped and responses are private/no-store. Successful lifecycle/policy events are audited as `run.started`, `run.ended` and `run.policy_updated`; heartbeats do not flood the journal.

Retained registry bounds are 10,000 launches per member and 100,000 per team. Maintenance removes records only after 90 days without heartbeat/request evidence, with old/absent end evidence and no remaining reservation/waiter references. Run details/foreign keys are additive; published migrations are preserved. A rollback to older binaries does not preserve run policy enforcement or counters, so it is not equivalent behavior.

## Verification

`AgentRunPersistenceTest` exercises independent MySQL/Hikari pools, credential/scoped permissions, lifecycle replay, stale/revoked observations, concurrent limits, paused queued work, exact/uncertain settlement, retained totals after detail retention, stable pagination, subagent fan-out, bounded metadata and current delivery attribution. `RunRulesTest` covers hard constraints and pacing/readiness semantics. `AgentRunRoutesTest` checks typed SDK operations, narrow receipts, escaping, origin/CSRF and scope boundaries. `AgentRunHttpTest` uses real HTTP replicas and a loopback SSE provider to verify attribution, exact usage, cross-replica policies and removal of team headers before transport.

The actual database dump/restore and packaged two-process smoke include run lifecycle, policies and preserved accounting. `RunLifecycleTest` runs shared wrapper/cancellation logic on JVM/native tests. Explicit local native acceptance is:

```sh
./gradlew :app:smokeAgentRuns
# Another already-built native target:
./gradlew :app:smokeAgentRuns -PteamcodeCliBinary=/absolute/path/to/teamcode
```

The default task builds the macOS ARM64 release executable. Its owned MySQL/HTTP fixture launches synthetic Claude/Codex executables, makes exactly two loopback inference requests, observes a real quiet heartbeat, preserves arguments and nonzero exit status, and verifies `sessions list/show` JSON across replicas. It never contacts a real provider or uses personal configuration. This local task is not an ordinary CI dependency; paid platform validation remains batched for releases/manual full runs. Separate opt-in `acceptance-provider.py --launcher --claude PATH --codex PATH --codex-model MODEL` now checks actual vendor launches with guidance disabled, native conversation attribution, settled run/team counters and `sessions show` JSON. Actual macOS Claude/Haiku and Codex/Luna evidence, including the Codex terminal-close counter correction, is recorded in [status](status.md). Broader platform/hooks/fan-out and workload/performance acceptance remain open. Chart deployability is a release requirement; operators deploy it on their own infrastructure.
