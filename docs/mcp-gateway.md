# MCP gateway (V2, server side)

This page describes the authenticated MCP gateway for the V2 roadmap item [MCP gateway with one endpoint per server](roadmap.md#mcp-gateway-with-one-endpoint-per-server). It builds on [environment and MCP variants](variants.md): an MCP server reaches a member only through an MCP variant assignment, and the gateway is the consumer that turns those assignments into tool discovery and per-call authorization. Each assigned upstream server has its own endpoint; sources are never merged into one MCP server. `teamcode run` connects the launched agent to those endpoints through the local proxy.

## Status

| Capability | State |
| --- | --- |
| One Streamable HTTP endpoint per assigned server, `/mcp/<server>`, stateless (no `Mcp-Session-Id`), protocol revisions 2025-11-25, 2025-06-18, 2025-03-26 | Done |
| `GET /api/v1/mcp/servers`: the caller's assigned servers and their endpoint paths | Done |
| Discovery (`tools/list`) limited to that server's tools allowed by the member's variant policy | Done |
| Per-call authorization rechecked in MySQL under the team lock; immediate revocation | Done |
| Per-variant tool allowlists (API and dashboard) | Done |
| Shared and delegated (per-member) upstream credentials, encrypted at rest, never returned | Done |
| Timeouts, response/request size bounds, concurrency and per-minute rate per member and server | Done |
| Metadata-only journal, per-member usage (API and dashboard) | Done |
| Private-network (SSRF) refusal at connection time | Done, with the DNS rebinding limit below |
| Member-driven delegated OAuth with the upstream server | Planned (credentials are supplied by an administrator today) |
| `teamcode run claude` / `teamcode run codex` connecting one agent-side MCP server per assigned server, for the launch only | Done; not yet exercised with a real agent |
| Resources, prompts, sampling, elicitation, server-initiated notifications | Not supported |

## Endpoint and protocol

Agents connect to `POST <teamcode>/mcp/<server>`, where `<server>` is the MCP variant slot (the server name), with `Authorization: Bearer <credential>`. `GET /api/v1/mcp/servers` lists the caller's assigned servers with their paths (names only: no upstream address or credential). The credential is an interactive session token or a service key with the new `mcp` scope; it identifies the member, and nothing in the request can choose another identity. A service key without the `mcp` scope is refused (403).

The gateway implements the Streamable HTTP transport in its stateless form: each POST carries exactly one JSON-RPC message and receives one `application/json` response. It never returns `Mcp-Session-Id`, so any replica can answer any message and nothing is kept between requests. `GET` and `DELETE` return 405 (no server-initiated stream, no session to end). Notifications and client responses return 202. Batches (JSON arrays) are refused. An `Origin` header other than `TEAMCODE_PUBLIC_URL` is refused (403), as the specification requires to prevent DNS rebinding from a browser. An unsupported `MCP-Protocol-Version` header is refused (400); `initialize` negotiates the client's revision when supported, otherwise the newest.

Supported methods: `initialize`, `ping`, `tools/list`, `tools/call`. Capabilities advertise `tools` only (`listChanged: false`). The server capability is `mcp.gateway.v1`.

Tools keep their upstream names and definitions: each endpoint relays one server, so no prefix is needed and two servers can expose tools with the same name. Upstream tools whose names are not 1–128 characters of `A-Z a-z 0-9 _ - .` are skipped. A request to a server that is unknown, not assigned to the member or revoked is answered 404 with the same JSON-RPC error (`-32600`, "Unknown MCP server.") for every method, including `initialize`.

## Authorization

- **Every message** resolves the member's effective variants (`ResolveEffectiveVariantsUseCase`, read from MySQL each time) and checks that the endpoint's server is assigned.
- **Discovery** lists the upstream tools of that server allowed by the variant's tool policy. A server that fails, times out or is saturated lists no tools. Hiding a tool is not the boundary.
- **Every call** checks the tool policy, then is admitted by `SqlMcpAccessStore.admit` in a transaction that takes the team lock (the same lock every variant, assignment, exclusion and policy write takes). Admission revalidates the credential (`requireCurrent`: token, role, scopes, disabled, temporary password), checks that the member's assignment row for this server still names this variant with the same variant version and assignment time as the resolution (so a rotated shared or delegated credential resolved just before the change is never used), that no exclusion exists and that the current policy allows the tool. A call admitted after a revocation commits is therefore refused on every replica, without a cache.
- On an assigned server, a tool that is unknown, outside the allowlist or revoked is answered with the same JSON-RPC error (`-32602`, "Unknown tool."); a request to an unassigned server gets the 404 above. Neither reaches an upstream. The refusal is journaled without taking the team lock, so a flood of refused calls does not queue behind admissions or administrator writes (the journal bound is then approximate under concurrent refusals).
- A disabled member's credential stops working at the next request (401). Ineligible members (disabled, temporary password) resolve to no servers.
- Calls already running when a revocation commits finish (bounded by the call timeout); the next call is refused.

### Tool policies

A tool policy belongs to an MCP **variant**, not to an assignment. Members who need different tools of the same server get different variants of it, for example `tracker / read-only` (allowlist `search`) and `tracker / full` (all tools), so the existing direct, bulk and exclusion rules of [variants](variants.md) apply unchanged. A variant without a policy exposes every tool, which keeps the meaning of existing assignments.

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/variants/{id}/tool-policy` | `{"mode":"ALL"}` or `{"mode":"ALLOWLIST","tools":[...]}` |
| `PUT /api/v1/variants/{id}/tool-policy` | Replace it (interactive administrator, MCP variants only, at most 256 names); audited as `mcp.tool_policy_updated` |

The dashboard page `/dashboard/mcp` edits the same policies (one tool per line; an empty list means all tools). Deleting a variant deletes its policy.

## Upstream credentials

The gateway connects to the variant's HTTPS endpoint with the credential of the effective assignment: the shared credential for a `SHARED` variant, the member's delegated credential for a `PER_MEMBER` one. Both stay AES-256-GCM encrypted in MySQL (see [variants](variants.md#secrets)) and are decrypted only for the request. They are sent as `Authorization: Bearer <credential>`, or as the raw value of the header named by the non-secret variant configuration key `credentialHeader` (for example `X-Api-Key`; transport headers such as `Host`, `Cookie` or `Mcp-Session-Id` are refused). Other configuration keys are not used by the gateway yet.

Credentials never reach the workstation: they are not part of any response, error or journal row, and every upstream result, tool definition and error message is scrubbed of the credential before it is returned, in case an upstream echoes it. Scrubbing works on the decoded JSON tree (string values, keys and bare tokens), so the result stays valid JSON, and an error message is scrubbed before it is truncated.

**Delegated OAuth.** Only the minimal base exists: an administrator stores a member's delegated token at assignment, and replacing or removing the assignment rotates or revokes it. A member-driven authorization flow with the upstream server (OAuth discovery, consent, refresh-token rotation, member-initiated revocation) is not implemented. An expired delegated token makes the upstream answer 401, which the member sees as a tool error until an administrator updates the assignment.

## Upstream client

`HttpMcpUpstream` is a stateless Streamable HTTP client: each operation sends `initialize`, `notifications/initialized`, the request, then a best-effort `DELETE` of the upstream session when the upstream issued one (also after a timeout or a client disconnect, bounded to 2 seconds). It accepts JSON and server-sent event responses (other events and server-to-client requests on the stream are ignored), sends `MCP-Protocol-Version` after initialization, refuses redirects, and accepts upstream revisions 2025-11-25, 2025-06-18 and 2025-03-26. `tools/list` follows at most 16 pages.

Before connecting, the host is resolved and the gateway refuses loopback, private (RFC 1918), link-local (including `169.254.169.254`), carrier-grade NAT, unique-local IPv6, multicast, unspecified and reserved addresses, IPv4-mapped and IPv4-compatible forms, and the 6to4, Teredo and NAT64 prefixes that embed IPv4 destinations. **Limit:** the HTTP engine resolves the name again when it connects, so DNS rebinding between the two lookups is not prevented by the gateway. Deployments should also restrict the server's egress at the network layer (for example a Kubernetes NetworkPolicy).

Opening an upstream session per operation costs extra round trips but keeps replicas stateless; caching upstream sessions in MySQL is a possible optimisation.

## Limits

All limits are enforced in shared MySQL (`mcp_calls`), so they hold across replicas. Defaults, configurable by environment variable:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TEAMCODE_MCP_CALL_TIMEOUT_MS` | 60000 | Whole `tools/call`, including the upstream handshake (1000–600000) |
| `TEAMCODE_MCP_LIST_TIMEOUT_MS` | 15000 | Listing one server (1000–120000) |
| `TEAMCODE_MCP_MAX_RESPONSE_BYTES` | 4194304 | Upstream response size per message |
| `TEAMCODE_MCP_MAX_REQUEST_BYTES` | 1048576 | Agent request body |
| `TEAMCODE_MCP_MEMBER_CONCURRENCY` | 4 | Running operations per member and server |
| `TEAMCODE_MCP_SERVER_CONCURRENCY` | 32 | Running operations per server across the team |
| `TEAMCODE_MCP_MEMBER_CALLS_PER_MINUTE` | 120 | Operations per member and server per minute |
| `TEAMCODE_MCP_SERVER_CALLS_PER_MINUTE` | 1200 | Operations per server per minute across the team |

At most 32 servers per member and 256 tools per server are listed. Listings count toward concurrency and rate like calls. An exceeded limit, a timeout, an oversized response or an upstream failure is returned as a tool result with `isError: true` and a TeamCode-written message (an upstream JSON-RPC error message is included, scrubbed), so the agent can report it. Each operation holds an `ACTIVE` journal row as its slot; the row is settled when the operation ends, including on client disconnect, and an orphaned slot from a crashed replica expires 30 seconds after the operation's own deadline. Admission runs two indexed counts (active leases, operations of the last minute) for the member and for the server and takes the team lock, which serializes gateway admissions with inference admission and administration; its cost is part of load acceptance.

## Journal and usage

`mcp_calls` records, per operation: member, server, variant ID, operation (`LIST`/`CALL`), tool name, outcome (`OK`, `TOOL_ERROR`, `DENIED`, `UPSTREAM_ERROR`, `TIMEOUT`, `TOO_LARGE`, `CANCELLED`), start time, duration and response size. Never arguments, results, endpoints or credentials. Refused calls are journaled within the member's per-minute rate; past it they are not stored, which bounds the table, and calls refused for a rate or concurrency limit are not stored either. Rows are kept 30 days and removed by the scheduled maintenance.

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/mcp/usage?days=7` | Totals per member and server (calls, lists, errors, denied, bytes, duration) over 1–30 days. Interactive administrators see every member; other members and `usage:read` service keys see only their own |
| `GET /api/v1/mcp/calls?before=<id>` | Newest operations first, 50 per page, interactive administrators only |

`/dashboard/mcp` shows tool policies, usage and recent operations. It is not yet linked from the navigation bar, like the variants page.

## Persistence

Tables `mcp_tool_policies` (one row per restricted variant, cascade on variant deletion) and `mcp_calls`, generated from `infrastructure/database/tables/McpTables.kt` and reviewed into one migration. `McpAccessPersistenceTest` checks that the migrated schema matches the Exposed tables.

## `teamcode run` connection

`teamcode run claude` and `teamcode run codex` read `GET /api/v1/mcp/servers` before launching (skipped with `--no-mcp`, when the server lacks `mcp.gateway.v1`, or with a warning when the list cannot be read) and give the agent one HTTP MCP server per assigned server, for this launch only:

- Claude Code: `--mcp-config` with one `http` server per assignment, named `teamcode-<server>`, URL `http://127.0.0.1:<port>/mcp/<server>`, header `Authorization: Bearer ${TEAMCODE_MCP_TOKEN}` expanded from the agent's environment. The option is placed after the caller's own options and before a literal `--`, because it is variadic.
- Codex: one `-c mcp_servers.teamcode-<server>={url=..., bearer_token_env_var="TEAMCODE_LOCAL_TOKEN"}` override per assignment, next to the existing model-provider overrides.
- `.` in a server name becomes `_` in the agent-side name; two servers that would get the same name are both left out.

The URL is the local proxy (the launch's own proxy, or the user service when it is running), which accepts only its random local token, from loopback, without an `Origin` header, and relays each message to `/mcp/<server>` on the TeamCode server with the member's TeamCode credential. The agent therefore holds no TeamCode credential and no upstream credential: only that local token, which is valid on loopback only and is not a TeamCode credential. No configuration file is written and personal MCP servers are untouched. The list of servers is read at launch; a server assigned later appears at the next launch, and a revoked server is refused at the next message even in a running session.

## Design choice: a gateway with one endpoint per server

Two requirements are fixed: the server decides which member can use which MCP server and tool, on every request, and upstream MCP credentials never reach the workstation. Three designs were assessed on 2026-10-19.

- **Provider-side injection** (the inference proxy adds Anthropic `mcp_servers` or OpenAI `type: "mcp"` tools to the provider request): not usable. OpenAI documents hosted MCP as unsupported for ChatGPT plan usage ([Sign in with ChatGPT limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)); Anthropic documents its [MCP connector](https://platform.claude.com/docs/en/agents-and-tools/mcp-connector) for API keys only. It would also need servers reachable from the provider over the Internet and agents able to replay server-executed blocks (Codex drops unknown response items).
- **Proxy-executed tools** (the proxy adds tool definitions to the request, intercepts the model's call and runs it): not clearly feasible. The proxy would have to hide its own tool turns from the agent, which then replays a history without them; a response mixing team and agent tools cannot be completed by either side alone; thinking and encrypted reasoning items must be replayed unmodified; and one agent request would become several provider requests to stream and account for.
- **Gateway with one endpoint per server** (this page): uses only the agents' standard MCP clients, checks every message in MySQL, keeps upstream credentials on the server, and needs no provider feature. Its cost is a local MCP configuration per launch, which `teamcode run` generates without writing files.

## Implementation choice: no MCP SDK dependency

The official Kotlin MCP SDK was evaluated. Its Streamable HTTP server keeps sessions in process memory, which conflicts with stateless replicas, and the gateway only needs a narrow subset of the protocol (four methods, no server-initiated messages) on both sides. The gateway therefore implements JSON-RPC over Streamable HTTP directly on Ktor and kotlinx.serialization, which the server already uses, with the protocol fixtures in `HttpMcpUpstreamTest` and `McpGatewayAcceptanceTest`. Adopting the SDK remains possible if resources, prompts or sampling are added.

## Verification

- `McpGatewayAcceptanceTest` (app, real MySQL, two replica pools, the HTTP gateway and two in-process fake upstream MCP servers on the Ktor test engine, one JSON and one SSE with sessions): two members with different assignments list different servers and see and invoke only their tools, each server on its own endpoint; a member without an assignment lists no server and cannot initialize one; the former aggregated `/mcp` path does not exist; direct calls to unassigned, restricted or unknown tools fail and never reach an upstream; unassignment, a policy change and an exclusion made through the other replica apply to the next call; a disabled member gets 401; an upstream echoing its credential is scrubbed; no response contains an upstream credential; the scope, origin and transport rules hold; usage and journal are visible to administrators with metadata only.
- `McpAccessPersistenceTest` (infrastructure, real MySQL, two pools): admission and revocation, policies, shared concurrency and rate limits across pools, bounded refusal journaling, usage, pagination, retention and migration.
- `HttpMcpUpstreamTest` (infrastructure, mock engine): JSON and SSE framing, upstream sessions and their deletion, credential headers and scrubbing, timeout, size bound, refused credentials, protocol version and private-address checks.
- `McpGatewayTest` (domain): one server per endpoint with upstream tool names, policy validation, discovery filtering, unknown-server and unknown-tool denial paths and lease release on cancellation.
- `LocalProxyTest` and `AgentLaunchTest` (CLI): the local relay forwards each server to its own gateway endpoint with the TeamCode credential and refuses unauthenticated, browser, malformed and oversized requests; Claude Code and Codex receive one MCP server per assignment, the local token only through the environment.

No real MCP server, provider or agent was called. Whether Claude Code expands `${TEAMCODE_MCP_TOKEN}` in an inline `--mcp-config` and Codex accepts the `-c mcp_servers...` override still needs one launch of each real agent.
