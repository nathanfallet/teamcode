# End-to-end testing playbook

This is the reproducible method used during V1 development, including real subscription tests. Use it with the [unfinished V1 brief](v2-validation-handoff.md) and [routing acceptance](routing.md). A command below is a procedure, not a claim that it has passed on every operating system.

## Prerequisites and isolation

- Use a dedicated checkout/worktree and record `git rev-parse HEAD`, operating system/CPU, `java -version`, the native `teamcode version`, and the actual Claude/Codex client versions.
- Install JDK 21, Python 3, Docker with permission to start isolated MySQL 8.4 containers, and the repository's Gradle wrapper. Browser fixtures additionally need Node 22+ and Chrome. Linux needs the transport/crypto libraries in [bootstrap requirements](bootstrap.md).
- Build and execute the native target for the host. JVM tests or successful cross-compilation do not establish native runtime behavior.
- Run Gradle commands sequentially in each checkout. Never stop other processes, shared Gradle daemons, existing services or somebody else's container. A port/lock collision is not permission to kill its owner.
- Real-provider tests use an explicitly authorized TeamClaude configuration. Pass only its path; never copy its credentials into the repository, reports or command arguments. Leave the source application and refresh ownership unchanged.

The provider harness starts its own MySQL container, encrypted database, server, native proxy and temporary agent configuration/workspace. It imports with `--access-only`, checks the source file's SHA-256, and removes its resources in `finally`. It does not refresh source credentials. Expired/unsupported entries can produce an explicit partial import while usable accounts still succeed. Access-only acceptance does **not** prove refresh takeover or browser enrollment.

Examples below use a macOS ARM64 host. Choose the matching target and executable path:

| Host | Gradle target suffix | Output directory | Executable |
| --- | --- | --- | --- |
| macOS Apple Silicon | `MacosArm64` | `macosArm64` | `teamcode.kexe` |
| Linux x64 | `LinuxX64` | `linuxX64` | `teamcode.kexe` |
| Linux ARM64 runtime | `LinuxArm64` | `linuxArm64` | `teamcode.kexe` |
| Windows x64 | `MingwX64` | `mingwX64` | `teamcode.exe` |

Linux ARM64 compilation uses an x64 compiler host; execute the resulting binary on ARM64. The existing full workflow handles this split. The real-provider Python harness has been exercised on macOS; its POSIX executable symlinks and real Windows client behavior still need platform acceptance.

## 1. Provider-free checks first

```sh
python3 scripts/check-module-graph.py
python3 -m unittest discover -s scripts -p 'test_acceptance_*.py'
python3 -m unittest discover -s scripts -p 'test_release_*.py'
./gradlew :api:jvmTest :client:jvmTest :domain:test :infrastructure:test \
  :presentation:test :cli:jvmTest :app:test :app:buildFatJar \
  :cli:linkReleaseExecutableMacosArm64 :cli:macosArm64Test
```

Set task-specific variables in your own shell; these contain paths, not credentials:

```sh
teamcode_binary="$PWD/cli/build/bin/macosArm64/releaseExecutable/teamcode.kexe"
teamcode_jar="$PWD/app/build/libs/app-all.jar"
teamcode_source="/absolute/path/to/authorized/teamclaude.json"
```

Run the actual native binary through isolated synthetic upstreams:

```sh
python3 scripts/smoke-native.py --binary "$teamcode_binary" --jar "$teamcode_jar"
python3 scripts/smoke-run.py --binary "$teamcode_binary" --jar "$teamcode_jar"
python3 scripts/smoke-queued-cancel.py --binary "$teamcode_binary"
python3 scripts/smoke-devices.py --binary "$teamcode_binary"
python3 scripts/smoke-hook.py --binary "$teamcode_binary"
python3 scripts/smoke-team.py --binary "$teamcode_binary" --jar "$teamcode_jar"
python3 scripts/smoke-replicas.py --jar "$teamcode_jar"
```

These establish real process/native/network behavior with synthetic provider responses: authentication, TLS/streaming, literal launcher arguments and exit status, cancellation while queued, devices, hook protocol, persistent team management and shared database behavior across server processes. They do not consume subscriptions. `smoke-team.py` checks the initial `admin` username with the `owner` role, password replacement, browser member creation, service keys, restart persistence, revocation and audit. Replica scenarios are controlled correctness tests, not sustained-load benchmarks.

Hosted MCP servers (transport stdio) with the real server and [MCP host](mcp.md) jars, two replicas and one isolated MySQL:

```sh
./gradlew :app:buildFatJar :mcp:buildFatJar
python3 scripts/smoke-mcp-stdio.py --jar "$teamcode_jar" --host-jar "$PWD/mcp/build/libs/mcp-all.jar"
python3 scripts/smoke-mcp-stdio.py --jar "$teamcode_jar" --host-jar "$PWD/mcp/build/libs/mcp-all.jar" --fixture
```

