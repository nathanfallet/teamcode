# Native local proxy services

The native CLI can register a per-user local proxy. Native runtime acceptance has passed on macOS, Linux and Windows, including Task Scheduler installation, reuse, draining, restart and uninstall. Install the binary at a stable absolute path, sign in, and run:

```sh
teamcode login --server https://teamcode.example.org
teamcode service install --port 8789
teamcode service status
teamcode run claude -- --dangerously-skip-permissions
teamcode run codex -- exec "Explain this repository"
```

`service install` registers a macOS LaunchAgent, Linux systemd user unit or Windows scheduled logon task, starts it now, and enables startup at user login. Linux requires a running systemd user manager. These are user services; the Windows task uses the current user's interactive token at least privilege and stores no Windows password. Use the explicit `--at-boot` (`--system`) mode below for startup before login. Native binary updates are handled separately by the managed installer/updater below.

## Startup before login

After signing in to TeamCode, run the native CLI as your own OS user:

```sh
teamcode service install --at-boot
# Equivalent alias:
teamcode service install --system
teamcode service status
```

Changing an existing login registration to boot mode requires `service uninstall` followed by installation with this flag. Saved TeamCode authentication is preserved. `service print --at-boot` previews a new registration without installing it. `service status` distinguishes boot from login startup.

- **macOS:** a root-owned 0644 plist in `/Library/LaunchDaemons` starts at boot and uses `UserName` to run the proxy as its configuration owner. Only fixed OS installation/launchctl/removal commands use sudo. The manifest includes absolute headless configuration/home paths and no credential. The CLI requests administrator authorization; do not run TeamCode itself as root. Updates that control this system registration also need authorization; quiet automatic operations use noninteractive sudo and fail safely if it is unavailable.
- **Linux:** the existing systemd user unit is enabled with explicit user-manager lingering through `sudo loginctl enable-linger UID`. It can run at boot and after logout. Linger is shared policy for the user's services, so uninstall removes TeamCode's unit and deliberately leaves lingering enabled. Do not disable it without reviewing the user's other services.
- **Windows:** use an elevated terminal for the same user, then answer the masked Windows-account password prompt. This creates a least-privilege boot-triggered task with password logon, running whether or not an interactive session exists. The password is supplied directly to Task Scheduler and never saved by TeamCode or placed in command arguments/environment. Use the Windows account password, not a PIN or TeamCode password. A password change or local/domain batch-logon restriction can require repairing the task by uninstalling/reinstalling it. Effective task identity, trigger and command are checked before control operations.

The saved configuration and executable must be readable by that user before login. This does not unlock encrypted disks/homes or a personal keychain, create another service account or silently copy credentials. Offline startup keeps the local listener available and later requests retry connectivity. The Windows cooperative stop/drain behavior also applies to its boot task.

TeamClaude's `src/service.js` and `test/service.test.js` remain the reference for explicit service registration, literal executable/configuration paths and user identity. Its user-only integration has no Windows/boot implementation. TeamCode extends the same native daemon lifecycle with OS boot mechanisms, rather than adding a second proxy daemon or elevating its inference process. References: [Apple launch daemons](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html), [systemd lingering](https://www.freedesktop.org/software/systemd/man/252/loginctl.html), [Windows boot task registration and password logon](https://learn.microsoft.com/en-us/windows/win32/taskschd/taskfolder-registertask).

Actual workstation reboot, Windows boot-password lifecycle and boot-mode update authorization follow-through are [owner-deferred V2 validations](v2-validation-handoff.md), not claims implied by rendering a manifest.

## Lifecycle

- `service print` renders the registration without installing it. It never includes tokens.
- `service install [--port PORT] [--binary ABSOLUTE_PATH]` is idempotent for the existing installation. The default executable is the immutable launcher for a managed installation, or the canonical running native executable for a manual installation. To change the executable path or port, uninstall and reinstall; the team login is preserved.
- `service start`, `service stop` and `service restart` operate only on the registration recorded for the selected configuration. Stopping drains accepted streams for up to ten minutes. A restart waits for the previous process to exit before launching its replacement.
- `service status` reports installation, authenticated local proxy response, central connectivity and team authentication separately. A failed central connection leaves authentication unverified, not necessarily expired.
- `service logs` prints a bounded local log of lifecycle and generic transport diagnostics. It contains at most 128 lines and no credentials, prompts, raw exceptions or response bodies.
- `service uninstall` removes its own registration and service settings, preserving the saved team login and logs. Repeating it is harmless. Externally modified registrations are not overwritten or removed.

On macOS the registration is under `~/Library/LaunchAgents/`; on Linux it is under `$XDG_CONFIG_HOME/systemd/user/` (default `~/.config/systemd/user/`). Windows keeps its Task Scheduler XML beside the private service settings. Each installation has a random identifier so isolated configurations do not control each other's service. A Linux service manager may create enablement links in its own configuration directory.

On Windows, `service stop` disables the scheduled task and writes a private stop request. The daemon drains accepted streams and exits; the CLI waits until Task Scheduler reports no running instances. `service start` clears that request and re-enables both immediate execution and automatic logon startup. It does not use Task Scheduler's forceful end operation. Use TeamCode's start/stop commands so the cooperative stop state stays consistent.

