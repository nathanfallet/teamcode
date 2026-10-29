# Workflows

A **workflow** is a saved sequence of steps a team reuses: an agent works on something and delivers a result, a person reviews it, another workflow takes over. It is built on the same engine as [waits](waits.md): a `human` step is a wait, and an `agent` step ends only when the agent delivers.

Workflows belong to the team (there is no per-team scoping beyond the deployment). Everyone reads them; who may create, edit and delete is a team setting.

## Status

| Piece | State |
| --- | --- |
| API contract and typed client (`workflows`, `workflow`, `createWorkflow`, `updateWorkflow`, `deleteWorkflow`, `workflowSettings`, `updateWorkflowEditors`) (`api/…/models/Workflows.kt`) | Implemented |
| Domain: validation of a definition at save, sub-workflow checks (`domain/…/workflows`) | Implemented |
| MySQL store (`workflows`, `workflow_settings`; reviewed migration), routes `GET/POST/PUT/DELETE /api/v1/workflows`, `GET/PUT /api/v1/workflows/settings`, the `workflows.v1` capability, editing rights, OpenAPI reference | Implemented. Definitions only: nothing runs them yet |
| Run engine and run service (`domain/…/workflows`): the engine moves runs on one visit at a time; the service starts, reads, cancels and takes deliveries; run and delivery contract in the API and the typed client (`startWorkflowRun`, `workflowRuns`, `workflowRun`, `cancelWorkflowRun`, `deliverWorkflowRun`) | Implemented and tested against in-memory stores; **not connected to the server yet** |
| Persisted runs (tables, store), the run routes, the worker loop in the server, the cloud-session and wait adapters | Planned (next change) |
| `teamcode-workflow` MCP server (`deliver`) | Planned |
| Stop hook that makes an agent deliver or ask | Planned |
| Companion editor and run view, `teamcode workflows`, automations that start a workflow | Planned |

Definitions can be saved, versioned and read; no run, no MCP tool and no screen exists yet, so a saved workflow does nothing.

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

**Failure codes.** `session_failed`, `session_deleted`, `session_missing`, `ended_without_delivery` (the turn that took the instruction ended and nothing was delivered; a turn that began before the instruction does not count), `profile_missing`, `starter_unavailable`, `wait_cancelled`, `wait_missing`, `max_visits:<node>`, `max_total_visits`, `max_depth`, `workflow_missing`, `invalid_arguments`, `sub_workflow_failed|cancelled`, `expired` (two weeks), `no_transition`, plus the code of a refusal that cannot be retried. Cancelling (the starter or an administrator, signed in) withdraws the open wait, cancels the child run and ends the run `cancelled`; the session is left as it is.

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

## What is planned

The order of work: the server (store, routes, capability, editing rights), then runs (persisted node visits that resume after a restart, agent nodes on sessions, human nodes as waits, the `teamcode-workflow` MCP server with `deliver`), then the Stop hook, then the companion editor, the CLI and automations that start a workflow. Each step updates this page.