An administrator declares a `SHARED` npm server (`@modelcontextprotocol/server-everything@2.0.0`) and a `PER_MEMBER` PyPI server (`mcp-server-fetch==2026.10.24`) through the API, a member lists and calls them through the gateway as an agent does, and the host is stopped and started again; no answer or log may hold a secret. The default run downloads the real packages (`npx`, `uvx` and Node.js on `PATH`, network needed); `--fixture` runs the host's Node.js fixture server instead, offline; CI runs the `--fixture` mode after the replica smoke. `scripts/smoke-mcp-image.py` checks the host image itself.

## 2. Small real Claude/Codex requests

Start with one agent rather than a whole matrix. The following launches actual Claude Code through `teamcode run claude`, requests a short Haiku reply and verifies server dispatch/settlement:

```sh
python3 scripts/acceptance-provider.py \
  --binary "$teamcode_binary" --jar "$teamcode_jar" --source "$teamcode_source" \
  --launcher --model claude-haiku-4-5-20251001 \
  --claude /absolute/path/to/actual/claude
```

For Codex instead:

```sh
python3 scripts/acceptance-provider.py \
  --binary "$teamcode_binary" --jar "$teamcode_jar" --source "$teamcode_source" \
  --launcher --skip-claude --codex-model gpt-6-luna \
  --codex /absolute/path/to/actual/codex
```

Use an actual agent executable, avoiding terminal integration shims. Model IDs are explicit test inputs, not permanent availability guarantees; check the account's supported models before changing them. The existing tiny tests have a 90-second agent timeout. Their brief prompt is not a guaranteed token/spend ceiling. They still perform account verification and imports; choose bounded individual runs and check available quota first.

Expected evidence:

- Native import reports verified accounts; reimport retains IDs and advances credential versions. Keep the default reimport check for enrollment work. `--skip-reimport` is only a shortcut after that behavior was already verified.
- The real client exits successfully and returns `TEAMCODE_OK` in the parsed response.
- `TeamCode inference` metadata shows the intended `provider` and `model`, HTTP 200 and settled usage. Correlate the `request` ID across dispatch and settlement; `units=unknown` is not proof of measured usage.
- Launcher runs also validate the durable run registry, completion/exit information and native session attribution. Read the printed registry metadata; a successful prompt alone does not prove correct attribution.
- The source configuration hash is unchanged. The harness destroys its test database and configuration afterward; it does not enroll accounts into your deployed server.

The harness isolates personal agent homes/configuration and disables unrelated telemetry, tools and hooks for the tiny prompt. This proves the selected native route; it does not prove a user's full personal configuration or quota reset behavior.

## 3. Real coding task and multi-turn tools

After tiny prompts pass, exercise the real launcher, client, tools, proxy, provider and accounting together:

```sh
python3 scripts/acceptance-provider.py \
  --binary "$teamcode_binary" --jar "$teamcode_jar" --source "$teamcode_source" \
  --coding-task both --coding-route native --coding-reasoning default \
  --model claude-haiku-4-5-20251001 --codex-model gpt-6-luna \
  --claude /absolute/path/to/actual/claude --codex /absolute/path/to/actual/codex
```

Use `--coding-task claude` or `codex` to narrow a regression. `both` executes sequentially. The harness creates a broken Python source file and tests in a disposable workspace. Passing requires actual tool execution, a corrected source file, an unchanged test file, four independently rerun assertions, multiple provider dispatches and usage settlements. Native routing leaves experimental conversion disabled. The `default` profile retains the client's default reasoning controls while isolating unrelated configuration.

Each coding agent has a 180-second deadline; Claude additionally has a twelve-turn cap. Codex has no equivalent fixed turn/token cap here. These bounds do not guarantee a particular credit cost. A portable reasoning-disabled run is a separate compatibility profile and must not be reported as default-reasoning acceptance.

V1 evidence includes Claude Code 2.1.302 and Codex 0.163.0 on macOS ARM64, with native Haiku/Luna coding tasks. Record your own versions/results rather than carrying those results forward to a newer client automatically.

## 4. Fallback without deliberately exhausting subscriptions

For synthetic same-provider quota/auth rejection, use domain/infrastructure tests and the controlled HTTP fixtures first. Do not burn a subscription to manufacture a 429.