The login remains in `teamcode/config.json`, and `teamcode/service.json` holds the local capability, port and registration metadata. Both files are private to the user: POSIX 0600 files/0700 directories, or a protected Windows DACL with only the current user granted access. Service manifests contain only absolute paths and non-secret startup arguments. The daemon does not need a shell, a working-directory convention or environment variables containing credentials. Windows management uses built-in Task Scheduler tools and a fixed PowerShell COM query for running instances; it never evaluates agent arguments as shell code.

## Device daemon

The service is also the device's daemon (`cloud.sessions.remote.daemon.v1`): the member starts a [remote session](cloud-sessions.md#sessions-started-by-a-devices-daemon) on this device from any other client, and the daemon hosts it here. It is signed in with the CLI's own `config.json` (the account of `teamcode login`, never a separate identity), and the server sees it [online](devices.md#device-daemon) while it polls.

```sh
teamcode folders add ~/git/teamcode   # a folder other devices may start sessions in
teamcode folders list
teamcode folders remove ID_OR_PATH
```

- **Folders.** `folders.json` beside `config.json` (private, written under `config.lock`) lists the folders the owner allowed, each with a stable random id. `folders add` resolves `..` and symbolic links first, and refuses the file system's root, the home directory, a directory that contains it and system directories. The daemon publishes the folders (id, last segment, a `~/...` label: never a path the server relies on) when it starts and whenever the file changes; `folders add` and `remove` also publish at once when signed in. `folders add` saves the terminal's `PATH` for the agents: a service manager starts the daemon with a minimal one, so the daemon's hosts search the saved `PATH`, the service's, then `~/.local/bin`, `~/.claude/local`, `~/.npm-global/bin`, `~/.bun/bin`, `~/.volta/bin`, `/opt/homebrew/bin` and `/usr/local/bin` when they exist.
- **One per device.** The service holds `daemon.lock` beside `config.json` for its whole run (a BSD `flock` on POSIX, `LockFileEx` on Windows, never inherited by its children). A second `service serve` of the same configuration exits at once, before writing anything: two daemons on one queue would each accept the same session, and each acceptance fences the other.
- **Orders.** The daemon long-polls `POST /devices/current/daemon/poll` (20 s, again at once after each answer, exponential back-off to one minute on errors). It checks each order against its own `folders.json`: a folder no longer listed (`folder_not_configured`), gone (`folder_missing`), now resolving elsewhere through a symbolic link (`folder_moved`), refused by the rules above (`folder_refused`), with another name (`folder_mismatch`), or an agent this version does not run (`agent_unsupported`) is refused with that reason. Otherwise it accepts with `POST /cloud/sessions/{id}/host`, writes the session's record in `remote/` (the folder's path and the agent, as `teamcode remote start` does), and starts `teamcode remote host SESSION` in the background from the service's executable, with the epoch it opened. The host is the same runner as `teamcode remote host`: the permission mode, agent, model and first message come from the session, the conversation id from `remote/`, its log is `remote/<session>.log`.
- **Supervision.** A host that exits while its session should still run on its epoch is started again on a new epoch, at most three times in ten minutes; after that a session back in the queue is refused (`host_failed`). A host whose session paused, ended or moved to another host is forgotten.
- **Stop and restart (decision 5).** On stop, the daemon stops polling, asks the server to pause every session it hosts (each agent finishes its turn, its host checkpoints and exits), waits up to nine minutes, then calls `DELETE /devices/current/daemon`; the proxy then drains in the time left of the ten minutes. `daemon-sessions.json` remembers the sessions it hosts and the ones it paused: the next daemon resumes the paused ones by itself, and a session left hosting by a daemon that did not stop cleanly once the server paused it for its lost host (`host_offline`). A session its owner paused is never resumed.
- **Where it does not run.** In a cloud session's pod, `service install` and `service serve` do nothing and the daemon never starts: one pod runs one session (the server also refuses a pod's credential, `cloud_session_credential`). On Windows the service runs its proxy only: the session host does not run agents there yet, so the device never shows online. A service registered for another configuration than the CLI's hosts nothing.

## Authentication and recovery

The local proxy binds only to `127.0.0.1` and requires its own random capability. Its identity endpoint also requires that capability and rejects browser origins. `teamcode run` checks the installation ID and configured upstream origin before reusing the service. When no matching service responds, `run` uses an isolated ephemeral proxy as before. It never stops an existing service when the child exits.

The daemon reads the saved team token for every upstream request. Signing in again to the same server takes effect without a service restart; revoked sessions are still rejected centrally. Changing the server URL requires `service restart`, so a token for another origin can never be sent to the old server. If the login file is absent at startup, the daemon waits for it. It does not open a browser or ask for passwords at login/boot.

Upstream outages do not terminate the listener. Later client requests retry connectivity; inference requests are not replayed locally. The service manager retries a failed daemon after 30 seconds on macOS/Linux, or once per minute for at most 255 attempts on Windows. A busy local port is an error and never causes another process to be stopped. No model work runs in the service process itself: the remote sessions its daemon hosts run in their own host processes.

