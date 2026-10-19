# Implementation status

## J0: first verified transport slice

Implemented:

- Seven standalone Gradle modules with `me.nathanfallet.teamcode` package/group and enforced production dependency boundaries.
- KMP API contracts and typed client; authenticated capabilities/status endpoints and bounded streaming transport.
- Explicit development-only fixture mode. It makes no provider requests and does not pretend to be a production account pool.
- Native CLI commands: `version`, `configure`, `status`, `proxy start`.
- Configurable self-hosted server origin; saved URL/authentication in XDG or `~/.config/teamcode/config.json`; atomic private POSIX writes.
- Authenticated loopback-only Messages proxy, request size limit, narrow header forwarding, streaming response and coroutine cancellation.
- Pure server-side forecast and in-memory allowance reference with concurrency/idempotency tests. The allowance reference is not wired into production admission and is not durable.
- Independent JVM/native CI and synthetic smoke tests.

Local evidence (2026-10-18): 25 JVM tests passed; macOS ARM64 native binary linked; actual native CLI -> local proxy -> JVM fixture returned progressive SSE; saved configuration permissions were 0700/0600. Tests used random synthetic credentials and isolated config. No real provider was called. Linux x64 also links locally; actual Linux runtime validation is delegated to CI.

Still planned, not advertised as working: real Claude Max pooling, durable quotas, predictive controller/agent integration, model translation, OS service installation and releases for all platforms. Windows private config is fail-closed until its DACL implementation is verified. See [roadmap](roadmap.md).

## Account enrollment groundwork

Implemented as independently tested building blocks, not yet an enabled enrollment API or CLI command:

- Bounded TeamClaude OAuth account parser with per-entry unsupported/invalid results; no proxy keys or source identity hints imported.
- Credential redaction and account+organization identity types.
- A credential merge use case preserving TeamCode identity, custom name, disabled state, pool, priority and handover status; stale versions are rejected.
- AES-256-GCM credential envelopes with random nonces and authenticated account/team context; wrong key/context or malformed ciphertext fails without exposing the input.
- SDK connection/idle timeouts, management deadlines and a regression test keeping upstream response lifetimes open until downstream streaming completes.

The database adapter, server-side provider identity verification and add/import/reconnect commands must land before this becomes actual account enrollment. In-memory records are not presented as durable team state.

## J1: persistent team and enrollment slice

Implemented:

- MySQL 8.4 / HikariCP / Flyway persistence, bounded transactional repositories, encrypted provider credentials.
- One-time first-run `admin` creation; temporary bootstrap password logged only by the creating startup. No public registration or email infrastructure.
- PBKDF2 password hashing, forced temporary-password change, expiring hashed session tokens, per-account login lockout and a bounded login rate.
- Admin-created members, role/disable controls, password reset, session revocation and last-active-admin protection.
- Server-rendered dashboard with same-origin form checks, CSRF tokens, HttpOnly/SameSite cookies, no-store responses and escaped content.
- Native `login` / `logout`, hidden password prompts and saved server/session configuration.
- `accounts add` local manual PKCE flow, `accounts import --from teamclaude [--config PATH] [--dry-run]`, `accounts list`, and explicit `accounts handover ID --confirm`.
- Server-side Claude profile/Max verification, identity-based upsert, version conflict checks, import operation idempotency, encrypted credentials, preserved operator metadata, and pending handover by default.

Verification: MySQL concurrency tests prove one bootstrap, persistent sessions, revocation, last-admin protection, concurrent enrollment conflict behavior, metadata preservation, encrypted storage and team isolation. HTTP tests cover authorization, temporary-password restrictions, absent registration, CSRF/origin enforcement and cookie/API separation. Provider tests use a mock engine; no real subscription has been connected in development or CI. macOS ARM64 CLI links with the account commands.

Still needed for J1 exit: actual Max inference and provider usage display, plus an opt-in real-provider acceptance run. The ordinary server currently exposes the synthetic gateway fixture, clearly reported as fixture mode, while the persistent management plane is available. OAuth code exchange is implemented against the protocol observed in TeamClaude; it has not been exercised against a real account.

End-to-end evidence (2026-10-18): `scripts/smoke-team.py` passed against its own MySQL 8.4 container, the packaged server and the macOS ARM64 native CLI. It verified one-time bootstrap, password replacement, dashboard member creation, password restrictions, persistent sessions after server restart, native logout/revocation and absence of ordinary passwords in logs. The test exposed and fixed a server startup coroutine deadlock that unit tests did not cover.

## J2–J4: Claude pool, weighted admission, policy and pacing slice

Implemented:

- Real streaming Claude Max transport, bounded provider requests, metadata-only request/settlement logs and usage settlement. Native request fields pass through without conversion.
- Atomic SQL reservations across member and pool daily budgets; weighted allocation, hard caps, concurrent-request limits and separate protected account pools. Conservative charging for interrupted/missing-usage requests; no replay after dispatch.
- Serialized refresh with a database row lock; interrupted refresh requires reconnect. Explicit access-only activation never invokes refresh, including at expiry.
- Provider usage sampling plus response-header observations, per-window reset-aware histories, forecasts and expiring member directives. Server admission applies guidance constraints.
- Model and effort policy editor/API, Fable denied by default, thinking-budget cap and rejection before provider dispatch.
- Dashboard capacity, pool budgets, model restrictions and guidance; native guidance watch/hook output with delivery receipts.

Real-provider evidence (2026-10-18): the native CLI imported five verified Claude Max accounts from an authorized TeamClaude configuration in access-only mode. Reimport preserved account IDs and advanced credential versions. A real Claude Code 2.1.302 process, isolated with bare mode/tools disabled, called `claude-haiku-4-5-20251001` through the native loopback proxy and central packaged server. Provider HTTP 200, expected short response, and 305 settled team credits were verified. Source configuration SHA-256 was unchanged; no provider refresh ran. The script removed its own test database and processes. The Codex and expired source entries were explicitly skipped, with nonzero partial-import exit status.

Local deterministic verification: JVM domain/infrastructure/presentation/CLI suites and native macOS ARM64 link passed. SQL tests include competing admissions, pool isolation, weighted 5/5/1 allocation, retained spend after policy edits, exactly-once settlement and concurrent refresh. Gateway tests include streaming usage, cancellation without replay and sanitized upstream errors.

Still outstanding: bounded pre-stream failover, session affinity, forecast charts/accuracy validation, sparse per-session hook delivery, Codex/OSS adapters, cross-protocol tool loops, service installation and Windows private configuration. These are not implied by the successful Haiku smoke test. The earlier J1 fixture-only limitation is superseded by this slice.

## Native Codex subscription slice

Implemented: Codex entries in native TeamClaude import/upsert, remote account/user verification against authenticated usage, isolated Codex refresh/usage/Responses transport, provider-filtered durable admission, native `/v1/responses` proxy and Responses usage settlement. Default unsaved model policies now include `gpt-*`; saved administrator policies are unchanged. Provider-native request bodies keep tool definitions and protocol-specific fields. Direct Codex OAuth `accounts add` and cross-provider translation are still pending.

Real-provider evidence (2026-10-18): six source accounts imported successfully (five Claude Max and one Codex; one expired entry rejected). The actual Codex CLI 0.163.0, using a temporary custom provider configuration, completed a short `gpt-6-luna` request through the native proxy and central subscription pool. HTTP 200, `response.completed`, expected reply and 18,001 settled team credits were observed. The live test exposed that the Codex subscription endpoint can omit Content-Type on a valid SSE response; this case is now covered by a deterministic regression test. No source credentials were refreshed.

Deterministic tests cover remote identity mismatch, provider credential isolation, unchanged tool definitions, required store=false, no double counting of cache/reasoning usage, distinct model/window quota scopes and provider-specific SQL account selection. The prior Claude pool commit `688a83f` passed JVM, native macOS and native Linux CI. Local MySQL tests were moved to ephemeral tmpfs after Docker disk exhaustion; all affected suites passed again without deleting unrelated data or stopping other services.

## Replica coordination, deployment and native launchers

Implemented:

- Database-backed login throttling, worker leases and polling order, reservation counts and completed-request totals. Database time governs durable leases, session expiry and budget windows. Local caches do not own team state.
- Crash-safe refresh intent committed before the provider call, versioned credential replacement and mandatory reconnect after uncertain rotation. Refresh no longer holds a database connection during a network request. This supersedes the earlier lock-across-refresh implementation.
- Public database-aware `/ready`, separate `/health`, HikariCP pools of eight connections per replica, bounded JDBC waits and configurable graceful drain.
- Helm chart with two application replicas, optional official MySQL 8.4 StatefulSet, external MySQL support, HPA, streaming ingress, persistent secrets and non-root containers. CI gates the AMD64/ARM64 Docker Hub image on JVM, native and Helm checks. The chart uses `nathanfallet/teamcode:latest` with `Always`; publishing does not itself restart pods.
- Ktor 3.6.0 with the universal `ktor-client-engine-defaults` dependency for native clients.
- Native `teamcode run claude -- <args>` and `teamcode run codex -- <args>` on macOS/Linux: ephemeral authenticated local proxy, literal arguments, inherited terminal/cwd, per-child environment, child exit status and owned-process cleanup. No personal agent configuration is rewritten.

Local verification (2026-10-18): concurrent MySQL integration tests passed for shared login budgets, one-time bootstrap, competing reservations, exactly-once settlement, credential rotation/import fencing and worker lease ownership. Two actual server processes passed `smoke-replicas.py`, including simultaneous startup, session sharing, process crash/restart, database outage/readiness recovery and cross-replica logout. JVM suites, native macOS ARM64 link/runtime/progressive-stream tests and Linux x64 cross-link passed. Helm render/lint/contract/schema checks passed for bundled and external database modes. No Kubernetes cluster was deployed.

The native launcher smoke verifies literal arguments (including shell metacharacters), stdin/stdout/stderr, concurrent proxies, exit/signal status and unchanged configuration. A real Claude Code process through `teamcode run` completed the tiny Haiku acceptance with 305 settled credits. A real Codex 0.163.0 process through `teamcode run` completed the tiny `gpt-6-luna` acceptance with HTTP 200, `response.completed` and 18,001 settled credits. Seven verified source accounts were imported and reimported without changing their IDs; source credentials were never refreshed. The test caught root-level Codex configuration being ignored by `exec --ignore-user-config`; launch overrides now follow user arguments in the leaf command scope, before a literal `--` delimiter. Native Windows process/config support, OS service installation, cross-provider routing/translation and releases are still outstanding.

## Explicit routes and initial cross-provider tool profile

Implemented: team-scoped versioned SQL route rules, administrator API/dashboard CRUD, typed client methods, bounded glob matching, requested/resolved model and effort checks, and provider-specific admission. Ordered fallback handles unavailable capacity, unusable credentials before dispatch and definite 401/403/429 rejection. It tries the preferred target's eligible accounts before the next target, with at most 64 distinct accounts and a ten-minute overall deadline. Exhausted eligible capacity returns 429; reaching the account bound returns 503 without claiming exhaustion. Member/team limits remain enforced. Ambiguous failures and committed streams are not replayed. Native fields/streams bypass conversion; cross-protocol requests use explicit conversation and SSE adapters.

