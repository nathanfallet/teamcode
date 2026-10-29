# Waits

A **wait** is what a session needs before it can go on: a person's answer, a decision, a thing only a person can set up. The agent ends its turn, the wait is stored by the server, and the answer wakes the session. Waits work with or without a [workflow](#what-is-planned).

A wait is asked of an **assignee**: the session's owner unless the asker names another member (a product question goes to the product owner while the session stays with the developer). It carries a title, a Markdown body, references to look at (artifacts, sessions, links), **fields** to fill and **actions** that end it. This is deliberately generic: a plan review is one text field and two actions (`approve`, and `request_changes` which requires the feedback); access to a third-party account is a text field and a `done` action; a questionnaire is several fields and one button.

## Status

| Piece | State |
| --- | --- |
| API contract, typed client (`waits`, `wait`, `createWait`, `answerWait`, `cancelWait`) (`api/…/models/Waits.kt`) | Implemented |
| Domain: validation of definitions and answers, access rules, first-answer-wins (`domain/…/waits`) | Implemented |
| MySQL store (`waits` table, reviewed migration), routes `GET/POST /api/v1/waits`, `GET /api/v1/waits/{id}`, `POST …/answer`, `POST …/cancel`, the `waits.v1` capability, OpenAPI reference | Implemented |
| Live updates: `cloud.wait.created` / `.resolved` / `.cancelled` on the [caller-wide feed](cloud-conversations.md#event-feed) | Implemented |
| "Waiting for you" in the companion apps, `teamcode waits` | Planned (next change) |
| The `teamcode-waits` MCP server (`ask_human`) and waking the session | Planned |
| Waits for a time or an event (`resolver` `time` / `event`) | Contract only: refused with `422 not_available_yet` |
| Secret fields | Contract only: refused with `422 not_available_yet` |
| Stop hook that requires an agent to deliver or ask; `turn_failed` | Planned |
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
| Answer | The assignee, or an interactive administrator. The first answer wins; the next gets `409 wait_closed`. An administrator answering for someone else is audited (`wait.answered`). |
| Cancel | The one who asked, the assignee, or an interactive administrator (`wait.cancelled`). |

## What is planned

The order of work: the server (store, routes, capability), then the apps (companion section, CLI), then the MCP server and the waking of sessions, then the Stop hook and `turn_failed`, then waits that resolve on their own (a time, a CI), then workflows built on the same engine. Each step updates this page.