The existing real-client `--coding-fallback` fixture is specifically an **experimental cross-provider** test. It requires `--coding-route translated`; it cannot currently establish native-only account failover. It disables source-provider availability only in its disposable database at a real tool-result boundary, preserves the original request history/route and restores account settings afterward. Examples and exact assertions are in [routing acceptance](routing.md#mid-conversation-fallback-acceptance).

This establishes an actual conversation/provider switch under controlled availability. It does not establish a real provider quota reset, opaque reasoning portability or all default client settings. Conversion stays default-off, and extending its acceptance is outside the unfinished V1 brief. Native-provider quota/reset boundaries remain a separate pending validation.

## 5. Guidance, run registry and browser

Synthetic hook protocol:

```sh
python3 scripts/smoke-hook.py --binary "$teamcode_binary"
```

On macOS/Linux, actual installed agent hook integrations against synthetic local responses, without subscription inference:

```sh
python3 scripts/smoke-claude-guidance.py --binary "$teamcode_binary" --claude /absolute/path/to/actual/claude
python3 scripts/smoke-codex-guidance.py --binary "$teamcode_binary" --codex /absolute/path/to/actual/codex
./gradlew :app:smokeAgentRuns -PteamcodeCliBinary="$teamcode_binary"
./gradlew :app:smokeForecastBrowser
```

Files sent to a session, read by the real Claude client (docker for MySQL and an S3-compatible bucket; no provider call):

```sh
python3 scripts/smoke-session-files.py --binary "$teamcode_binary" --jar "$teamcode_jar" --claude /absolute/path/to/actual/claude
```

It starts the server jar, a Versity S3 Gateway behind a one-day self-signed certificate (artifact storage requires https; only the server's JVM trusts it) and a remote session hosted by the native CLI. It checks the `file_storage_not_configured` refusal before a bucket exists, saves and tests the bucket, sends a file with `/attach` from `teamcode attach`, and checks that the actual Claude reads it with its Read tool at the path the host wrote (a `teamcode-<uid>/<session>` directory outside the working directory, mode 0600) and that the sent message names the file without that path. Like the guidance fixtures above it is opt-in and not in pull-request CI, which has no Claude executable; the same flow is covered without one by `CloudFilePersistenceTest`, the host tests and the CLI and app tests.

The Gradle tasks own the MySQL/server setup and pass private loopback fixture information to their child scripts. Do not invoke `smoke-agent-runs.py` or `smoke-forecast-browser.mjs` without that fixture input. The browser task writes its screenshot under `app/build/preview/forecasts-live.png`. See [agent guidance](agent-guidance.md) for Codex hook trust and delivery semantics.

Separate four assertions: the server issued advice, the CLI received it, the native hook emitted it, and the model followed it. The first three do not prove the fourth. Likewise, synthetic forecast replay validates control rules, not forecast accuracy under outside-proxy spend or real load.

## 6. OS services, boot and signed updates

On macOS/Linux, run the owned default service fixture, then explicitly authorized boot-mode fixture:

```sh
python3 scripts/smoke-service.py --binary "$teamcode_binary"
python3 scripts/smoke-service.py --binary "$teamcode_binary" --at-boot
```

Boot testing needs administrator authorization; the POSIX fixture checks `sudo -n` first. It creates and removes a uniquely named registration. Linux needs a working systemd user manager and retains shared lingering during cleanup. Never disable lingering or alter another application's service. These tests do not reboot the machine. Actual logout/reboot/pre-login network access and unavailable encrypted homes remain workstation tests in the brief.

Windows native coverage uses `scripts/smoke-windows.py --binary /path/to/teamcode.exe`. Its `--allow-machine-test-certificate` option is for a disposable authorized test host: it temporarily changes machine trust for its owned TLS fixture. Windows boot/password lifecycle needs a real elevated same-user session and the separate procedure in [services](services.md); do not substitute an interactive task for a boot test.

Signed distribution checks consume the **same publisher-signed release and installation-baseline bundles** produced by the full workflow. Use its `installation-fixture.py`, `smoke-installation.py`, `smoke-bootstrap.py`, `smoke-automatic-updates.py` and `smoke-service-setup.py` steps from [Build](../.github/workflows/build.yml), with their exact bundle/version/target inputs. Never regenerate the publisher key or relabel one executable as two versions. See [distribution](distribution.md), [bootstrap](bootstrap.md) and [automatic updates](automatic-updates.md).

The release workflow covers native install/service switching, generated bootstrap and independent update jobs on actual targets. Clean public-download installation, real reboots, boot-mode update authorization and future cross-version upgrades are distinct follow-through scenarios. Validate the Helm archive with `scripts/check-helm.py` and rendered Kubernetes schemas; deployment remains the user's responsibility.

## Reporting and diagnosing a failure

For each PR, record commit/tag, host/CPU, actual native/client versions, exact command, fixture versus real subscription, expected assertions, observed result and remaining limits. Keep sanitized metadata-only output outside git until reviewed. Do not attach tokens, raw agent conversations, HTTP bodies or initial bootstrap passwords. Match request IDs and terminal usage, not just HTTP 200 or an agent's success claim.

The harness intentionally withholds raw subprocess output and cleans temporary files on failure. If more evidence is needed, add a bounded diagnostic that exports only operation/error categories and metadata before cleanup; do not dump captured stdout/stderr or preserve an unredacted temporary directory in the PR. Reproduce a discovered defect with a focused synthetic regression before another real-provider attempt. Do not repeat broad real-agent or paid-platform matrices for a fixture-only investigation.

Keep ordinary push/PR CI Linux-only. Full native/signing/installation matrices are deliberate release checks, not something to dispatch after every commit. Documentation-only follow-up does not require another native matrix. For behavior references, inspect the corresponding TeamClaude source/tests listed in the [comparison ledger](teamclaude-comparison.md).