Failover regression evidence: six rejected accounts followed by a seventh available account produce exactly one successful client response, without switching providers early. The 64-account safety bound returns 503. A real MySQL test with two independent connection pools verifies that zero-charge rejections do not trigger pacing and that excluded accounts cannot be selected again. Domain, infrastructure and presentation suites passed after these changes.

Local evidence (2026-10-18): the full JVM suites, macOS ARM64 native link and actual native transport smoke passed. SQL tests verify cross-replica route persistence, isolation and stale-version conflicts. HTTP tests verify route authorization and browser CSRF. Codec/gateway tests cover multiple tools, empty arguments, split UTF-8, usage/cache accounting, missing terminal usage, downstream cancellation, unsupported opaque content, preserved native fields, forbidden destinations, definite rejection and ambiguous failures.

Opt-in real-provider tool acceptance passed through the native proxy in both directions: Messages/Haiku → Codex GPT-6 Luna (169 and 121 settled credits) and Responses/Luna → Claude Haiku (two successful streamed turns). Each test received a `sum` call with exact JSON arguments, returned its result with the original call ID, and verified the final calculated answer. A real Anthropic HTTP 429 also exercised account cooldown, zero-charge settlement and fallback to another account. Source TeamClaude credentials were imported access-only and never refreshed or changed.

These tool-loop acceptances explicitly disable reasoning and permit the `none` effort in their isolated team policy. Earlier default-reasoning attempts correctly exposed an incompatible opaque reasoning item. Signed/encrypted reasoning replay, custom/built-in tool conversion, session/account affinity and a complete default-Claude-Code/default-Codex cross-provider coding task remain outstanding. Do not infer their compatibility from this initial profile. Codex subscription `response.metadata` events are now handled as transport metadata, with a deterministic regression test.

Deployment evidence: commit `64a5484` passed JVM, Helm and native macOS/Linux CI, then published the AMD64/ARM64 Docker Hub image. The `latest` manifest was verified directly; the chart defaults to that image with `Always`. No Kubernetes deployment was performed. The separately committed V2 roadmap covers centralized MCP/skill administration and is outside current V1 implementation scope.

## Native user service slice

Implemented macOS LaunchAgent and Linux systemd user registration, idempotent install/start/stop/restart/uninstall, manifest rendering, bounded logs, separate status checks, private local capabilities and authenticated reuse by `teamcode run`. Saved team tokens are reloaded per request; upstream outages keep the listener alive. Shutdown is bounded and waits for accepted streams and the old process before restart. Externally edited registrations are preserved.

Local evidence (2026-10-18): JVM CLI tests passed. The actual macOS ARM64 binary passed `smoke-service.py` with launchd, synthetic upstream, login replacement, offline recovery, native child reuse, stop during an active stream, restart and uninstall. This exposed Ktor Native replacing the service's signal handler at startup; the handler is now installed after startup, allowing both stream draining and exit of the outer command loop. Test registrations were isolated and removed. The same native service acceptance passed on Linux with its actual systemd user manager in [CI run 39173156701](https://github.com/nathanfallet/teamcode/actions/runs/39173156701), alongside native transport, agent-launch and persistent team tests. Windows, system installation before login, automated updates and actual reboot/logon acceptance remain outstanding.

## Windows native support (in progress, required for V1)

Windows is a V1 requirement for team members using Windows workstations. The private-file, secure-random and process-launch stubs have been replaced with Windows adapters: protected owner-only DACLs and atomic file replacement, BCrypt random tokens, Unicode configuration/environment paths and CreateProcess with explicitly inherited standard streams. Standard Claude/Codex npm installations launch their JavaScript entry point through Node directly, preserving literal arguments without interpreting a `.cmd` shell script.

The Windows executable cross-links locally and JVM argument-encoding tests pass. A dedicated Windows CI job builds and executes the native binary, inspects actual file/directory ACLs and exercises authentication, progressive streaming and both agent launchers with synthetic inputs. Its first execution failed during configuration, so runtime support is not yet accepted. Configuration failures now report sanitized operation identifiers to distinguish upstream verification from private-file stages without exposing raw exceptions or credentials.

Windows logon service registration is implemented using Task Scheduler, an interactive least-privilege principal, no Windows password and a private cooperative stop marker. Stop disables the task, drains accepted streams and waits for its running instances to exit; start re-enables logon execution. Unicode arguments are read through the Windows wide-character API. The expanded Windows smoke includes actual service registration, reuse, drainage, restart and uninstall; these Windows runtime checks remain pending. macOS service regression and JVM tests passed after adding this platform branch.

Windows diagnosis: the native configuration failure returned `ERROR_PATH_NOT_FOUND` for a Unicode path because Okio 3.17.0 still used ANSI file APIs. Okio is now pinned to 3.18.2, whose Windows implementation uses wide-character APIs for directory creation, metadata and file access. The Windows smoke also covers login with a forced password change, trusted/untrusted HTTPS and descendant cleanup. Launchers use a private Windows job assigned before the child is resumed; closing it terminates only that launch's descendants. Runtime acceptance of these changes is pending. CI uploads each development binary only after its platform smoke tests pass.

The subsequent Windows execution passed native configure, login, required password replacement, atomic config replacement and status using a Unicode path. Its ACL inspection fixture then exposed inherited PowerShell 7 module paths breaking Windows PowerShell 5.1 auto-loading. Both the fixture and the CLI's built-in service query now select Windows PowerShell's own system modules. Full Windows acceptance is still pending; macOS service regression passed after the Okio update.

The next Windows execution passed those checks, actual owner-only ACL inspection and rejection of an untrusted HTTPS certificate. The trusted-HTTPS fixture then timed out on the Windows current-user root-certificate confirmation dialog. The full test now requires an explicit disposable-machine certificate flag and uses the elevated CI runner's machine store, removing its generated certificate afterward. This affects the acceptance fixture only; TeamCode does not install certificates. The TeamClaude Windows comparison and necessary native adaptations are documented in [the workstation guide](windows.md#teamclaude-reference-and-native-adaptations).

### Windows launcher acceptance and Task Scheduler encoding

- Windows CI run `39177066176` passed the native login/password-change, private ACL, Unicode configuration/binary path, HTTPS trust/rejection, progressive SSE, Claude/Codex npm launcher, literal arguments/stdin/stdout, exit-code and descendant-cleanup checks. The first reached service installation failed while Task Scheduler parsed its XML encoding declaration.
- Windows registrations now use UTF-16LE with a BOM and a matching declaration; registration ownership compares the actual encoded bytes. The JVM manifest test parses those exact bytes, including the Unicode configuration path. Local CLI tests and MinGW linking passed; Task Scheduler runtime acceptance is still pending the next Windows run.

### TeamClaude parity review and protocol extension points

- Added `docs/teamclaude-comparison.md`, pinned to the reference checkout, with corrected differences, intentional shared-server adaptations and remaining acceptance gaps. Incomplete quota readings, model aliases, affinity, forecast relevance and full real-agent compatibility remain explicit open work.
- Corrected named Codex quota headers, shared named session windows, utilization above 100%, Claude status-only exhaustion, per-model fallback exclusions and temporary 403 cooldowns. A model-only rejection can now fall back to another model on the same subscription without cooling the whole account.
- Added bounded forced renewal after a rejected unexpired access token. Two independent SQL-backed credential sources renew the same rejected token once; a delayed rejection reuses the newer saved token. Existing pending-refresh/crash fencing remains enforced. A rejected first attempt does not refund an ambiguous second attempt.
- Split HTTP ingress, request codecs and SSE decoders/encoders into protocol adapters. Provider transports remain independent. Native passthrough skips translation; existing cross-protocol stream and tool tests pass after the split.
- Local verification: `:domain:test :infrastructure:test :presentation:test :cli:jvmTest :app:buildFatJar` passed, including actual MySQL replica tests: 11 domain, 35 infrastructure, 7 presentation and 14 CLI tests, zero failures/skips. No real provider calls were made for this review. Windows service acceptance remains a separate live CI gate.

### Direct Codex OAuth enrollment implementation

- `teamcode accounts add --provider codex` now uses Codex's public client, fixed localhost callback URI, PKCE and state validation. The user pastes the complete callback URL into a hidden prompt, including when the browser cannot load that local page. No callback listener or browser process is started. Claude retains its `code#state` flow (`--provider claude`, default; `claude-max` is also accepted).
- `OAuthEnrollmentProvider` has separate Claude and Codex implementations; `OAuthEnrollmentClient` shares bounded token exchange and response validation without forwarding TeamCode credentials or following redirects. Both send the resulting credentials through the existing server-verified upsert.
- Four new client tests cover authorization parameters, missing/mismatched/duplicate state, callback origin, provider errors, code exchange, incomplete credentials, body bounds and redirect refusal. Client/CLI JVM tests, macOS ARM64 and Windows x64 native linking passed. The rebuilt macOS binary passed the native launcher smoke against the rebuilt server (literal arguments/stdin, isolated capabilities, simultaneous proxies, exit status and cleanup).
- Live Codex browser consent/enrollment has not been performed; it remains an acceptance step requiring the account owner's interactive login. Existing real Codex import/inference evidence is separate from this new login path.

- Windows CI `39177857445` reached and passed actual Task Scheduler installation, authenticated health, idempotent install and service status. It then failed because the test still decoded the new UTF-16 registration as UTF-8; the fixture now reads the declared encoding. Native macOS/Linux, JVM/MySQL and Helm jobs passed in that run. Remaining Windows drain/reuse/restart checks require the next run.


### Windows native lifecycle accepted

CI run `39178163269`, commit `45b43fd`, passed all Windows native acceptance stages on the actual Windows runner:

- Login, required password change, configuration persistence, private NTFS ACLs, Unicode paths and HTTPS trust/rejection.
- Progressive authenticated SSE, Claude/Codex npm launchers, literal arguments/stdin, inherited stdout/stderr, exit codes, normal descendant cleanup and abrupt launcher termination.
- Task Scheduler installation/idempotence, authenticated service discovery and reuse, graceful drain during a live stream, start/restart, uninstall and logout.

The tested `teamcode-windows-x64` development artifact was uploaded after acceptance. This is runtime evidence, not just a successful cross-compilation. Actual workstation reboot/logon, system services before login and versioned distribution remain open V1 acceptance work.

The entire run completed successfully: JVM/MySQL, Helm, macOS native, Linux native, Windows native and Docker publication. Docker Hub inspection confirmed that `latest` and `sha-45b43fd59eea4451dee5fea6e95a3f8b3e8f0c30` resolve to the same OCI index, `sha256:818221e05ebcdb04f9df00bf98ebe6b551ac84966bd5d87bb82308a5e5205cb6`, with `linux/amd64` and `linux/arm64` images. The Helm default remains `nathanfallet/teamcode:latest`.

See [V1 review](v1-review.md) for the product walkthrough and the remaining release gates; this successful build does not close the full V1 roadmap.


### Durable conversation affinity across replicas

