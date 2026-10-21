# TeamCode

An open-source, self-hosted model gateway for teams: pooled provider accounts, fair access, predictive capacity management, a web dashboard, and a native Kotlin Multiplatform CLI.

**Initial release: 0.1.0.** Claude Max and Codex subscription inference, native account import/proxy, team administration, weighted daily budgets, model/effort policies and capacity guidance are implemented. Real Claude Code → Haiku and Codex CLI → GPT-6 Luna acceptance runs have passed through the native proxy and central server. Explicit native model routes and macOS/Linux user service installation are implemented. Cross-provider translation is experimental, disabled by default and outside V1 release acceptance; explicit startup before login and signed native distribution are included. Additional workstation/load/operational validation moves to V2 under the owner decision; see the [handoff](docs/v2-validation-handoff.md). Native Windows runtime and user service acceptance have passed. See [implementation status](docs/status.md) before using a command from the [product roadmap](docs/product.md).

## Install the native CLI

The release pipeline includes [one-command installers for macOS, Linux and Windows](docs/bootstrap.md). Install the latest stable release from its published release assets:

```sh
curl -fsSL https://github.com/nathanfallet/teamcode/releases/latest/download/install.sh | sh
```

```powershell
& ([scriptblock]::Create((Invoke-WebRequest -UseBasicParsing 'https://github.com/nathanfallet/teamcode/releases/latest/download/install.ps1').Content))
```

The script is pinned to the release it comes from, so every file it downloads matches that exact version. To install a specific version, replace `releases/latest/download/` with `releases/download/<version>/`. No JVM is required. The installer makes `teamcode` available by name (a `~/.local/bin` link on macOS/Linux, your user PATH on Windows) and preserves login and personal agent configuration. See the guide for prerequisites, custom roots, offline installation and current platform evidence.

## Run the team server

Use JDK 21 and MySQL 8.4. The server applies Flyway migrations at startup:

```sh
./gradlew :app:buildFatJar :cli:linkDebugExecutableMacosArm64
export TEAMCODE_DATABASE_URL=jdbc:mysql://localhost:3306/teamcode
export TEAMCODE_DATABASE_USER=teamcode
# Set TEAMCODE_DATABASE_PASSWORD from your secret store.
# Generate once, store securely, and reuse across restarts and restores:
export TEAMCODE_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export TEAMCODE_PUBLIC_URL=https://teamcode.example.org
java -jar app/build/libs/app-all.jar
```

Put an HTTPS reverse proxy in front of port 8080, or use `http://localhost:8080` as the public URL for local development. `TEAMCODE_HOST` defaults to loopback. The public URL must match the browser origin for form protection.

On an empty database, TeamCode creates owner account `admin` and prints its random temporary password **once** to the bootstrap log. Sign in at `/dashboard` and change it. Restarting does not print or reset it. There is no public registration, email setup or OAuth requirement for team membership. Administrators create users and receive a one-time temporary password to share privately. Password changes and resets revoke every existing session; disabling or changing a member's role also revokes their sessions. The [last ready owner](docs/team-roles.md) cannot be disabled, demoted or have their password reset; administrators cannot grant ownership or change an owner account. Administrators can also create [groups with cumulative budgets and scoped managers](docs/groups.md); managers can pause/resume admission within their groups without changing global roles or quotas.

```sh
teamcode login --server https://teamcode.example.org
teamcode accounts add --provider claude
teamcode accounts add --provider codex
teamcode accounts import --from teamclaude --dry-run
teamcode accounts import --from teamclaude
teamcode accounts list
teamcode accounts handover ACCOUNT_ID --confirm
teamcode logout
```

