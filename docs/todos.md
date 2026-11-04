# Session todo and the Stop hook

Design: the artifact "La todo de session et le Stop hook" (4 November 2026). Each part below says what is implemented and what is planned.

A session keeps one todo: a free-text `reference` (usually a link to the plan) and items, each with a complete description and a typed end condition. An item is done only when its checks pass, never because the agent says so. Items are added, never deleted without a person's agreement.

## What is implemented

| Part | Status |
| --- | --- |
| Domain model and rules (`domain/.../todos`) | implemented |
| Storage (`session_todos`, `session_todo_items`), routes `/api/v1/todos/session/…`, `todo:session` scope, capability `todos.v1`, typed client | implemented |
| `teamcode_todo` MCP server (`teamcode mcp todo`), its relay, the checks run from the agent's environment, links, `todo_help` | implemented; real-session and sample-plan trials: see the pull request |
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
| `POST /items/{item}/link` | Binds a PR, a release, an artifact or a wait to an item; a link is frozen (`409 already_linked`), except to replace a PR closed without merge |
| `POST /items/{item}/drop` | Opens a wait for the owner; the item is `drop_requested` until they answer |

Who may call: the session's owner (naming `sessionId`) reads and writes; an administrator, or a `team:read` key, reads; the session's execution credential (`tcex_`) carries the new scope `todo:session` and is confined to the one session it was issued for, whatever `sessionId` says, exactly like `waits:session` and `workflow:session`. The remote-host credential carries it too. A service key that carries `todo:session` (it is not offered when creating one), and an execution credential issued before the scope existed, get `403 credential_scope`.

The checks are stored with the item and cannot be changed; `proof` only reports each check's outcome by position. The server has no GitHub reader (see `docs/waits.md`): the process that runs a check, in the agent's environment, reports what it saw, so a caller holding the credential could report a false pass. The Stop hook (planned) re-runs the checks itself, outside the agent's context, for the same reason.

A drop request opens an ordinary wait for the session's owner with the actions `yes` and `no` (`WaitBackedDropWaits`). The todo reads the wait on the next `GET` or change, in one read for all open drop requests of the list: `yes` drops the item, `no` or a cancelled wait puts it back to `todo`. Answering wakes the session with the usual wait message.

### Cost

A read is two queries (the list row, then its items in order) plus one on `waits` by primary key only while a drop request is open; a change adds the same reads under the row lock and writes only the rows that changed. No query runs per item. `EXPLAIN ANALYZE` on MySQL 8.4 with 100 000 lists and 900 000 items (and 300 000 waits) is in the pull request.

## The `teamcode_todo` MCP server

`teamcode mcp todo --session <id>` is a stdio server the session runner (pods and remote hosts, Claude Code and Codex) starts beside the agent, like `teamcode_waits` and `teamcode_workflow`. It reaches the server through the launcher's loopback relay (capability `todo-relay-v1`), which allows only the todo routes above and holds the credential; the agent never holds it. It is connected when the server advertises `todos.v1` and the execution's credential may read the session's todo (an execution credential from before `todo:session` gets `403 credential_scope` on that read, and then the agent is simply not offered the tools). A local `teamcode run` has no session and gets none (planned: `local-run`). The agent's instructions gain one sentence (`TodoDocs.PROMPT`) that says to call `todo_create` before a multi-step task and that a turn cannot end while items remain unless a wait is open.

| Tool | What it does |
| --- | --- |
| `todo_help` | The rules, the schema of an item and three examples (a PR, a release, a research ending on an artifact). Local, no call |
| `todo_create` | Creates the list once: `reference`, optional `refs`, `items` |
| `todo_show` | The list as text: reference first, next item in full, one line per item |
| `todo_start` | Puts an item in progress; returns the marker to put in its PR body |
| `todo_complete` | Runs the item's stored checks and asks the server to decide; refusals say what is missing |
| `todo_link` | Binds an existing PR, release, artifact or wait; the kind of a GitHub address is deduced |
| `todo_add`, `todo_note`, `todo_drop` | Add an item with a reason, append a note, ask the owner to drop an item |

The server instructions (field `instructions` of the MCP handshake) and `todo_help` are `TodoDocs`; the refusals of the server carry a code and the tool adds the action to take (`not_done`, `todo_exists`, `already_linked`…). The typed client carries only the server's error code, so `todo_complete` reads the proof the server stored for a refusal and shows that.

### Where each check runs

| Check | Run by | How |
| --- | --- | --- |
| `pr_merged`, `pr_closed` | the MCP process, with `gh` | the PR linked to the item, else the PR of the repo whose body carries the item's marker (`gh pr list --search "<list prefix> <item id> in:body"`, then the exact marker decides) and whose author is the token's login; found PRs are bound to the item |
| `release_contains` | the MCP process, with `gh` | the earliest published, non-draft release (the 30 newest are listed, at most 15 compared) whose tag is ahead of or at the merge commit of the item named by `of` (or `commit`); it is bound to the item with its tag |
| `ci_green` | the MCP process, with `gh run list --commit` | every run on the commit completed and green; none yet, running or failed is "not done" |
| `command` | the MCP process, in a shell (`sh -c`, `cmd /c` on Windows) | the stored command, with `${pr:<item>.number}`, `${pr:<item>.mergeSha}`, `${release:<item>.tag}` replaced from the links; a value not linked yet leaves the check unknown and nothing runs; output is bounded to 300 characters and masked |
| `artifact_published`, `wait_answered` | the server | through the item's `artifact` and `wait` links; what the caller reports is ignored |
| `attested` | nobody | the agent gives `proof.text` and https URLs; the item is flagged `unverified` |

The condition is read from the server's stored item, never from the agent, so the agent chooses neither the command nor the moment. A check that does not answer (`gh` down, a timeout) is reported as unknown and never counts as done. A PR is found by GitHub's search index, which can lag a few minutes behind a new PR: the check then says no PR carries the marker, and a retry finds it. `gh` and the token are those of the agent's environment (`GH_TOKEN` in a pod).

### What is not verified yet

The agent's process runs the checks and reports their outcome, so an agent that bypasses the MCP and calls the API with its credential could report a false pass. The server has no GitHub reader; the Stop hook (planned) re-runs the cheap checks itself, outside the agent's context.