- Added bounded MySQL conversation hints, scoped by authenticated team/member and requested model, with one-hour expiry and two-minute activity scoring. Placement and admission share the team-locked transaction; all quota, pool, cooldown, priority and concurrency checks still apply.
- Claude-specific and Codex generic session headers now survive the native proxy/SDK/server path through a shared allowlist. Malformed or duplicate identifiers disable the hint. Opening-message hashes distinguish subagents without persisting raw session IDs or prompts.
- Remembered cross-provider targets remain stable across turns and are invalidated by route changes. Unsupported conversion skips to a configured compatible target; all-incompatible requests still fail explicitly before provider dispatch.
- Local evidence (2026-10-19): `:api:jvmTest :domain:test :infrastructure:test :presentation:test :client:jvmTest :cli:jvmTest :app:buildFatJar` passed. Actual MySQL tests used two independent connection pools and covered model-specific diversion, competing reservations, eligibility changes, member/pool isolation, expiry, revocation and the 1,000-hint member cap. Module boundaries and `git diff --check` passed.
- The rebuilt macOS ARM64 native binary passed `scripts/smoke-native.py` against the rebuilt server: private configuration, authenticated SDK/proxy transport and progressive SSE. No real provider calls were made for this milestone. Cross-platform CI for this change remains pending publication.

See [conversation affinity](session-affinity.md) for behavior, bounds and remaining live-agent/reset-boundary acceptance. This milestone does not close the remaining [V1 review gates](v1-review.md).


Conversation-affinity CI acceptance: run `39179684615` for commit `d96aee1` completed successfully across JVM/MySQL, Helm and actual native macOS/Linux/Windows tests, followed by successful Docker publication. Registry inspection verified the commit image as OCI index `sha256:c2fb9e389a5af7158fe16959060e37fd00e61684d311cfcf4a4e7fcedb0de362`, containing `linux/amd64` and `linux/arm64`. The subsequent forecast changes have separate local/CI acceptance and are not covered by that run.


### Forecast scope and durable recovery

- Advice now uses recently admitted provider/model targets, their original protocol and current route fingerprint. Requested/resolved model policy, effort and explicit thinking budgets are rechecked. Missing/inactive/invalid contexts yield UNKNOWN, rather than borrowing headroom from idle providers or unrelated model windows.
- Only accessible pool accounts in the selectable priority tier contribute. Proven 95% exhaustion allows the same lower-priority eligibility as admission. Advice respects physical account ceilings and the identical weighted member/pool allowance calculation used for reservations; zero share or exhausted budget produces an allowance-specific PAUSE.
- Tightening and unknown data apply immediately. Recovery must remain stable for two minutes; the pending candidate/timestamp persist in MySQL, and route/policy/pool/eligibility changes invalidate the previous context. Fresh observed exhaustion no longer requires a learned rate, and extremely small rates cannot overflow the forecast timestamp.
- Local evidence (2026-10-19): `:domain:test :infrastructure:test :presentation:test :app:buildFatJar` passed: 14 domain, 42 infrastructure and 7 presentation tests, zero failures/errors/skips. MySQL tests use independent connection pools and cover route/provider/model scope, effort changes, pool isolation, missing/stale observations, weighted allowances, recovery continuity and context invalidation. Existing account-affinity and replica regressions pass with the additive V8 migration.
- The actual macOS ARM64 CLI passed `scripts/smoke-native.py` against the rebuilt packaged server (private configuration, authenticated proxy/SDK, progressive SSE). Module boundaries and diff whitespace checks passed. Tests used synthetic data and no real provider requests. Cross-platform CI for this milestone is pending the new push.

Advice is still delivered per member, conservatively taking the most constrained active target. Sparse per-session delivery, automatic hook integration, outside-spend/reset/bursty real-load replay, observation-only rollout and fair scheduling remain V1 work; this milestone does not claim optimal orchestration or finish the full roadmap.


Forecast-scope CI acceptance: run `39180421393` for commit `6bee322` completed successfully across JVM/MySQL, Helm, native macOS/Linux/Windows and Docker publication. This run predates the session-delivery changes below.

### Scoped delivery and automatic Claude hooks

- Added separate session directives and durable 15-second emission claims/receipts. Independent replicas suppress acknowledged versions, recover lost claims and reject revoked, foreign, expired or replaced claims. The original member table retains its one-row primary-key contract for rolling upgrades; session metadata on reservations is additive.
- Admission applies pacing within the current session while retaining hard member, pool and account limits. A new identified session does not inherit another session's forecast throttle. Headerless callers retain the member aggregate. Current scoped advice requires recent activity; raw session IDs are hashed before persistence.
- `teamcode run claude` now installs a temporary invocation-only HTTP-hook plugin. Local processing is bounded and forwards only the session ID/delivery stage, discarding tool content and transcript paths. Changed advice is emitted as additional context at UserPromptSubmit/PreToolUse; failures emit empty context. Personal settings remain unchanged. `--no-guidance` and explicit `--bare` skip automatic installation.
- Added `agents watch --session` and required `--session` for manual `agents hint`, with separate received/emitted stages. The loopback proxy uses the existing local capability; plugin files contain an environment-variable reference, never a credential. Normal launch completion removes its own plugin, and optional-plugin failures preserve the child execution/exit behavior.
- Local suite evidence (2026-10-19): API (1), domain (14), infrastructure (42), presentation (8), client (4) and CLI (16) tests passed, zero failures/errors/skips. The final MySQL targeted regression also passed after adding owner isolation, hard member concurrency and rolling-upgrade assertions. Packaged server, macOS ARM64 native and MinGW native linking passed; module boundaries, Python fixture syntax and diff whitespace checks passed.
- The rebuilt macOS native launcher passed Claude/Codex literal arguments, stdout/stdin/cwd, separate concurrent proxies, local HTTP hook configuration/calls, opt-out/bare behavior, exit/signal status and plugin cleanup. Windows native acceptance now includes the generated Unicode plugin path and HTTP hook in its existing npm/service fixture; execution of that updated fixture awaits the new CI run.
- `scripts/smoke-claude-guidance.py` passed with the actual installed Claude Code **2.1.302** and native TeamCode. The isolated deterministic server proved both UserPromptSubmit and PreToolUse context injection, correlation of real hook/inference session IDs, an actual harmless Bash tool round trip, emission receipts and temporary-plugin cleanup. It used no provider credentials or subscription calls. This proves client integration, not forecast accuracy or model obedience under real load.

See [agent guidance](agent-guidance.md) for the retry/receipt contract, defaults and limits. Automatic Codex injection, independent subagent fan-out, forecast replay/observation rollout and the other [V1 review gates](v1-review.md) remain open. Cross-platform CI for this milestone is pending the push; do not infer its acceptance from the previous green run.


## Native Codex guidance (2026-10-19)

