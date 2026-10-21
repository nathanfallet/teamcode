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
| `skills:publish` | Publish skill versions to the catalog (V2). Only an administrator's key can receive it; it cannot pin, assign or read the catalog |
| `cloud:read` | Read the owner's [cloud sessions](cloud-sessions.md), executions, transcripts, inputs and transfers, and the profiles the owner can use (`GET` only, V2) |
| `cloud:write` | `cloud:read`, plus creating and managing the owner's cloud sessions: pause, resume, delete, messages, interrupts, attachments and transfers (V2) |
| `admin:cloud` | `cloud:write` with an administrator's rights over every team cloud session and transfer, including `TeamCode-On-Behalf-Of` (V2). Only an administrator's key can receive it |

Inference also permits registering, heartbeating and ending the key's own [agent launches](agent-sessions.md); narrow lifecycle replies contain no usage totals. Another login cannot renew or dispatch under that launch ID. Cross-member/all-team registry reads and run policy changes remain interactive administration.

Every valid service key can read capabilities and its own authenticated member identity, and revoke itself through logout. Other endpoints require an interactive login unless explicitly added to this scope model. Service keys cannot administer users/accounts/policies/routes, change passwords, manage devices/sessions, inspect other members, or create/list/revoke other keys. Even a key owned by an administrator has only its selected service scopes; `admin:cloud` is the one scope that carries administrator rights, over cloud sessions only. Team-wide status/account/quota endpoints are not part of `usage:read`; use the owner's capacity endpoint. `whoami` reports the owner's member role, not additional rights for the key.

Keys share the member's existing daily ledger, weight, assigned pool, concurrency and model/effort policy. Creating, revoking or rotating keys does not add capacity or erase usage. Guidance and request identifiers remain member-scoped, rather than granting a separate quota/session namespace for each key. Dedicated CI members can have their own normal member policy if separate allocation is needed.

## Creation and automation

```sh
teamcode keys create --name github-actions --scopes inference,guidance --days 30 --json
teamcode keys list
teamcode keys revoke KEY_ID
```

Creation writes the one-time credential to stdout (JSON with `--json`); save it directly in an appropriate secret store. The accompanying notice goes to stderr. Subsequent list operations return only metadata. The dashboard/API likewise reveal the secret only in the successful creation response, with no-store headers; TeamCode does not log it or retain recoverable plaintext.

The default CLI scope is `inference`, with a 30-day lifetime. Lifetimes are 1–90 days and each member can retain at most 20 service-key records, including expired ones until removed. Permissions are immutable: create a replacement and revoke the old key to change scopes. Administrators can add `--member MEMBER_ID` to list/create commands.

On a GitHub Actions runner, use an isolated configuration directory and pass the service key through stdin:

```sh
export XDG_CONFIG_HOME="$RUNNER_TEMP/teamcode-config"
printf '%s\n' "$TEAMCODE_SERVICE_KEY" | teamcode configure --server https://teamcode.example.org
teamcode run claude -- -p "YOUR_TASK"
```

Use the actual arguments appropriate for the installed agent. `teamcode run codex -- ...` uses the same credential path. The key does not grant permission to bypass the client's own tool approval/sandbox rules. Keep the configuration private and remove it when the job ends. Inference-only keys can run without the optional guidance scope; server quota enforcement remains authoritative independently of guidance delivery.

## Revocation and persistence

Service keys use the shared token table, storing only a digest of a random `tcsk_` credential, expiry, name, owner and scopes. Authentication and admission validate current SQL state across replicas. Interactive session rotation has a separate 50-session limit and cannot evict unrelated CI keys. The device/session views exclude service keys; revoking a registered device does not remove independent CI keys.

An expired or explicitly revoked key cannot authenticate. The existing request monitor stops authenticated work on its next check when the original key is revoked or expires, without refunding uncertain provider work or replaying a stream. Password changes/resets, role changes and member disabling invalidate the member's keys together with other sessions. Logging out an interactive session alone does not revoke independent CI keys; logging out while using a service key revokes that key.

The additive Exposed migration makes `service_scopes` nullable. Existing session rows remain interactive; malformed non-null permissions fail closed. Domain authorization handles actions/scopes, transport code classifies known operations with an interactive-only default, and SQL stores revalidate principals. All replicas must run this version before relying on the service-key authorization contract.

## API

- `GET/POST /api/v1/service-keys`: list/create the current member's keys.
- `GET/POST /api/v1/members/{memberId}/service-keys`: another member requires administrator access.
- `DELETE /api/v1/service-keys/{id}`: revoke an owned key or, as an administrator, a same-team key.
- Creation accepts `name`, a nonempty list of the supported `scopes`, and `validDays`. It returns `metadata` and the one-time `accessToken`; list responses never include the token or digest.

Skill publication uses the dedicated `skills:publish` scope described in [skills](skills.md). Cloud orchestration uses the `cloud:*` scopes: an external orchestrator holding an `admin:cloud` key creates one session per task on behalf of a member and relays messages through its own backend, so no administrator credential reaches a device. A `cloud:read` or `cloud:write` key acts as its owner alone, even when the owner is an administrator. Cluster, profile, settings and access-policy administration, GitHub linking and every other administration stay interactive. Servers that accept these scopes advertise `cloud.keys.v1`.

## TeamClaude reference

TeamClaude's `src/server.js:resolveClientAuth` distinguishes operator/client keys and refuses selected configuration writes to client keys. Its `test/client-usage.test.js` verifies named client attribution. TeamCode keeps that separation but uses durable member identity, hashed expiring credentials, explicit scopes and shared SQL revocation/ledger state. It does not import TeamClaude's proxy operator/client keys or copy their permissions implicitly.