## Verification

Each `run` invocation can select `--workload background` before the agent argument delimiter. The service keeps both classes available without a global mode switch: only that launch uses the background inference path. Discovery advertises `workload-routing-v1`; a background launch reuses the service only when the authenticated installation ID, server URL and this capability match. Otherwise it uses its own ephemeral proxy and leaves the service intact. Interactive launches remain compatible with older service discovery responses.

`scripts/smoke-service.py --binary /absolute/path/to/teamcode` creates a temporary configuration, synthetic HTTP upstream and uniquely named OS registration. It verifies native installation/idempotence, private files, authenticated discovery, login replacement, offline recovery, `run` reuse, stop during a live stream, restart, registration ownership checks and uninstall. It cleans up only its own registration and processes. No provider account or real team credential is used.

This test exercises the actual OS service manager. Add `--at-boot` to test an owned boot registration with pre-authorized sudo; macOS also verifies root-owned manifest installation and cleanup. Existing full native jobs run both modes on macOS/Linux. It does not reboot the workstation. Windows password-backed boot registration needs a separate authorized workstation check.

`scripts/smoke-windows.py` separately runs the Windows executable, inspects real NTFS ACLs, checks Unicode paths/arguments and standard npm agent entry points, then exercises Task Scheduler installation, reuse, draining, restart and cleanup. Windows support is required for V1.

Platform references: [Apple LaunchAgents](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html), [systemd service execution](https://www.freedesktop.org/software/systemd/man/262/systemd.service.html). Ktor's [Native server startup](https://github.com/ktorio/ktor/blob/3.6.0/ktor-server/ktor-server-core/posix/src/io/ktor/server/engine/EmbeddedServer.posix.kt) installs its own signal callback; TeamCode installs its service handler after startup so shutdown also exits the outer CLI loop.

Windows references: [Task Scheduler logon triggers](https://learn.microsoft.com/en-us/windows/win32/taskschd/logon-trigger-example--xml-), [CreateProcess Unicode environments](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw), [system-preferred random generator](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptgenrandom).

The built-in Windows PowerShell service query uses system module paths explicitly, avoiding incompatible PowerShell 7 modules inherited through the caller. See Microsoft's [PSModulePath behavior for intermediate processes](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_psmodulepath).

## Native installation and updates

`teamcode install [--version VERSION | --bundle DIRECTORY] [--root ABSOLUTE_PATH]` creates a private managed installation. It also links `teamcode` into `~/.local/bin` (macOS/Linux) or adds the launcher directory to the user PATH (Windows); see [Command on PATH](bootstrap.md#command-on-path). Then use `teamcode service install` through that launcher. Existing login/device identity is preserved. A manual/source service registered to a different executable is left intact; uninstall/reinstall that service through the managed launcher to change its executable safely.

`teamcode update --check [--version VERSION]` authenticates published metadata without reading team authentication or touching the service. `teamcode update`, `update --version VERSION`, offline `update --bundle DIRECTORY` and `update --rollback` operate on a managed installation. New payloads pass publisher signature, exact hash/length and native version/API compatibility checks before activation. A configured server must be reachable for an installation/update compatibility check; a previously accepted rollback remains available offline. See [distribution behavior and remaining acceptance](distribution.md).

The immutable launcher selects a private versioned payload, so Windows never replaces a running executable. A writer lock serializes updater processes; a separate activity lock protects every managed `teamcode run` for its entire lifetime. Downloads can stage during agent work, but activation refuses while a quiet or busy managed agent holds its lease. No agent is killed. Previous payloads are retained.

An update cooperatively drains/stops a matching running service, switches the selected payload atomically, starts through the same registered launcher and checks the authenticated local identity/version. Stopped services stay stopped. Failed startup restores the previous selection and restarts the previous service where possible. A durable before/after journal supports recovery after updater interruption; a recovery failure retains the journal rather than claiming success. Service registration, configuration and credentials are preserved.

Local real acceptance covers macOS ARM64 installation, two compiled versions, service restart, active quiet-agent refusal, offline rollback and interrupted selection. The unchanged manual-service lifecycle smoke also passes. The versioned release workflow runs five-target signed installer/update checks. Additional clean-workstation reboot/logon and boot-mode update checks are V2 follow-up. Managed services now check signed release metadata in the background with a shared 24-hour cache; use `teamcode update --notifications off` to disable this installation's notifications. Checks never install code. Opt in to the independent updater with `teamcode update --automatic on`; it is disabled by default. See [notification behavior](distribution.md#background-release-notifications).

Server upgrades use Helm rolling deployments with readiness and stream draining. CI publication alone does not restart Kubernetes pods. The chart keeps `latest` as its default, with immutable image tags available for explicit upgrades and rollback. Prefer additive database migrations that remain compatible with the preceding server release; document any exception before release. Application rollback must never automatically undo a database migration destructively. Full-database restore acceptance has local evidence; additional operator and mixed-version rollout checks are tracked in the V2 handoff.
