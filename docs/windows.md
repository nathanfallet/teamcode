# Windows workstations

Windows x64 native runtime acceptance passed in [CI run 39178163269](https://github.com/nathanfallet/teamcode/actions/runs/39178163269), including authentication, TLS, Unicode paths, agent launchers and Task Scheduler lifecycle. Additional real-workstation reboot/logon checks are owner-deferred to V2; see [implementation status](status.md). TeamCode runs directly on Windows, without WSL, Java or Gradle at runtime.

## Release installer

The pipeline generates a [PowerShell installer](bootstrap.md) for the signed Windows x64 release. Use the version-pinned `0.2.0` command in that guide for the published release. It targets Windows PowerShell 5.1 and PowerShell 7, prints a stable launcher path to add to user PATH and preserves saved authentication and personal agent configuration. All-platform bootstrap runtime acceptance remains separate from the earlier native transport/service evidence above.

## Install a tested development build

Open a successful [Build workflow run](https://github.com/nathanfallet/teamcode/actions/workflows/build.yml) and download its `teamcode-windows-x64` artifact. It is uploaded only after that run's Windows acceptance test passes. Extract `teamcode.exe` and keep it at a stable location, for example `%LOCALAPPDATA%\TeamCode\bin\teamcode.exe`. These artifacts are development builds; use the signed release installer for versioned installation. Managed signed updates and the new [opt-in user update job](automatic-updates.md) have separate acceptance requirements; inspect the versioned workflow results for the actual Windows installation/update evidence.

In a regular PowerShell session:

```powershell
$teamcode = Join-Path $env:LOCALAPPDATA 'TeamCode\bin\teamcode.exe'
& $teamcode login --server https://teamcode.example.org
& $teamcode status
& $teamcode run claude -- --dangerously-skip-permissions
& $teamcode run codex -- exec "Explain this repository"
```

Use the username and temporary password created by your TeamCode administrator. The first login asks you to replace the temporary password. TeamCode saves the server URL and session in `%USERPROFILE%\.config\teamcode\config.json` by default; `HOME`, when set, takes precedence over `USERPROFILE`, and `XDG_CONFIG_HOME` overrides the configuration root. Configuration files have a protected NTFS access-control list for the current user.

Install Claude Code or Codex separately and make the chosen command available on `PATH`. TeamCode supports native `.exe` installations and the standard npm package layouts. For npm installations it launches the package's JavaScript entry point with Node.js; Node must also be available. Arguments following `--` are passed to the agent literally. TeamCode does not rewrite the agent's personal configuration.

To use the shorter `teamcode` command, add the executable's directory to your **user** `Path` in Windows Environment Variables and open a new terminal.

## Start automatically at login

```powershell
& $teamcode service install
& $teamcode service status
& $teamcode service logs
```

This registers a task for the current user's login and starts the authenticated local proxy immediately. It stores no Windows password. `teamcode run` reuses that proxy after verifying its identity. Keep the executable at its registered location.

```powershell
& $teamcode service stop
& $teamcode service start
& $teamcode service restart
& $teamcode service uninstall
```

Stopping disables automatic login startup and waits for accepted streams to finish; starting enables it again. Uninstall preserves your TeamCode login. Use `service install --at-boot` (or `--system`) in an elevated terminal for startup before login. The masked prompt requires your Windows account password; Task Scheduler protects it, and TeamCode does not store it. Uninstall the login task before switching modes. Additional boot/password/reboot validation is tracked for V2. See [service lifecycle and recovery](services.md) for the full behavior.

## Build and test from source

Development requires JDK 21. From the repository in PowerShell:

```powershell
.\gradlew.bat :cli:linkDebugExecutableMingwX64
python scripts/smoke-windows.py --binary cli/build/bin/mingwX64/debugExecutable/teamcode.exe --allow-machine-test-certificate
```

Run the full acceptance script in a disposable elevated Windows environment, such as its GitHub-hosted CI runner, with Node.js and PowerShell 7 (`pwsh`). The explicit flag permits adding the generated, short-lived certificate to that machine's trusted roots for the HTTPS test. It uses synthetic credentials, a temporary configuration and its own scheduled task. It verifies native authentication, HTTPS trust, file permissions, streaming, agent process handling and the service lifecycle without calling a model provider. The test removes its own certificate and task afterward. Ordinary TeamCode use does not install certificates or require elevation.

## TeamClaude reference and native adaptations

The reference below was checked against TeamClaude commit `88fe9b37862c40fefa459a90cd3dbd2c31db98ce`.

| Behavior | TeamClaude implementation | TeamCode adaptation |
| --- | --- | --- |
| Agent launch | [`runCommand`](https://github.com/KarpelesLab/teamclaude/blob/88fe9b37862c40fefa459a90cd3dbd2c31db98ce/src/index.js#L1506) locates Claude through PATH, inherits stdio and passes Windows npm shims through the shell | Preserve PATH discovery, stdio, working directory and exit status. The native adapter resolves `.exe` files and standard npm entry points, using Unicode process APIs and explicit argument quoting. Custom shell wrappers are not currently supported. |
| Saved configuration | [`getConfigPath` and `writeJsonAtomic`](https://github.com/KarpelesLab/teamclaude/blob/88fe9b37862c40fefa459a90cd3dbd2c31db98ce/src/config.js#L10) use XDG or the home `.config` directory, then a temporary file, flush and atomic replacement | Keep the same location convention and atomic-write sequence. Store the team's server URL/session locally; provider credentials belong on the server. Use Windows DACLs for private credentials. |
| Service registration | [`serviceKind`](https://github.com/KarpelesLab/teamclaude/blob/88fe9b37862c40fefa459a90cd3dbd2c31db98ce/src/service.js#L22) supports LaunchAgent and systemd user units; Windows is explicitly unsupported in its tests | Preserve the explicit per-user install/startup workflow. Task Scheduler supplies the Windows integration required by TeamCode. |
| Provider sign-in | [`openBrowser`](https://github.com/KarpelesLab/teamclaude/blob/88fe9b37862c40fefa459a90cd3dbd2c31db98ce/src/login-flow.js#L80) opens the browser with an HTTP(S) URL and keeps a manual fallback | Claude and Codex enrollment print the URL for manual opening and validate a pasted result. Codex uses the registered localhost callback address without starting a listener. Automatic browser opening and live Codex browser-consent acceptance remain follow-up work. |

TeamClaude uses Node for Unicode file access and process support. TeamCode's native executable must supply those platform adapters; the reference's service behavior does not include a Windows implementation to port.
