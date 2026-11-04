# Session todo and the Stop hook

Design: the artifact "La todo de session et le Stop hook" (4 November 2026). Each part below says what is implemented and what is planned.

A session keeps one todo: a free-text `reference` (usually a link to the plan) and items, each with a complete description and a typed end condition. An item is done only when its checks pass, never because the agent says so. Items are added, never deleted without a person's agreement.

## What is implemented

| Part | Status |
| --- | --- |
| Domain model and rules (`domain/.../todos`) | implemented |
| Storage (`session_todos`, `session_todo_items`), routes `/api/v1/todos/session/…`, `todo:session` scope, capability `todos.v1`, typed client | implemented |
| `teamcode_todo` MCP server and checks run on GitHub | planned |
| `teamcode cloud todo-stop` Stop hook | planned |
| Reference at wake-up and after compaction | planned |
| Server-side reminder (Codex, safety net) | planned |
| Companion Todo view | planned |
| Session for a local `teamcode run` | planned |

### Domain rules (`TodoRules`)

- One list per session, active at once (`create`, `requireNoList`). At most 50 items; ids are slugs of at most 40 characters; a description is 80 to 4 000 characters; a list refuses unknown or circular dependencies, an item without a typed check, and a guessed PR number or version (`guessed_number`).
- Check types: `pr_merged`, `pr_closed`, `release_contains`, `ci_green`, `command`, `artifact_published`, `wait_answered`, `attested`. A check never names a PR number: the PR is found by the marker `TodoRules.marker` returns.
- `next`: the first `todo` item whose dependencies are done. A dropped dependency counts as settled, so a dropped item never stalls the list.
- `start`: one item `doing` at a time, except an item given to a background agent.
- `add`: marked `discovered` with its reason, placed after the item in progress and after its own dependencies.
- Name, description and conditions are frozen; `note` only appends. `requestDrop` puts an item in `drop_requested`; only `resolveDrop` with the owner's yes drops it. Nothing is deleted.
- `complete`: done only when every check passed; a failed or unknown check (unreachable, not linked yet) refuses, and the proof of what was seen is kept.
- `settle`: once every item is done or dropped, a final verification runs; an item whose check now fails is reopened, otherwise the list is done.
- `stop`: the stall counter. A blocked stop with no item finished, no item added and no note since the previous blocked stop adds one; at 3 (`STALL_LIMIT`) the hook asks the owner.

## Storage, routes and credentials

Two tables, both ending with their session (cascade): `session_todos` (one row per session, its primary key is the session id; it is also the lock every change takes) and `session_todo_items` (primary key `(session_id, id)`, ordered by `position`, JSON columns for the condition, dependencies, proof and notes). The notes live in the item's JSON rather than a table of their own: they are bounded (100 per item, 2 000 characters each) and always read with the item. Nothing deletes an item.

Every route lives under `/api/v1/todos/session` and is advertised by the capability `todos.v1`:

| Route | What it does |
| --- | --- |
| `GET ?sessionId=` | The list (or `todo: null`), with `nextItemId`. Answers to drop requests are applied on this read |
| `POST` | Creates the list, once per session (`409 todo_exists`) |
| `POST /items` | Adds a `discovered` item with its reason |
| `POST /items/{item}/start` | Puts an item in progress and returns the marker for its PR |
| `POST /items/{item}/notes` | Appends a dated note |
| `POST /items/{item}/proof` | Reports what each check observed; done only if all passed (`409 not_done` keeps the proof) |
| `POST /items/{item}/drop` | Opens a wait for the owner; the item is `drop_requested` until they answer |

Who may call: the session's owner (naming `sessionId`) reads and writes; an administrator, or a `team:read` key, reads; the session's execution credential (`tcex_`) carries the new scope `todo:session` and is confined to the one session it was issued for, whatever `sessionId` says, exactly like `waits:session` and `workflow:session`. The remote-host credential carries it too. A service key that carries `todo:session` (it is not offered when creating one), and an execution credential issued before the scope existed, get `403 credential_scope`.

The checks are stored with the item and cannot be changed; `proof` only reports each check's outcome by position. The server has no GitHub reader (see `docs/waits.md`): the process that runs a check, in the agent's environment, reports what it saw, so a caller holding the credential could report a false pass. The Stop hook (planned) re-runs the checks itself, outside the agent's context, for the same reason.

A drop request opens an ordinary wait for the session's owner with the actions `yes` and `no` (`WaitBackedDropWaits`). The todo reads the wait on the next `GET` or change, in one read for all open drop requests of the list: `yes` drops the item, `no` or a cancelled wait puts it back to `todo`. Answering wakes the session with the usual wait message.

### Cost

A read is two queries (the list row, then its items in order) plus one on `waits` by primary key only while a drop request is open; a change adds the same reads under the row lock and writes only the rows that changed. No query runs per item. `EXPLAIN ANALYZE` on MySQL 8.4 with 100 000 lists and 900 000 items (and 300 000 waits) is in the pull request.