The CLI prompts for username/password, handles required password changes, and saves only the server URL and revocable session token. Provider sign-in uses a local PKCE flow: the CLI opens the sign-in link in your default browser (it only prints it over SSH, without a display, or with `--no-browser`); paste the complete `code#state` (Claude) or final `http://localhost:1455/auth/callback?...` address (Codex) into the hidden prompt. Codex uses its registered redirect address; no local callback listener is started, so paste the address even when the browser reports that the page cannot load. Credentials are sent to TeamCode, where the Claude profile/Max subscription or Codex authenticated subscription identity is checked before encrypted storage. Import reads TeamClaude/TeamRouter's XDG configuration or `--config PATH` and never modifies the source. Existing accounts keep their ID, custom name, disabled state and priority. An account added this way is active at once; imported accounts wait for a handover. Stop any other credential refresher before explicit `handover --confirm`, the dashboard Handover button, or import `--take-ownership`. Reconnect with `accounts add --provider claude` or `accounts add --provider codex`; verified enrollment updates an existing account. The Codex login flow has synthetic protocol tests; a live browser-consent acceptance run remains pending. For a temporary trial while TeamClaude still owns refresh, import with `--access-only`: TeamCode uses the current access tokens and refuses to refresh them. Reimport current credentials when they expire.

## Service and CI credentials

Create a scoped, expiring key from **Dashboard → Service keys** or `teamcode keys create --name ci --scopes inference,guidance --days 30`. The secret is shown once; store it in your CI secret manager and use `teamcode configure --server ...` with the credential on stdin. Keys share the owner's quotas and cannot administer the server, even when the owner is an administrator. Use `teamcode keys list` and `teamcode keys revoke KEY_ID` from an interactive login. See [permissions, API and automation setup](docs/service-keys.md). Current native launchers also register [agent sessions](docs/agent-sessions.md): inspect them with `teamcode sessions list --json` or **Dashboard → Sessions**, including run budgets and gateway activity.

## HTTP API reference

