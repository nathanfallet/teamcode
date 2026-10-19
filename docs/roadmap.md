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

## Release acceptance

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

### MCP access through inference-request injection

Decided by the owner on 2026-10-19: TeamCode exposes no MCP endpoint to workstations. The inference proxy itself attaches the team's MCP servers to the request it sends to the provider, and the provider's own MCP client calls them.

- Administrators register remote MCP servers as MCP variants (see [environment/MCP variants](#environmentmcp-variants-and-per-user-assignments)) and assign them to members. Each MCP variant gains an optional per-tool allowlist (not part of the current variant contract).
- On every authenticated inference request, the proxy resolves the member's current effective MCP variants, decrypts their credentials and adds them to the provider-bound body only:
  - Anthropic Messages API: one `mcp_servers` entry per server (`type: "url"`, `url`, `name`, `authorization_token`) and one `mcp_toolset` in `tools` whose `mcp_server_name` matches it and whose `default_config`/`configs` enable only the allowed tools. The proxy appends the MCP connector beta (`mcp-client-2025-11-20` at the time of writing) to the client's comma-separated `anthropic-beta` value without dropping the betas the client already sends.
  - OpenAI Responses API: one `{type: "mcp", server_label, server_url, headers, allowed_tools, require_approval: "never"}` entry per server in `tools`.
- Authorization happens in the proxy at each request: removing an assignment, disabling a member or rotating a credential applies to the next request, with no workstation state to revoke. `teamcode run` installs and configures nothing for MCP, and personal MCP configuration is left untouched.
- Credentials never reach the workstation: they exist only in the server and in the request to the provider. Provider responses can echo the request's tool configuration and provider errors can quote the request, so the proxy removes injected server configuration (URL, headers, token) from streamed and non-streamed responses and errors before returning them, and keeps it out of logs and audit records.
- Injected servers use a reserved name prefix. The proxy rejects a request whose newly declared `tools` or `mcp_servers` entries use that prefix, so a client cannot shadow or replace a team server; replayed history blocks that reference injected servers are expected and accepted, and other client-declared tools pass through unchanged. Append injected entries after the client's tools in a deterministic order. Tools come first in the cached prompt prefix, so per-member tool lists differ between members and an assignment change invalidates the cache of an ongoing conversation; capacity forecasts must account for it.
- Record metadata only: which servers and tools were attached to a request, and which MCP tool calls the provider reported in its response (server, tool, status), for per-member usage visibility. No tool input, output or credential is stored.
- Keep resolution stateless and identical on every replica; it reads the same persisted variants and assignments as the rest of the proxy.

Honest limits of this design:

- The provider calls the MCP server, so the server must be reachable from the public Internet over HTTPS (Anthropic: Streamable HTTP or SSE). A server on a private network or a local stdio server cannot be used. Only MCP tools are supported, not prompts or resources.
- The provider runs the tool calls, so TeamCode cannot bound their duration, rate-limit or deny individual calls: it can only choose which servers and tools are attached to a request and record the calls the provider reports. Per-member usage depends on parsing provider response blocks, including streamed ones.
- Credentials are static values from the variant. TeamCode does not run a delegated OAuth flow or refresh per-member MCP tokens; a server that needs short-lived user tokens requires that flow to be designed first.
- The model, and therefore the provider, sees tool inputs and results, and the provider receives the decrypted credential with each request. Anthropic documents its MCP connector as not eligible for zero data retention.
- Anthropic accepts a single bearer `authorization_token` per server; a variant that needs other headers can only be injected on OpenAI.
- The feature depends on each provider's MCP support for the credentials TeamCode uses. On 2026-10-19, OpenAI documents hosted MCP/connectors as unsupported for ChatGPT plan usage ([Sign in with ChatGPT limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)), so Codex injection is blocked until that changes. Anthropic documents the connector for API-key access ([MCP connector](https://platform.claude.com/docs/en/agents-and-tools/mcp-connector)) and says nothing about subscription OAuth credentials; this must be proven before it is implemented.
- Server-executed MCP calls come back as provider-specific blocks (`mcp_tool_use`/`mcp_tool_result` on Anthropic, `mcp_list_tools`/`mcp_call` items on OpenAI) that the agent must keep in its history and replay. Whether Claude Code and Codex accept and replay these blocks is unverified.
- Injection works only on native routes. Experimental cross-provider translation does not translate server-executed MCP blocks and must not inject servers until it does.

Acceptance: with a fake provider, two members with different assignments produce outgoing bodies that contain only their own servers and allowed tools, a member without an assignment gets none, and an assignment removal applies to the very next request; credentials never appear in what the workstation receives (including with a fake provider that echoes the tool configuration in responses and errors), in logs or in audit records; replayed history that references an injected server is accepted; a client-declared reserved name is rejected. For each provider, an opt-in run with a real subscription account shows the provider executing an injected tool call and the unmodified agent (Claude Code, Codex) completing a multi-turn conversation that replays those blocks. Injection stays disabled for a provider until that run passes.

### Skill catalog and synchronization

- Administrators manage a versioned skill catalog in the dashboard, pin approved versions and assign skill access to members or groups. Provide update, rollback and removal controls. Skills use versioned artifacts and yes/no access assignments, not the environment/MCP variant mechanism.
- Populate the catalog through GitHub synchronization or publication through the API from a CI pipeline; decide whether to support one or both sources when designing V2. Define explicit published versions and authenticated publishing permissions, keeping a reproducible approved version for installation and rollback.
- The native CLI synchronizes assigned skills into an isolated, agent-compatible local installation before `teamcode run`. Preserve personal skills and report naming conflicts explicitly.
- Track installation/version status separately from permission to use protected resources. Skills are instructions and files loaded by the agent, not server-executed MCP tools.
- Do not treat downloaded skill files as an access-control mechanism: users can copy instructions they have received. Enforce access to protected tools, credentials and data through the proxy's MCP injection or the backing service.
- Acceptance: clean setup and subsequent launches install the assigned pinned versions, updates/rollback are reproducible, removed assignments stop being provisioned, and personal configuration is preserved.

The dashboard, team identity, native launcher and shared persistence provide the integration points. The inference proxy attaches assigned MCP servers to provider requests; it does not install skills, which require an explicit CLI capability.


## V2: Kubernetes cloud agent sessions

Explore running agent sessions remotely on a connected Kubernetes cluster. This is V2 scope; the API contracts and interaction model remain to be discussed before implementation.

- Administrators connect a Kubernetes cluster and configure the namespaces, execution templates and resources available to the team.
- Start sessions from either the authenticated API or native CLI, with the same authorization and lifecycle rules, automatic provisioning and cleanup. The CLI uses the typed client/API modules rather than talking directly to Kubernetes. A proposed command family is `teamcode cloud ...`; exact commands and HTTP contracts remain to be designed.
- Select a Docker image for each session or a reusable environment profile. Teams can supply their own image with dependencies already installed, for example npm, Node.js, a JDK and Gradle. Record the resolved image digest and environment configuration for reproducible restoration; define the runner/agent entrypoint contract and private-registry authentication without baking TeamCode/provider credentials into images.
- Separate the installed toolchain image from per-session environment preparation inside the session container. Configure repositories to clone, requested branches/refs, working directories, Git identity and any bootstrap commands such as dependency installation. Apply the assigned environment configuration and prepare repository/GitHub access before launching the agent. Expose preparation progress/failures in the session lifecycle; do not report the agent ready before required setup succeeds.
- Provision GitHub authentication for Git and the `gh` CLI during environment preparation, using the effective user's authorized repositories and operations. Choose the GitHub App/delegated-user/CI credential mechanism during V2 design. Inject credentials at runtime rather than embedding them in the image, repository remote URLs, transcripts or reusable workspace snapshots; bound their scope and lifetime and support revocation.
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

- Provide explicit API-key generation and management for members and administrators. Personal keys can access only the owner's authorized resources; administrator keys with an explicit administration scope can operate across team members, create cloud sessions on their behalf and transfer sessions between them. Key permissions must remain bounded by the current owner's role and assignments, with expiry, revocation and metadata-only audit. An ordinary key cannot gain team-wide access by supplying a different member ID.
- Keep the initiating API-key owner distinct from a session's effective member so administrative orchestration has correct audit attribution and bills quota to the intended member. Revalidate permissions after role changes, revocation and ownership transfers.
- Expose machine-readable proxy/account/usage/quota status through the API and a matching CLI JSON view, comparable in purpose to `teamclaude accounts` JSON output. Extend or reuse existing TeamCode account/status endpoints and DTOs rather than maintaining a second quota calculation path. Include relevant provider/model windows, remaining/used capacity where known, resets, observation freshness, availability/cooldowns and the caller's effective allowance; preserve unknown values instead of presenting guessed headroom.
- Member keys see only their own usage/allowances and pool capacity or account metadata they are allowed to inspect. Explicit administrator keys can inspect the team's permitted operational view. Never return provider credentials, other members' private session content or unrelated account details through a status response.
- Acceptance: exercise personal and administrator keys against session creation, ownership transfer, transcripts and quota/status reads; demonstrate denied cross-member access for personal keys, administrator orchestration, immediate revocation and consistent CLI/API JSON values. Endpoint names, scopes and response schemas remain to be discussed.

The cloud-specific operations and automation surface above are V2. This does not defer the V1 scoped service/CI credentials or existing account/status API capabilities; V2 extends those foundations.


### Environment/MCP variants and per-user assignments

Use one variant-assignment model for cloud environment variables and MCP servers/configuration. Administrators define named variants and assign the selected variant to each authorized user; a user without an assigned variant has neither the environment variable nor MCP access. Shared/global values are reusable variants, not an implicit grant to every user. Provide bulk assignment with explicit exclusions and per-user variant selection; define any later group/default precedence without changing the no-assignment rule.

- **Environment variables:** variants contain the variable values required by an execution environment, using shared values or individual-user values. Support excluding selected users even when a shared variant is assigned in bulk. Keep secret values encrypted and out of transcripts/logs; expose only authorized values to that user's cloud execution. Define whether variants group variables or belong to individual variables before fixing the contract.
- **MCP:** variants define a server's endpoint, configuration and credential binding, allowing a shared team credential or an individual credential stored per member. Assignment controls which servers and tools the inference proxy injects into that member's provider requests. Removing an assignment or disabling a user removes access from the next request; team MCP credentials never reach the workstation, so no local configuration can keep using them.
- Show effective assignments in the dashboard and expose them through the authorized API. Persist variant versions and assignments for consistent behavior across replicas. On cloud creation, resume or ownership transfer, resolve the effective user's assignments again; do not retain the former owner's environment secrets or MCP credentials. Define explicit refresh/revocation behavior for running sessions rather than silently restarting active work.
- Acceptance: two users receive different variants of the same variable/MCP, a third without an assignment receives none, and a user explicitly excluded from bulk assignment stays excluded. Verify assignment changes, current-user authorization, absence of unassigned MCP servers from provider requests, resume and ownership transfer without former-owner resource access.

Skills remain a separate versioned catalog with access assignments, described above. A user without skill access receives no provisioned copy. On session creation/resume/transfer, resolve the new effective user's authorized pinned catalog artifacts. Already delivered skill files cannot be made secret again; enforce protected tool/data access independently.

Exact environment/MCP variant granularity, assignment UI/API and update semantics remain V2 design discussions. The GitHub-versus-CI-API skill publication choice is also open; no skill variants are planned at this stage.
