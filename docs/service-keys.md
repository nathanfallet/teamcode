# Service and CI credentials

Service keys let unattended jobs authenticate to TeamCode without retaining an interactive login. Create/revoke them through **Dashboard → Service keys**, the native `teamcode keys` commands or the typed API. An administrator can manage keys for another eligible member from the team page or the member-scoped API/CLI option. The target member must have replaced their temporary password and be enabled.

## Permissions and quotas

Select only the required scopes:

| Scope | Access |
| --- | --- |
| `inference` | Registered inference endpoints and authenticated request cancellation |
| `usage:read` | The owner's capacity/allowance and agent-launch registry at `GET /api/v1/capacity` and `/api/v1/runs`, the owner's pool accounts under neutral labels at `GET /api/v1/pools/accounts`, and MCP gateway usage at `GET /api/v1/mcp/usage` |
| `guidance` | The owner's current/session pacing directives, delivery claims and receipts |
| `mcp` | The [MCP gateway](mcp-gateway.md) endpoints `/mcp/<server>` and `GET /api/v1/mcp/servers`, with the owner's MCP assignments (V2 branch) |
| `skills:read` | The owner's effective skill manifest and artifacts (V2, see [skills](skills.md)) |
| `skills:publish` | Publish skill versions to the catalog and pin them (V2). Only an administrator's key can receive it; it cannot unpin, assign, retire or read the catalog |
| `cloud:read` | Read the owner's [cloud sessions](cloud-sessions.md), executions, transcripts, inputs and transfers, and the profiles the owner can use (`GET` only, V2) |
| `cloud:write` | `cloud:read`, plus creating and managing the owner's cloud sessions: pause, resume, delete, messages, interrupts, attachments and transfers (V2) |
| `admin:cloud` | `cloud:write` with an administrator's rights over every team cloud session and transfer, including `TeamCode-On-Behalf-Of` (V2). Only an administrator's key can receive it. It also sees every member's sessions in the [event feed](cloud-conversations.md#event-feed) |
| `team:read` | Read-only operational view of the whole team, as an interactive administrator sees it, with no write and no `TeamCode-On-Behalf-Of`. Only an administrator's key can receive it. See [Team-wide read access](#team-wide-read-access) |

Inference also permits registering, heartbeating and ending the key's own [agent launches](agent-sessions.md); narrow lifecycle replies contain no usage totals. Another login cannot renew or dispatch under that launch ID. Cross-member/all-team registry reads need a `team:read` key (below) or an interactive administrator; run policy changes remain interactive administration.

