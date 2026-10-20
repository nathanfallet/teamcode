# Opt-in automatic native updates

Managed user installations can register an independent OS job for signed updates. It is **off by default** and separate from informational release notifications. Current platform evidence is recorded in [status](status.md); compiling the adapters does not prove actual Windows/Linux scheduling or a workstation reboot.

```sh
teamcode update --automatic on
teamcode update --automatic status
teamcode update --automatic off
teamcode update --automatic uninstall
```

Use `--root ABSOLUTE_MANAGED_ROOT` to manage a specific installation. Source/manual copies do not schedule themselves; the selected managed layout and its launcher/payload must validate. POSIX root never runs automatic updates. Installation prerequisites and the first pending public `0.1.0` release remain described in [distribution](distribution.md) and [bootstrap](bootstrap.md).

## Scheduling and authentication

An explicit opt-in records a current-user job with an installation-specific random ID. It invokes the stable managed launcher with `update --automatic-run --root ...`. The job is separate from the proxy's registration, process tree and systemd control group, so stopping the proxy does not kill the updater. No shell profile, global PATH, personal agent configuration or provider credential is changed.

| System | User job |
| --- | --- |
| macOS | Separate LaunchAgent, initial start request and hourly `StartInterval` |
| Linux | Separate systemd user oneshot service and hourly persistent timer |
| Windows x64 | Separate hourly Task Scheduler task, current interactive user, least privilege and no simultaneous duplicate instance |

The Linux timer uses [calendar and persistence semantics](https://www.freedesktop.org/software/systemd/man/257/systemd.timer.html). Windows uses the [Task Scheduler repetition schema](https://learn.microsoft.com/en-us/windows/win32/taskschd/taskschedulerschema-repetition-triggerbasetype-element). These are current-user schedules: logged-out/offline/asleep behavior follows the OS manager. They do not implement the separate V1 pre-login system-service requirement.

The private policy stores the selected configuration path as metadata; no login is copied into a task definition or command argument. This matters when the original login uses a custom `XDG_CONFIG_HOME`: the OS manager may have a different environment. Installation compatibility checks and management of a matching proxy service explicitly read the original saved configuration. The normal server compatibility check remains mandatory when a login exists. A stopped proxy stays stopped, and a source/unrelated proxy registration is not adopted.

## Verification and activation

Each job considers only a newer **stable** unprefixed SemVer release. It uses the existing credential-free release transport, compiled publisher roots, exact binary hash/length checks and compatibility probe. The private daily cache is shared with notifications, but automatic installation ignores the notification preference and requires an explicitly successful authenticated observation. Old cache entries and retained metadata from a failed check remain informational until a later successful check; they cannot authorize automatic installation. Failed/absent checks retain the existing 24-hour network throttle. A `NO_UPDATE` result means no eligible freshly authenticated newer stable release was observed, not proof of a successful network check.

The worker passes the exact approved metadata into the ordinary native installer. It does not resolve a mutable latest alias again or choose an explicit downgrade. Download/staging can happen while an agent works; activation needs the same exclusive activity lock as a manual update. `teamcode run` holds a shared lease for the complete agent lifetime, including quiet gaps. A busy installation or another writer produces `DEFERRED`; the next scheduled job can reuse verified staged bytes. No agent is terminated.

When safe to activate, the native installer cooperatively drains a matching running proxy, switches the atomically selected immutable payload, restarts it through the stable launcher and verifies its version/identity. Existing transaction recovery and offline rollback apply. Previously accepted executable files remain available. An attempt has a 15-minute work deadline; cancellation propagates through the installer's recovery path. Payload transfer, probe, service drainage/start and manager calls retain their own bounds. Integrity, compatibility and network failures never become a successful update result.

```sh
teamcode update --rollback
teamcode update --notifications off
```

Manual update/rollback remain available. Notifications and automatic installation are independent preferences. Any nonempty `TEAMCODE_DISABLE_AUTOUPDATE` suppresses both mechanisms **in that process**; OS jobs do not normally inherit shell variables. Use persisted `--automatic off` to disable the scheduled worker.

## Disable, removal and observations

`off` atomically disables future work before disabling the schedule. An update which already started may finish; disabling does not kill it or the proxy. `uninstall` first disables future work and requires the worker lock before removing its exact owned registration. If work is in flight, it returns `automatic_worker_busy`; retry after that attempt finishes. Externally modified registration files are refused instead of overwritten or removed. Saved login, proxy registration, installed versions and the last bounded update result are preserved.

The private installation root contains `automatic-update.json`, `automatic-update-result.json` and OS lock files. Status reports configured intent, definition-file presence and the last result; it is not proof of OS enablement, current network access or a live worker. Results are `STARTED`, `NO_UPDATE`, `UPDATED`, `DEFERRED` or `FAILED`, with a timestamp, optional candidate version and a fixed diagnostic code. An interrupted process can leave `STARTED`; that record alone never authorizes terminating a process. No remote response body, token or prompt belongs in these records.

Registration setup leaves the policy disabled until the job is installed. An initial start request can fail after opt-in was committed; inspect status and the OS manager rather than assuming the schedule was rolled back. Scheduling and worker locks prevent competing local operations without deleting lock files to guess that a holder is stale.

## Verification before publication

Common JVM/native tests exercise explicit opt-in, root/environment refusal, owner binding, stable/newer selection, worker concurrency, cancellation, busy retry, failure diagnostics, independent notifications, unsuccessful/legacy cache rejection and modified-definition refusal. OS definitions use the existing XML/Windows/systemd quoting rules.

`scripts/smoke-automatic-updates.py --binary NATIVE --first-bundle BASELINE --second-bundle CANDIDATE` installs two distinct compiled signed fixtures on an actual target host. It pre-stages verified bytes while holding the native activity lock, seeds authenticated successful metadata and uses an owned OS job to prove busy deferral, subsequent stable activation, proxy restart, custom-login preservation, independent notification settings and disable/removal. The ordinary installation smoke separately proves a real quiet native agent holds that activity lock. No real provider or public release is used. Failed cleanup retains the private fixture for recovery instead of deleting executables still referenced by a job.

The existing full native matrix runs this acceptance. For a **stable** pre-tag candidate, explicitly select `full_validation=true` and `validation_version=0.1.0` on the Build workflow. This compiles/signs the candidate for acceptance without creating a tag or release. A snapshot/prerelease run tests automatic prerelease refusal; it does not prove stable activation. Ordinary pushes remain Linux-only. Do not publish tag/release `0.1.0` before the complete V1 plan and required acceptance pass.

TeamClaude's `src/updater.js` / `test/updater.test.js` are the reference for source-install preservation, root/opt-out guards, bounded checks and avoiding synchronous work in the inference process. TeamCode adapts global npm replacement into an independent native user job which reuses its signed installer and service recovery; no npm or JVM is introduced.
