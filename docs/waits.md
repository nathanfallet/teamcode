# Waits

A **wait** is what a session needs before it can go on: a person's answer, a decision, a thing only a person can set up. The agent ends its turn, the wait is stored by the server, and the answer wakes the session. Waits work with or without a [workflow](#what-is-planned).

A wait is asked of an **assignee**: the session's owner unless the asker names another member (a product question goes to the product owner while the session stays with the developer). It carries a title, a Markdown body, references to look at (artifacts, sessions, links), **fields** to fill and **actions** that end it. This is deliberately generic: a plan review is one text field and two actions (`approve`, and `request_changes` which requires the feedback); access to a third-party account is a text field and a `done` action; a questionnaire is several fields and one button.

## Status

| Piece | State |
| --- | --- |
| API contract, typed client (`waits`, `wait`, `createWait`, `answerWait`, `cancelWait`) (`api/…/models/Waits.kt`) | Implemented |
| Domain: validation of definitions and answers, access rules, first-answer-wins (`domain/…/waits`) | Implemented |
| MySQL store (`waits` table, reviewed migration), routes `GET/POST /api/v1/waits`, `GET /api/v1/waits/{id}`, `POST …/answer`, `POST …/cancel`, the `waits.v1` capability, OpenAPI reference | Implemented |
| `teamcode waits list`, `show`, `answer`, `cancel`, `ask` ([CLI](#cli)); the "waits" part of the next row is the companion apps only | Implemented |
| Live updates: `cloud.wait.created` / `.resolved` / `.cancelled` on the [caller-wide feed](cloud-conversations.md#event-feed) | Implemented |
| "Waiting for you" in the companion apps ([companion](companion.md)): a section above the sessions list, updated live from the feed, answered in place (typed fields; `file` and `secret` fields are filled in the session; no cancel button) | Implemented |
| Waking the session: an answer is queued on the wait's session as a message, which resumes a paused session | Implemented (see below) |
| The `teamcode-waits` MCP server (`ask_human`, `list_waits`, `get_wait`, `cancel_wait`), connected beside the agent of a remote session and of a cloud session ([agents](#agents)); never an answer tool, and the loopback relay never relays `…/answer` | Implemented. A local `teamcode run` has no session, so it gets no wait tools. A Kubernetes cloud session gets them from its execution credential's `waits:session` scope ([execution credentials](#execution-credentials)); a session whose execution started before that scope existed keeps running without them until its next start |
| Waits for a time or an event (`resolver` `time` / `event`) | Contract only: refused with `422 not_available_yet` |
| Secret fields | Contract only: refused with `422 not_available_yet` |
| `turn_failed`: when a session's turn ends in an error, the server asks its owner to try again or dismiss ([below](#failed-turns)) | Implemented |
| Stop hook that requires an agent to deliver or ask | Planned |
| Notifications when a wait is created | Out of scope; the hook point is `WaitService.create` |

## Model

| Field | Rules |
| --- | --- |
| `kind` | `permission` and `question` are a cloud session's own requests (`cloud.permissions.v1`) seen as waits; they are answered through the session, never by `POST /waits/{id}/answer` (`409 answer_through_session`). `human_request` is asked by a member or an agent; `turn_failed` by the server. |
| `source` | `wait`: a stored row. `session`: a session's open permission request, which has no row of its own and is listed on the first page only. |
| `status` | `open`, `resolved`, `cancelled`. Final states never change. |
| `title`, `body` | 1–200 characters without control characters; body at most 32,000. |
| `refs` | At most 10: `artifact` or `session` by `id`, or `url` (`https` only). |
| `fields` | At most 32, ids unique (`[A-Za-z][A-Za-z0-9_]{0,63}`). See below. |
| `actions` | At most 8, ids unique. None given means one `done` action. `requires` names fields that must be filled for that action. |
| `answer` | The action chosen, the values by field id, the author and the time. |

| Field type | Value |
| --- | --- |
| `text` | A string, at most `maxLength` (8,000 by default, 32,000 at most) |
| `choice` | One of `options` (1–50), a list when `multiple`; `allowOther` accepts a free answer of at most 500 characters |
| `confirm` | `true` or `false` |
| `number` | A finite JSON number |
| `date` | `YYYY-MM-DD`, a real calendar date |
| `file` | The name of an uploaded file (`cloud.files.v1`) |
| `info` | No value: it shows `help` |
| `secret` | Not available yet |

An answer is refused with `400 invalid_answer` when it names an unknown action or field, gives a value of the wrong type, or leaves a `required` field or one required by its action empty. Nothing is stored for a refused answer.

## Routes

| Route | Purpose |
| --- | --- |
| `GET /api/v1/waits` | What waits for the caller; `?assignee=all` or `?assignee=<member>` for an administrator. `?status=` `open` (default), `resolved`, `cancelled` or `all`; `?before=&limit=` (1–100, 50), newest first. The first page also carries the open permission requests and questions of the assignee's cloud sessions (`source: session`, at most 100), ahead of the stored waits |
| `POST /api/v1/waits` | Ask (`{wait}`), `201`; `Idempotency-Key` returns the first wait (`200`, `Idempotency-Replayed: true`) |
| `GET /api/v1/waits/{id}` | Read; a session's permission request is read by the session's identifier |
| `POST /api/v1/waits/{id}/answer` | `{action, values}` |
| `POST /api/v1/waits/{id}/cancel` | Withdraw |

Reads need an interactive login or a key with `cloud:read` (or a wider cloud scope); writes need `cloud:write`. Errors: `invalid_wait`, `invalid_assignee`, `invalid_answer`, `invalid_cursor`, `wait_limit` (409), `wait_closed` (409), `answer_through_session` (409), `wait_session_owner_only` (403), `wait_assignee_only` (403), `not_available_yet` (422).

## Who can do what

| Operation | Who |
| --- | --- |
| Create | The session's owner (an agent acts with its owner's credential); the assignee must be an active member (`400 invalid_assignee`). An interactive login or a `cloud:write` key. At most 20 open waits per session (`409 wait_limit`). `Idempotency-Key` returns the first wait. |
| Read | The assignee, the one who asked, and an administrator (`team:read` for a key). Anyone else gets `404`. |
| List | One's own waits; `assignee=<member>` or `all` for administrators. Open ones by default, newest first, keyset pagination. |
| Answer | The assignee, or an interactive administrator. The first answer wins; the next gets `409 wait_closed`, except the same member sending the same action and values again, which gets the wait back (and the message below queued, once). An administrator answering for someone else is audited (`wait.answered`). |
| Cancel | The one who asked, the assignee, or an interactive administrator (`wait.cancelled`). |

## CLI

```sh
teamcode waits list [--all | --status open|resolved|cancelled|all] [--assignee MEMBER|all] [--before ID] [--limit N] [--json]
teamcode waits show ID [--json]
teamcode waits answer ID [--action ACTION] [--set FIELD=VALUE]... [--set-json FIELD=JSON]... [--json]
teamcode waits cancel ID [--json]
teamcode waits ask --session ID --title TEXT [--body TEXT] [--assignee MEMBER] [--ref artifact:ID|session:ID|url:https://…]... [--field ID:TYPE:LABEL[:required]]... [--option FIELD=VALUE]... [--action ID:LABEL]... [--json]
```

- The commands need the `waits.v1` capability and say so when the server does not advertise it. `--all` is `--status all`; `--assignee` is for administrators.
- `list` prints id, status, kind, title, session, age and source. A row with source `session` is a cloud session's own permission request or question: answer it in the session, not with `waits answer` (which refuses it).
- `answer` reads the wait first and converts `--set` by each field's declared type: `text`, `file` and `date` (`YYYY-MM-DD`) stay strings, `number` becomes a number, `confirm` takes `true`/`false` (or `yes`/`no`), `choice` takes one of the option values (any text when `allowOther`), and a multiple choice repeats `--set tags=a --set tags=b`. `--set-json` gives a JSON value as is. An unknown field, an unconvertible value or an unknown action is refused before anything is sent. `--action` may be omitted when the wait has exactly one action (`done` when it declares none). A wait already answered or cancelled (`409 wait_closed`) is reported as such.
- `ask` creates a `human_request` with a fresh idempotency key; `secret` fields are refused, and a `choice` field takes its options from `--option`.

## Execution credentials

A Kubernetes cloud session's agent reaches the server with the `tcex_` credential the runner holds. It carries the scope `waits:session` (besides inference, MCP, skills, hooks and `artifacts:write`) and is bound to the one session it was issued for (`cloud_inference_credentials`). With it, and nothing wider:

| Operation | Allowed |
| --- | --- |
| `GET /waits` | Always that session's waits, asked of anyone (the `assignee` and `session` parameters are ignored); no permission request of another session is added |
| `GET /waits/{id}`, `POST /waits/{id}/cancel` | Only a wait of that session, else `404` |
| `POST /waits` | Only on that session, else `404` |
| `POST /waits/{id}/answer` | Never: `403 credential_scope`. The session's owner is the default assignee and the credential is the owner's, so the scope is what keeps an agent from answering its own question |

`waits:session` means nothing without the session a credential is bound to: a service key that carries it (it is not offered when creating one) is refused with `credential_scope`, as is an execution credential issued before the scope existed. Everyone else keeps reading and writing waits with `cloud:read`/`cloud:write` as before.

`GET /waits?session=<id>` is open to anyone who may read the session's waits (its owner, an administrator, a `team:read` key): the waits of that session, asked of anyone, newest first. The server filters by session, so a tool no longer reads pages of the caller's waits to find them.

## Waking the session

An answer wakes the session it was asked on. Once the answer is stored, the server queues a message on that session, written by whoever answered, and wakes the session like any message does (a paused session resumes, a running one reads it after its turn). The message has this shape, which the agent's instructions describe:

```
[TeamCode wait answered] <title>
wait: <wait id>
action: <action label> (<action id>)
answered by: <username>
<field label>: <value>        one line per filled field, a choice also with its label, a list joined by ", "; a multi-line value is indented by two spaces
```

It is cut at 32,000 characters, the most a message holds. The message is queued with the key `wait-answer-<wait id>`, scoped to the session and the author by the conversation store, so sending it twice queues one. That is what makes a failure safe: the answer is stored first and the message queued second, in two transactions; if the second fails the caller gets an error (`5xx`), and sending the same answer again (the companion app does, on a retry) queues the message without a `wait_closed`. A session that has ended takes nothing, and the answer stays recorded.

A session whose agent holds its owner's GitHub token takes instructions from its owner only (`github_owner_only`, [cloud sessions](cloud-sessions.md)): an assignee who is not the owner gets that `403` **before** the answer is stored, so a wait never ends up answered but undeliverable. A wait cancelled by someone else does not wake the session.

## Failed turns

When a runner reports a turn that ended with an error (`phase: failed`), the server opens a `turn_failed` wait for the session's owner, in the same transaction as the runner events, so the owner sees it in "Waiting for you" without the agent having to ask. It has a `note` text field and two actions: `retry` and `dismiss`.

- One is open at a time: a new failure replaces the previous one (cancelled), and the next turn that starts cancels it, since the session went on without anyone answering.
- `retry` wakes the session with the usual [answer message](#waking-the-session), carrying the note; `dismiss` records the answer and queues nothing.
- It is created by the server (`createdById` is empty), counts toward the 20 open waits of a session (none is created beyond that) and is announced on the feed like any other wait.

## Agents

A session's agent asks with the stdio MCP server `teamcode mcp waits --session <id> [--run <id>]`, connected under the name `teamcode_waits` exactly as [`teamcode_artifacts`](artifacts.md#agents) is: Claude Code gets a stdio entry in `--mcp-config`, Codex `-c mcp_servers.teamcode_waits={command=…,args=[…],env={TEAMCODE_WAITS_URL=…,TEAMCODE_WAITS_TOKEN_VAR="TEAMCODE_LOCAL_TOKEN"},env_vars=["TEAMCODE_LOCAL_TOKEN"]}`. The server talks to the loopback relay of the proxy that launched the agent (`TEAMCODE_WAITS_URL`, `http://127.0.0.1:<port>` only) with the local token, passed by variable name, never on a command line. It acts with the proxy's credential, which is the session owner's.

| Tool | What it does |
| --- | --- |
| `ask_human` | `title` (required), `body` (Markdown), `assignee` (a member **id**: the server takes no username or GitHub login; omitted, the session's owner), `fields[]` (`id`, `type`, `label`, `help`, `required`, `default`, `options[{value,label,description}]`, `multiple`, `allowOther`, `maxLength`; types `text`, `choice`, `confirm`, `number`, `date`, `file`, `info`, `secret` being refused for now), `actions[]` (`id`, `label`, `requires[]`, `style`), `refs[]` (`artifact`/`session` by `id`, or an `https` `url`). Creates the wait for this session only, with a fresh `Idempotency-Key` per call. Returns the id and the status, and tells the agent to end its turn: the answer arrives as a message starting with `[TeamCode wait answered]` |
| `list_waits` | This session's waits asked of its owner (`status`, open by default), read from `GET /waits` (at most five pages of 100). A wait asked of another member is not listed there: the agent keeps its id |
| `get_wait` | One of this session's waits, its fields, actions and answer |
| `cancel_wait` | Withdraws one of this session's open waits |

- Arguments are checked before anything is sent (unknown properties, types, ids, counts, `https` urls), and the server's refusals (`invalid_wait`, `invalid_assignee`, `wait_limit`, `wait_closed`, `not_available_yet`, `wait_session_owner_only`, `credential_scope`) come back with what to do. A wait of another session, or a session's own permission request, is refused by `get_wait` and `cancel_wait`.
- **No answer.** The session's owner is the default assignee and the relay holds the owner's credential, so the relay is the guard: it relays only `GET /api/v1/waits`, `GET /api/v1/waits/{id}`, `POST /api/v1/waits` and `POST /api/v1/waits/{id}/cancel`, with the same checks as the artifact relay (the token compared in constant time, loopback, no `Origin`, a bounded body, `no-store`, a refused sign-in as `502 relay_sign_in_refused`). Everything else, `POST …/answer` included, is a `404` that never reaches the server. The user service announces it as `wait-relay-v1`.
- The agent is told, beside the artifact instruction (Claude Code `--append-system-prompt`, Codex `developer_instructions`, with the same exceptions): when it needs an answer, a decision, an approval or a setup only a person can give, call `ask_human` with typed fields instead of guessing, then end its turn.
- **Where.** A [remote session](cloud-sessions.md) on a member's device gets the tools when the server advertises `waits.v1` (the host runs with the member's own login). A Kubernetes cloud session gets them when the server advertises `waits.v1` and one `GET /waits?limit=1` with the execution credential succeeds, which it does for a credential issued with `waits:session` ([execution credentials](#execution-credentials)); an execution that started with an older credential is refused with `403 credential_scope`, the runner logs it and starts the agent without the tools, and the next start of the session has them. A local `teamcode run` never gets them: it has no session.

The answer comes back as a message of the session, described in [Waking the session](#waking-the-session); the CLI only names its first line (`WAIT_ANSWERED_PREFIX`).

## What is planned

The order of work: the server (store, routes, capability), then the apps (companion section, CLI), then the MCP server (done) and the waking of sessions, then the Stop hook (`turn_failed` is done), then waits that resolve on their own (a time, a CI), then workflows built on the same engine. Each step updates this page.
