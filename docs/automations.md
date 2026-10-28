# Automations

An automation starts a [cloud session](cloud-sessions.md) by itself: on a schedule (`cron`) or when an HTTP request reaches its own URL (`webhook`), with a predefined profile and agent and a prompt whose variables come from the run or from the request body. Every trigger is a **run**, kept in the automation's history with its status and the session it created. An automation is a member's own feature, like sessions and artifacts: it runs **as its owner**, with the owner's quota, accounts, model policy, MCP servers, skills and GitHub link. The scheduler and the webhook receiver are part of the server: no external scheduler is involved. Servers that serve automations advertise `automations.v1`.

| Route | Purpose |
| --- | --- |
| `GET /api/v1/automations` | The caller's automations; `?owner=all` or `?owner=<member>` for an administrator. Newest first, `?before=<id>&limit=` (1–100, 50) |
| `POST /api/v1/automations` | Create (`{automation}`); created **disabled**, `201` with the webhook secret once |
| `GET/PUT/DELETE /api/v1/automations/{id}` | Read; change (`{version, automation}`, `409 stale_version`); delete with its history |
| `POST /api/v1/automations/{id}/enable`, `/disable` | Arm (owner only), pause (owner or administrator) |
| `POST /api/v1/automations/{id}/webhook/rotate-secret` | A new secret, shown once; the former one is accepted 10 more minutes |
| `POST /api/v1/automations/{id}/run` | A test: `{payload?, dryRun?}` (see [Testing](#testing)) |
| `POST /api/v1/automations/preview` | Check and render an unsaved definition: `{automation, payload?}` |
| `GET /api/v1/automations/{id}/runs`, `…/runs/{runId}` | History (newest first, `?before=&limit=&source=&status=`); one run |
| `POST /api/v1/automations/{id}/runs/{runId}/cancel` | Withdraw a waiting run, or pause the session of a working one |
| `POST /api/v1/automations/{id}/trigger` | The webhook (no login: the signature or the secret URL is the proof) |

The typed client has the same operations (`automations`, `createAutomation`, `runAutomation`, `previewAutomationRun`, `automationRuns`, …); the DTOs are in `api/…/models/Automations.kt`.

## Definition

```json
{
  "name": "Evening PR summary",
  "trigger": {"kind": "cron", "cron": "0 21 * * *", "timezone": "Europe/Paris", "catchup": "once", "catchupGraceMinutes": 360},
  "action": {"kind": "cloud", "profileId": "…", "model": "sonnet", "effort": "medium", "maxRuntimeMinutes": 20},
  "prompt": "List the pull requests merged on {{run.date}} ({{run.timezone}}) and post a ten-line summary.",
  "variables": [],
  "overlap": "skip",
  "maxRunsPerDay": 24
}
```

| Field | Rules |
| --- | --- |
| `name` | 1–120 characters, no control character; unique per owner |
| `trigger.kind` | `cron` or `webhook` |
| `action.kind` | `cloud`. `remote` is part of the contract but refused with `422 not_available_yet` (see [Decision](#decision)) |
| `action.profileId` | A profile assigned to the owner (`403 profile_unassigned` otherwise); a run uses its **latest** version and records it |
| `action.model`, `action.effort` | Optional, as for a session; checked against the owner's model policy at every inference |
| `action.maxRuntimeMinutes` | 1–1440 (60): a run still working after that long ends `timed_out` and its session is paused |
| `prompt` | 1–32,000 characters; every `{{…}}` must name a declared or a run variable (`400 invalid_automation` at save, not at run) |
| `variables` | At most 32 payload variables (webhook only), see [Variables](#variables) |
| `samplePayload` | Webhook only: a JSON object (64 KiB) validated against the variables at save; it pre-fills a test and no real run uses it |
| `overlap` | `skip` (default): a trigger while a run of the automation is waiting or working is recorded `skipped_overlap`; `queue`: one run waits at most, the next ones are skipped; `allow` |
| `maxRunsPerDay` | 1–1000 (24): cron and webhook runs started over any 24 hours; beyond, `skipped_budget` (and `429` for a webhook). Tests are not counted |

`version` starts at 1 and grows with every saved `PUT` of the definition. Enabling, pausing and rotating the secret do not change it, so a [versioned webhook URL](#versions) survives a pause. A member owns at most `maxAutomationsPerMember` automations (team cloud setting, 20 by default, `409 automation_limit`).

## Cron

Five fields: minute, hour, day of month, month, day of week (`0`–`7`, Sunday being `0` or `7`), with `*`, lists, ranges, steps (`*/15`, `1-5/2`) and the names `JAN`–`DEC` and `SUN`–`SAT`. When both the day of month and the day of week are restricted, either matches (the classic cron rule). Two consecutive slots must be at least **5 minutes** apart (`400 invalid_automation`). The `timezone` is a required IANA name (`Europe/Paris`); the server's clock zone is never used.

- **Daylight saving.** A slot inside the spring gap runs at the first valid time after it (02:30 on the night clocks jump from 02:00 to 03:00 runs at 03:30). A local time repeated in autumn runs once, at its first occurrence.
- **Catch-up after an outage.** When the scheduler finds the latest due slot more than two minutes late: `once` (default) runs it once if it is at most `catchupGraceMinutes` old (0–1440, 360), never a burst of every missed slot; `skip` runs nothing. In both cases the next slot is then computed from now. A missed slot older than the grace is never run: a 21:00 summary missed during a 40-minute restart runs at 21:40, the evening one is not run at noon the next day.
- **Enabling** computes the next slot from now: nothing missed while paused is caught up.

The view gives `nextRunAt` and the three `upcomingRuns` of an enabled cron automation; `POST /preview` gives `nextRuns` for a draft.

## Webhook

A webhook automation receives `POST` requests with a JSON object body of at most 64 KiB. It accepts two proofs, both from the one secret `whsec_…` shown at creation and at each rotation; the person copying the URL chooses one for their sender.

- **Signed** (preferred): `POST https://<server>/api/v1/automations/<id>/trigger` with the headers of the [outgoing webhooks](webhooks.md#what-is-sent): `TeamCode-Timestamp` (Unix seconds) and `TeamCode-Signature: v1=<hex>`, the HMAC-SHA256 of `<timestamp>.<raw body>` keyed with the whole secret, at most five minutes old. The body is integrity-protected and a capture cannot be replayed later.
- **Secret URL**, for a sender that cannot sign: `POST https://<server>/api/v1/automations/<id>/trigger/<secret>`. Anyone who reads the URL (a proxy log, a shared screenshot) can trigger the automation: treat the whole URL as the secret.

An unknown automation, a disabled one's wrong proof, a bad signature, a stale timestamp and a wrong secret all answer the same `401 invalid_signature`, so the answer does not tell which automations exist. A valid request answers `202 {runId, status}` at once, without waiting for the session; the status can already be `skipped_overlap`. A paused automation answers `409 automation_disabled` (after the proof only).

### Versions

`…/<id>/v/<n>/trigger` (and `…/v/<n>/trigger/<secret>`) runs only while the automation is at version `n`; after any change of its definition it answers `409 automation_version_changed` and records nothing, so a sender set up for the definition it saw (prompt, variables) is never run against a different one. `…/<id>/trigger` and `…/v/latest/trigger` follow the current version. The run records the version it ran. See the [Decision](#decision) for why this is the automation's version and not the profile's.

### Rotation

`POST …/webhook/rotate-secret` returns a new secret once. The former secret stays valid for **10 minutes** (`webhook.previousSecretValidUntil`), the time to update the sender; a second rotation within that window ends the first one's grace. The secret is stored sealed with the server's encryption key (context `automation:<id>`) and never returned again; a service key can neither rotate nor read it.

### Deduplication and limits

A run is identified by its delivery key: the sender's `Idempotency-Key` or `TeamCode-Delivery` header (1–128 characters of `A-Za-z0-9._:-`), or, without either, the SHA-256 of the raw body within the same 60-second window. A request with a known key answers the same `202` with `Idempotency-Replayed: true` and starts nothing, for as long as the run is in the history (14 days). Per automation, at most 30 requests a minute are accepted (`429 automation_rate_limit` with `Retry-After`), then `maxRunsPerDay` applies (`429` and a `skipped_budget` run). A body that is not a JSON object, or whose variables do not validate, answers `422 invalid_payload` naming the paths and rules (never the values) and records an `invalid_payload` run.

## Variables

```text
Prepare the fix of support ticket {{ticket.number}} (priority {{ticket.priority}}).
Run {{run.id}} on {{run.date}} at {{run.time}} ({{run.timezone}}).
```

- **Run variables**, computed by the server for every run, tests included: `run.id`, `run.date` (`YYYY-MM-DD`) and `run.time` (`HH:mm`) of the slot (cron) or of the reception, in the automation's timezone (UTC for a webhook), `run.timezone`, `run.source` (`cron`, `webhook`, `test`), `automation.id`, `automation.name`.
- **Payload variables** (webhook only) are declared, never inferred: `name` (letters, digits, `_`, `-`, dot-separated, not starting with `run.` or `automation.`), `path` (`$`, then `.key`, `['key with spaces']` or `[index]` segments, at most 16), `type` (`string`, `int` (64-bit), `number`, `boolean`, `enum` with its `values`), `maxLength` (1–4096, 256: the rendered text), `required` (true) and `sensitive` (false). A string variable takes a JSON string, number or boolean as its text; an object or array never validates.
- **Validation** happens before any session: a missing required value, a wrong type, a value too long or outside the enum make the run `invalid_payload` with the problem list `[{path, rule}]` (`required`, `type:int`, `max_length`, `enum`, `not_scalar`). An optional missing value renders as nothing.
- **Rendering** is plain substitution: `{{ name }}` (spaces allowed) becomes the value's text, nothing else in the prompt is interpreted, and a value is never re-scanned for `{{`. The payload is untrusted input: the owner chooses what enters the prompt and which tools the agent has. TeamCode imposes no other filter.

## Testing

`POST /api/v1/automations/{id}/run` tests the **saved** definition (same version, profile and rules as a real run), enabled or not.

| | `/trigger` (real) | `/run` (test) |
| --- | --- | --- |
| Proof | Signature or secret URL | The owner signed in, or the owner's `cloud:write` key |
| Payload | The request body | `payload` (webhook only, required: `422 payload_required`; refused on cron: `422 payload_not_accepted`) |
| Size, JSON, variables, rendering | 64 KiB, the same validation and rendering function | identical, same error codes |
| Overlap, runtime cap, running cap | yes | yes |
| `maxRunsPerDay`, consecutive failures, next slot, catch-up | yes | **not counted** |
| Quota, account, MCP, GitHub | the owner's | the owner's: a test is a real run, with real tokens |
| Session labels | `automation=<id>` | `automation=<id>`, `automation-test=1` |

- **Real test** (`dryRun: false`): `Idempotency-Key` required, as for `POST /cloud/sessions` (the same key replays the same run, `Idempotency-Replayed: true`, for as long as the run is in the history; the same key with another payload is `422 idempotency_conflict`). Answers `202` with the run (`source: "test"`). There is no separate test budget.
- **Dry run** (`dryRun: true`): validates the payload, renders the prompt and resolves the profile, then answers `200 {renderedPrompt, variables, problems, profileVersion, wouldSkip, nextRuns}`: no run, no session, no quota. `variables` lists each value with its type, sensitive ones masked; `wouldSkip` is `overlap`, `budget` or `disabled` when a real trigger would not start now. The rendered prompt is returned to the caller only: it is not stored, logged or audited.
- `POST /api/v1/automations/preview` does the same for an unsaved draft (the editor's preview), without the overlap and budget checks.
- A signed request on `/trigger` is never a test, whatever it carries.

## Execution

A run goes `queued`, then `starting` once its session exists, `running` once the agent works on the prompt, and ends:

| Status | Meaning |
| --- | --- |
| `succeeded` | The agent consumed the prompt and the turn that took it ended `completed`; the transcript in the session says what it did |
| `failed` | The session could not be created or ended before the turn (`reason`: `owner_unavailable`, `profile_unassigned`, `profile_not_found`, `cloud_session_limit`, `session_failed`, `session_deleted`, `message_rejected`, `turn_failed`, `turn_interrupted`, …) |
| `timed_out` | Still working after `maxRuntimeMinutes`; its session is paused |
| `cancelled` | Cancelled while waiting, or its session paused on request |
| `expired` | Waited 30 minutes for capacity (`reason: capacity_timeout`) |
| `skipped_overlap`, `skipped_budget` | Not started, see `overlap` and `maxRunsPerDay` |
| `invalid_payload` | The payload did not validate; no session |

- **Session.** A run creates one cloud session owned by the automation's owner, named after the automation, with the rendered prompt as its initial message and the labels `automation=<id>` (and `automation-test=1`): `teamcode cloud list --label automation=<id>` finds them. The creation uses the `Idempotency-Key` `automation-run:<runId>`, so a replica that crashes between the creation and its record never creates a second session for the same run. Idle sessions pause on their profile's idle timeout as usual; after a run ends, the automation keeps the sessions of its 10 latest runs and deletes older ones (their transcripts follow the team's transcript retention).
- **Identity.** The owner's rights are rechecked at each run. Creating or enabling an automation records a credential bound to the owner (never shown, not listed among service keys, scope `cloud:write`); it is revoked like the owner's service keys when the owner is disabled, changes role or password. A run that finds it revoked fails with `owner_unavailable` and pauses the automation (`disabledReason: owner_unavailable`); the owner enables it again. Enabling is the owner's consent to lend their GitHub link to the agent: the session has `githubAccess: owner`, as one the owner creates.
- **Capacity.** Sessions of automations do not count toward the owner's `maxRunningSessionsPerMember` nor `maxRetainedSessionsPerMember`: they have their own team cloud setting, `maxRunningAutomationsPerMember` (2 by default), the number of one member's automation runs that may be starting or working at once. A run over it stays `queued` (up to 30 minutes, then `expired`). The cluster's `maxRunningPods` and the FIFO order of [capacity](cloud-sessions.md) still apply to their pods. A test counts like any run.
- **Cancel.** A queued run ends `cancelled` at once; a starting or running one is marked `cancelRequested`, then the worker pauses its session and records `cancelled`.

### History

`GET …/runs` lists runs newest first (cursor `before`, at most 100 a page; filters `source` and `status`, repeatable). Each run has its version, source, status, reason, session, profile version, slot and times; the detail adds the extracted variables (sensitive ones masked). The view's `consecutiveFailures` counts `failed`, `timed_out`, `expired` and `invalid_payload` cron and webhook runs since the last `succeeded` one; tests never change it. Runs are kept **14 days** after they end.

## Permissions

| Who | Can |
| --- | --- |
| A member | Create, change, enable, pause, test (real or dry run), cancel and delete their own automations; read their history |
| An administrator | Everything above on their own; on the team's (`?owner=all`): read, history, dry run, pause, cancel a run, delete. Never enable, change or run a real test of another member's automation: it would spend their quota with their GitHub link |
| A `cloud:read` key | Read the owner's automations and history, and dry-run them (`/run` with `dryRun: true`, `/preview`) |
| A `cloud:write` key | Also really test them (`/run`) and cancel runs. Every other write, and every secret, stays interactive |
| A webhook sender | Trigger its automation only; it reads nothing |

## Audit

`automation.created`, `.updated`, `.enabled`, `.disabled`, `.deleted`, `.secret_rotated`, `.test_run`, `.test_previewed`, `.run_cancelled` (actor: the member; credential recorded), and `automation.run_started` and `automation.owner_unavailable` (actor `system`, subject `<automationId>/<runId>` or the automation). No prompt, payload, variable value or secret is ever in the journal. The run keeps a SHA-256 of its variables (`variables_digest`) and its version.

## Storage

Migrations `V20261028*`: `automations` (one row per automation, its secret sealed in the `automation:<id>` context, its previous secret and grace, the owner credential's id, `next_run_at` and an index `(enabled, trigger_kind, next_run_at)` for the scheduler), `automation_runs` (unique `(automation_id, delivery_key)`; indexes `(automation_id, queued_at, id)` for the history, the minute rate and the daily budget, `(automation_id, status)` for the overlap policy, `(status, next_attempt_at)` for the dispatcher, `(owner_id, status)` for the running cap and `(ended_at)` for retention), and a nullable `cloud_sessions.automation_id`. The rendered prompt of a waiting run is kept in its row until its session is created (it then lives in the session's messages, like any message), and cleared at once on any other end.

Workers, one replica at a time per step through the scheduled-work table: the scheduler (every 15 s) reads due automations by `next_run_at` and, for each, moves `next_run_at` only if it is still the slot it read, inserting the slot's run in the same transaction, so two replicas never record a slot twice; the dispatcher (every 2 s, only while cloud sessions are on: otherwise runs wait and expire after 30 minutes) claims queued runs under a one-minute lease, creates their sessions outside any transaction and follows working runs; maintenance (every minute) purges old runs. A crashed dispatcher's lease expires in a minute; the unique delivery key and the session's idempotency key make a retried step a no-op. A run ends when the first turn that ended after its prompt was consumed is recorded in the session's transcript (`turn` event), so a session that is ready but has not started the turn yet is never taken for a finished one.

## Decision

Decided by the owner (28/10/2026), recorded here with the choices this implementation had to make.

- **Identity: the owner.** An automation runs as its owner (quota, accounts, MCP, skills, GitHub). No team bot member. The owner credential is a server-held token row rather than a stored login, so the existing revocation rules (disable, role, password) stop automations without new code paths.
- **No remote action yet.** `action.kind: remote` is in the contract so clients can show it disabled; the server refuses it with `422 not_available_yet`, because a remote session cannot start without its device's daemon online and nothing would queue it.
- **Webhook proof: signature and secret URL, one secret.** Both proofs use the same `whsec_` secret, so there is one value to show once, copy and rotate; the user picks the form when copying the URL. A second, URL-only token would add a value to manage without making either mode stronger. Rotation keeps the former secret valid 10 minutes, unlike outgoing webhooks, because here TeamCode is the receiver and cannot re-sign what the sender already sends.
- **Version: the automation's definition version.** The profile already follows its latest version (recorded per run); what a sender depends on is the automation's prompt and variables. The simplest exact meaning of `/v/<n>` is therefore a guard on the automation's `version`: the request runs only while the definition is the one at `n`, and is refused otherwise. Old definitions are not kept and re-run: that would make every saved version immutable, and an emitter never needs a superseded prompt. `latest` (or no `/v/`) follows the current definition. Enabling and pausing do not bump the version, so a pause does not break a pinned URL.
- **Catch-up: `once`** by default (six hours of grace), never a burst.
- **Own concurrency cap.** `maxRunningAutomationsPerMember` (2) counts automation runs apart from the member's two interactive sessions, so an evening cron never blocks interactive work and the reverse; automation sessions are also outside the retained-session cap, bounded instead by the 10 sessions an automation keeps.
- **Tests cost what runs cost.** A real test is a real run on the owner's quota and account, with no test budget; only the production counters ignore it. No notifications and no injection filter: the owner configures the tools and the prompt.
- **Scopes.** No new service-key scope: reading and testing an automation are cloud operations (`cloud:read`, `cloud:write`), and creating, changing or arming one stays interactive because it lends the owner's GitHub link and reveals a secret.
- **Daily budget** is a rolling 24 hours rather than a calendar day, so it needs no timezone and cannot double at midnight.
