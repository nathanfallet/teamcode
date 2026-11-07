# Session todo and the Stop hook

Design: the artifact "La todo de session et le Stop hook" (4 November 2026). Each part below says what is implemented and what is planned.

A session keeps one todo: a free-text `reference` (usually a link to the plan) and items, each with a complete description and a typed end condition. An item is done only when its checks pass, never because the agent says so. Items are added, never deleted without a person's agreement.

## What is implemented

| Part | Status |
| --- | --- |
| Domain model and rules (`domain/.../todos`) | implemented |
| Storage (`session_todos`, `session_todo_items`), routes `/api/v1/todos/session/…`, `todo:session` scope, capability `todos.v1`, typed client | implemented |
| `teamcode_todo` MCP server (`teamcode mcp todo`), its relay, the checks run from the agent's environment, links, `todo_help` | implemented; real-session and sample-plan trials: see the pull request |
| `teamcode cloud todo-stop` Stop hook, its routes (`/stop`, `/hook-error`), the resume message, the stall counter, orphan PRs, hook events | implemented, always blocking |
| Reference at wake-up (the wait message, then the block), after a compaction and on resume (`SessionStart` hook `teamcode cloud todo-context`), route `GET /context` | implemented |
| Server-side reminder (Codex, safety net): the engine's `todo-reminder` pass, the workflow `deliver` gate (`todo_open`) | implemented, always on |
| Companion Todo view: the panel's Agents / Todo selector, the "4 / 9" counter in the session header and the office card, `cloud.todo` live (`todos.live.v1`), two shortcuts | implemented (desktop, Android and iOS share the screen; tested on the desktop target and in common code) |
| Session for a local `teamcode run` (`kind: local`, the Stop hook that also holds the agent for an open wait, the `SessionStart` reminder; Claude Code only for the hooks) | implemented |

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
| `GET /context` | The reminder block (`text` is null without an active list); read by the `SessionStart` hook |
| `POST /stop` | One stop of the agent, asked by the Stop hook in up to three phases (see [The Stop hook](#the-stop-hook)) |
| `POST /hook-error` | Records a failure of the Stop hook on the list (`hookEvents`, the newest 20) |

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

The agent's process runs the checks and reports their outcome, so an agent that bypasses the MCP and calls the API with its credential could report a false pass (`/proof` and `/stop` take the same reports). The server has no GitHub reader; the Stop hook re-runs the cheap checks itself, from the agent's machine and outside its context, at every stop. A server-side reader remains planned.

## The Stop hook

`teamcode cloud todo-stop --relay URL --session ID [--run ID]` is a `Stop` hook (not `SubagentStop`: sub-agents do not keep the list), installed with `--settings` next to the runner's and the workflow's, with a timeout of 150 s. It exists for Claude Code sessions in a cloud pod or hosted remotely that have the `teamcode_todo` tool; Codex has no such hook (the [server-side reminder](#server-side-reminder) covers it).

It asks the server, through the launcher's loopback relay, up to three times per stop (`POST /api/v1/todos/session/stop`, phase `begin`, `verify`, `settle`), and runs on the agent's machine what the answers ask for:

1. **`begin`.** The stop passes, with nothing run, when the session has no list, the list is done or abandoned, a background task or scheduled wake-up of the session is still in the hook input (`background_tasks`, `session_crons`), or **any** wait of the session is open (one `exists` on `wait_session`). A list never blocks a session that has none: a quick question costs nothing. Otherwise the server answers `verify`.
2. **`verify`.** The hook runs the checks of the item in progress (a `command` too, at most 120 s) and of every `todo` item whose checks are all cheap (`pr_merged`, `release_contains`, `artifact_published`, `wait_answered`; never a `command` of an item not in progress), in parallel, each bounded; a PR found by its marker is linked. It reads the session's transcript for the pull requests it opened (the address a successful `gh pr create` printed; only addresses leave the hook). The server ticks what passed with its proof (the agent often finished without ticking), verifies `artifact_published` and `wait_answered` itself, adds each PR no item covers as a `discovered` item "Finish PR #n" already linked (`pr_merged`; drop it with `todo_drop` if it was closed), then counts the stop.
3. **`settle`.** When every item is done or dropped, the hook re-runs **every** check of the done items; if all hold the list becomes `done` and the stop passes, otherwise a regressed item is reopened and the stop is blocked.

The counter (`stall`, kept on the list, so it survives a restart and two replicas count once each under the list's lock) adds one for a stop with no item finished, no item added and no note since the previous blocked stop, and goes back to 0 on progress. **The hook never relaxes on `stop_hook_active`**: an agent that makes progress is sent back as many times as there are items; the bound is the counter. At 3 the server opens a wait for the owner ("Blocked: …", with the list, the agent's last message at most 600 characters and the actions `Resume`, `Drop this item`, `Stop here`), marks the stuck item `drop_requested` on that wait (so `Drop this item` uses the drop path above and `Resume` puts it back to `todo`), starts the counter again and lets the stop pass. Claude Code's own cap (8 consecutive blocks without a tool call) stays above ours.

A block's `reason` is the resume message (`TodoMessages.resume`, at most 8 000 characters): the count, the plan reference first, the next item in full with its last check, the whole list one line per item, and one instruction (do it; `ask_human` for a person, `wait_for` for a CI or a delay, `todo_drop` if it no longer makes sense).

**It fails open.** A relay that is unreachable, a server older than `todos.v1`, an answer later than 20 s, the 140 s budget exceeded or any error lets the stop pass; the failure is recorded on the list (`hook_error`, in `hookEvents`, newest 20) when the relay still answers, so a broken hook shows in the companion (planned) instead of passing unnoticed. A check that does not answer is `unknown`, never done.

**Always blocking.** There is no observation mode and no flag or environment variable; the only switch is the per-member one below, set by an administrator: a stop with open items and no wait is refused, on every runner. Ticks, linked PRs, added orphans and the final settle apply as before. Nothing but addresses, outcomes and the last message leaves the machine; prompts, tool inputs and the rest of the transcript are not read beyond the search for `gh pr create`.

### Turning the Stop hook off for a member
An administrator can turn the hook off for one member, in the dashboard (**Team members**, the checkbox "Todo Stop hook" and **Save**) or with `PUT /api/v1/members/{id}/todo-stop-hook` (`{"enabled": false}`). A member has it on until then (no row in `member_todo_settings`). The setting is the **owner of the session**'s: for a session whose owner has it off, the server answers every `begin` with `allow` (`why: hook_disabled`) before anything is run, counted or written, so no check runs on the agent's machine, and the server-side reminder does not remind it either. The todo itself is unchanged: the agent still keeps its list and its proofs, the companion still shows it, and a workflow `deliver` is still refused with `409 todo_open` while items are open. The wait-holding Stop hook of a local `teamcode run` is a different thing and is not affected. Only interactive administrators change it (a service key cannot); the cost is two primary-key reads more per stop (the session's owner, then the owner's row).

Cost per stop: reading the list, one `exists` on open waits, the ticks and counter in one transaction, one read of the artifacts and one of the waits for all items however many (no query per item), plus 1 to 5 `gh` calls from the agent's machine. `EXPLAIN ANALYZE` of the new reads (MySQL 8.4, 300 000 `waits` and 300 000 `artifacts`): the open-wait check is an index lookup on `wait_session (session_id, status)` returning one row in about 0.07 ms (`team_id` is checked on that row: session identifiers are unique), the artifact and wait batches are primary-key range scans of a few rows (about 0.02 ms).

## The reference everywhere

The plan reference must be in front of the agent whatever woke it, not only at a block:

- **A wait is answered, a delay passes or an event arrives.** The message `[TeamCode wait answered] …` ([waits](waits.md#waking-the-session)) is followed by the block of the session's todo, composed by the server in the same place (`WaitService`, through `WaitWakeContext` wired to `TodoService.wakeBlock`). The block is the list as it stands after the owner's answer: a drop that was agreed shows the item as dropped, a refused one puts it back to `todo`. No list, a finished list or a failure to read it: the message is unchanged. It is cut before the answer is.
- **After a compaction and on resume.** A `SessionStart` hook with the matcher `compact|resume` (`teamcode cloud todo-context`, 25 s, installed by `--settings` beside the Stop hook, Claude Code only) prints the same block as `additionalContext`, which Claude Code injects. At `startup` or `clear`, without an active list, or on any failure it prints nothing and a session never fails to start because of it.
- **The block** (`TodoMessages.context`) is the stop message of [The Stop hook](#the-stop-hook) without its first line and last instruction: the reference first, the item in progress (else the next one) in full with its last check, the whole list one line per item. It never orders the agent to go on; what to do comes from the message it follows.
- A background agent's notification adds nothing: the list comes back at the next stop. At the end of a list no last message is imposed: the companion shows the finished list with its proofs.

### Companion
The session's right panel (subagents, background commands) gains a selector **Agents | Todo** once the agent has made a list (with no subagents the panel is the Todo view alone; on a phone it is the same screen as the subagents'). The view shows the plan's reference, the counter ("4 / 9": dropped items are not counted), the item in progress, every item in order with its state (to do, in progress, done, drop requested, dropped), its end condition, its last proof and time, the chips "Added along the way" and "Not verified" (an `attested` check), a warning when more than a third of the items rest on the agent's word, and the Stop hook's errors (`hook_error` in `hookEvents`). The counter is also a pill in the session header (it opens the panel on the Todo view) and replaces the transcript's `TodoWrite` steps on the office card.

It is current live: with `todos.live.v1` the server publishes `cloud.todo` (`{sessionId, kind, status, done, total}`) on the caller-wide feed after each change a screen shows (not after the reminder's bookkeeping), to the session's owner, and the app reads the list again at that event, when the feed opens and after a gap; the transcript is never read for it. A server without `todos.live.v1` is read once. Two shortcuts send an ordinary message to the session (same path and same right as a typed message): **Verify against the plan** and **Add an item…** ("Add to your todo: … Write it as a complete item … with todo_add"). The waits that drop and stall create appear in "Waiting for you" like other waits. Not done: editing the list from the app (only the agent edits it, or a person through a wait).

### Server-side reminder
The engine's `todo-reminder` pass (every 5 s, with the other engine steps) covers the sessions that have no Stop hook (Codex in a cloud pod or hosted remotely) and is a safety net for Claude Code when its hook did not run. It reads, in batches of 200, the sessions that are `active` and wanted `running` and have an active list updated in the last 30 days, and the latest `turn` event of each (`phase` `completed`). For a turn end it has not judged yet, with open items, no open wait, and no hook pass during the turn (`lastHookAt` is recorded by the hook's `begin` phase), it sends the same resume message as the hook, with the same stall counter: after 3 reminders without progress it opens the same blocked wait to the owner (Resume / Drop this item / Stop here). It does not re-run GitHub checks: it goes by the last proof the agent reported. `reminderSeq` is set under the list's row lock, so a turn end is judged once across replicas and restarts; a failed delivery gives the count back. Delivery is idempotent (`todo-reminder-<list>-<seq>`).

A workflow `deliver` (the agent's tool or the starter's) is refused with `409 todo_open` while the session's list has open items, so a step cannot end with work declared and not done.

### Not done yet
The reminder's own trial on a real Codex session (tested only by the engine and persistence tests); a Codex terminal (a local session has the todo tools and the server-side reminder, but Codex runs no `Stop` hook).
