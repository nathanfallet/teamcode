# Product plan

## Product

TeamCode provides a shared model gateway, web administration and a native local CLI. First provider path: Claude Code through a pool of Claude Max subscriptions. V1 also includes pooled Codex subscriptions. Anthropic/OpenAI cross-protocol translation is experimental, disabled by default behind the administrator setting `experimentalCrossProviderTranslation`, and is not a V1 release gate. Tested open-source model backends, centrally managed MCP/skills and API-managed Kubernetes cloud agent sessions are planned for [V2](roadmap.md#v2-kubernetes-cloud-agent-sessions). Capacity should complete useful work fairly; unused quota is not permission to invent tasks or spend money.

A deployment initially serves one team with multiple groups and roles. SaaS billing, resale and a hosted multi-tenant service are outside the initial scope. Provider authentication rights and account-sharing terms remain independent of the open-source software license. TeamClaude describes its own scope as one person's accounts; publishing TeamCode does not grant permission to share subscriptions. Document supported authentication arrangements per provider without claiming unverified access is officially supported.

## Native CLI

Target commands (not all implemented):

```text
teamcode login --server https://teamcode.example.org
teamcode whoami
teamcode accounts add --provider claude-max
teamcode accounts import --from teamclaude
teamcode accounts import --from teamclaude --config /path/to/config.json
teamcode accounts list
teamcode accounts reconnect <account-id>
teamcode proxy start
teamcode run claude --profile default
teamcode run codex --profile default
teamcode status
teamcode sessions list
teamcode doctor
teamcode logout
teamcode service install
teamcode service install --system
teamcode service start|stop|restart|status|logs|uninstall
```

### Two authentication lifecycles

Implemented identity-management commands include `teamcode whoami`, `teamcode devices list|rename|revoke` and `teamcode auth sessions list|revoke`. See [devices and authentication sessions](devices.md). These login sessions are distinct from the [implemented local agent-launch registry](agent-sessions.md) behind `teamcode sessions list`.

`login` asks for a username/password created by the team administrator, handles the required initial password change, and stores only a revocable session token. Persist the selected self-hosted server URL and TeamCode authentication under `$XDG_CONFIG_HOME/teamcode/`, falling back to `~/.config/teamcode/`. Ask for the server URL during first setup; subsequent invocations reuse the saved origin and authentication. Use an owner-only directory (0700) and configuration file (0600) on POSIX, and an equivalent owner-only DACL on Windows. Never store provider pool credentials or the user's password in this configuration.

`accounts add` signs into the provider in a local browser, binds the callback to state/PKCE as supported by that provider, and uploads the necessary authentication to a dedicated HTTPS server operation. The server verifies the account identity, encrypts credentials and becomes their refresh owner. No provider password is sent to TeamCode. The local proxy needs only a TeamCode credential after enrollment. Account enrollment requires an explicit permission, not merely membership.

`accounts import --from teamclaude` reads a selected local TeamClaude/TeamRouter config, with XDG discovery and explicit path override. Show account metadata without secrets. Select accounts and report created/updated/unchanged/failed outcomes individually. Leave source files unchanged. Never import proxy admin keys or assume routing policies are equivalent. Detect unsupported accounts rather than silently dropping them.

Add, import and reconnect perform a server-side upsert using verified canonical provider identity, scoped to the team. Claude identity includes the organization when authorizations differ; display names/emails or TeamClaude row IDs are not enough. Preserve TeamCode account ID, history, pool, permissions, limits, priority, custom name and disabled state when updating credentials. Enforce uniqueness, idempotent retries and versioned credential updates. An invalid/stale import cannot replace working newer credentials. Resolve ambiguous identities through reconnection, not name guessing.

Imports do not stop another program. Coordinate a refresh ownership handover before activation: two engines must not rotate the same refresh token. Imported account records remain pending until the operator confirms handover, from the CLI or the dashboard. An `accounts add` sign-in is TeamCode's own session and is active immediately. Accounts are shown as "Name (email)" when the provider reports an email, and by name alone otherwise. Do not mutate local Claude Code/Codex credential stores.

### Local proxy and service

Bind to loopback only and require a per-user capability even on localhost. Validate the destination and never implement an unrestricted forward proxy. `run` reuses an existing owned daemon and only changes its child's environment. Installing persistent integration is explicit and reversible.

Default service installation starts at user logon: launchd LaunchAgent on macOS, systemd user unit on Linux, scheduled logon task on Windows. System installation starts at boot before login: launchd LaunchDaemon, systemd system service (or explicit linger configuration), a Windows least-privilege boot-triggered task with password logon. It requires appropriate elevation, a service identity and secrets available without an unlocked personal keychain. Never copy personal credentials into a system account implicitly.

Track installed/running/authenticated/connected states separately. Retry network failures with backoff without opening a browser at boot. Keep logs bounded, use a stable absolute binary path, prevent duplicate daemons, drain streams during upgrades and support rollback. Uninstall only TeamCode-owned registrations/files and preserve credentials unless explicitly requested. A busy port is an error, not permission to kill another process. A daemon keeps transport available but never autonomously launches model tasks.

## Dashboard

- Overview: usable capacity, 5-hour/weekly windows, reset times, forecast confidence, waiting work and controller decisions.
- Provider accounts: CLI enrollment instructions, connection/refresh health, ownership, pools, permissions, pause/drain and reconnection status; no secret display.
- Team: administrator-created users, groups, roles, devices and CI credentials.
- Budgets: member/group/run limits, concurrency, protected reserves and [audited temporary daily-allocation overrides](budget-overrides.md).
- Model routing: requested aliases, actual targets, capabilities, explicit fallbacks and a why-this-route explanation.
- Sessions: activity metadata, directives, delivery/acknowledgment status; private content is not a team leaderboard.
- Personal account: own usage, reservations, device management, CLI integration and refusal explanations.
- Operations: audit history, versions, provider failures and dependency health.

Proposed first UI: server-rendered FreeMarker with targeted client-side updates and SSE charts. Backend API remains usable independently. Roles: owner, admin, group manager, member and service identity. [Owner/admin/member and scoped-manager authority](team-roles.md) is implemented; other planned dashboard/session work remains tracked separately. Team authentication uses administrator-created usernames and passwords, with no public registration, email infrastructure or mandatory external identity provider. First startup on an empty database creates `admin` and prints its temporary password once. It must be changed before other actions. Admin-created accounts follow the same temporary-password flow; password resets and role changes revoke sessions.

## Capacity and fairness

Provider percentage, internal fairness credits and monetary cost are different measures. Claude Max percentages have no contractual fixed conversion to tokens. Meter uncached input, cache reads/writes and output separately. Version credit weights and advertise uncertainty rather than exact subscription accounting.

Reserve budget before dispatch, enforce concurrency over the full stream, aggregate all devices of one member and bound per-member queues. Interactive work has priority over background work. Protected capacity requires accounts excluded from other members' eligible pools; a soft credit ledger alone cannot restore capacity spent earlier. Borrow from shared overflow, not promised future reserves. Direct provider use outside the proxy can still consume capacity.

## Predictive controller and agent messages

Forecast per account and per relevant 5-hour, weekly and model-family window. Use fresh observation timestamps, recent/longer smoothed rates, resets and uncertainty. Never estimate across a reset or stale gap. Unknown or zero measured speed is not infinite capacity. Model pool eligibility and staggered resets instead of adding percentages across heterogeneous subscriptions.

Regimes: available, normal, slow down, finish cleanly, unknown. Increase allowed concurrency only with fresh headroom, policy permission and useful queued work. Under pressure reduce new background work first. Keep a bounded allowance for checkpointing and refuse new work after exhaustion. Use hysteresis, cooldown and gradual changes. First deploy the controller in observation mode and compare forecasts with reality; no LLM call is needed to make every control decision.

Send versioned, deduplicated, expiring directives scoped to a member/run/session. Distinguish received by local bridge, injected at a model boundary and confirmed by the orchestrator. Reconnects resume durable events. The proxy's hard limits remain authoritative if an agent ignores advice.

Claude Code hooks inject changed advice at SessionStart/PostToolUse boundaries. Avoid Stop hooks that repeatedly wake the model. A local MCP channel can provide push when supported; channels are a version-dependent preview, and ordinary MCP does not imply push. Codex/orchestrator integration requires its own demonstrated mechanism, not a fabricated universal inbox. A local service is not itself an agent session.