Every server documents its own API. Open `/api/docs` (for example `https://teamcode.example.org/api/docs`) for an interactive Swagger UI, or fetch the OpenAPI 3.1 document at `/api/openapi.json` to generate a client. Both are public and contain no team data; the document gives the API version, not the server build (that stays behind `GET /api/v1/capabilities`). The document lists the management routes under `/api/v1`, the `/v1/messages` and `/v1/responses` inference endpoints and the `/mcp/<server>` gateway, with their bodies, responses and errors (`{"error": {"type", "message"}}` under `/api/v1`; the inference endpoints use their provider's error format and the MCP gateway JSON-RPC errors). Each operation states who may call it: an interactive login, and which service-key scopes (such as `skills:publish` or `cloud:read`) are accepted. Click **Authorize** in Swagger UI and paste a session or service-key token to try a request. The document is generated from the running server's routes, so it only lists the features that server enables, and it is never committed; dashboard pages and the cloud runner channel are not part of it. A new route needs its description in `presentation/.../docs`: `ApiDocsTest` fails otherwise.

## Devices and authentication sessions

Use the dashboard's **Devices** page to see your CLI installations and login sessions. Administrators can manage a member's devices from **Team**. Register a friendly label with `teamcode login --device "Developer workstation"`; reconnecting preserves a dashboard-edited name.

```sh
teamcode whoami
teamcode devices list
teamcode auth sessions list
teamcode devices rename DEVICE_ID --name "Support laptop"
teamcode auth sessions revoke SESSION_ID
teamcode devices revoke DEVICE_ID
```

Revoking a device signs out its sessions across replicas. Usage remains shared by member; adding or reconnecting devices does not reset allowances. The non-secret installation identity survives logout, while the credential file is removed. See [device lifecycle and authorization](docs/devices.md). Authentication sessions are separate from the [agent-run registry](docs/agent-sessions.md).

## Kubernetes

The [Helm chart](helm/teamcode/README.md) follows the `pkg` chart conventions, with two stateless server replicas, streaming ingress settings, a persistent optional MySQL 8.4 instance, external database support, HPA, probes and draining. All replicas share database-backed sessions, quotas, refresh ownership and worker leases; see [replica behavior](docs/replicas.md). Redis and sticky sessions are not required. The image workflow publishes `nathanfallet/teamcode:latest` and immutable commit tags after verification.

## Model routes

Administrators can configure ordered model replacements and fallbacks in **Dashboard → Routes**, such as `claude-opus-*` to `anthropic` then `codex:gpt-6-astra`. Requests without a matching rule retain their native provider. Cross-provider translation is **experimental and disabled by default**; administrators can enable it in **Dashboard → Models** using `experimentalCrossProviderTranslation`. While disabled, targets requiring conversion are skipped; native account/model fallback remains available. Both requested and resolved models must pass the team's policy. See [routing and compatibility](docs/routing.md) for the supported translation profile, bounded retries and limitations; a cross-provider route does not imply every agent tool or reasoning format is portable.

## Local service

After login, `teamcode service install` starts a local proxy automatically at user login on macOS, Linux and Windows. `teamcode run claude -- …` and `teamcode run codex -- …` reuse the authenticated service when available. Use `service status`, `service logs`, `service stop`, `service start`, `service restart` or `service uninstall` to manage it. Installation requires a stable native binary path and preserves your saved login on uninstall. See [service behavior and verification](docs/services.md); Native Windows Task Scheduler acceptance has passed; `teamcode service install --at-boot` (`--system`) enables startup before login with the OS authorization described in that guide. Extra real-workstation reboot/logon checks are V2 follow-up. See the [Windows workstation guide](docs/windows.md).

## Native updates

Managed installations support signed `teamcode update` and offline `teamcode update --rollback`. Automatic installation is off by default; opt in with `teamcode update --automatic on`, inspect it with `--automatic status`, or disable it with `--automatic off`. A separate current-user job uses the existing installer and defers activation while an agent holds its activity lease. Notifications are configured independently with `--notifications on/off/status`. See [automatic behavior and platform evidence](docs/automatic-updates.md). Versioned publication is gated by the release workflow.

## Try the transport fixture

Build with JDK 21 using the checked-in Gradle wrapper:

```sh
./gradlew :app:buildFatJar :cli:linkDebugExecutableMacosArm64
export TEAMCODE_MODE=fixture
# Generate a private random value; do not use the examples as production credentials.
export TEAMCODE_ACCESS_TOKEN="$(openssl rand -hex 32)"
java -jar app/build/libs/app-all.jar
```

In another terminal, with the same test access token available:

```sh
printf '%s\n' "$TEAMCODE_ACCESS_TOKEN" | \
  ./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe configure --server http://127.0.0.1:8080
./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe status
export TEAMCODE_LOCAL_TOKEN="$(openssl rand -hex 32)"
./cli/build/bin/macosArm64/debugExecutable/teamcode.kexe proxy start
```

The CLI saves the self-hosted server URL and authentication in `$XDG_CONFIG_HOME/teamcode/config.json`, falling back to `~/.config/teamcode/config.json` (directory 0700/file 0600 on POSIX). Other invocations reuse it. Omit `--server` to be prompted for the URL. `configure` is a development helper for an existing test token. Normal installations use `login`. Do not enter secrets into shell history or command arguments.

The proxy accepts authenticated JSON `POST /v1/messages` and `POST /v1/responses` on `127.0.0.1:8789`. In server mode it routes to eligible imported Claude Max or Codex subscription accounts; in fixture mode it returns synthetic events. Point Claude Code at this loopback origin with `ANTHROPIC_BASE_URL`, with the local proxy token either in the `x-teamcode-local-token` header (`ANTHROPIC_CUSTOM_HEADERS`, which keeps a claude.ai login) or as `ANTHROPIC_AUTH_TOKEN`. Host/port environment variables for the central fixture: `TEAMCODE_HOST` (default loopback) and `TEAMCODE_PORT` (default 8080).

## Verification

See the [end-to-end testing playbook](docs/testing.md) for native, real-account/client, replica, hook, service and signed-distribution procedures, expected assertions and evidence limits.

```sh
python3 scripts/check-module-graph.py
./gradlew :api:jvmTest :client:jvmTest :domain:test :infrastructure:test :presentation:test :cli:jvmTest
python3 scripts/smoke-native.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar
```

The smoke test starts and stops only its own processes, uses an isolated configuration directory and synthetic credentials, and checks private file permissions, authentication and progressive SSE. Native targets are declared for desktop macOS/Linux/Windows. Native runtime checks pass on macOS, Linux and Windows, including configuration, TLS, streaming, agent launchers and user services. MySQL integration tests require Docker. `scripts/smoke-team.py` verifies the team server with its own isolated MySQL and the native CLI. Ordinary CI runs JVM/replica tests, Helm checks and Docker on Linux. Native transport/service and signed installation checks across macOS/Linux/Windows run only on version tags or explicit manual full validation; see [distribution and CI controls](docs/distribution.md). CI does not use provider credentials. Each native platform uploads its development binary only after its acceptance tests pass.

## Architecture

```text
cli -> client -> api
app -> presentation -> domain
app -> infrastructure -> domain
presentation -> api
```

Seven modules, root package `me.nathanfallet.teamcode`. The CLI is Kotlin/Native, not a JVM wrapper. The server is Ktor/JVM. No private dependencies or ControlResell services are required.

Read [architecture](docs/architecture.md), [product](docs/product.md), [roadmap](docs/roadmap.md) and [contributing](CONTRIBUTING.md). Original code is Apache-2.0.

## Capacity and model policies

Interactive administrators can inspect **Dashboard → Audit** or `GET /api/v1/audit` for paginated administrative and authentication metadata. Service/CI keys cannot read this journal. See [audit coverage and API pagination](docs/audit.md).

The dashboard exposes member weights, optional per-member concurrency limits (unlimited by default) and hard daily caps, pool budgets, observed provider quota windows, model/effort restrictions and current agent guidance. Daily credits are an internal team budget, not a conversion of Max subscription percentages. With weights 5/5/1 and a budget of 1,100 credits, members receive 500/500/100; changing weights does not erase spend. Reservations and actual usage survive restarts. Administrators can grant [temporary daily allocations](docs/budget-overrides.md) with reasons, expiry and retained history; these never reset spend or bypass pool/group/run limits. Separate pool identifiers isolate accounts for protected members. Administrators can [review unknown usage](docs/usage-reconciliation.md) and reconcile it from request-specific provider evidence; lease expiry never refunds a charge.

Default model policy permits `claude-*` and `gpt-*` (existing saved policies are not broadened) and denies `*fable*`. Administrators can set allowed/denied patterns and reasoning efforts in `/dashboard/models`. Requests are rejected before dispatch when a policy is violated. No effort is silently replaced.

`teamcode run claude -- ...` automatically adds temporary HTTP hooks for scoped, deduplicated guidance without editing personal settings. Use `--no-guidance` before `--` to opt out. `teamcode agents watch --session SESSION_ID` prints scoped updates; `teamcode agents hint --session SESSION_ID --format claude` supports manual bridges. Receipts record bridge emission, not model compliance. `teamcode run codex -- ...` adds native command hooks for the same events; review and trust them in Codex `/hooks` before first use. Existing hook files are preserved and explicit CLI hook overrides remain authoritative. See [agent guidance](docs/agent-guidance.md) and [capacity behavior](docs/capacity.md).

Real-provider acceptance is explicitly opt-in and never runs in CI:

```sh
python3 scripts/acceptance-provider.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --jar app/build/libs/app-all.jar \
  --source /path/to/teamclaude.json
```

It starts its own MySQL/server/proxy, imports in access-only mode, verifies a repeated import, asks an isolated real Claude Code process for one short Haiku response, checks metadata-only server logs and destroys its own test database. It never edits the source config or refreshes provider credentials.

## Claude Code through the local proxy

After signing in to TeamCode and importing/activating an eligible Claude Max account:

```sh
teamcode run claude -- --dangerously-skip-permissions
teamcode run claude -- --model claude-haiku-4-5-20251001 -p "Hello"
teamcode run claude --workload background -- -p "Review the test coverage"
```

`run` reuses a matching authenticated TeamCode service when available; otherwise it starts a dedicated proxy on a free loopback port. It launches the installed agent from your PATH, forwards the arguments after `--` literally and preserves your working directory and terminal. The dedicated proxy closes when the agent exits; a shared service stays running. The agent's exit status is returned. URL/session settings come from your saved TeamCode configuration; personal agent configuration files are not edited. Each dedicated proxy gets its own local capability. Native launcher and user-service acceptance has passed on macOS/Linux/Windows; see [status](docs/status.md) for feature-specific evidence.

Use `--workload background` before `--` for unattended work, or `--workload interactive` (the default) for interactive sessions. This is a scheduling hint for the [shared admission queue](docs/capacity.md#shared-admission-queue); it does not detach the process, change permissions or bypass quotas. Each launch keeps its own class even when sharing the local service. An older service without workload support is left running while the launcher uses a dedicated proxy for the background session.

For a manually managed proxy:

```sh
export TEAMCODE_LOCAL_TOKEN="$(openssl rand -hex 32)"
teamcode proxy start
# In another terminal with the same TEAMCODE_LOCAL_TOKEN, Claude Code logged in with claude.ai:
ANTHROPIC_BASE_URL=http://127.0.0.1:8789 \
ANTHROPIC_CUSTOM_HEADERS="x-teamcode-local-token: $TEAMCODE_LOCAL_TOKEN" \
claude --model claude-haiku-4-5-20251001
# Claude Code without its own login:
ANTHROPIC_BASE_URL=http://127.0.0.1:8789 \
ANTHROPIC_AUTH_TOKEN="$TEAMCODE_LOCAL_TOKEN" \
claude --model claude-haiku-4-5-20251001
```

Remove conflicting `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` or `CLAUDE_CODE_OAUTH_TOKEN` environment overrides before using this manual setup. When Claude Code is logged in with a claude.ai subscription, keep that login and send the local capability in the `x-teamcode-local-token` header, as `teamcode run claude` does: any environment credential puts Claude Code in API mode, which disables your organization's claude.ai connectors. The proxy ignores the login Claude Code sends and never forwards it. Without a login, use `ANTHROPIC_AUTH_TOKEN`, not `ANTHROPIC_API_KEY`, which an interactive session asks about before use; a refused key answers `401 A local TeamCode capability is required.` The local capability protects the loopback proxy; the proxy uses your saved TeamCode session to reach the team server. Provider credentials remain on the server. The requested model and effort must satisfy your team's policy. Native Messages fields and tool definitions pass through unchanged.

## Codex through the local proxy

```sh
teamcode run codex --
teamcode run codex -- exec --model gpt-6-luna "Explain this repository"
teamcode run codex --workload background -- exec "Review the test coverage"
```

The launcher supplies the custom TeamCode provider for this invocation, using your saved server configuration and a temporary loopback capability. All remaining CLI arguments are passed through. An explicit client-side provider override can override these launch settings; server policy applies only to requests sent through TeamCode.

For a manually managed proxy, use a custom provider pointed at `http://127.0.0.1:8789/v1`, with `wire_api = "responses"` and `env_key = "TEAMCODE_LOCAL_TOKEN"`. Select `model_provider = "teamcode"` and an explicit model available on the imported account. These settings can be passed per invocation without editing personal configuration. The [official custom provider documentation](https://learn.chatgpt.com/docs/config-file/config-advanced#custom-model-providers) describes the client configuration.

The subscription adapter uses ChatGPT's Codex endpoint, never the metered OpenAI API. It requires streamed requests with complete conversation input; provider-managed previous-response/thread state is currently rejected. Tool definitions and native Responses fields are preserved. Usage accounting includes cached input and reasoning output exactly once.

To exercise the actual Codex CLI instead of Claude in the opt-in acceptance script, add `--skip-claude --codex-model gpt-6-luna`. Model availability depends on the subscription. Native Codex-to-Codex transport is verified; Claude-to-Codex translation requires the experimental settings flag and the documented compatible profile.

Native `install`, `update --check`, managed `update` and offline `update --rollback` are implemented, with signed metadata, an immutable launcher and service-aware switching. Managed services/agent launches check signed release metadata in the background with a shared daily cache; use `teamcode update --notifications off` to disable notifications. Automatic installation is opt-in with `teamcode update --automatic on`. The versioned workflow gates publication on five-target installation/bootstrap/update checks; extended real-workstation validation is tracked for V2. See [native distribution, commands and remaining gates](docs/distribution.md).
