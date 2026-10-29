# Workflows

A **workflow** is a saved sequence of steps a team reuses: an agent works on something and delivers a result, a person reviews it, another workflow takes over. It is built on the same engine as [waits](waits.md): a `human` step is a wait, and an `agent` step ends only when the agent delivers.

Workflows belong to the team (there is no per-team scoping beyond the deployment). Everyone reads them; who may create, edit and delete is a team setting.

## Status

| Piece | State |
| --- | --- |
| API contract and typed client (`workflows`, `workflow`, `createWorkflow`, `updateWorkflow`, `deleteWorkflow`, `workflowSettings`, `updateWorkflowEditors`) (`api/…/models/Workflows.kt`) | Implemented |
| Domain: validation of a definition at save, sub-workflow checks (`domain/…/workflows`) | Implemented |
| MySQL store (`workflows`, `workflow_settings`; reviewed migration), routes `GET/POST/PUT/DELETE /api/v1/workflows`, `GET/PUT /api/v1/workflows/settings`, the `workflows.v1` capability, editing rights, OpenAPI reference | Implemented. Definitions only: nothing runs them yet |
| Runs: node visits persisted, agent nodes on sessions, human nodes as waits, `teamcode-workflow` MCP (`deliver`) | Planned |
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
