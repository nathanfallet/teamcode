# Workflows

A **workflow** is a saved sequence of steps a team reuses: an agent works on something and delivers a result, a person reviews it, another workflow takes over. It is built on the same engine as [waits](waits.md): a `human` step is a wait, and an `agent` step ends only when the agent delivers.

Workflows belong to the team (there is no per-team scoping beyond the deployment). Everyone reads them; who may create, edit and delete is a team setting.

## Status

| Piece | State |
| --- | --- |
| API contract and typed client (`workflows`, `workflow`, `createWorkflow`, `updateWorkflow`, `deleteWorkflow`, `workflowSettings`, `updateWorkflowEditors`) (`api/…/models/Workflows.kt`) | Implemented |
| Domain: validation of a definition at save, sub-workflow checks (`domain/…/workflows`) | Implemented |
| MySQL store (`workflows`, `workflow_settings`; reviewed migration), routes `GET/POST/PUT/DELETE /api/v1/workflows`, `GET/PUT /api/v1/workflows/settings`, the `workflows.v1` capability, editing rights, OpenAPI reference | Implemented |
| Run engine and run service (`domain/…/workflows`): the engine moves runs on one visit at a time; the service starts, reads, cancels and takes deliveries; run and delivery contract in the API and the typed client (`startWorkflowRun`, `workflowRuns`, `workflowRun`, `cancelWorkflowRun`, `deliverWorkflowRun`) | Implemented |
| Persisted runs (`workflow_runs`, `workflow_visits`; reviewed migrations), the run routes (below), the worker loop in the server (every 2 seconds, on the replicas that run the cloud), the cloud-session and wait adapters | Implemented |
| Delivery by session (`POST /workflows/session/deliver`), the execution credential scope `workflow:session`, `deliverWorkflowSession` in the typed client | Implemented |
| `teamcode-workflow` MCP server (`teamcode mcp workflow`, tool `deliver`), its loopback relay (`workflow-relay-v1`), connected beside the agent of remote sessions and of Kubernetes cloud sessions | Implemented. Tested on the JVM (server, relay, launch arguments); not exercised with a native binary or a real Claude Code or Codex session |
| Stop hook that sends the agent of a workflow step back to work once (`GET /workflows/session/pending`, `pendingWorkflowSession` in the typed client, `teamcode cloud workflow-stop`) | Implemented for Claude Code in remote and Kubernetes cloud sessions. Tested on the JVM (server, relay, hook logic, settings); not exercised with a native binary or a real Claude Code session. Codex is not covered ([below](#stop-hook)) |
| Companion screens to use workflows (`companion`, shown when `workflows.v1` is announced): the runs list (filters, keyset pages, administrators' `?all=true`), the run detail (visits, session, blocking wait, cancel), the read-only definitions list and detail, and a start form (inputs, profile) | Implemented. Tested on the JVM (use cases, view models, repository mapping, UI, demo mode); `companion-android` and `companion-ios` were not compiled |
| Live run updates: the feed event `cloud.workflow.run.updated` (published in the transaction of each run change, to the starter), announced by the capability `workflows.live.v1`; the list now carries `currentNodeId` and `currentNodeKind` and a read titles each visit (`nodeTitle`) | Implemented |
| Companion definition editor (editing definitions is still API-only), `teamcode workflows`, automations that start a workflow | Planned |

Definitions can be saved, versioned and read, and started over the API. An agent node ends when its agent calls `deliver` of the [`teamcode_workflow` MCP server](#agent-delivery) (or when the starter sends a delivery with their own credential, `POST …/deliver`); a turn that ends without a delivery (and without an open wait) first gets one reminder from the [Stop hook](#stop-hook) and, if it still ends without either, fails the run with `ended_without_delivery`. The companion has screens to start, follow and cancel runs and to read definitions; editing a definition is still done over the API.

## Definition

| Field | Rules |
| --- | --- |
| `name`, `description` | 1–120 characters; description at most 2,000 |
| `start` | The id of the first node |
| `sessionMode` | `same`: the next agent step arrives as a message of the same session. `handoff`: a new session starts with the previous deliverables in context |
| `inputs[]` | At most 16: `id` (`[A-Za-z][A-Za-z0-9_]{0,63}`), `label`, `required`, `default`, `maxLength`. Used as `{{inputs.id}}` |
| `nodes[]` | 1 to `limits.maxNodes` (at most 100), ids unique |
| `limits` | `maxNodes` (50), `maxVisits` in all (100, at most 500), `maxDepth` of sub-workflows (3, at most 5) |

A node has `id`, `kind`, `title`, `maxVisits` (1–20, default 3: how many times a loop may come back to it) and `transitions[]`: each is an outcome (`on`) and a next node (`to`); a transition without a target ends the workflow.

| Kind | What it is | Carries | Outcomes |
| --- | --- | --- | --- |
| `agent` | An agent works from `instruction` and ends only by delivering | `instruction` (required), `deliverable` (required), `profileId`, `agent`, `model` | `delivered` |
| `human` | A person is asked, as a [wait](waits.md) | `title`, `instruction` (the wait's body), `fields`, `actions`, `assignee` (`owner` of the run by default, or a member) | one per action id |
| `workflow` | Another workflow runs and its output comes back | `workflowId`, `arguments` (input id to template) | `delivered` |

A node carries nothing of another kind. Every outcome must have a transition, so a graph never stops by accident.

**Deliverables.** `artifact` (an artifact id), `url` (https, optionally within a `domain` such as `github.com`), `text`, or `json` matching a `schema`. The server checks the type before accepting a delivery.

**Templates.** `{{inputs.x}}` and `{{nodes.id.output}}` are replaced by plain text; nothing else between braces is accepted, and a node cannot read its own output.

## Checks at save

A save is refused with `400 invalid_workflow` (and a message naming the node) when: the start node does not exist; a transition goes to a missing node; a node cannot be reached from the start; no node ends the workflow; ids repeat; an outcome has no transition or an unknown one; a template names an unknown input or node; a deliverable is missing, or has a `domain` without being a `url` or a `schema` without being `json`; or a human node breaks the [wait rules](waits.md#model) (fields, actions, `requires`; secret fields are not available yet).

Sub-workflows are checked against the other saved workflows: each must exist, none may lead back to the one being saved (a call loop is refused with the path), the chain may not be deeper than `limits.maxDepth`, and the arguments must cover the callee's required inputs and name no unknown one.

Reads need an interactive login or a key with `cloud:read` (or a wider cloud scope). **Writes are interactive only**: a service key never saves or deletes a definition, whatever its scopes. Errors: `invalid_workflow`, `invalid_editors`, `invalid_limit`, `invalid_cursor` (400), `workflow_editors_only`, `forbidden` (403), `not_found` (404), `workflow_conflict`, `workflow_name_taken`, `workflow_limit` (200 per team), `workflow_in_use` (409).

**Concurrency.** A save or a delete first locks the team's `workflow_settings` row, so two replicas saving at once cannot both create a call loop, take the same name or pass the cap; the call-loop check runs on the definitions read under that lock. A replacement with `expectedVersion` is one conditional update on the version, so of two replicas saving the same version exactly one succeeds. Saves, deletions and changes of the rule are audited (`workflow.created`, `workflow.updated`, `workflow.deleted`, `workflow.editors.changed`).

## Runs

A **run** is one execution of a workflow, on the **version it started with**: the definition is copied into the run, so editing or deleting the workflow never changes a run in flight. It is started signed in, as its **starter** (their sessions, quota and GitHub link), with the inputs and, for agent nodes that name no profile, a cloud `profileId` (`400 profile_required` when one is missing, `400 invalid_inputs` for an unknown, missing or too long input). A member has at most 20 unfinished runs (`409 run_limit`); the same `Idempotency-Key` returns the first run.

The start node is an agent or sub-workflow node, never a human one: a person is asked about a session ([waits](waits.md)), so an agent works first.

Every pass through a node is a **visit**, stored with its outcome and output. The engine keeps nothing in memory: each pass claims the unfinished runs nobody holds, reads in a few batched reads what they wait for (sessions, waits, child runs) and records one step, so a restart or another replica continues from the stored visits. A step that fails for a reason that may pass (a busy cloud) is tried again on the next pass; one that cannot succeed ends the run `failed` with a short code.

| Node | What a step does | Ends with |
| --- | --- | --- |
| `agent` | Renders the instruction. In `same` mode the first node starts a session and the next ones are **messages of that session**; in `handoff` mode every node starts a session whose first message carries what the earlier nodes delivered. Then waits for `deliver` | `delivered` |
| `human` | Creates a wait of kind `workflow` for the node's assignee (the starter by default) about the run's session, and waits for its answer | the action id; the output is the answer as `label: value` lines |
| `workflow` | Starts a child run (depth + 1, same session when the child is in `same` mode, arguments rendered from the parent) and waits for it | `delivered`, with the child's output |

A wait of kind `workflow` is answered like any wait, but its answer **moves the run on instead of waking the session**. After an outcome the engine follows its transition; the target's `maxVisits` and the run's `limits.maxVisits` stop loops (`max_visits:<node>`, `max_total_visits`). The run's output is what the last node delivered.

**Delivery.** `POST /workflows/runs/{id}/deliver` (the starter, `cloud:write`) hands in what the current agent node was asked for once its instruction reached the session: `artifact` (an id), `url` (https, on the node's `domain` if any), `text`, or `json` checked against the node's `schema` (a subset: `type`, an object's `properties` and `required`, an array's `items`, a string's `enum`). A wrong delivery is `400 invalid_deliverable` and stores nothing; a run that waits for none is `409 nothing_to_deliver`. Whether the artifact exists is not checked yet.

**Delivery by session.** `POST /workflows/session/deliver` with `{sessionId?, output}` finds the unfinished run whose latest active visit is an agent visit bound to that session (one read on `workflow_visits (session_id, status)`, then the run by primary key) and then does exactly what `…/runs/{id}/deliver` does: the same validation (`400 invalid_deliverable`), the same conditional completion of the visit (of two calls, one wins; the other is `409 nothing_to_deliver`). Two callers may use it:

- **The run's starter**, with a login or a key carrying `cloud:write`, who must name `sessionId`. A [remote session](cloud-sessions.md) on a member's device acts with the member's own login.
- **A Kubernetes cloud session's execution credential** (`tcex_`), which carries the scope `workflow:session` and is bound to the one session it was issued for (`cloud_inference_credentials`). It is confined to that session whatever `sessionId` says, and the scope grants nothing else: not `…/runs/{id}/deliver`, not a read of a run, not any cloud route. As for `waits:session`, a service key that carries the scope (it is not offered when creating one) gets `403 credential_scope`, and so does an execution credential issued before the scope existed.

A session that does not exist, or belongs to another member, or whose run belongs to another starter, is `404 not_found`; the caller's own session that runs no workflow step is `409 nothing_to_deliver`. Cost per call: five transactions for an execution credential (the revalidation of the caller, the credential's session, the run with its visit, the conditional completion, the run read back), four for the starter's own login (no credential lookup), and one more read of the session's owner only when no step is found.

**Failure codes.** `session_failed`, `session_deleted`, `session_missing`, `ended_without_delivery` (the turn that took the instruction ended and nothing was delivered, **after one reminder** from the [Stop hook](#stop-hook) where it is installed; a turn that began before the instruction does not count), `profile_missing`, `starter_unavailable`, `wait_cancelled`, `wait_missing`, `max_visits:<node>`, `max_total_visits`, `max_depth`, `workflow_missing`, `invalid_arguments`, `sub_workflow_failed|cancelled`, `expired` (two weeks), `no_transition`, plus the code of a refusal that cannot be retried. Cancelling (the starter or an administrator, signed in) withdraws the open wait, cancels the child run and ends the run `cancelled`; the session is left as it is.

## Editing rights

`mode` is `all` (every member), `admins`, or `members` with a list. The default is `admins`. Reading is open to every member. Administrators can always edit. The rule is changed by an administrator, signed in, through `PUT /api/v1/workflows/settings` (a dashboard screen comes with the editor); a member list names active members of the team.

## Routes

| Route | Purpose |
| --- | --- |
| `GET /api/v1/workflows` | The definitions by id, `?after=&limit=` (1–100, 50) |
| `GET /api/v1/workflows/{id}` | One definition |
| `POST /api/v1/workflows` | Create (`{workflow, expectedVersion}`) |
| `PUT /api/v1/workflows/{id}` | New version; `409 workflow_conflict` when `expectedVersion` is stale |
| `DELETE /api/v1/workflows/{id}` | Delete (returns the definition); `409 workflow_in_use` while another workflow calls it |
| `GET`/`PUT /api/v1/workflows/settings` | Who may edit |

### Run routes

| Route | Purpose |
| --- | --- |
| `POST /api/v1/workflows/{id}/runs` | Start (`{inputs, profileId}`), `201`; `Idempotency-Key` returns the first run (`200`, `Idempotency-Replayed: true`). Signed in |
| `GET /api/v1/workflows/runs` | One's own runs, newest first; `?all=true` (administrators), `?workflow=`, `?status=`, `?before=&limit=` (1–100, 50). A list never carries the definition snapshot, but names the step in progress (`currentNodeId`, `currentNodeKind`); a read titles each visit (`nodeTitle`) |
| `GET /api/v1/workflows/runs/{id}` | A run with its visits (the starter, or an administrator) |
| `POST /api/v1/workflows/runs/{id}/cancel` | Cancel (the starter or an administrator, signed in) |
| `POST /api/v1/workflows/runs/{id}/deliver` | `{output}`; a key needs `cloud:write` |
| `POST /api/v1/workflows/session/deliver` | `{sessionId?, output}`; the starter (naming `sessionId`, a key needs `cloud:write`) or the session's execution credential (`workflow:session`). See "Delivery by session" |
| `GET /api/v1/workflows/session/pending?sessionId=` | `{needsAction, runId?, nodeTitle?, deliverable?{type, domain?}}`; the same credentials and session resolution as the delivery. See [Stop hook](#stop-hook) |

A run starts a cloud session as its starter through a server-held credential minted for the run (scope `cloud:write`, stored hashed, deleted when the root run ends; child runs share it). Several replicas may run the worker: a pass claims a run with a 30-second lease, and a start, a step or a completion is a conditional update, so a step happens once. Page cost, whatever the team size: a list is two transactions (the caller check, one read of the page), a run three (the caller check, the run, its visits).

### Agent delivery

The agent of a step delivers with the stdio MCP server `teamcode mcp workflow --session <id> [--run <id>]`, connected under the name `teamcode_workflow` exactly as [`teamcode_waits`](waits.md#agents) is: Claude Code gets a stdio entry in `--mcp-config`, Codex `-c mcp_servers.teamcode_workflow={command=…,args=[…],env={TEAMCODE_WORKFLOW_URL=…,TEAMCODE_WORKFLOW_TOKEN_VAR="TEAMCODE_LOCAL_TOKEN"},env_vars=["TEAMCODE_LOCAL_TOKEN"]}`. It serves one tool, `deliver`, with one argument `output` (any JSON value: a string for `text`, `url` and `artifact`, an object or array for `json`). The tool tells the agent to call it once, when the task of the current step is done, with exactly what the step asked for, and to end its turn after it; the session id is the server's own, the agent cannot name another. The server's refusals (`invalid_deliverable`, `nothing_to_deliver`, `credential_scope`, `not_found`) come back with what to do.

- **What the agent is told.** The message the engine sends for an agent step ends with a sentence naming the form of its deliverable (the artifact id, an https address on the node's `domain`, the text, a JSON value that matches the schema), telling it to call `deliver` of `teamcode_workflow` once and end its turn. Beside the artifact and wait instructions (Claude Code `--append-system-prompt`, Codex `developer_instructions`, with the same exceptions) the agent is also told that it calls `deliver` only on a workflow step, never for an ordinary session.
- **Relay.** The tool reaches the proxy that launched the agent through a loopback relay (announced by the user service as `workflow-relay-v1`) that relays `POST /api/v1/workflows/session/deliver` and nothing else, with the same checks as the wait relay (the token compared in constant time, loopback, no `Origin`, a bounded body of at most 2 MiB, `no-store`, a refused sign-in as `502 relay_sign_in_refused`). Everything else, delivering by run id, reading, starting or cancelling a run included, is a `404` that never reaches the server.
- **Where.** A remote session gets the tool when the server advertises `workflows.v1` (the host runs with the member's own login). A Kubernetes cloud session gets it when the server advertises `workflows.v1` and the execution credential may deliver, which the runner asks without delivering: an empty body to the route is refused as `400 invalid_body` after the credential is checked, so that answer means yes and `403 credential_scope` (an execution that started with an older credential) or `404` (an older server) means no, and the agent starts without the tool. A local `teamcode run` never gets it: it has no session.

### Stop hook

An agent can end its turn without calling `deliver` and without asking a person. Where the hook is installed, the first such stop is sent back to work once; the second ends the turn as before.

- **The question.** `GET /api/v1/workflows/session/pending?sessionId=` answers `needsAction: true` (with `runId`, `nodeTitle` and `deliverable` with its `type` and, for a `url`, its `domain`; never the schema or the instruction) when the session has an unfinished run whose latest active visit is an agent visit bound to it **and** the session holds no open wait. Any kind of stored wait counts as having asked (`ask_human`, or a wait created some other way); a session's own permission request is not a stored wait, so it does not count. Credentials, `sessionId` and the answers for an unknown or foreign session are those of the delivery: the starter's key with `cloud:write` names `sessionId`, an execution credential (`workflow:session`) is confined to its own session, another member's or an unknown session is `404 not_found`, and a session of the caller's own that runs no step is `200` with `needsAction: false`. It writes nothing. Cost per call: four transactions for an execution credential (the revalidation, the credential's session, the run with its visit, the open wait), three for the starter's own login; one more read of the session's owner when no step is found. The visit is found on `wfvisit_session`, the wait on `wait_session (session_id, status)` with `limit 1`; no index was added (plans in the pull request).
- **The hook.** `teamcode cloud workflow-stop --relay http://127.0.0.1:<port> --session <id> [--run <id>]` (internal) is a `Stop` hook of Claude Code. It reads the hook input on standard input and: (a) prints nothing and exits 0 when `stop_hook_active` is true (the agent was already sent back, so it is never trapped twice and the run then fails with `ended_without_delivery`); (b) otherwise asks the launcher's loopback relay (`workflow-relay-v1`, which relays this one read besides the delivery, with the same guards), and when `needsAction` is true prints `{"decision":"block","reason":"..."}`, where the reason names the step, the form of the deliverable, and tells the agent to call `deliver` of `teamcode_workflow` with the result or, if it needs a person's answer, `ask_human` of `teamcode_waits`, then end its turn; (c) on any error (unreadable input, no or unreachable relay, an older server without the route, a refusal, no answer within 2 seconds) prints nothing and exits 0: an infrastructure problem never keeps the agent from stopping. It reads nothing but `hook_event_name` and `stop_hook_active` of the input, and logs and sends nothing of the agent's messages. The relay token comes from the agent's environment (`TEAMCODE_MCP_TOKEN`), never from a command line.
- **Where.** In the settings passed to Claude Code (`--settings`), as one more `Stop` entry beside the team's hooks and the runner's own wake-up hook (none replaces another), only when the `teamcode_workflow` tool is connected: remote sessions when the server advertises `workflows.v1`, Kubernetes cloud sessions when they also pass the `deliver` probe. A local `teamcode run` has no session and gets neither. A remote session on Windows runs the command through the shell Claude Code uses there; this was not exercised.
- **Codex is not covered.** Codex has a `Stop` hook in this repository only for a local `teamcode run`'s activity report; cloud and remote Codex sessions run no hooks at all (Codex runs only hooks a person trusted), so a Codex agent that ends its turn without delivering still fails the run with `ended_without_delivery`, with no reminder.
- **Waiting state.** The reminder keeps the turn going inside Claude Code: cloud and remote sessions learn their agent's turns from the runner (`ready`/`active`), not from `Stop` hooks, and the launch activity report of `teamcode run` (`waiting_for_human`) is not installed there, so a refused `Stop` leaves no wrong state. This was reasoned from the code, not observed on a real session.

## What is planned

The order of work: the companion definition editor, the CLI and automations that start a workflow. Each step updates this page. Not done on the Stop hook: Codex; a reminder for an ordinary (non-workflow) session, which is never forced to deliver or ask; verifying it against a real Claude Code session and a native binary.
