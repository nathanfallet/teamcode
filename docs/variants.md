# Environment and MCP variants (V2, server foundation)

This page describes the server-side foundation for the V2 roadmap item [Environment/MCP variants and per-user assignments](roadmap.md#environmentmcp-variants-and-per-user-assignments). It covers the model, persistence, administrator API and dashboard, and the effective-resolution use case. It does **not** yet deliver the MCP gateway, cloud sessions or any CLI command: those are the consumers this foundation prepares.

## Model

A **variant** is a named, versioned value for one **slot**:

| Kind | Slot (`target`) | Variant holds | Secret part |
| --- | --- | --- | --- |
| `ENV` | One environment variable name, e.g. `GITHUB_TOKEN` (`[A-Z_][A-Z0-9_]*`, at most 128) | The value | The value itself |
| `MCP` | One MCP server name, e.g. `tracker` (`[a-z0-9][a-z0-9._-]*`, at most 64) | HTTPS endpoint and non-secret configuration (`key=value`, at most 32 entries) | The credential, optional for a server without authentication |

Each variant has a **binding**:

- `SHARED`: one encrypted value on the variant (a team-wide environment value or a shared MCP credential).
- `PER_MEMBER`: no value on the variant; each assignment carries the member's own encrypted value (an individual environment value or a delegated MCP credential).

Several variants can exist for the same slot (`org-a` and `org-b` for `GITHUB_TOKEN`). Names are unique per slot (case-insensitively, following the MySQL collation). Kind, slot, binding and the MCP endpoint are immutable after creation; create another variant to change them. An immutable endpoint means stored shared or delegated credentials can never be redirected to a new host by an edit. A shared MCP credential can be replaced or cleared (`clearSecret`); a shared environment value can only be replaced.

Environment names that would take over the process, its dynamic loader, proxies/certificates or the agent's own TeamCode/provider connection are refused: for example `PATH`, `HOME`, `NODE_OPTIONS`, `HTTPS_PROXY` and the prefixes `LD_`, `DYLD_`, `TEAMCODE_`, `ANTHROPIC_`, `CLAUDE_`, `OPENAI_`, `CODEX_`, `XDG_`, `GIT_CONFIG_` (full list in `VariantService`).

Every update increments `version`, and an update must send the version it read (`expectedVersion`), so two administrators cannot silently overwrite each other. Assignments point to the variant, not to a version: an update applies to every assignee on their next resolution.

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

It is a trusted server-side port: it does not authenticate anyone. Callers must already act for an authenticated member (or an authorized session for them), and must neither log its result nor return it to another member. No HTTP route returns these values. An assigned slot that cannot be served (a value that fails to decrypt because of a wrong key or an envelope moved to another row, or a delegated MCP assignment without a credential) is withheld and listed in `unavailable`, by name only; the member's other slots are still returned. Each entry carries the variant `version` (bumped by variant edits) and the assignment's `assignedAt` (changed by a new selection or a rotated member value), so a consumer can detect either change.

## Secrets

- Secret values (shared values, shared credentials, member values, delegated credentials) are stored as AES-256-GCM envelopes through the existing `CredentialCipher`, with the authenticated context `variant:<team>:<variant>[:<member>]`. An envelope copied to another row does not decrypt.
- Secret values are write-only. API responses and the dashboard only say `hasSharedSecret` / `hasMemberSecret`. Request DTOs and `SecretValue` redact themselves in `toString`.
- The audit journal records metadata only: `variant.created`, `variant.updated`, `variant.deleted` and `variant.bulk_assigned` (subject: the variant ID); `variant.assigned` (also written once per member affected by a bulk assignment), `variant.unassigned`, `variant.excluded` and `variant.included` (subject: the member ID). Values, endpoints and configuration never enter the journal. The journal has a single subject per event, so a member event does not name the slot or variant; the current assignment view does.
- Endpoints and configuration are **not** secret: administrators can read them. Put credentials in the secret field, never in the endpoint URL (URLs with user information are refused; a query string is accepted and visible) or in configuration.
- Endpoints are only syntactically checked (HTTPS, host, no user information or fragment). Refusing loopback, link-local and private destinations must happen in the MCP gateway at connection time, after DNS resolution; it is not done here.

## Administrator API

All endpoints require an interactive administrator. Service/CI keys are refused, even when their owner is an administrator, and the current role is revalidated against MySQL on every call. Writes take the team lock. DTOs live in `api/models/Variants.kt`.

| Method and path | Purpose |
| --- | --- |
| `GET /api/v1/variants` | List variant metadata |
| `POST /api/v1/variants` | Create (`kind`, `target`, `name`, `binding`, `endpoint`, `config`, `secret`) |
| `PATCH /api/v1/variants/{id}` | Replace `name` and `config`; `secret` replaces the shared value, `clearSecret` removes a shared MCP credential; requires `expectedVersion` |
| `DELETE /api/v1/variants/{id}` | Delete a variant and its assignments |
| `POST /api/v1/variants/{id}/bulk-assign` | Bulk-assign a shared variant; returns `assigned`, `keptDirect`, `excluded` |
| `GET /api/v1/variant-assignments` | Effective assignments and exclusions of every member (metadata) |
| `GET /api/v1/members/{memberId}/variants` | The same for one member |
| `PUT /api/v1/members/{memberId}/variants` | Assign (`variantId`, `memberSecret` for per-member variants) |
| `DELETE /api/v1/members/{memberId}/variants/{kind}/{target}` | Remove the member's selection for a slot |
| `PUT /api/v1/members/{memberId}/variant-exclusions` | Exclude the member from a slot (`kind`, `target`) |
| `DELETE /api/v1/members/{memberId}/variant-exclusions/{kind}/{target}` | Remove the exclusion |

Capabilities advertise `team.variants.v1`. The dashboard page is `/dashboard/variants`: list, creation, deletion, bulk assignment, assignment, exclusions and effective assignments per member. Its forms use the existing origin and CSRF checks. It is not yet linked from the navigation bar, to avoid conflicting with concurrent dashboard work. Editing an existing variant is available through the API only.

## Persistence

Tables `variants`, `variant_assignments` (primary key member/kind/target, so at most one selection per slot) and `variant_exclusions` (same key), generated from the Exposed definitions in `infrastructure/database/tables/VariantTables.kt`. The generator emitted the `variants` table twice across two files; the reviewed migration keeps one file with the three tables in dependency order. A test checks that the migrated schema matches the Exposed tables. At most 500 variants per team; secret values are at most 16,384 characters.

## Limits and open questions

- **Granularity**: one variant fills one slot (one variable or one server). Grouping several variables into one assignable bundle is not supported; it could be added as a layer that expands into slots.
- **Update semantics**: assignees always get the latest version; there is no pinning and no stored history of earlier values, so there is no rollback to a previous value other than updating it again. Running sessions are not notified; consumers compare `version` and `assignedAt` when they resolve again.
- **Delegated credentials** are supplied by an administrator at assignment. A member-driven delegated flow (e.g. OAuth with the upstream MCP server, with explicit ownership and revocation by the member) is not implemented.
- **Per-tool MCP restrictions** are not modelled; an MCP assignment grants the whole server until the gateway lands.
- **Groups and defaults**: the precedence slot exists; group assignments, and whether a team default should exist at all, remain open. A default must never grant a member who has no assignment of any kind.
- **Consumers**: the MCP gateway (discovery and per-call authorization), cloud session preparation, the typed SDK/CLI commands and the revocation behavior for running sessions remain to be built and accepted against the roadmap.

## TeamClaude reference

TeamClaude has no comparable mechanism: its MCP endpoint (`test/mcp-endpoint.test.js`, `test/mcp-tools.test.js`) exposes the proxy's own control plane as tools, and it has no per-user environment or upstream MCP registry. This model therefore follows TeamCode's own patterns: team-scoped Exposed tables, `CredentialCipher` envelopes, administrator revalidation under the team lock and metadata-only audit.