The preceding session-guidance commit `f1327d8` completed [CI 39181558080](https://github.com/nathanfallet/teamcode/actions/runs/39181558080) successfully: JVM, macOS, Linux, Windows, Helm and multi-architecture Docker publication all passed. This includes the previously pending Claude Windows plugin/HTTP-hook fixture.

This milestone implements `teamcode agents hook` and automatic command hooks in `teamcode run codex`, sharing parsing, scoped claim lookup and receipt handling with Claude. Hook definitions contain only stable executable/config paths; credentials remain in private configuration. Input and network deadlines are bounded. Codex trust review remains mandatory; the launcher does not bypass it or alter existing configuration files. Explicit CLI hook overrides are preserved with a notice and a documented manual bridge.

Local evidence:

- `:cli:jvmTest`, macOS native linking and MinGW native linking passed. Production module boundaries and whitespace checks passed.
- `scripts/smoke-hook.py` passed on the actual macOS binary: both supported events, exact 64-KiB boundary, malformed/invalid UTF-8 input, held-open stdin deadline, network deadline, expiry, receipts/deduplication, private payload isolation and Unicode configuration path.
- `scripts/smoke-run.py` passed with the actual native launcher, including execution of generated Codex hook commands, literal arguments, opt-out, concurrent proxies and cleanup.
- `scripts/smoke-codex-guidance.py` passed with actual **codex-cli 0.163.0** and native TeamCode. Five isolated scenarios cover both context boundaries, an actual harmless `exec_command` round trip, correlation of hook/inference session IDs, receipts, preservation of user hooks, refusal to run untrusted hooks, opt-out, leaf-scope overrides with `--ignore-user-config`, and preservation of custom CLI hooks. Executable/configuration paths include spaces, Unicode, an apostrophe and a dollar sign. No real provider requests or user configuration were used.
- The shared bridge refactor also passed the real **Claude Code 2.1.302** guidance fixture with its Bash tool round trip.

Native stdin and generated-command checks are wired into desktop CI, including Windows PowerShell execution. Their updated cross-platform run is pending this push; MinGW linking alone is not Windows runtime acceptance. Full real Codex Windows client acceptance, independent subagent fan-out, forecast load replay/charts, and the remaining [V1 gates](v1-review.md) stay open.


### Windows hook executable lookup

CI runs [39182797785](https://github.com/nathanfallet/teamcode/actions/runs/39182797785) and [39217843645](https://github.com/nathanfallet/teamcode/actions/runs/39217843645) exposed a Windows hook invocation failure. The Windows acceptance deliberately removes nonessential PATH entries; Windows PowerShell lives below `System32/WindowsPowerShell/v1.0`, outside that minimal PATH. Native/npm launches worked but the new guidance command could not find PowerShell. Docker publication remained blocked by the failing Windows gate.

The launcher now adds the OS-provided PowerShell directory to the Codex child environment only. The minimal-PATH acceptance remains intact. Local CLI JVM tests, macOS/MinGW linking and the real Codex 0.163.0 five-scenario guidance fixture pass. Windows runtime confirmation awaits the fix's CI run; linking is not a substitute for it.


## Unknown reset observations and rounded windows (2026-10-19)

Provider usage and response headers now retain a valid percentage when no reset epoch is reported. `AccountQuota`, the public quota DTO, both SQL quota tables and dashboard rendering distinguish an unknown date from zero usage. Missing/invalid percentages still create no observation; known expired windows are not revived. Claude numeric/ISO reset and percentage aliases are covered. Codex usage/header durations use TeamClaude's shared 10% tolerance around five hours/seven days, rejecting unrelated hourly/monthly windows and preserving named weekly scope.

Admission excludes an unknown-reset saturated window only while its observation is at most five minutes old; repeated fresh saturation extends protection. Known future resets retain the previous exclusion behavior. A newer unknown-reset zero observation can clear an old saturation; delayed observations cannot overwrite it. Forecasting never infers an idle/reset-first trajectory from an unknown epoch, while a fresh exhausted window remains actionable even if another window is absent.

Evidence:

- **93 tests passed**: API 2, domain 16, infrastructure 47, presentation 8, client 4, CLI 16. This includes parser aliases/duration boundaries/absent versus zero readings, unknown-reset forecasting and freshness, and API null/known-date compatibility.
- `UnknownQuotaResetPersistenceTest` upgrades an actual MySQL V9 database with an existing quota to V10, preserves the known row, and uses two independent connection pools to prove newer/late observation ordering, scoped saturation, freshness expiry, known-reset protection and future-sample rejection.
- The packaged two-process replica smoke passed: concurrent startup/migration, one admin bootstrap, shared members/sessions, cross-replica revocation, killed-pod recovery and database readiness/recovery. It uses only its own test database and processes.
- Server packaging, native macOS linking and the production module graph check passed. No provider credentials or subscription calls were used.

Deploy all application replicas and update quota API consumers before relying on nullable-reset protection; old readers did not understand it. Active-limit/model-family mappings, removed model caps, real-load forecast accuracy, subagent fan-out and the other full V1 gates remain open. Updated CI/Docker publication for this quota milestone is still pending.

## Exposed persistence and canonical Codex quota scopes (2026-10-19)

The previous unknown-reset milestone (`6b54733`) completed CI run `39219656827`, including every platform and Docker publication. The preceding Windows hook PATH fix also completed all jobs in run `39218729009`; this verifies the native bridge and launcher fixture, not a full real Codex session on Windows.

This slice replaces handwritten JDBC queries throughout the application and test fixtures with Exposed 1.5.0, matching the standalone asonar/pkg conventions. The 23 typed tables retain the existing database shape. HikariCP still owns the bounded connection pool, Flyway still applies migration resources, and database-clock leases/read-committed transactions retain replica coordination. Exposed SQL logging is disabled and automatic transaction retries are disabled. The module-boundary check now rejects raw SQL strings and direct JDBC execution in Kotlin sources. Previously published migrations remain unchanged.

Codex response headers now bind the resolved model to the serving account's canonical active-limit slug. Usage probes use `metered_feature` rather than display labels, and admission, forecasts and rejection classification share those bindings. A complete probe can retire model caps; partial/malformed data cannot. Snapshot timestamps start before the network request, and shared watermarks prevent delayed responses from restoring retired caps. Historical samples remain stored, with separate window-incarnation timestamps preventing a new cap from extrapolating across its prior retirement. Bindings are limited to 32 models per account and never applied to another subscription or to Claude accounts.

Local verification passed:

- **99 tests**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 52, presentation 8 and CLI 16. The infrastructure suite includes real MySQL independent-pool admission races, refresh fencing, directive claims, alias isolation, stale snapshot ordering and cap retirement.
- The packaged server passed the two-process replica smoke: concurrent startup/migration, one administrator bootstrap, shared members/sessions, cross-replica revocation, killed-pod recovery and database readiness/recovery.
- Exposed generated exactly three additive migrations for quota snapshots, canonical model bindings and window-incarnation timestamps. Replaying all 13 migrations and generating again produced **zero migrations**, confirming no schema drift. Published V1–V10 resources remain unchanged.
- Server packaging, module boundaries, the raw-query convention check and diff whitespace checks passed. No provider credentials or subscription calls were used.

Cross-platform CI and Docker publication for this slice are pending its push; the previous green runs do not validate these changes.

The first open-source model adapter is now explicitly V2 at the owner's request. Versioned CLI updates, signed release manifests, cooperative service updates and rollback are documented design targets in `services.md`, not implemented commands. The remaining V1 gates in `v1-review.md` are still required.


## Claude model-cap retirement (2026-10-19)

Claude usage probes now carry an authoritative model-cap snapshot only when the payload contains a valid explicit `limits` enumeration. Valid legacy `seven_day_<family>` readings retain precedence, and listed zero usage remains an observation with an optional reset. A null legacy slot, missing enumeration, malformed entry or failed request cannot prove removal. Model-family identifiers are normalized and bounded before persistence.

This follows the sibling TeamClaude `src/oauth.js`, `test/quota-probe.test.js` and `test/scoped-weekly.test.js` behavior. TeamCode additionally validates completeness before retirement and shares snapshot ordering across replicas through Exposed transactions. The probe timestamp precedes the network request. A delayed or equal-time response cannot resurrect a retired model window, and a newer inference observation survives an older probe. Retirement is account-local, never clears a shared subscription window, preserves history and starts a new history interval when a cap reappears. Codex active-limit bindings remain Codex-only. No schema migration is required beyond the preceding Exposed milestone.

Evidence:

- **57 infrastructure tests passed**, zero failures/errors/skips, plus server packaging, module/query conventions and whitespace checks.
- `ClaudeQuotaSnapshotTest` covers full/partial/malformed enumerations, null versus zero, legacy precedence, unknown families, expired windows, request timing and unsuccessful probes using the actual transport with a synthetic HTTP engine.
- `ClaudeQuotaRetirementPersistenceTest` uses real MySQL and two independent pools to verify admission, account isolation, shared exhaustion, retained history, equal/late responses and concurrent header/probe ordering. The existing Codex binding and retirement tests remain green.
- `RouteDirectivePersistenceTest` now also proves that retiring a Claude cap releases forecast pressure after persisted recovery hysteresis; retained history does not recreate the pause.

No provider credentials or subscription calls were used. Cross-platform CI and Docker publication await this push. More subscription-plan shapes, real reset-boundary behavior, complete agent tasks and the other V1 acceptance gates remain open.


## Real Claude Code task through Codex (2026-10-19)

The preceding Claude quota-retirement commit `e2b0f29` completed [CI 39226100498](https://github.com/nathanfallet/teamcode/actions/runs/39226100498) successfully, including JVM, native macOS/Linux/Windows, Helm and multi-architecture Docker publication. This also validates the Exposed conversion; its earlier standalone run was superseded before the macOS/image jobs completed.

The real coding-task fixture found and fixed two conversion boundaries beyond the existing text/function smoke: Claude Code explicitly sends neutral temperature 1, and a tool execution error must remain actionable after translation. Neutral temperature/top-p defaults now use the Codex endpoint's defaults, while custom values remain rejected. Failed Messages tools become explicitly marked Responses tool outputs, retaining their original content and call IDs within the tool-result role. Claude's non-strict tool default is now explicit on Responses requests, so optional parameters are not implicitly normalized into required fields. Explicit strict mode is retained.

Evidence:

- **107 tests passed**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 60, presentation 8 and CLI 16. New codec regressions cover default/custom sampling, strictness and optional arguments, failed tool results, IDs and original text/image preservation. Server packaging, module/query conventions, Python syntax and whitespace checks passed.
- The actual **Claude Code 2.1.302** ran through the macOS ARM64 native `teamcode run claude`, a packaged server and a forced route from `claude-haiku-4-5-20251001` to the real Codex subscription model **`gpt-6-luna`**. It used Read/Edit/Bash, corrected a broken Python function on disk and produced a successful final response. Four independently rerun tests passed, and the test file remained byte-for-byte unchanged.
- All **eight inference requests** used the intended Codex target and settled known usage. Six authorized accounts were imported in access-only mode; the source configuration hash remained unchanged. No refresh ran, and the fixture removed its own processes and disposable database. No raw prompts, agent output or subscription credentials were logged.
- The harness uses isolated agent configuration, restricted task tools, an eight-turn/180-second bound, a real native launcher and actual file/test assertions. It is opt-in and excluded from CI.

This closes one real coding-task case, not the entire portability gate: reasoning is explicitly disabled, tools are the selected built-in Read/Edit/Bash set, and the destination is forced from the first turn. Mid-conversation fallback, other reasoning modes and reverse coding-task acceptance remain open. A synthetic capture of installed Codex **0.163.0**, without any provider request, identified the next concrete reverse-route gap: `additional_tools` input items, namespaced/custom tools, reasoning context and verbosity controls. They must be mapped before claiming default Codex-to-Claude coding support. All other V1 release gates remain in scope. CI/Docker publication for this slice awaits its push.


## Real Codex task through Claude and custom tool translation (2026-10-19)

The preceding Claude-to-Codex commit `82a5fe4` completed [CI 39228202859](https://github.com/nathanfallet/teamcode/actions/runs/39228202859) successfully across JVM, native macOS/Linux/Windows, Helm and multi-architecture Docker publication.

Responses developer `additional_tools`, one-level namespaces and supported custom text tools now cross the Claude route. Deterministic aliases preserve tool identity without replica-local state. Claude receives custom text through a strict single-string wrapper; the response adapter validates the complete wrapper, grammar length and Unicode before restoring raw custom text, namespace and call ID. Conflicting declarations, unsupported grammars, invalid named choices and unadvertised tool calls fail explicitly. Ordinary function streaming and native protocol passthrough remain supported.

The supported grammar boundary is free text, two unrestricted regex forms, and the exact nonempty-text Codex code-mode Lark grammar. Arbitrary grammar conversion is not implemented. Verbosity becomes an explicit response-length instruction. Reasoning context `all_turns` is accepted only when reasoning is explicitly disabled. Empty/null `logprobs` and annotations can be replayed; nonempty metadata is not silently dropped.

Evidence:

- **114 tests passed**, zero failures/errors/skips: API 2, client 4, domain 17, infrastructure 67, presentation 8 and CLI 16. Tests cover deterministic identities, collisions, namespaces, custom input and result history, named tool choices, supported/unsupported grammars, Unicode chunking, malformed wrappers and empty output metadata. A gateway regression performs two full turns through the translated transport and verifies raw custom input, replayed IDs and both usage settlements.
- The actual **Codex 0.163.0** ran through the macOS ARM64 native `teamcode run codex`, a packaged server and a forced route from `gpt-6-luna` to the real Claude Max model **`claude-haiku-4-5-20251001`**. It corrected the Python source, executed the tests and completed its turn. The test file remained unchanged and all four independently rerun tests passed. Its normal namespaced/code-mode tools were retained; reasoning was explicitly disabled.
- All **sixteen inference requests** used the intended Claude target and settled known usage. Six verified accounts were imported in access-only mode; the source configuration hash stayed unchanged. No refresh ran, and the fixture cleaned up its own processes and disposable database. No raw prompts, agent output or credentials were logged. Earlier reverse attempts rejected replayed empty `logprobs` before dispatch; the accepted empty-metadata mapping fixes that boundary.
- The first full run exposed an intermittent missing forecast in `RouteDirectivePersistenceTest`; the unchanged targeted case passed. Its synthetic quota snapshot now uses the database clock, matching its sample timestamps and the future-observation guard, instead of mixing host/VM clocks. The fixture asserts every shared-window sample was persisted before evaluating guidance. The subsequent complete suite passed; production forecast behavior was not relaxed.
- Packaged server, module/query conventions, Python syntax and diff whitespace checks passed. No schema, CLI binary implementation or dependency change is required.

Both directions now have a complete real coding-task case with reasoning disabled and the destination forced from the first turn. This does not close full-default reasoning, arbitrary tool grammars, live mid-conversation fallback, real quota/reset-boundary acceptance or the other V1 gates. Cross-platform CI and Docker publication for this slice await its push.


## Real coding tasks across a mid-conversation fallback (2026-10-19)

The preceding custom-tool bridge commit `b5cf352` completed [CI 39231303645](https://github.com/nathanfallet/teamcode/actions/runs/39231303645) successfully: JVM, Helm, native macOS/Linux/Windows and multi-architecture Docker publication.

The opt-in coding fixture now supports `--coding-fallback`. Both agents start on their native provider with the other provider configured second. A loopback HTTP fixture recognizes a matching call/result in the real agent's history and disables native-provider accounts through the disposable server's management API before forwarding the original request bytes. The route version and targets must remain unchanged. Provider responses are streamed unchanged; the fixture does not manufacture inference output. Its private configuration, account settings, listener and processes are cleaned up on exit.

Evidence:

- **Claude Code 2.1.302:** one native `claude-haiku-4-5-20251001` request, followed by **nine Codex `gpt-6-luna` requests** after the tool boundary. Actual Read/Edit/Bash execution corrected the source, left the test file unchanged and passed four independent tests. The final agent result succeeded.
- **Codex 0.163.0:** one native `gpt-6-luna` request, followed by **eleven Claude `claude-haiku-4-5-20251001` requests**. Its namespaced/code-mode tool conversation continued through the switch, corrected the source and completed the turn. Four independent tests passed and the test file was unchanged.
- All **22 client inference responses were HTTP 200** with the expected target, correlated by response request ID to dispatch and successful terminal usage. There were no unknown-usage settlements, translation failures or hidden nonzero unmatched attempts. The evidence verifier was rerun against both completed metadata logs after its final extraction.
- **Four provider-free fixture tests pass:** matching tool-history detection; authenticated HTTP forwarding and exact body preservation; one-time capacity removal and restoration on failure; and rejection of incorrect targets, 429s, missing correlations, failed streams and charged unmatched attempts. These tests now run in JVM CI before Gradle. Real subscription tests remain opt-in and excluded from CI.
- Each run imported six verified accounts in access-only mode, did not refresh provider credentials, preserved the source configuration hash and removed its own disposable resources. No provider tokens, prompts or raw agent output were logged. Only fixture/server metadata is reported.

An early Claude run failed within an eight-turn budget after tool errors; a later task completed but failed the old count-only request assertion. The final fixture allows twelve Claude turns within the existing 180-second deadline and correlates individual response IDs instead of assuming every provider attempt equals one client request. Any unmatched attempt must have settled with zero charge; any client error still fails acceptance. The final successful runs required ten and twelve requests respectively. No production Kotlin code, schema, dependency or native launcher changed in this slice; its existing 114-test JVM baseline is additionally covered by the preceding green CI.

This validates controlled mid-conversation fallback with reasoning disabled. It does not simulate actual quota exhaustion, reset timing, signed/encrypted reasoning transfer or every client default. Those gates, fairness/reconciliation, the remaining team features, distribution and operational acceptance stay in scope. CI publication for this fixture/documentation slice awaits its push.

## Shared admission queues and pre-response cancellation (2026-10-19)

The previous coding-fallback milestone (`d2e2c8e`) completed [CI run 39233016292](https://github.com/nathanfallet/teamcode/actions/runs/39233016292), including JVM, native macOS/Linux/Windows, Helm and Docker publication.

Temporary member concurrency, scoped forecast pacing and busy physical accounts now wait through a bounded, shared admission queue. Exposed persists pending metadata and member turns by team/pool/provider. Interactive requests have priority; background requests age into that class after 30 seconds. Ready members alternate admission turns, independently of their weighted daily credit share. Limits are 8 pending requests per member, 128 per team, 90 seconds per admission attempt and a renewable 15-second lease. No database connection or team credits are held while waiting. Authentication, route/model policy and budgets are checked in the reservation transaction. Queue failures can try the next configured route target; remaining queue pressure returns 503 rather than pretending all subscription capacity is exhausted. Waiting counts are visible in the dashboard and CLI status.

Real socket tests found that Ktor's default lifecycle continued a pending inference handler after client disconnect, before response headers. Both inference hops now opt into `HttpRequestLifecycle.cancelCallOnClose`. Management/enrollment routes keep their existing lifecycle. The regression fails on the preceding published native binary and passes on the fixed macOS binary; the real Netty test also failed before the fix and passes afterward.

Local evidence:

- **123 tests passed**, zero failures/errors/skips: API 3, client 4, domain 21, infrastructure 70, presentation 8, CLI 16 and app 1. After the final cleanup-lock and isolation additions, the targeted `AdmissionQueuePersistenceTest` and packaged server build passed again.
- Real MySQL with two independent connection pools verifies member rotation, FIFO/interactive/background aging, provider/pool isolation, atomic duplicate claims, bounded leases/queues, resumption after another replica releases capacity, cancellation without quota reservation, policy/route changes and session revocation while pending.
- Gateway tests verify waiting before a single dispatch, workload validation and fallback after a queue deadline. Virtual-time tests cover cancellation, pacing deferral, permanent denials and retained place under physical contention.
- The actual Netty server and macOS ARM64 native proxy pass pre-response TCP disconnect tests. `scripts/smoke-queued-cancel.py` uses an isolated loopback fixture without provider calls or credentials. It is now included in all three native platform CI jobs; updated Linux/Windows execution awaits this push.
- Existing actual native authentication/progressive SSE and two-process replica tests passed, including concurrent migrations/bootstrap, shared sessions, revocation, owned-process crash recovery and database readiness recovery.
- Exposed generated two additive migrations. Replaying all **15 migrations** and generating again produced **zero migrations**. Published migrations are unchanged. Module-boundary/raw-query checks, Python syntax and diff whitespace checks passed. No subscription traffic was needed for this milestone.

Upgrade all server replicas before relying on fair scheduling; an older replica can bypass it. Update the local CLI as well to propagate agent disconnects across both hops. Native launchers currently default to interactive; background selection is available through the HTTP workload header, not a CLI flag. Real-load scheduling/polling cost, soft borrowing, reconciliation, forecast acceptance, remaining administration and distribution/operations gates remain V1 work. This milestone does not complete V1; cross-platform CI and image publication for it are pending.

CI follow-up: [run 39236435650](https://github.com/nathanfallet/teamcode/actions/runs/39236435650) passed JVM, Helm and native macOS. Native Linux and Windows passed their earlier runtime/launcher checks but failed the new pre-response cancellation fixture: the upstream connection did not close within five seconds after the caller disconnected. Docker publication was skipped. This is an unresolved cancellation gate, not a green cross-platform milestone.

## Native workload selection and isolated inference cancellation (2026-10-19)

`teamcode run claude --workload background -- ...` and the equivalent Codex command now select background scheduling for that invocation. Interactive remains the default. A launch-specific authenticated loopback path becomes the server's existing workload header; canonical server paths, provider bodies, quotas and permissions are unchanged. Service discovery advertises `workload-routing-v1`, allowing background and interactive sessions to share one service without changing global state. A background launch falls back to its own ephemeral proxy when an older service lacks that capability. Agent arguments after `--` remain literal, and configuration files are unchanged.

The preceding Linux cancellation failure was reproduced with the actual Linux x64 native binary in a local container. The route received cancellation, but the upstream transport stayed open. The SDK now owns a separate engine for each inference and closes it, together with the request job, when the caller disconnects or the SDK closes. Other inferences and the management client are left running. This keeps the universal Ktor engine dependency and avoids copying provider/HTTP implementations. It deliberately gives up inference connection reuse; TLS/engine setup overhead remains part of performance acceptance.

Verification:

- **23 targeted JVM tests passed:** client 5 and CLI 18, zero failures/errors/skips. New coverage verifies shared-proxy workload isolation, conflicting metadata, legacy/mismatched service capability rejection, per-request cancellation and cancellation of remaining inference when the SDK closes.
- Native macOS ARM64 and Linux x64 built successfully. The enhanced real TCP fixture passes on **both**: closing one pre-response request disconnects its upstream, a simultaneous request completes, and a subsequent request still succeeds. The earlier Linux implementation failed the same pre-response disconnect condition. No subscription was called.
- The actual macOS launcher fixture passes Claude/Codex background and interactive invocations, literal arguments, malformed workload rejection, unchanged config, stream forwarding and cleanup. The actual launchd fixture passes installation/reuse/drain/restart/uninstall and verifies server-observed workloads `interactive -> background -> interactive` through a shared service.
- Linux/macOS launcher and service fixtures and the Windows npm/Task Scheduler fixture now include workload checks. The real TCP cancellation fixture runs in all three native CI jobs. Updated Windows runtime evidence and image publication await this push.
- Module boundaries, raw-query conventions, Python fixture syntax and whitespace checks passed. No new migration or provider credential was needed.

Full V1 remains open: real-load scheduling/forecast acceptance, soft borrowing/reconciliation, remaining team administration, full-default provider portability and distribution/operations gates are unchanged. The native cancellation fix is locally proven on Linux/macOS; Windows is not claimed until its runtime CI passes.

CI follow-up: [run 39238882503](https://github.com/nathanfallet/teamcode/actions/runs/39238882503) passed JVM, native macOS, native Linux and Helm. Windows passed its HTTPS, launcher/workload and Task Scheduler checks but still failed the pre-response disconnect condition. Docker publication was skipped. An isolated diagnostic branch confirmed that the Windows handler, request job and engine close paths execute while the upstream TCP connection remains open ([diagnostic run](https://github.com/nathanfallet/teamcode/actions/runs/39239703637)). Adding `Connection: close` did not fix that condition ([second diagnostic](https://github.com/nathanfallet/teamcode/actions/runs/39240251900)); that experimental header and diagnostic logging are not on `main`.

The next reliability step is an authenticated, request-scoped cancellation control path to the server, with bounded shared database metadata so it works across replicas. This must be tested before claiming Windows cancellation: local engine disposal alone is insufficient evidence that queued or upstream work has stopped. The existing strict fixture remains a failing gate until the cancellation contract is implemented and verified; it must not be weakened to hide the failure.

## Authenticated cancellation across replicas (2026-10-19)

The SDK now tags each inference with a fresh UUID and submits an authenticated cancellation control call when its caller or the SDK closes before response consumption completes. It retains the original request credential across config changes. Control calls own their engine, retry at most twice within 2.5 seconds and never replay inference. Completed responses do not submit cancellation. A real macOS fixture exposed cancellation thrown while leaving the outer transport scope; handling only `execute` was insufficient. The handler now covers that entire scope.

`RequestCancellationService` and `CancellableGateway` cover admission, fallback and stream consumption. Exposed stores only team/member/UUID/expiry markers, bounded to 256 per member and 4,096 per team with ten-minute expiry. Markers can arrive before dispatch and are shared by all replicas. Polls every 500 ms check the marker and revalidate the original session. Revocation or database failure ends monitored work; scheduled maintenance reclaims expired markers. A 204 response acknowledges a stored marker, not provider shutdown or a quota refund. TCP cancellation remains enabled; control delivery is best effort during outages and forced exits. No Redis or process-local request registry was added.

Verified locally:

- **131 tests passed**, zero failures/errors/skips: API 3, client 5, domain 24, infrastructure 71, presentation 8, CLI 18 and app 2. The packaged server and macOS ARM64/Linux x64 native builds passed.
- Real MySQL with two independent pools verifies cancel-before-dispatch, cross-replica queue removal without new reserved credits, streaming cleanup, concurrent marker idempotence without retention extension, member/team isolation, revoked-session rejection, expiry/maintenance and both storage bounds.
- Two real Netty servers with independent MySQL pools verify cancellation through the second HTTP replica while the first inference connection stays open, before headers and after streaming starts. Invalid/duplicate inference identifiers, unauthenticated requests and cookie-only cancellation are rejected. Control metadata does not enter provider headers.
- Both actual native binaries pass the expanded TCP fixture: disconnect submits a scoped authenticated control call and stops only that server operation; concurrent and subsequent requests complete. The fixture now models the cancellation endpoint and still requires real server-side connection/work termination. It also captures background handler assertion failures so they cannot produce a false green result. Production HTTP/MySQL tests independently verify the fixture's server contract. Updated Windows runtime evidence remains pending CI.
- SDK tests verify original-credential capture, independent requests, SDK shutdown, no control call after successful consumption, bounded control retries and no inference replay. Domain tests verify monitor lifetime, cancellation cleanup and fail-closed database errors.
- The two-process server smoke passes concurrent migration/bootstrap, shared sessions, cross-replica revocation, owned-process crash recovery and database readiness recovery.
- Exposed generated one additive migration. Replaying all **16 migrations** and generating again produced **zero migrations**. Published migrations are unchanged. Module/raw-query checks, four acceptance-script tests, Python syntax and diff whitespace checks passed. No subscription calls or real credentials were used.

Upgrade every server replica and native CLI, including restarting an installed local service, before relying on the cancellation control path. Windows runtime acceptance, image publication, database polling/engine setup costs and all other V1 gates remain open. The contributor instructions also record the owner's squash-only PR merge convention.

CI follow-up for `f3ff5f6`: [run 39243219124](https://github.com/nathanfallet/teamcode/actions/runs/39243219124) passed JVM, native macOS/Linux and Helm, including the native launch/service checks. Windows passed its other runtime and Task Scheduler checks but the cancellation fixture still failed to observe server-side connection termination; image publication was skipped. An isolated [Windows diagnostic](https://github.com/nathanfallet/teamcode/actions/runs/39243969220) confirms that the SDK sends cancellation and receives HTTP 204. The synthetic server recorded the matching control call and one handler exception; that exception still needs inspection before changing the fixture or claiming Windows acceptance. Diagnostic logging is confined to the separate branch.

The [follow-up diagnostic](https://github.com/nathanfallet/teamcode/actions/runs/39244419901) identifies that exception as `BrokenPipeError: WinError 10058` (`WSAESHUTDOWN`) in the synthetic server's `recv`, after its authenticated control handler shuts down that socket's receive direction. [Winsock documents this behavior](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-recv). The fixture now recognizes only that exact Windows error and only when the same UUID has a recorded cancellation. Other handler errors still fail, the five-second stop deadline is unchanged, a matching authenticated control call is still required, and concurrent/subsequent requests must still succeed. Captured handler errors are included in timeout failures. No production Kotlin change was needed after `f3ff5f6`. A further SDK test verifies that the control call can unblock an inference transport whose cleanup waits for remote cancellation. Full Windows runtime acceptance awaits the corrected fixture's CI run.


Final acceptance for this slice: [CI run 39245062065](https://github.com/nathanfallet/teamcode/actions/runs/39245062065) passed **every job** for `f727dbc`: JVM, native macOS, native Linux, native Windows, Helm and Docker publication. The native Windows cancellation fixture and hooks passed, along with HTTPS/login, npm launchers and Task Scheduler lifecycle. The additional SDK test brings the local JVM total to **132 tests**, zero failures/errors/skips. Local native authentication/progressive streaming, launchers and launchd lifecycle also passed again.

Registry inspection confirms that `nathanfallet/teamcode:latest` and `nathanfallet/teamcode:sha-f727dbc842f384e5b6d905533ff2ac789ad02452` resolve to the same manifest digest, `sha256:49fc4491af50d19b9125f9d944268ebd6c83fc24fd21c1cb5acbf8ffa7b1514b`, with both `linux/amd64` and `linux/arm64` images. Publication does not deploy or restart a Kubernetes workload. At the owner's request, both temporary diagnostic branches and their clean owned worktree were removed after their runs completed. Their findings and CI references remain recorded here.

This closes the native cancellation gate, not V1 release acceptance. Real-load fairness/forecasting, polling/engine overhead, soft borrowing/reconciliation, remaining team administration, full-default provider portability, versioned distribution/update/rollback and operational acceptance remain in scope.


## Registered devices and authentication sessions (2026-10-19)

The CLI now registers a logical installation on password login. Its atomically created private `installation-id` file survives logout and server changes; concurrent first logins reuse one identity. Registration is scoped to the authenticated member, preserves dashboard-edited names and does not change member allowances. Older CLI and browser sessions remain valid without an associated device.

Owners and administrators can list, rename and remove devices and list/revoke authentication sessions through the dashboard, API and native CLI. Commands include `teamcode whoami`, `teamcode devices list|rename|revoke` and `teamcode auth sessions list|revoke`. The planned top-level `teamcode sessions list` agent-run registry remains separate and unimplemented. Device removal revokes all attached tokens; reconnecting requires password authentication. Revocation now monitors authenticated inference even when an older client supplied no cancellation UUID. Already-committed streams are terminated without appending a replacement JSON error.

Verification before publication:

- **138 JVM tests passed**, zero failures/errors/skips: API 3, client 6, domain 25, infrastructure 72, presentation 10, CLI 20 and app 2. Packaged server and actual macOS ARM64/Linux x64 native binaries built successfully.
- Real MySQL tests with independent pools verify concurrent enrollment, member/team authorization, legacy-session compatibility, name persistence, bounded registrations, scoped session/device revocation and unchanged member spending across devices/reconnects. Typed Exposed fixtures verify upgrades from published identity schemas, including schema 16 to 18; no handwritten fixture SQL was introduced.
- Two real HTTP servers with independent MySQL pools verify that device revocation on one replica stops an untagged authenticated stream on the other, invalidates subsequent authentication and does not append JSON to committed SSE.
- Actual macOS and Linux native binaries pass the isolated device fixture, including four concurrent password logins, private atomic installation identity, logout/login persistence, Unicode names, typed lists and authorization/revocation. The native CLI against the real server/MySQL also passes bootstrap/password change, browser member creation, rename, reconnect and multi-session revocation while preserving the independent browser session.
- Existing macOS native authenticated streaming, pre-response cancellation and service lifecycle fixtures pass again. Exposed replays all **18 migrations** and generates **zero additional migrations**. Published migration files are unchanged. Module/query conventions and whitespace checks pass. No personal credentials or provider requests were used.

An accidental overlap of two local Gradle invocations corrupted their shared test-result output. Both invocations terminated; the full suite and native builds then passed in one serial invocation. Contributor instructions now prohibit overlapping Gradle tasks in the same checkout.

The preceding main commit `567ca97` has a fully green [CI run](https://github.com/nathanfallet/teamcode/actions/runs/39246084876). This new device slice still requires CI, particularly Windows runtime acceptance of concurrent private identity creation and the new commands, before cross-platform acceptance or image publication can be claimed. See [device documentation](devices.md) for behavior and limits. Remaining V1 administration, routing/reasoning, fairness/forecasting, distribution and operations gates remain open.


Device-slice CI follow-up: [run 39250893771](https://github.com/nathanfallet/teamcode/actions/runs/39250893771) passed JVM (including two-process replica acceptance), Linux native runtime and Helm. Windows passed its existing full runtime fixture and four concurrent installation logins but failed the new fixture's PowerShell ACL inspection; the subprocess error hid stderr. Image publication remains blocked. The device fixture now uses the same SID/ACL projection already accepted by `smoke-windows.py`, checks both identity file and directory ownership/protection/sole full-control rule, and includes inspection stderr and observed rules on failure. These assertions have not been relaxed. This fixture-only revision passes again on macOS; updated Windows acceptance remains pending CI.


The [second device CI run](https://github.com/nathanfallet/teamcode/actions/runs/39251941289) passed JVM, both native macOS/Linux jobs and Helm. Its detailed Windows error identifies the fixture problem: Windows PowerShell 5.1 inherits the runner's PowerShell 7 module path and cannot load `Microsoft.PowerShell.Security` for `Get-Acl`. The new fixture now sets the Windows PowerShell module path exactly as the established `smoke-windows.py` helper already does. Native Windows compilation, the preceding full Windows runtime fixture and concurrent identity creation passed; the remaining identity commands and publication still require the corrected fixture's CI. No production security code or ACL assertion was changed for this environment fix.


Final device-slice acceptance: [CI run 39252661603](https://github.com/nathanfallet/teamcode/actions/runs/39252661603) passed **every job** for `7175123`: JVM, Helm, actual native macOS/Linux/Windows runtime and Docker publication. The corrected Windows fixture verifies concurrent identity creation, owner-only protected file/directory ACLs, login/logout identity persistence, metadata commands, rename and revocation. Existing Windows HTTPS, agent launchers, Task Scheduler, cancellation and hook fixtures also pass. No production ACL change was required; the fixture needed the established Windows PowerShell module environment.

Registry inspection confirms `nathanfallet/teamcode:latest` and `nathanfallet/teamcode:sha-7175123e00ed75b313a30478722aff6b395fbf14` resolve to the same OCI index, `sha256:26d44812c6685d121b1158d69f047a802cdbccbc251b65147f1b34ad87cc419a`, including `linux/amd64` and `linux/arm64`. This publishes the image; it does not deploy a Kubernetes workload. The 138-test device milestone is accepted across these platform fixtures; full V1 acceptance and its other open gates remain unchanged. The subsequent cloud-session changes are V2 planning documentation only.


## Default-reasoning gate reproduced (2026-10-19)

The opt-in coding harness now distinguishes `--coding-reasoning portable` (existing default, explicitly disabled reasoning) and `default` (no disable override, including removal of inherited `MAX_THINKING_TOKENS`). Task isolation is unchanged. Eight script tests pass, including profile isolation and a diagnostic regression: Codex error events can contain a string `message`, which must not mask the real compatibility failure with an AttributeError.

Both bounded real coding probes imported six verified access-only accounts, then failed before inference dispatch and left the source/test files unchanged. Claude-to-Codex is rejected for `context_management`; Codex-to-Claude is rejected for reasoning context. The owned fixture processes/databases terminated. Separate loopback-only captures show Claude's keep-all thinking edits, explicit 31,999-token manual budget/display option, and Codex's medium/all-turns encrypted reasoning request. See [reproduction, evidence and TeamClaude comparison](reasoning-portability.md). No production decoder was relaxed and no reasoning capability is newly claimed. The next implementation must address the reasoning representation, request controls and stream/history round trips together; removing one rejected field does not close this gate.


## Default-off experimental translation (2026-10-19)

The owner explicitly removed Claude/Codex conversion from V1 release gates and requested an opt-in settings boolean. The persistent model policy now includes `experimentalCrossProviderTranslation`, default false for fresh teams, upgraded saved policies and older API payloads. Administrators can change it in Dashboard → Models or the existing model-policy API. Native Claude Max and Codex pooling and same-provider model/account fallback remain V1; full-default conversion reasoning and conversion-specific portability limits are experimental follow-up.

Disabled targets are skipped before conversion, reservation or provider dispatch, even if shared affinity remembers a previous experimental target. Native targets remain eligible. Cross-only routes return 422 `translation_disabled`; exhausted native targets retain their normal capacity error. Queued admission rechecks the current shared policy, and forecasts exclude disallowed translated activity. Disabling the setting does not replay or terminate work that has already dispatched. Upgrade every replica before relying on this setting; older binaries do not enforce it.

Verified locally:

- **141 JVM tests passed**, zero failures/errors/skips: API 3, client 6, domain 25, infrastructure 74, presentation 11, CLI 20 and app 2. The packaged server built successfully.
- Gateway tests cover default-off behavior in both directions without provider traffic/reservations, opting in to the existing conversion tests, disabling a remembered experimental affinity and retaining native fallback. Existing translation/tool-stream tests still pass with explicit opt-in.
- Real MySQL with two independent pools verifies persistent flag changes, denied admission after another replica disables queued translation, no quota charge for that denial, successful admission after re-enabling, and forecast invalidation/recovery. HTTP tests cover administrator-only mutation, default/legacy JSON behavior, dashboard state and browser origin/CSRF enforcement.
- Eight acceptance-script tests pass. The existing real coding/conversion fixtures now explicitly enable the experiment only in their disposable server. No additional real provider task was run for this feature-flag change.
- The actual macOS native CLI against the packaged server/MySQL passes team bootstrap, password change, browser member creation, device/session commands, restart/reconnect and multi-session revocation. Exposed generated one additive boolean migration; replaying all **19 migrations** generates **zero additional migrations**. Published migrations are unchanged. Module/query conventions and whitespace checks pass.

The pre-flag main commit `951de02` passed [CI run 39253886675](https://github.com/nathanfallet/teamcode/actions/runs/39253886675), including every native platform and image publication. This new feature-flag slice still requires CI after its push. All other V1 requirements remain in scope. The updated V2 roadmap separately records environment/MCP variants and a versioned skill catalog with yes/no access assignments; GitHub synchronization versus CI/API publication remains an open V2 choice, and skills do not use variants.


## Native coding tasks with default reasoning (2026-10-19)

The coding harness now defaults to native-provider routes. Experimental conversion requires explicit `--coding-route translated`, and the cross-provider boundary fixture additionally requires that route selection. `--coding-reasoning default` retains client reasoning defaults; the portable profile is still explicit and separately reproducible. Eight script tests pass, and syntax/whitespace checks pass.

Opt-in real acceptance passed on macOS ARM64 through the actual native launcher, central server and access-only subscription pool, with `experimentalCrossProviderTranslation=false`:

- Claude Code **2.1.302**, requesting `claude-haiku-4-5-20251001`, used only Anthropic. **Seven requests** completed with HTTP 200, known settled usage and `message_stop`.
- Codex **0.163.0**, requesting `gpt-6-luna`, used only Codex. **Four requests** completed with HTTP 200, known settled usage and `response.completed`.
- Each agent read the broken source/tests, reproduced the failure, edited only the source and ran the tests. The independent post-agent run passed all four tests, and the test-file hash stayed unchanged. Claude's Read/Edit/Bash tools and Codex's real tool execution were exercised. No `MAX_THINKING_TOKENS=0`, `alwaysThinkingEnabled=false` or `model_reasoning_effort=none` override was used.
- Six accounts were imported in access-only mode; partial import rejection remained explicit. The source TeamClaude configuration hash stayed unchanged, no refresh ran and the owned processes/disposable database were removed. Only metadata and result assertions were logged, without raw agent content or provider credentials.

See [native reproduction and separate experimental conversion tests](routing.md#real-coding-task-acceptance). This closes these two native default-reasoning coding cases; native quota/reset-boundary behavior, wider workload/architecture coverage and the other V1 gates remain open. It does not turn experimental conversion into a V1 requirement again.


Feature-flag publication acceptance: [CI run 39255977020](https://github.com/nathanfallet/teamcode/actions/runs/39255977020) passed **all jobs** for `4c21dce`, including JVM/replica tests, Helm, actual native macOS/Linux/Windows runtime and multi-architecture Docker publication. Registry inspection confirms `nathanfallet/teamcode:latest` and `nathanfallet/teamcode:sha-4c21dce4fe2f808409a03f517564f2ac94541a9f` share OCI digest `sha256:d8b09036408e6b45a1a7341f84716a201c6dbc8cb0fbc7a0a3a0520ba640fa6b`, containing `linux/amd64` and `linux/arm64`. This accepts the default-off settings slice; it does not claim experimental conversion compatibility or complete V1. Subsequent commits add the native acceptance fixture/evidence and V2 environment-setup documentation without changing production server code.


## Scoped service and CI credentials (2026-10-19)

`ServiceKeyService` now provides one-time creation, metadata listing and revocation through the dashboard, typed SDK/API and native `teamcode keys create|list|revoke`. Keys have explicit `inference`, `usage:read` and/or `guidance` scopes, a 1–90 day lifetime and a 20-record limit per member. They share the owner's ledger, pool, weight, model/effort rules and concurrency limits. Even an administrator-owned service key cannot administer the server or manage credentials. Only an interactive session can create/manage keys; an administrator may manage another eligible same-team member's keys.

The token table stores only a digest, owner, metadata, expiry and nullable scopes. Existing tokens migrate as interactive sessions. Malformed non-null scopes fail closed, and current role/scopes are revalidated during admission/cancellation. Interactive login rotation and device/session views exclude service keys; repeated logins cannot evict independent CI credentials. Password/role changes, resets and member disabling invalidate keys with other sessions. A key can revoke itself through logout. See [scope boundaries, API, lifecycle and TeamClaude reference](service-keys.md). The separate V2 administrative API-key/cloud scope remains future work.

Verified locally before publication:

- **147 JVM tests passed**, zero failures/errors/skips: API 3, client 6, domain 28, infrastructure 75, presentation 13, CLI 20 and app 2. The packaged server and actual macOS ARM64/Linux x64 binaries built successfully. The final presentation test rerun also passes, including escaped labels and absence of secrets in metadata/dashboard refresh.
- Real MySQL with independent connection pools verifies upgrade from the published pre-scope identity schema, shared key metadata/authentication, hash-only storage, owner/team boundaries, administrator-key restrictions, forged-principal rejection, scope denial, expiry/malformed scopes, disabled-member invalidation, atomic competing creation at the 20-key bound and independent interactive-session rotation.
- The same SQL test verifies that a service key and interactive session cannot over-reserve the same member allowance, that revocation/rotation leaves settled spend unchanged, and that revocation through the other pool stops monitored active work. Usage/guidance credentials cannot dispatch inference or bypass their scopes.
- HTTP/SDK tests verify a closed operation allowlist (including unknown future endpoints), inference-only access, own usage/guidance boundaries, blocked administrator operations/password changes, service-cookie rejection, one-time secret responses, metadata redaction and browser origin/CSRF enforcement.
- The enhanced metadata fixture passes with the **actual macOS and Linux native binaries**, including Unicode names and service-key creation/JSON/list/revocation with explicit one-time secret output only. The real native CLI/server/MySQL smoke passes key creation, private CI configuration, scope restrictions, persistence across restart and revocation while preserving the owner's interactive session. An initial fixture attempt observed Docker return before its host-port mapping; the fixture now polls the same owned container with a 30-second bound and checks for terminal state instead of indexing an empty result. The subsequent real-server smoke passed.
- Exposed generated one additive migration; replaying all **20 migrations** produces **zero further migrations**. Published migrations are unchanged. Eight acceptance-script tests, module/query conventions, Python syntax and whitespace checks pass. No real provider requests or personal credentials were used for this slice.

The preceding main `948215d` passed every job in [CI run 39257270320](https://github.com/nathanfallet/teamcode/actions/runs/39257270320), including Docker publication. This new service-key slice still requires cross-platform CI and publication after its push. Groups/additional roles, agent-run registry, complete audit, fairness/forecast, distribution/update and operational V1 gates remain in scope. Experimental cross-provider conversion remains excluded from V1 acceptance by the owner's decision.


Final service-key acceptance: [CI run 39261166929](https://github.com/nathanfallet/teamcode/actions/runs/39261166929) passed **every job** for `c8427f8`: JVM/replica tests, Helm, actual macOS/Linux/Windows native runtime and Docker publication. The Windows metadata fixture passed scoped key creation, one-time JSON output, Unicode labels, metadata listing and revocation, alongside the existing HTTPS, agent launcher, Task Scheduler, cancellation and hook tests. Linux also passed the enhanced native CLI/server/MySQL key lifecycle fixture.

Registry inspection confirms `nathanfallet/teamcode:latest` and `nathanfallet/teamcode:sha-c8427f80d2841375523d60a9d9cf67f4db365cc2` resolve to OCI index `sha256:ee1f0768d88b4597650f1d03b8541da61990e3935e8803020371ad435e3992d5`, with both `linux/amd64` and `linux/arm64` images. Publication does not deploy a cluster. This closes the scoped service/CI credential slice; the remaining V1 gates are unchanged. A subsequent documentation-only acceptance commit does not require rebuilding the already accepted production code/image.


## Administrator audit journal and account-write authorization (2026-10-19)

Implemented the admin-only `AuditService`, `GET /api/v1/audit`, typed SDK `audit(beforeId)` and **Dashboard → Audit**. Reads revalidate current SQL role/session/scope and return at most 50 metadata-only records through team-scoped keyset pagination. The new Exposed-generated `(team_id, id)` index avoids offset scanning. Ordinary members and even administrator-owned service keys are denied. The browser escapes text; both interfaces are private/no-store. See [coverage, API and limitations](audit.md).

Account persistence now requires an explicit `AccountMutationActor`: authenticated human or trusted system operation. Enrollment/reimport, metadata configuration and access-only/refresh-ownership transitions append actor/action/subject metadata in the same team-locked transaction. Current interactive administrator authorization is rechecked after remote identity verification, before any mutation or idempotency replay. A revoked in-flight import cannot later create the account. Idempotent enrollment retries and unchanged handover state do not duplicate events; denied/conflicting writes append none. Bootstrap and successful logout now append metadata as well, without exposing passwords or key values.

Local acceptance:

- **150 JVM tests passed**, zero failures/errors/skips: API 3, client 6, domain 28, infrastructure 77, presentation 14, CLI 20 and app 2. Packaged server and actual macOS ARM64 executable built successfully.
- Two new real-MySQL tests use two independent pools: committed actor/subject attribution, reimport/idempotency/conflicts, revoked/forged/service/foreign-team denial, revocation while provider verification is delayed, fixed-size pagination across appends, team isolation, role/scopes/expiry revalidation and retained bootstrap/logout events.
- HTTP/typed-SDK tests verify administrator-only access, cookie/API separation, escaped metadata, malformed/duplicate/overflow cursors, no-store and older/empty pages. The actual native CLI plus packaged server/MySQL smoke passes bootstrap/password/device/key lifecycle, persistent audit reads across restart and recorded native logout. No real provider requests were made.
- All **21 migrations** replay successfully; running the Exposed generator again produces **zero further migrations**. Published migrations are unchanged. Module/query-convention and whitespace checks pass.
- The first full suite exposed an existing quota-ordering fixture mixing database-clock samples with a host-clock envelope; strict future-envelope rejection could discard the synthetic update. That fixture now supplies its envelope from the same database clock. Its future-window rejection assertion remains; production quota ingestion is unchanged. The subsequent complete suite passes.

Cross-platform CI and image publication for this slice follow its code push. This milestone does not close group/role expansion, agent-run registry, complete operational audit acceptance or the other V1 gates. V2 cloud-session documentation separately includes container repository setup, assigned environment configuration, Git/GitHub/gh authentication and idempotent preparation on restore/transfer.


Audit publication acceptance: [CI run 39264607879](https://github.com/nathanfallet/teamcode/actions/runs/39264607879) passed **every job** for `0181e4006a1049289c3e68ec8d5fe5f61a1cf9cf`: JVM/MySQL and actual two-server replicas, Helm, native macOS/Linux/Windows runtime and Docker publication. Registry inspection confirms `nathanfallet/teamcode:latest` and `nathanfallet/teamcode:sha-0181e4006a1049289c3e68ec8d5fe5f61a1cf9cf` share OCI digest `sha256:f2ac556764718546bf33b2758f561c41b5272c6eaa20f0d700f1d8853b979249`, containing `linux/amd64` and `linux/arm64`. The Helm default continues using `latest` with `Always`; no Kubernetes deployment was performed. The source tree is recorded separately from this accepted production-image revision. This milestone is accepted; full V1 remains open.


## Native release distribution groundwork (2026-10-19)

The build now generates API `BuildInfo.VERSION` from a bounded Gradle version property; both native CLI and packaged server capabilities use it. The default is still `0.1.0-SNAPSHOT`, while an explicit property or version tag selects the compiled version. CI now builds optimized release executables, adds actual macOS Intel/Linux ARM64 runtime checks and stages target/version/commit/hash metadata only after native acceptance. Linux ARM64 is cross-compiled on a supported x64 compiler host, then executed on the actual ARM64 runner. The combined five-target bundle gates image publication; version tags additionally publish the verified binary manifest/checksums and target-specific assets. Tag server images are versioned without replacing main's `latest` or snapshot commit image. See [distribution contracts and remaining acceptance](distribution.md).

Verified locally so far: 43 affected JVM tests pass (API 3, client 6, presentation 14, CLI 20), optimized macOS ARM64 binary and packaged server build with `0.1.0-alpha.1`; actual native/server capability versions match, private configuration/authenticated streaming smoke passes, and the optimized binary passes service registration/reuse/drain/restart/offline recovery. Actual native asset staging passes with a synthetic local commit marker; CI staging supplies the real source commit. The version-generation task correctly switches back to the default snapshot, and malformed numeric prerelease values fail before compilation. Three release-bundle tests cover all five binary headers, full hash/manifest assembly, tampered/mixed/missing/foreign/symlink assets, unsafe filenames, unexpected files and refusal to overwrite output. `actionlint`, module/query conventions and whitespace checks pass. No provider requests were made.

The optimized Linux ARM64 executable also cross-links locally and its ELF architecture is verified. Full five-target runtime CI and actual version-tag publication remain to be accepted. User installers, self-update/rollback, service-aware binary replacement and the other V1 gates remain open; this groundwork does not mark distribution or V1 complete.


Release publisher signing preparation: added independent RSA-3072/PSS-SHA256 manifest signing and verification, fixed MGF1-SHA256/32-byte salt, bounded versioned signature envelope and SHA-256 SubjectPublicKeyInfo key ID. The public root is tracked separately from a dedicated GitHub signing secret and an owner-only local backup. Secret values are never logged, passed as command arguments or inherited by OpenSSL children; temporary private files are removed. Six release tests pass, covering byte tampering, unknown/mismatched key pairs, algorithm/schema substitution, malformed/oversized signatures and overwrite refusal. Publication is being gated on signature verification; native verifier/key rotation/updater tests and actual tagged publication remain required. A direct Linux ARM64 launch in the existing minimal MySQL base image reached the loader but failed because that image lacks `libcrypt.so.1`; this dependency is documented and is not counted as native runtime acceptance. The dedicated actual ARM64 CI host provides the required runtime test instead.


Local native test preparation: `:cli:macosArm64Test` passes against the actual native test executable. The optimized macOS Intel target also cross-compiles on Apple Silicon in the local toolchain (1 minute 8 seconds); CI is moving that compilation to the Apple Silicon builder while retaining all runtime smoke tests on a real Intel runner. Both cross targets share one build/runtime matrix; jobs now have explicit deadlines. This changes the compiler host, not the target-runtime acceptance requirement.


Optimized five-target publication acceptance: [CI run 39267870477](https://github.com/nathanfallet/teamcode/actions/runs/39267870477) passed **every job** for `7121be7065b7e60433a99e81463ffc0c7e99a9ca`, including actual macOS ARM64/Intel, Linux ARM64/x64 and Windows x64 runtime, the complete bundle assembly, JVM/MySQL/replicas, Helm and image publication. All five downloaded bundle binaries independently match their native header, declared version/commit, size and hash; every checksum matches. The actual downloaded snapshot manifest signs and verifies locally with the dedicated real publisher key, without publishing a release. Registry inspection confirms `latest` and `sha-7121be7065b7e60433a99e81463ffc0c7e99a9ca` share OCI digest `sha256:4bf452d8abbc9171f7e7fb7963a566ab0ae9a5efeabe9ef9ecf6a1a3c289289f`, containing `linux/amd64` and `linux/arm64`. No cluster deployment occurred.

The subsequent publisher-signature and faster cross-build matrix changes have separate acceptance: seven release-script tests pass locally, including refusal when manifest bytes change during signing. Signing/verifying uses bounded private snapshots of the exact payload; successful asset uploads remain a draft until the final publish step. The public trust key ID is `9ed0bd1107ac7bbb7387c0ce3c933b51c66a095412a2bab0ad221a84f15b5339`. Native updater verification, key rotation, first real tagged publication, installers/update/rollback and other V1 work remain open. New CI for these follow-up changes is still required after push.


Publisher groundwork publication acceptance: [CI run 39271207716](https://github.com/nathanfallet/teamcode/actions/runs/39271207716) passed every required job for `30e335bd85ebba4e548c42b049acb9c58d615930`, including actual runtime on all five native OS/CPU targets, bundle assembly and Docker publication. The tag-only release job was correctly skipped for the main-branch push. Registry inspection confirms `latest` and the immutable commit image share the same `linux/amd64` and `linux/arm64` OCI digest `sha256:84fa63b73f5e7ddbfbe548cd41c48bfe23d0d733160fe34efce21d1cc0b12ece`. Native verification/update and first actual tagged publication remain separate gates.


## Native release authentication (2026-10-19)

Implemented offline `teamcode verify-release --manifest PATH --signature PATH`, optionally with both `--binary PATH --target TARGET`. The CLI embeds build-generated RSA-3072 public trust roots from `release/trust`; neither the server nor downloaded metadata can supply a root. It authenticates the exact bounded bytes before parsing manifest fields, validates schema/version/tag/commit/canonical assets and streams binary SHA-256 with exact length enforcement before any execution. This command does not install or update a binary; those V1 commands remain pending.

Cryptography uses Apple Security, Windows Crypt32/BCrypt and runtime OpenSSL EVP on Linux, with a JCE test implementation. Common public vectors use disposable RSA keys and exercise fixed PSS SHA-256/MGF1-SHA256/salt-32 parameters, wrong padding/salt/MGF, unknown/spoofed roots, an explicit old→bridge→new key rotation, authenticated invalid metadata, byte bounds, future-target compatibility and binary mismatch. Private fixture keys are discarded. The public publisher fixture separately checks the actual compiled production root without exposing its private signing secret to native/PR jobs.

Local evidence: **31 affected JVM tests** (API 3, CLI 28) and **17 actual macOS ARM64 common tests** pass. The real optimized macOS CLI passes the publisher-root smoke with temporary Unicode paths, signature/envelope/size/file failures and no team configuration. It also verifies the authentic manifest and actual macOS binary downloaded from the accepted `7121be7` snapshot bundle. The new smoke exposed an unclassified invalid-UTF-8 error; a regression test failed with `MalformedInputException` before the fix and now passes with bounded release-specific errors for both the signature envelope and an authenticated malformed payload. The Linux x64 executable also passes all 17 common tests and the compiled publisher-root fixture in an owned x64 Temurin container under local emulation; this is adapter evidence, not actual-host architecture acceptance. Final macOS/Linux/Windows cross-linking is checked separately. No provider calls were made.

CI now builds/runs the common native test executable as well as the optimized CLI and publisher-root smoke on all five actual target hosts. Cross-target tests are built on supported compiler hosts and uploaded separately, then executed on actual macOS Intel/Linux ARM64 hosts. Five-target acceptance follows this push; it is not inferred from local JVM/macOS tests. Installer/update/rollback and first tagged release remain open. V2 work is explicitly isolated on its own branches/worktrees and must not merge into main before V1 acceptance.

## V2 foundation: environment/MCP variants and per-member assignments (2026-10-19)

Server-side model, persistence, administrator API/dashboard and effective resolution for [variants](variants.md); no gateway, cloud session or CLI consumer yet. Evidence: `VariantPersistenceTest` (real MySQL, two replica pools) shows two members receiving different variants of the same variable and of the same MCP server, a third member without an assignment receiving nothing, an exclusion surviving repeated bulk assignment, one unreadable envelope withholding only its own slot, members with a temporary password receiving nothing, changes visible on the next resolution through the other replica, encryption at rest with row-bound contexts, metadata-only audit and administrator-only access; `VariantRoutesTest` covers API/dashboard authorization, CSRF/origin, escaping and write-only secrets; `VariantResolutionTest` covers precedence and validation. No real provider was called.

## V2: MCP gateway, server side (2026-10-19)

Authenticated stateless Streamable HTTP gateway with one endpoint per assigned upstream server (`/mcp/<server>`, never one merged server) over the member's effective MCP variants, with per-variant tool allowlists, per-call reauthorization under the team lock, encrypted shared/delegated upstream credentials, bounded execution with MySQL-shared limits and a metadata-only journal; see [MCP gateway](mcp-gateway.md). `teamcode run` connects the launched agent to each assigned server through the local proxy, for the launch only; member-driven delegated OAuth remains planned. Evidence: `McpGatewayAcceptanceTest` (real MySQL, two replica pools, in-process fake upstream MCP servers) shows two members listing different servers and seeing and invoking only their assigned tools, unauthorized direct calls failing without reaching an upstream, revocation, policy and exclusion changes applying to the next call, and no upstream credential in any response; `McpAccessPersistenceTest`, `HttpMcpUpstreamTest` and `McpGatewayTest` cover admission, limits, retention, framing, bounds and denial paths; `LocalProxyTest` and `AgentLaunchTest` cover the local relay and the per-launch agent configuration. No real MCP server, provider or agent was called.