Every valid service key can read capabilities and its own authenticated member identity, and revoke itself through logout. Other endpoints require an interactive login unless explicitly added to this scope model. Service keys cannot administer users/accounts/policies/routes, change passwords, manage devices/sessions, inspect other members, or create/list/revoke other keys. Even a key owned by an administrator has only its selected service scopes; `admin:cloud` is the one scope that carries administrator rights, over cloud sessions only. Team-wide status/account/quota endpoints are not part of `usage:read`, even on an administrator's key: it reads its owner's figures alone (`?all=true`, `?member=`, another member's run or capacity and the team's accounts, quota windows and MCP usage are refused or reduced to the owner's view). Use `team:read` for the team view. `whoami` reports the owner's member role, not additional rights for the key.

Keys share the member's existing daily ledger, weight, assigned pool, concurrency and model/effort policy. Creating, revoking or rotating keys does not add capacity or erase usage. Guidance and request identifiers remain member-scoped, rather than granting a separate quota/session namespace for each key. Dedicated CI members can have their own normal member policy if separate allocation is needed.

## Team-wide read access

`team:read` gives an unattended monitor the operational view an interactive administrator has, and nothing more. It includes `usage:read` (the owner's own figures) and `cloud:read`, and adds, read-only:

| Area | Reads |
| --- | --- |
| Agent launches | `GET /api/v1/runs` with `?all=true` or `?member=`, and `GET /api/v1/runs/{id}` of any member ([agent sessions](agent-sessions.md)) |
| Capacity and usage | `GET /api/v1/members/{id}/capacity`, `GET /api/v1/pools/budgets`, `GET /api/v1/mcp/usage` for every member |
| Accounts and quotas | `GET /api/v1/accounts`, `GET /api/v1/quotas`, and every account by name and email at `GET /api/v1/pools/accounts` (the data behind `teamcode status` for an administrator). No provider credential is ever returned |
| Cloud sessions | `GET /api/v1/cloud/sessions?owner=all` or `owner=<member>`, a session's detail and executions, its transcript, inputs and live stream (`events`, `messages`, `events/stream`), and `GET /api/v1/cloud/transfers?scope=team` ([cloud sessions](cloud-sessions.md)) |

Rules:

- Only a key owned by an administrator can be created with this scope (`400 invalid_service_key` otherwise, also for an administrator creating a key for a member), like `admin:cloud`. The owner's role is rechecked in shared state on every call, so a demoted or disabled owner loses the key at once.
- It never writes. Every other action, including run policies, capacity and pool settings, account changes, key management and all cloud writes, stays interactive or behind its own scope. `team:read` does not carry `TeamCode-On-Behalf-Of` and does not turn a `cloud:write` key into a cloud administrator: combined with `cloud:write`, writes still act as the owner alone.
- A `usage:read` key stays limited to its owner, even for an administrator.
- Transcripts are other members' private content: keep a `team:read` key in a secret store, prefer an expiring key and revoke it as soon as it is not needed. Reads are not written to the audit journal, as for interactive administrators.
- Servers that accept the scope advertise `team.read-key.v1`.

```sh
teamcode keys create --name team-monitor --scopes team:read --days 30 --json
```

## Creation and automation

```sh
teamcode keys create --name github-actions --scopes inference,guidance --days 30 --json
teamcode keys list
teamcode keys revoke KEY_ID
```

An administrator can create a key that never expires, for a long-lived CI job that would otherwise need rotation:

```sh
teamcode keys create --name skills-ci --scopes skills:publish --no-expiry --json
```

`--no-expiry` cannot be combined with `--days`. Only a key owned by an administrator may have no expiry: a member's keys, and keys an administrator creates for a member with `--member`, stay limited to 1–90 days and are refused otherwise. A key without expiry is otherwise an ordinary key: it can be revoked or replaced at any time, `teamcode keys list` and the dashboard show `never` as its expiry, it counts toward the 20-key limit, and it still stops working when its owner's password changes or is reset, the owner is disabled, or the owner's role changes (an owner demoted from administrator loses it). Revoke it as soon as the job no longer needs it.

Creation writes the one-time credential to stdout (JSON with `--json`); save it directly in an appropriate secret store. The accompanying notice goes to stderr. Subsequent list operations return only metadata. The dashboard/API likewise reveal the secret only in the successful creation response, with no-store headers; TeamCode does not log it or retain recoverable plaintext.

The default CLI scope is `inference`, with a 30-day lifetime. Lifetimes are 1–90 days (or no expiry for an administrator's key, above) and each member can retain at most 20 service-key records, including expired ones until removed. Permissions are immutable: create a replacement and revoke the old key to change scopes. Administrators can add `--member MEMBER_ID` to list/create commands.

On a GitHub Actions runner, use an isolated configuration directory and pass the service key through stdin:

```sh
export XDG_CONFIG_HOME="$RUNNER_TEMP/teamcode-config"
printf '%s\n' "$TEAMCODE_SERVICE_KEY" | teamcode configure --server https://teamcode.example.org
teamcode run claude -- -p "YOUR_TASK"
```

Use the actual arguments appropriate for the installed agent. `teamcode run codex -- ...` uses the same credential path. The key does not grant permission to bypass the client's own tool approval/sandbox rules. Keep the configuration private and remove it when the job ends. Inference-only keys can run without the optional guidance scope; server quota enforcement remains authoritative independently of guidance delivery.

## Revocation and persistence

Service keys use the shared token table, storing only a digest of a random `tcsk_` credential, expiry (`Long.MAX_VALUE` for a key without expiry, so every `expires_at > now` check applies unchanged and no migration is needed), name, owner and scopes. Authentication and admission validate current SQL state across replicas. Interactive session rotation has a separate 50-session limit and cannot evict unrelated CI keys. The device/session views exclude service keys; revoking a registered device does not remove independent CI keys.

An expired or explicitly revoked key cannot authenticate. The existing request monitor stops authenticated work on its next check when the original key is revoked or expires, without refunding uncertain provider work or replaying a stream. Password changes/resets, role changes and member disabling invalidate the member's keys together with other sessions. Logging out an interactive session alone does not revoke independent CI keys; logging out while using a service key revokes that key.

The additive Exposed migration makes `service_scopes` nullable. Existing session rows remain interactive; malformed non-null permissions fail closed. Domain authorization handles actions/scopes, transport code classifies known operations with an interactive-only default, and SQL stores revalidate principals. All replicas must run this version before relying on the service-key authorization contract.

## API

- `GET/POST /api/v1/service-keys`: list/create the current member's keys.
- `GET/POST /api/v1/members/{memberId}/service-keys`: another member requires administrator access.
- `DELETE /api/v1/service-keys/{id}`: revoke an owned key or, as an administrator, a same-team key.
- Creation accepts `name`, a nonempty list of the supported `scopes` (`skills:publish`, `admin:cloud` and `team:read` only for an administrator's key), and `validDays` (1–90; omitted means 30; an explicit `null` creates a key without expiry, administrators' keys only, otherwise `400 invalid_service_key`). It returns `metadata` and the one-time `accessToken`; list responses never include the token or digest. `metadata.neverExpires` is true for a key without expiry; its `expiresAt` is then `9223372036854775807` so clients written before this option still decode the list. Creating such a key records `service_key.created_no_expiry` in the [audit journal](audit.md) instead of `service_key.created`. A client that sends `validDays: null` to a server without this option gets a `400` (it never receives a 30-day key by mistake).

Skill publication uses the dedicated `skills:publish` scope described in [skills](skills.md). Cloud orchestration uses the `cloud:*` scopes: an external orchestrator holding an `admin:cloud` key creates one session per task on behalf of a member and relays messages through its own backend, so no administrator credential reaches a device. A `cloud:read` or `cloud:write` key acts as its owner alone, even when the owner is an administrator. Cluster, profile, settings and access-policy administration, GitHub linking and every other administration stay interactive. Servers that accept these scopes advertise `cloud.keys.v1`.

## TeamClaude reference

TeamClaude's `src/server.js:resolveClientAuth` distinguishes operator/client keys and refuses selected configuration writes to client keys. Its `test/client-usage.test.js` verifies named client attribution. TeamCode keeps that separation but uses durable member identity, hashed expiring credentials, explicit scopes and shared SQL revocation/ledger state. It does not import TeamClaude's proxy operator/client keys or copy their permissions implicitly.
