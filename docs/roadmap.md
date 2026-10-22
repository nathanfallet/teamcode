# Implementation roadmap

This is the target sequence; see [V1 progress](progress.md) for the current overview and [status](status.md) for actual evidence. Estimates are planning ranges for a developer familiar with Kotlin/KMP, not a deadline or a claim about agent throughput.

| Milestone | Deliverable | Exit evidence | Rough effort |
| --- | --- | --- | --- |
| J0 | Seven modules, contracts, native transport and deterministic local upstream | Native CLI -> typed client -> Ktor; streaming and cancellation demonstrated | 2–4 days |
| J1 | Team login, CLI account add/import/upsert/reconnect, secure server enrollment, basic dashboard, one Max account | Administrator-created login -> CLI -> provider -> visible usage; no provider credentials used by developer proxy | 1–2 weeks |
| J2 | Reliable pool, refresh serialization, all quota windows, bounded failover and session affinity | Exhaustion, concurrent refresh, early/late stream errors and disconnects reproduced | 1–2 weeks |
| J3 | Members/groups/devices, durable reservations, fair scheduling, protected pools, audit | Heavy member cannot starve a protected colleague; restart/revocation cannot reset allowance | 1–2 weeks |
| J4 | Forecasting and predictive pacing, directives and local hooks, forecast UI | Replayed load slows/recovers without oscillation; delivery distinguished from enforcement | 1–2 weeks |
| J5 | Catalog and native Responses/Codex support | Real multi-tool code tasks for advertised native routes; experimental translation is not a V1 gate | 2–4 weeks |
| J6 | Native releases, user/system service installers, updates, recovery and operations | Clean installs on target OSes, boot/logon reconnection, restoration and rollback demonstrated | 1–2 weeks |

First PR/commit sequence: document architecture; wire seven modules and enforce graph; deterministic streaming upstream and native proxy; team/device identity; real provider enrollment; provider reliability; team product; controller; multi-model compatibility. Keep slices runnable and push tested milestones.

Tests use synthetic fixtures and local fake providers. Real-provider acceptance is opt-in, capacity-bounded and never a public PR CI dependency. Measure gateway-added latency, first-byte latency, queue wait, success rate, memory per stream, registry throughput and forecast error before choosing production SLOs or quoting hosting costs.

## Finalized 0.1.0 scope (owner decision, 2026-10-20)

Finish explicit boot startup, then create tag/release `0.1.0`. Additional native-client, load, forecasting, workstation and operational validation moves to V2 for another agent's PR, as detailed in the [handoff](v2-validation-handoff.md). The original milestones above describe the broader development sequence; their extended exit evidence is not all claimed complete. Built-in release CI and delivery of the native installers/image/deployable chart still gate publication.

## Release acceptance

Under the finalized owner scope, complete boot-startup implementation and the built-in release checks when creating the `0.1.0` tag and publishing GitHub release **0.1.0**. The release must provide documented one-command installation on every advertised macOS/Linux/Windows target, accepted server images and a versioned, distributed Helm chart ready for users to deploy wherever they choose. Preparing and validating the deployable chart is in scope; deploying it on an owner or user cluster is not requested or required. The extended scenarios below are retained as V2 follow-up, not claimed as passed by publication.

- Standalone public checkout; only `cli -> client -> api` on the local side.
- Native HTTPS, streaming, backpressure and cancellation on advertised OS targets.
- Account identity verified server-side; add/import upsert preserves policy/history; source config unchanged; partial imports and retries explicit.
- No credential leakage, unsafe refresh overwrite, unbounded retry or replay after output.
- Atomic quota admission, missing-usage reconciliation, fair interactive access and protected pool isolation.
- Forecasts handle resets, outside spend, stale measurements and unknown capacity without accelerating blindly.
- Directed advice is sparse, expiring and acknowledged honestly; no arbitrary process termination.
- Native-provider model/account tool loops and result correctness, not just text responses. Cross-provider translation and its reasoning compatibility remain experimental outside V1 acceptance.
- Dashboard authorization/CSRF, audit, revocation and route explanations.
- Service install/uninstall idempotence, boot/logon behavior, locked keychain/offline recovery and draining updates.

