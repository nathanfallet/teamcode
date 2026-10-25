# Environment and MCP variants (V2, server foundation)

This page describes the server-side foundation for the V2 roadmap item [Environment/MCP variants and per-user assignments](roadmap.md#environmentmcp-variants-and-per-user-assignments). It covers the model, persistence, administrator API and dashboard, and the effective-resolution use case. It does not deliver cloud sessions or any CLI command. The [MCP gateway](mcp-gateway.md) is its first consumer.

## Model

A **variant** is a named, versioned value for one **slot**:

| Kind | Slot (`target`) | Variant holds | Secret part |
| --- | --- | --- | --- |
| `ENV` | One environment variable name, e.g. `GITHUB_TOKEN` (`[A-Z_][A-Z0-9_]*`, at most 128) | The value | The value itself |
| `MCP` | One MCP server name, e.g. `tracker` (`[a-z0-9][a-z0-9._-]*`, at most 64) | HTTPS endpoint and non-secret configuration (`key=value`, at most 32 entries), or a [hosted command](#hosted-mcp-commands) and its visible variables | The credential, optional for a server without authentication; for a hosted command, its secret variables |

Each variant has a **binding**:

- `SHARED`: one encrypted value on the variant (a team-wide environment value or a shared MCP credential).
- `PER_MEMBER`: no value on the variant; each assignment carries the member's own encrypted value (an individual environment value or a delegated MCP credential).

Several variants can exist for the same slot (`org-a` and `org-b` for `GITHUB_TOKEN`). Names are unique per slot (case-insensitively, following the MySQL collation). Kind, slot, binding, the MCP transport and endpoint, and a hosted command with its visible variables are immutable after creation; create another variant to change them. An immutable endpoint means stored shared or delegated credentials can never be redirected to a new host by an edit. A shared MCP credential can be replaced or cleared (`clearSecret`); a shared environment value can only be replaced.

Environment names that would take over the process, its dynamic loader, proxies/certificates or the agent's own TeamCode/provider connection are refused: for example `PATH`, `HOME`, `NODE_OPTIONS`, `HTTPS_PROXY` and the prefixes `LD_`, `DYLD_`, `TEAMCODE_`, `ANTHROPIC_`, `CLAUDE_`, `OPENAI_`, `CODEX_`, `XDG_`, `GIT_CONFIG_` (full list in `VariantService`).

Every update increments `version`, and an update must send the version it read (`expectedVersion`), so two administrators cannot silently overwrite each other. Assignments point to the variant, not to a version: an update applies to every assignee on their next resolution.

## Hosted MCP commands

An MCP variant reaches its server in one of two ways, its **transport**: `http` (the default, and the value of every variant created before this field existed) calls its HTTPS endpoint; `stdio` runs a package on the server's MCP host (see [the gateway](mcp-gateway.md#hosted-stdio-servers)). A hosted command is made of:

| Field | Rule | Can change? |
| --- | --- | --- |
| `launcher` | `npm` (run by `npx`) or `pypi` (run by `uvx`), among the launchers the server's MCP host offers | No |
| `package` | One exact version: `name@1.2.3` or `@scope/name@1.2.3` on npm (a full semantic version, no range, tag or URL), `name==1.2.3` on PyPI (no range, wildcard, extra, marker or URL), at most 256 characters | No |
| `args` | At most 32 arguments of 1–512 characters, without control characters, passed as they are with no shell. `${NAME}` in an argument is replaced at launch by the secret variable `NAME`, which must exist | No |
| `config` | Visible environment variables (`NAME=value`, at most 32, values of at most 1,024 characters). Names follow the environment rules below, and `NPM_CONFIG_*`, `UV_*`, `PIP_*`, `NODE_*`, `PYTHON*`, `COREPACK_*`, `SSL*` and `CURL_*` are reserved too, so a variant can neither redirect the registry, turn install scripts back on, nor change how the Node.js or Python runtime loads code or checks certificates (`NODE_TLS_REJECT_UNAUTHORIZED`, `PYTHONBREAKPOINT`) | No: they decide where the secrets go (an `API_URL`, for example), like an endpoint |
| Secret variables | One encrypted envelope holding a JSON object `{"NAME":"value"}`: at most 16 variables, names as for visible ones and different from them, each value at least 8 characters (shorter values could not be masked in the server's output without hiding ordinary text), 16,384 characters in all | Yes, all at once, as any shared secret or member value |

The secret variables use the same envelopes as any variant (`shared_secret` for `SHARED`, `member_secret` on the assignment for `PER_MEMBER`), so shared and per-member values, rotation, bulk assignment and exclusions work unchanged. A `SHARED` command's variables must define every `${NAME}` its arguments use, and cannot be cleared while an argument uses one; a `PER_MEMBER` command's member value must hold them all at assignment. A bare credential is refused for a hosted command: its secrets are always named variables; the other way round, secret variables are refused for an HTTPS server or an environment variable. Arguments are visible to administrators: a credential belongs in a secret variable referenced as `${NAME}`. With `SHARED`, every assigned member uses the same process, so anything the server keeps in memory is visible to all of them; the dashboard says so.

A hosted command is declared by an interactive administrator only, like every variant: a service key is refused, even one of an administrator. The variant's creation time is kept as the date its dependencies will be resolved at (so a version published later is not picked up). The journal's `variant.created` event names its transport, launcher and package (`details`), never its arguments, variables or values.

**Not served yet.** This server has no MCP host configured: declaring a hosted command answers `409 mcp_disabled`, and a hosted variant already in the database (written by a later version) is listed in `unavailable` by effective resolution, so members' clients never see a server the gateway cannot call. Existing hosted variants can still be renamed, rotated, assigned and deleted.

API fields: `transport`, `launcher`, `package`, `args`, `config` and `secretVariables` on `POST /api/v1/variants`; `secretVariables` on `PATCH` (send `config` unchanged); `memberSecretVariables` on `PUT /api/v1/members/{memberId}/variants`. `VariantView` returns `transport` (MCP variants only), `launcher`, `package` and `args`; never a secret value. The dashboard form offers "Remote server (HTTPS)" or "Hosted command" and lists a hosted variant with the exact command it declares.

## Assignments and exclusions

A member has **at most one variant per slot**. The rules:

1. **No assignment, nothing.** A shared variant is never an implicit grant: a member receives a slot only through an assignment row. New members receive nothing until assigned.
2. **Direct assignment** selects a variant for one member and replaces any previous selection for that slot. A `PER_MEMBER` variant requires the member's value at assignment; a `SHARED` variant refuses one.
3. **Bulk assignment** assigns a `SHARED` variant to every **currently enabled** member, writing ordinary rows marked `BULK`. It replaces earlier `BULK` selections for that slot, keeps `DIRECT` selections and skips excluded members. It is an action, not a standing rule: members created later receive nothing until assigned. `PER_MEMBER` variants cannot be assigned in bulk.
4. **Exclusions** are per member and slot. Creating one removes the member's current selection for that slot; while it exists, direct assignment is refused (`409 variant_excluded`) and bulk assignment skips the member. Removing an exclusion grants nothing back.
5. A member that authentication would refuse (disabled, or still holding a temporary password) resolves to nothing; the stored assignments apply again once the member is eligible.
6. Deleting a variant removes its assignments.

### Precedence, prepared for groups

`AssignmentSource` orders sources: `DIRECT` (300) > `GROUP` (200, reserved) > `BULK` (100), and an exclusion beats every source. `VariantResolution.select` applies this order to a list of candidates. Nothing produces `GROUP` today, and this code does not depend on groups.

Direct and bulk selections are materialized in the same row (one per member and slot), so today at most one candidate exists per slot and "direct beats bulk" is enforced on write: bulk assignment skips direct rows, and a direct assignment replaces a bulk row. Removing a direct selection therefore leaves the slot empty; it does not fall back to an earlier bulk value. Group assignments are expected to be resolved from their own table (group → variant) and added as `GROUP` candidates at read time, which needs no change to `variant_assignments` and keeps the no-assignment and exclusion rules intact.

## Effective resolution

`ResolveEffectiveVariantsUseCase(teamId, memberId)` returns the member's effective environment variables and MCP servers, with decrypted values. It reads MySQL on every call, so a change made on any replica is visible to the next resolution, with no cache to invalidate. This is the entry point the MCP gateway and cloud sessions will call when they create, resume or transfer a session.

It is a trusted server-side port: it does not authenticate anyone. Callers must already act for an authenticated member (or an authorized session for them), and must neither log its result nor return it to another member. Two HTTP routes return environment values, each to one audience only: `GET /api/v1/environment` returns the caller's own, and `POST /api/v1/cloud/runner/variables` the session owner's to that session's runner (see [delivery to agents](#delivery-to-agents)). An assigned slot that cannot be served (a value that fails to decrypt because of a wrong key or an envelope moved to another row, or a delegated MCP assignment without a credential) is withheld and listed in `unavailable`, by name only; the member's other slots are still returned. Each entry carries the variant `version` (bumped by variant edits) and the assignment's `assignedAt` (changed by a new selection or a rotated member value), so a consumer can detect either change.

## Secrets

- Secret values (shared values, shared credentials, member values, delegated credentials) are stored as AES-256-GCM envelopes through the existing `CredentialCipher`, with the authenticated context `variant:<team>:<variant>[:<member>]`. An envelope copied to another row does not decrypt.
- Secret values are write-only. API responses and the dashboard only say `hasSharedSecret` / `hasMemberSecret`. Request DTOs and `SecretValue` redact themselves in `toString`.
- The audit journal records metadata only: `variant.created`, `variant.updated`, `variant.deleted` and `variant.bulk_assigned` (subject: the variant ID); `variant.assigned` (also written once per member affected by a bulk assignment), `variant.unassigned`, `variant.excluded` and `variant.included` (subject: the member ID). Values, endpoints and configuration never enter the journal; a hosted command's `variant.created` names its transport, launcher and package in `details`, never its arguments or variables. The journal has a single subject per event, so a member event does not name the slot or variant; the current assignment view does.
- Endpoints and configuration are **not** secret: administrators can read them. Put credentials in the secret field, never in the endpoint URL (URLs with user information are refused; a query string is accepted and visible) or in configuration.
- Endpoints are only syntactically checked (HTTPS, host, no user information or fragment). Refusing loopback, link-local and private destinations happens in the MCP gateway at connection time, after DNS resolution (see [its limits](mcp-gateway.md#upstream-client)).

## Administrator API

All endpoints require an interactive administrator. Service/CI keys are refused, even when their owner is an administrator, and the current role is revalidated against MySQL on every call. Writes take the team lock. DTOs live in `api/models/Variants.kt`.

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/variants` | List variant metadata |
| `POST /api/v1/variants` | Create (`kind`, `target`, `name`, `binding`, `endpoint`, `config`, `secret`; for a [hosted command](#hosted-mcp-commands), `transport`, `launcher`, `package`, `args`, `secretVariables`) |
| `PATCH /api/v1/variants/{id}` | Replace `name` and `config`; `secret` replaces the shared value, `clearSecret` removes a shared MCP credential; requires `expectedVersion` |
| `DELETE /api/v1/variants/{id}` | Delete a variant and its assignments |
| `POST /api/v1/variants/{id}/bulk-assign` | Bulk-assign a shared variant; returns `assigned`, `keptDirect`, `excluded` |
| `GET /api/v1/variant-assignments` | Effective assignments and exclusions of every member (metadata) |
| `GET /api/v1/members/{memberId}/variants` | The same for one member |
| `PUT /api/v1/members/{memberId}/variants` | Assign (`variantId`, `memberSecret` for per-member variants) |
| `DELETE /api/v1/members/{memberId}/variants/{kind}/{target}` | Remove the member's selection for a slot |
| `PUT /api/v1/members/{memberId}/variant-exclusions` | Exclude the member from a slot (`kind`, `target`) |
| `DELETE /api/v1/members/{memberId}/variant-exclusions/{kind}/{target}` | Remove the exclusion |

Capabilities advertise `team.variants.v1`. The dashboard page is `/dashboard/variants`: list, creation, deletion, bulk assignment, assignment, exclusions and effective assignments per member. Its forms use the existing origin and CSRF checks. The page opens with an explanation of what a variant is, how it is assigned and where it reaches a session. The creation form shows only the fields of the selected type (environment variable: name and value; MCP server: name, endpoint, configuration and optional credential) and, for a per-member variant, hides the shared value. The server reads only the selected type's fields and drops the others, then applies the per-type validation described above. It is not yet linked from the navigation bar, to avoid conflicting with concurrent dashboard work. Editing an existing variant is available through the API only.

## Persistence

Tables `variants`, `variant_assignments` (primary key member/kind/target, so at most one selection per slot) and `variant_exclusions` (same key), generated from the Exposed definitions in `infrastructure/database/tables/VariantTables.kt`. Hosted commands added three columns to `variants`: `transport` (`http` by default), `command` (the launcher) and `args` (a JSON array: the package, then the arguments), added by one `ALTER TABLE` so that they arrive together, appended in place by MySQL 8.4 (`ALGORITHM=INSTANT` is chosen without rebuilding the table). A stored command this version cannot read withholds only that server. The generator emitted the `variants` table twice across two files; the reviewed migration keeps one file with the three tables in dependency order. A test checks that the migrated schema matches the Exposed tables. At most 500 variants per team; secret values are at most 16,384 characters.

## Delivery to agents

The environment variables assigned to a member reach their agent, in `teamcode run` and in cloud sessions, for Claude Code and Codex.

**At start**, the agent's process environment holds the current values, so its Bash commands, tools, hooks and MCP servers see them. `teamcode run` reads `GET /api/v1/environment` (capability `team.environment.v1`) before launching; a cloud session's runner reads `POST /api/v1/cloud/runner/variables` (`cloud.variables.v1`) each time it starts the agent. TeamCode's own settings for the agent are applied after them, and the CLI drops reserved names again (the server's list plus `GH_CONFIG_DIR`, `GIT_TERMINAL_PROMPT` and shell-option variables), naming them on stderr. `teamcode run --no-variables` starts without them. A read that fails starts the agent without them, with a notice.

**During the session**, Claude Code gets `CLAUDE_ENV_FILE`, a script that Claude Code runs in the shell of every Bash command, before the command. Measured with Claude Code 2.1.309, Claude Code reads the file's content once, when the session starts, and runs that same content each time: rewriting the file later changes nothing, while content that reads another file, or runs a command, gets fresh values every time. So TeamCode's script holds no value and fetches them:

- **Locally**, `teamcode run claude` writes two files under `<configuration directory>/environment/`, both `0600` in a `0700` directory: the script, and a values file it sources. A loop reads `GET /api/v1/environment` every 60 seconds while the agent runs and rewrites the values file atomically when something changed, with `unset` for a name the agent had and lost. Both files are removed when the run ends. A script the user had in `CLAUDE_ENV_FILE` still runs, first, so the team values win.
- **In a cloud session**, the script runs `teamcode cloud environment`, which asks the runner over a socket on the pod's memory volume; the values stay in the runner's memory (see [cloud sessions](cloud-sessions.md#agent-variables)).

So a value rotated, added or removed while the agent runs, and the recipient's values after a cloud transfer, reach the next Bash command: within 60 seconds locally, 30 seconds in the cloud.

**Why this mechanism.** A `SessionStart` hook writing to the `CLAUDE_ENV_FILE` it receives would run once, and that file is read once too; a `PreToolUse` hook cannot set a command's environment (`updatedInput` only replaces the command, and rewriting the command would put the values in the transcript); settings `env` and the launch environment are fixed for the process; `CLAUDE_CODE_SHELL_PREFIX` also wraps hooks and MCP server start-up. `CLAUDE_ENV_FILE` set at launch applies to every Bash command, needs no hook or plugin (so it works with `--bare` and with a user's own hooks), and keeps values out of the command line and the transcript.

**Limits.**

- Codex has no per-command environment script: it gets the values of its start. A change reaches a local Codex at the next `teamcode run`, and a cloud Codex when its agent process starts again. A `shell_environment_policy` in the user's Codex configuration still filters what its commands inherit.
- Only Claude Code's Bash commands see changes. Its process environment, and so its MCP servers, hooks and other tools, keep the values of the start.
- Claude Code passes the variables of `CLAUDE_ENV_FILE` to PowerShell commands only when the file holds plain assignments; TeamCode's script reads another file, so PowerShell commands (Windows) keep the values of the start.
- The agent can read the values: they are its own, as they would be in a member's shell. A command that prints them puts them in the transcript; TeamCode never does.
- A local run that is killed (`SIGKILL`, power loss) leaves its two files in the private directory until they are removed by hand.
- Service keys never receive a member's values (`403`): `teamcode run` signed in with a key starts the agent without them.

## Limits and open questions

- **Granularity**: one variant fills one slot (one variable or one server). Grouping several variables into one assignable bundle is not supported; it could be added as a layer that expands into slots.
- **Update semantics**: assignees always get the latest version; there is no pinning and no stored history of earlier values, so there is no rollback to a previous value other than updating it again. Running sessions are not notified: Claude Code sessions read the values again before their Bash commands ([delivery to agents](#delivery-to-agents)); other consumers resolve again at their next start.
- **Delegated credentials** are supplied by an administrator at assignment. A member-driven delegated flow (e.g. OAuth with the upstream MCP server, with explicit ownership and revocation by the member) is not implemented.
- **Per-tool MCP restrictions** are tool policies on the MCP variant, enforced by the [MCP gateway](mcp-gateway.md#tool-policies); a variant without a policy grants the whole server.
- **Groups and defaults**: the precedence slot exists; group assignments, and whether a team default should exist at all, remain open. A default must never grant a member who has no assignment of any kind.
- **Consumers**: the [MCP gateway](mcp-gateway.md) implements discovery and per-call authorization; cloud session preparation and [agents](#delivery-to-agents), local and cloud, receive environment variables. There is no administrator CLI command for variants yet.

## TeamClaude reference

TeamClaude has no comparable mechanism: its MCP endpoint (`test/mcp-endpoint.test.js`, `test/mcp-tools.test.js`) exposes the proxy's own control plane as tools, and it has no per-user environment or upstream MCP registry. This model therefore follows TeamCode's own patterns: team-scoped Exposed tables, `CredentialCipher` envelopes, administrator revalidation under the team lock and metadata-only audit.
