# Unfinished V1 work deferred after 0.1.0

## Owner decision and baseline

On 2026-10-20 the owner finalized V1: finish startup before login, then publish the unprefixed `0.1.0` tag/release. The additional validation and operational work below moves to V2 and will be handled by another agent in a PR. This is an explicit scope decision, not a claim that the remaining scenarios have passed.

This brief covers only unfinished implementation or validation from the original V1 scope. It is not the V2 product plan and does not assign new V2 features. Cloud sessions, centralized MCP, skills and open-source providers remain in the separate [product roadmap](roadmap.md).

Baseline is now published: [0.1.0](https://github.com/nathanfallet/teamcode/releases/tag/0.1.0), source `b191c0475f9dcb999c8782ba882558dcfc73b211`. [Full publication acceptance](https://github.com/nathanfallet/teamcode/actions/runs/39344895836) passed, including five-target native runtimes and signed installation/bootstrap/automatic updates. Anonymous public-download installation was additionally verified on macOS ARM64, and the released chart renders with bundled/external MySQL. These results do not close the extended scenarios below.

Start from `0.1.0`, fetch current `origin/main` and rebase the working branch before review. Other V2 agents already own separate branches/worktrees; preserve them and coordinate overlaps. Squash-merge PRs. Repository content stays English. Keep provider credentials, prompts and response bodies out of git/logs. Never stop another person's processes or Gradle daemons. Do not deploy an operator/user cluster.

Use [progress](progress.md), [detailed evidence](status.md) and the [TeamClaude comparison](teamclaude-comparison.md). Exact logs in `/tmp` are local reproduction evidence, not portable release assets; reproduce assertions on your machine/runner. Successful CI proves only the scenarios its scripts execute.

Use the [end-to-end testing playbook](testing.md) for prerequisites, native build commands, isolated real-account import/upsert, tiny Claude/Codex prompts, real coding tasks, dispatch/usage evidence, hooks/browser fixtures, services and signed distribution checks. It distinguishes actual subscriptions from synthetic upstreams and records what each method does not establish.

## Validation log

Per-section result of the post-release pass of 2026-10-20 (source `1726630`, the head of `main` after `0.1.0`; macOS 27.0.1 ARM64, JDK 21, Docker Desktop, Claude Code 2.1.303, Codex 0.163.0). Every result is **synthetic** (no subscription requests) unless stated. Anything not listed as passed is not validated. Commands are listed after the table.

| Section | State | Evidence and limits |
| --- | --- | --- |
| 1. Boot and lifecycle | Partly validated | Default-user `smoke-service.py` passes natively on macOS ARM64. `--at-boot` could not run: no `sudo -n` authorization, so the root LaunchDaemon, actual boot, logout, offline recovery and locked-home scenarios are **not validated**; `service print --at-boot` renders a non-root headless manifest. Linux and Windows boot/password scenarios are **not validated** (no such host). |
| 2. Native clients | Not validated | Needs an explicitly authorized provider configuration and a live Codex browser login; neither was provided. Provider-free native run/launcher smokes pass. Quota-exhaustion failover, Codex Windows hooks and subagent fan-out are not validated. |
| 3. Scheduling and performance | Not validated | The controlled two-replica `FairSchedulingHttpTest` passes inside `:app:test`. Sustained multi-replica workloads, latency/memory/SQL cost measurements and SLOs need a purpose-built bounded benchmark and are not done. |
| 4. Predictive control and hooks | Partly validated | Real Claude 2.1.303 and Codex 0.163.0 guidance smokes, agent-run registry and the live forecast browser fixture pass on macOS. Real Codex on Windows, outside-proxy spend replay, forecast-error measurement and fan-out are not validated. |
| 5. Administration and audit | Partly validated | Team, device, replica and full-database restore acceptance pass. The upgrade/rollback procedure is now written in [operations](operations.md#rolling-upgrade-and-rollback) (not run on a cluster). Role-change walkthrough, CSRF/escaping review and audit-coverage review are not done. |
| 6. Distribution | Mostly validated | Workflow 39344895836 conclusions and the 11 release assets were inspected; the released chart renders and passes `check-helm.py` for bundled and external MySQL, including `image.tag=0.1.0`; the public `install.sh` installs 0.1.0 on a clean macOS ARM64 home and into a custom root with spaces and Unicode. No second release exists, so cross-version update/rollback, Intel macOS, Linux and PowerShell 5.1/7 public-download installs are not validated here. |

Commands (from the repository root; `$B` is `cli/build/bin/macosArm64/releaseExecutable/teamcode.kexe`, `$J` is `app/build/libs/app-all.jar`):

```sh
python3 scripts/check-module-graph.py
python3 -m unittest discover -s scripts -p 'test_acceptance_*.py'   # 8 tests
python3 -m unittest discover -s scripts -p 'test_release_*.py'      # 21 tests
./gradlew --no-daemon :api:jvmTest :client:jvmTest :domain:test :infrastructure:test \
  :presentation:test :cli:jvmTest :app:test :app:buildFatJar \
  :cli:linkReleaseExecutableMacosArm64 :cli:macosArm64Test           # BUILD SUCCESSFUL in 8m 43s
python3 scripts/smoke-native.py --binary "$B" --jar "$J"
python3 scripts/smoke-run.py --binary "$B" --jar "$J"
python3 scripts/smoke-queued-cancel.py --binary "$B"
python3 scripts/smoke-devices.py --binary "$B"
python3 scripts/smoke-hook.py --binary "$B"
python3 scripts/smoke-team.py --binary "$B" --jar "$J"
python3 scripts/smoke-replicas.py --jar "$J"
python3 scripts/smoke-service.py --binary "$B"                       # passes
python3 scripts/smoke-service.py --binary "$B" --at-boot             # refused: no sudo -n
python3 scripts/smoke-claude-guidance.py --binary "$B" --claude ~/.local/bin/claude
python3 scripts/smoke-codex-guidance.py --binary "$B" --codex ~/.local/bin/codex
./gradlew --no-daemon :app:smokeAgentRuns -PteamcodeCliBinary="$B" :app:smokeForecastBrowser
python3 scripts/check-helm.py                                        # also against the downloaded teamcode-0.1.0.tgz
curl -fsSL https://github.com/nathanfallet/teamcode/releases/download/0.1.0/install.sh | sh   # empty HOME
teamcode install --version=0.1.0 --root "<private dir with spaces and non-ASCII>"
```

All of the above exited 0 except the `--at-boot` fixture. The executable was built from `1726630` (reporting `0.1.0-SNAPSHOT`); the installed public binaries report `0.1.0`. Smoke logs are local, not portable evidence.

## 1. Boot startup and workstation lifecycle

Implementation: `teamcode service install --at-boot` (`--system` alias). macOS uses a root-owned LaunchDaemon that drops to the configuration owner's username; Linux enables user-manager lingering; Windows uses a least-privilege boot-triggered task with password logon. Windows keeps the password in Task Scheduler, not TeamCode configuration. Default installation remains user-logon startup. See [service setup](services.md) and [Windows](windows.md).

- Validate fresh real workstation boot before login, subsequent login, logout, offline boot/recovery and an unavailable/locked home directory on macOS/Linux/Windows. Distinguish disk unlock from OS startup.
- Run `python3 scripts/smoke-service.py --binary /absolute/teamcode --at-boot` on macOS/Linux with authorization for its uniquely named fixture. It exercises actual registration, identity, token reload, outages, drain/restart, ownership refusal and cleanup; it does not reboot the computer. Never disable shared Linux lingering during cleanup.
- Windows: test an elevated terminal with a supported Windows account/password, masked credential prompt, actual boot task and literal Unicode paths. Test wrong/expired password, local/domain policy denial, password rotation and reinstallation. Prove authenticated HTTPS access before interactive login and inspect effective task identity/action/trigger. Do not substitute a PIN or silently fall back to an interactive/S4U logon.
- Verify managed install/update/rollback and the optional automatic updater with a boot registration. macOS quiet update operations use noninteractive sudo; lack of authorization must fail safely and preserve recoverable installation state. Improve that operator experience without granting broad passwordless sudo or killing agents.
- Retain default user-service runtime regression coverage in `smoke-service.py`, `smoke-windows.py`, `smoke-installation.py` and `smoke-automatic-updates.py`.

## 2. Native clients, enrollment and quota boundaries

Existing evidence includes actual macOS Claude/Haiku and Codex/Luna requests and coding tasks, import/upsert and session-registry attribution. Conversion stays experimental/default-off; OSS providers remain V2.

- Finish live Codex browser enrollment and full reconnect/upsert. Verify server-side identity/subscription validation, unchanged source files, explicit access-only versus refresh takeover, preserved IDs/policy/history and partial import failures.
- Exercise real multi-turn/tool loops through `teamcode run claude -- ...` and `teamcode run codex -- ...` on advertised clients/platforms, especially real Codex Windows hooks and subprocess/subagent fan-out.
- Demonstrate same-provider account/model failover at quota exhaustion/reset and revoked access, including a tool-result boundary. Preserve native reasoning/tool fields and conversation affinity. Do not replay output after committed bytes.
- Use opt-in `scripts/acceptance-provider.py` and the documented [coding/fallback acceptance](routing.md) where applicable; inspect current CLI options before invocation. Real-provider tests need authorized accounts and strict request/token limits; ordinary PR CI remains synthetic.

## 3. Scheduling, overflow, backpressure and performance

Existing independent-pool MySQL tests cover atomic reservations, member rotation, group/run caps, source/home budgets, explicit reserve access/revocation and immutable reconciliation attribution. Controlled replica HTTP fixtures cover cancellation and queue overflow; the new overflow cases do not prove sustained production fairness.

- Extend [scheduling acceptance](scheduling-acceptance.md) with sustained real HTTP workloads across replicas and differently weighted members. Include protected colleagues, borrowers from distinct home pools, interactive/background work, cancellation/revocation, queue bounds and changing source availability.
- Measure gateway-added and first-byte latency, queue wait, success/failure classes, memory per stream, thread/client creation and SQL polling/lock cost. Bound every run; hold no database connection while waiting for a provider/client.
- Inspect the inference engine setup and per-request transport lifecycle. Optimize demonstrated costs with before/after measurements and preserve streaming cancellation and shared-state invariants.
- Produce a backlog from measured bottlenecks and choose defensible initial SLOs; do not invent throughput/hosting-cost claims from unit tests.

## 4. Predictive control and native hook delivery

- Replay realistic quota observations including outside-proxy spend, changing burn, rounded/unknown resets, stale samples and reset discontinuities. Measure forecast error and verify tightening/recovery without oscillation across replicas.
- Include home/reserve conversations, multiple active targets, unrelated idle providers, scoped run limits and subagent fan-out. An idle account/conversation must not hide pressure on another active pin; fresh admission remains authoritative.
- Prove actual Windows Codex hook delivery and wider browser behavior. Retain observation-only silence and distinguish CLI receipt, hook emission and model compliance.
- Extend `scripts/smoke-forecast-browser.mjs` and existing fixture/replay tests rather than introducing a second forecasting calculation.

## 5. Administration, audit and operations

- Finish the role/device/service-key/audit walkthrough using current owner/admin/member identities. Test current authorization after revocation and role changes, browser CSRF/escaping, paginated history and metadata-only logging.
- Review audit coverage for owner recovery, enrollment/handover, policy/pool changes, group/run control, overrides and unknown-usage reconciliation. Original pool/source/group/run attribution must survive moves and retained-history cleanup.
- Build on the existing full-database backup/restore acceptance: canonical schema/row equality, encrypted credentials, management HTTP access and preserved auth/quota state already pass locally. Document operator backup, restore, rolling upgrade, encryption-key continuity and non-destructive rollback procedures.
- Validate mixed-version behavior only where explicitly supported. All replicas must support new enforcement features before they are enabled; keep additive Exposed-generated migrations and never modify published migrations or write raw SQL.

## 6. Distribution follow-through

- Inspect the actual `0.1.0` workflow conclusions/assets before claiming five-target acceptance. The built-in release workflow verifies native bundles/signatures, installation/bootstrap/updates, JVM/replica behavior, Helm and images before publication.
- Add clean-machine/public-download install and subsequent cross-version update/rollback checks beyond those built-in scenarios. Cover Windows PowerShell 5.1/7, Linux shared-library requirements, macOS ARM64/Intel and custom private installation/configuration roots.
- Verify the deployable released chart against bundled/external MySQL and the intended image tags; deployments belong to users. `latest` follows tested main images; pin `image.tag: "0.1.0"` when exact version matching is desired.
- Keep ordinary push/PR CI Linux-only. Batch deliberate paid native validation; do not dispatch the full matrix per commit or expand it into redundant platform combinations.

## PR evidence expected

For each completed scenario, state the tested commit/tag, OS/CPU, exact bounded command, synthetic versus actual provider, observed assertions and remaining limits. Add focused regression tests for defects, update `docs/status.md` and this checklist, and distinguish new evidence from earlier history. Keep experimental conversion and the separately owned cloud/MCP/skill work out of this validation PR unless explicitly coordinated.