## Experimental: cross-provider translation

At the owner's request on 2026-10-19, Claude/Codex protocol conversion is disabled by default behind the persistent administrator setting `experimentalCrossProviderTranslation`. Native subscription pools, same-provider model/account fallback and their acceptance remain V1. Full-default cross-provider reasoning, unsupported tool grammars and experimental translation acceptance must not delay V1. Administrators can opt in through model settings or the model-policy API; known compatibility limits still apply when enabled.

## V2: open-source model providers

The first open-source model provider is deferred to V2. V1 supports pooled Claude Max and Codex subscriptions through their native protocols. Cross-provider translation is an optional experiment, not a V1 release gate. Keep the provider and protocol interfaces extensible; implementing or accepting an OSS backend is not a V1 release gate.

## V2: centrally managed MCP servers and skills

TeamCode will extend team administration to agent tools and reusable skills. This is planned V2 scope, not part of the current implementation or release acceptance.

### MCP gateway with one endpoint per server

Requirements fixed by the owner on 2026-10-19: the server decides which member can use which MCP server and tool, on every request, and upstream MCP credentials never reach the workstation. Nothing is installed or configured by hand on the workstation.

- Administrators register remote MCP servers as MCP variants (see [environment/MCP variants](#environmentmcp-variants-and-per-user-assignments)), assign them to members and can restrict each variant to an allowlist of tools.
- TeamCode exposes one authenticated MCP endpoint per upstream server, `/mcp/<server>`. Sources are never aggregated into a single MCP server, and tools keep their upstream names. Discovery shows only the tools the member's variant allows; every message re-resolves the member's assignments and every tool call is admitted against current server-side state, so a revocation applies to the next message on every replica. Hiding tools in a client configuration is not an authorization boundary.
- Upstream credentials stay encrypted on the server and are added only to the server's request to the upstream; responses, errors and the journal are scrubbed of them. Support shared team credentials and individual credentials stored per member; a member-driven delegated OAuth flow comes later.
- `teamcode run claude` and `teamcode run codex` read the member's assigned servers at launch and give the agent one MCP server per assignment, for that launch only, pointing at the local proxy, which relays to the server's endpoint with the member's TeamCode credential. The agent holds only the local proxy token; no file is written and personal MCP configuration is untouched.
- Add bounded tool execution, concurrency/rate limits, metadata-only audit records and per-member usage visibility. Keep authorization stateless across replicas.
- Acceptance: two members with different assignments list different servers and see and invoke only their authorized tools, each server on its own endpoint; a member without an assignment gets none; direct unauthorized calls fail without reaching the upstream; revocation takes effect at the next message; upstream credentials never reach the workstation, even when an upstream echoes them; one real launch of each agent uses an assigned server through `teamcode run`.

Designs set aside on 2026-10-19:

- **Provider-side injection** (the inference proxy adds Anthropic `mcp_servers` or OpenAI `type: "mcp"` tools to the provider request). OpenAI documents hosted MCP/connectors as unsupported for ChatGPT plan usage ([Sign in with ChatGPT limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)), and Anthropic documents its [MCP connector](https://platform.claude.com/docs/en/agents-and-tools/mcp-connector) for API-key access only, so neither is documented as working with the subscription credentials TeamCode pools (OpenAI explicitly excludes it). It would also require servers reachable from the provider over the Internet, give the provider the decrypted credential, and rely on agents replaying server-executed blocks they may not keep.
- **Proxy-executed tools** (the proxy adds tool definitions, intercepts the model's call and runs it). The agent would replay a history without the proxy's hidden tool turns, a response that mixes team and agent tools cannot be completed by either side alone, reasoning items must be replayed unmodified, and one agent request becomes several provider requests to stream and account for. Not clearly feasible; reconsider only with evidence from both agents.

### Skill catalog and synchronization

- Administrators manage a versioned skill catalog in the dashboard, pin approved versions and assign skill access to members or groups. Provide update, rollback and removal controls. Skills use versioned artifacts and yes/no access assignments, not the environment/MCP variant mechanism.
- Populate the catalog only through publication through the API, called from a CI pipeline (owner decision, 2026-10-19). A repository publishes from its own workflow with a publication-scoped service key; TeamCode has no GitHub connection (no App, OAuth or server-side pull). Define explicit published versions and authenticated publishing permissions, keeping a reproducible approved version for installation and rollback. The GitHub App link added for cloud sessions never feeds the catalog.
- The native CLI synchronizes assigned skills into an isolated, agent-compatible local installation before `teamcode run`. Preserve personal skills and report naming conflicts explicitly.
- Track installation/version status separately from permission to use protected resources. Skills are instructions and files loaded by the agent, not server-executed MCP tools.
- Do not treat downloaded skill files as an access-control mechanism: users can copy instructions they have received. Enforce access to protected tools, credentials and data at the MCP gateway or backing service.
- Acceptance: clean setup and subsequent launches install the assigned pinned versions, updates/rollback are reproducible, removed assignments stop being provisioned, and personal configuration is preserved.
- Server side in progress on a V2 branch: catalog, CI publication with `skills:publish`, pins, yes/no member decisions, delivery and installation status. CLI synchronization remains planned. See [skills](skills.md).

### Team hooks

- Team hooks are distributed like skills: CI publication with a separate `hooks:publish` scope, interactive administrator pins (never a key), yes/no member decisions, installation by `teamcode run` after the member approves each version on their computer, and by the runner in Claude Code cloud sessions. See [team hooks](hooks.md).
- Next: report "waiting for a human" from `Stop` and `Notification` hooks on the run or cloud session, without content; report approval status to the server; retirement; Windows; Codex cloud sessions.

The dashboard, team identity, native launcher and shared persistence provide the integration points. The inference proxy alone does not configure an agent's MCP connections or install its skills; these come from the MCP gateway, `teamcode run` and an explicit CLI skill capability.


## V2: Kubernetes cloud agent sessions

Explore running agent sessions remotely on a connected Kubernetes cluster. This is V2 scope. The contract and the implementation status are in [cloud sessions](cloud-sessions.md); the list below is the original direction.

- Administrators connect a Kubernetes cluster and configure the namespaces, execution templates and resources available to the team.
- Start sessions from either the authenticated API or native CLI, with the same authorization and lifecycle rules, automatic provisioning and cleanup. The CLI uses the typed client/API modules rather than talking directly to Kubernetes. A proposed command family is `teamcode cloud ...`; exact commands and HTTP contracts remain to be designed.
- Select a Docker image for each session or a reusable environment profile. Teams can supply their own image with dependencies already installed, for example npm, Node.js, a JDK and Gradle. Record the resolved image digest and environment configuration for reproducible restoration; define the runner/agent entrypoint contract and private-registry authentication without baking TeamCode/provider credentials into images.
- Separate the installed toolchain image from per-session environment preparation inside the session container. Configure repositories to clone, requested branches/refs, working directories, Git identity and any bootstrap commands such as dependency installation. Apply the assigned environment configuration and prepare repository/GitHub access before launching the agent. Expose preparation progress/failures in the session lifecycle; do not report the agent ready before required setup succeeds.
- Provision GitHub authentication for Git and the `gh` CLI during environment preparation, using the effective user's authorized repositories and operations. Mechanism: GitHub App user-to-server tokens of the effective member, linked per member ([GitHub access](cloud-sessions.md#github-access)). Inject credentials at runtime rather than embedding them in the image, repository remote URLs, transcripts or reusable workspace snapshots; bound their scope and lifetime and support revocation.
- Persist the reproducible setup specification separately from session workspace changes. On resume, restore saved work instead of blindly re-cloning over it, refresh required credentials and run only the appropriate idempotent preparation steps. On ownership transfer, remove former-owner Git/GitHub authentication and resolve the recipient's repository permissions and configuration; keep the session unready when required access is unavailable. Exact setup hooks, credentials and workspace provisioning contracts remain to be discussed.
- Support automatic pause after a configurable inactivity timeout and explicit pause/resume through the API and CLI. Detaching a workstation does not make a session idle while the agent, tools or queued messages are still doing work. Define activity signals and timeout behavior before implementation.
- On pause, stop accepting new work for that execution, drain or checkpoint safely, persist the workspace, transcript and supported agent continuation state, then terminate the pod or complete its Job. Paused sessions retain no running pod; stored data still consumes storage and may incur cost. Track pause progress/failure explicitly so an incomplete checkpoint is not advertised as resumable.
- Resume the same logical session in a newly provisioned pod, restore its workspace and supported conversation state, and issue fresh scoped credentials after rechecking current quotas, permissions, MCP assignments and skills. Do not assume Kubernetes preserves process memory or that every agent supports transparent continuation; verify the supported agent resume mechanism. Messages received while paused must have a defined queue/rejection and wake-up policy.
- Persist lifecycle transitions so concurrent resume requests or multiple server replicas cannot launch duplicate executions. Define retention, expiration and intentional deletion separately from inactivity pause; inactivity must not silently delete resumable work.
- Cloud sessions use the same TeamCode inference proxy, member quotas, routing rules and predictive pacing as local sessions. They receive only their assigned MCP access and pinned skills through the planned V2 management capabilities.
- Users can attach from their workstation through the native CLI to interact with a remote session and reconnect later. The precise terminal/control protocol remains open.
- Transfer a cloud session from one team member to another on request through the API or CLI, retaining its logical identity, workspace and supported history. Administrators can transfer any team session; ordinary users can request a transfer of their own sessions under a defined recipient authorization/acceptance policy. Define the safe handoff boundary for running work and message delivery before implementing live transfer.
- Recheck the destination member's permissions, model/effort policy, quota and MCP/skill assignments during transfer. Revoke or rotate prior attachment/pod credentials and define which former-owner access ends; future work is charged to the new member while historical usage remains attributable to its original member. Record both the initiating actor and affected members. A transfer must neither reset allowances nor let an ordinary user seize another user's session.
- Provide API access to session transcripts and allow sending messages to a running session. Discuss transcript format, pagination/streaming, message delivery/acknowledgment and concurrent CLI/API interaction before fixing a contract.
- Enforce member/group authorization for session creation, attachment, messages and transcripts. Scope pod credentials to the session, revoke them when it ends and keep cluster credentials on the server. Bound pod resources, concurrent sessions and idle retention.
- Persist desired session state and lifecycle events so server replicas can coordinate provisioning without duplicate pods. Define transcript/workspace retention and cleanup policies separately from pod lifetime.

Acceptance direction: create sessions by API and CLI using a selected custom toolchain image, prepare repositories and Git/gh authentication, observe its pod and status, attach from the CLI, send a message and retrieve the resulting transcript through the API; demonstrate shared quota/MCP authorization, reconnection, inactivity pause with no running pod, restoration into a new pod without losing supported workspace/history, owner/admin transfer with destination-policy enforcement and preserved history, replica coordination and cleanup. These are proposed V2 acceptance scenarios, not implemented capabilities. Workspace provisioning, repository access, artifacts, scheduling across clusters and interactive session semantics require further product discussion.


### API keys and proxy observability for automation

Implemented (V2 code). See [service keys](service-keys.md), [cloud sessions](cloud-sessions.md), [`teamcode status`](capacity.md#teamcode-status) and the [audit journal](audit.md); evidence in [status](status.md#api-keys-and-status-json-acceptance-2026-10-22).

- Members and administrators create, list and revoke keys through **Dashboard → Service keys**, `teamcode keys` and `/api/v1/service-keys` (administrators also for another member). A key has explicit scopes, an expiry of 1–90 days (or none, created by an administrator only), and metadata-only audit of its creator and owner. Personal scopes (`cloud:read`, `cloud:write`, `usage:read`, …) act as the key's owner alone, even on an administrator's key; supplying another member (`TeamCode-On-Behalf-Of`, `?member=`, `?owner=`) is refused. Only an administrator's key can hold `admin:cloud`, which creates, drives and transfers cloud sessions on behalf of any member, or `team:read`, the read-only operational view of the whole team.
- A session created or transferred by an orchestrator's key records the initiator (the key's owner and the key's ID) separately from the effective member, who owns the session and is charged for its work; the journal keeps both. The key and its owner's role and state are rechecked in shared SQL state on every call, so a revocation, a role change, a disabled owner or a password reset ends the key at once on every replica. A running inference request and an open transcript stream also end at their next check after a revocation, and a former owner loses a transferred session's transcript.
- `teamcode status --json` prints the API's own DTOs (`GET /api/v1/status`, `/capacity`, `/directives/current`, `/pools/accounts`) with the same serializer, without a second calculation: the caller's allowance, groups, pacing, and the accounts they can be routed to with each provider window's utilization, observation time and reset, cooldowns and whether the account can serve now. A value the server does not know stays unknown: a window that was never observed is absent, an unreported reset or cooldown is `null`, and a section the server or key does not serve is `null` with its reason in `unavailable`.
- A member's key reads only its owner's allowance and neutral account labels; `team:read` reads the team's accounts by name, quota windows and every member's capacity. No status response returns provider credentials or another member's private content.
- Acceptance, done: a personal key is refused on another member's session, transcript, messages, transfer, allowance and quota windows; an `admin:cloud` key creates a session for a member and transfers it, with the journal naming the key as initiator distinct from both members; a revoked key is refused at its next call and its open transcript stream ends at the next check; and the CLI JSON view carries the API bodies field for field (pacing as the `directive` of `/directives/current`), with unknown values kept null or absent. The CLI and server share the `api` DTOs; a field added by a newer server than the CLI is not printed until the CLI is updated.

The cloud-specific operations and automation surface above are V2. This does not defer the V1 scoped service/CI credentials or existing account/status API capabilities; V2 extends those foundations.


### Environment/MCP variants and per-user assignments

Use one variant-assignment model for cloud environment variables and MCP servers/configuration. Administrators define named variants and assign the selected variant to each authorized user; a user without an assigned variant has neither the environment variable nor MCP access. Shared/global values are reusable variants, not an implicit grant to every user. Provide bulk assignment with explicit exclusions and per-user variant selection; define any later group/default precedence without changing the no-assignment rule.

- **Environment variables:** variants contain the variable values required by an execution environment, using shared values or individual-user values. Support excluding selected users even when a shared variant is assigned in bulk. Keep secret values encrypted and out of transcripts/logs; expose only authorized values to that user's cloud execution. Define whether variants group variables or belong to individual variables before fixing the contract.
- **MCP:** variants define a server's endpoint, configuration and credential binding, allowing a shared team credential or an individual credential stored per member. Assignment controls which server endpoints and tools the gateway serves to that member. Removing an assignment or disabling a user removes access from the next message; team MCP credentials never reach the workstation, so no local configuration can keep using them.
- Show effective assignments in the dashboard and expose them through the authorized API. Persist variant versions and assignments for consistent behavior across replicas. On cloud creation, resume or ownership transfer, resolve the effective user's assignments again; do not retain the former owner's environment secrets or MCP credentials. Define explicit refresh/revocation behavior for running sessions rather than silently restarting active work.
- Acceptance: two users receive different variants of the same variable/MCP, a third without an assignment receives none, and a user explicitly excluded from bulk assignment stays excluded. Verify assignment changes, current-user authorization, MCP direct-call denial, resume and ownership transfer without former-owner resource access.

Skills remain a separate versioned catalog with access assignments, described above. A user without skill access receives no provisioned copy. On session creation/resume/transfer, resolve the new effective user's authorized pinned catalog artifacts. Already delivered skill files cannot be made secret again; enforce protected tool/data access independently.

Exact environment/MCP variant granularity, assignment UI/API and update semantics remain V2 design discussions. A first server-side foundation (model, persistence, administrator API/dashboard and effective resolution, without gateway, cloud or CLI consumers) and its provisional choices are described in [variants](variants.md). Skills are published from CI through the API, never synchronized from GitHub; no skill variants are planned at this stage.
