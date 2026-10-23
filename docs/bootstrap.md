# One-command native installation

The release pipeline generates `install.sh` and `install.ps1` from the exact publisher-signed five-target bundle. The commands below install the assets of the latest stable release after the script's built-in publication checks pass. The owner moved additional clean-machine and operational validation to V2. Source implementation, local acceptance and public-download acceptance are separate.

## Install a release

macOS Apple Silicon/Intel or glibc Linux ARM64/x64, as your own user:

```sh
curl -fsSL https://github.com/nathanfallet/teamcode/releases/latest/download/install.sh | sh
```

Windows x64, in Windows PowerShell 5.1 or PowerShell 7:

```powershell
& { $f = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName() + '.ps1'); try { Invoke-WebRequest -UseBasicParsing 'https://github.com/nathanfallet/teamcode/releases/latest/download/install.ps1' -OutFile $f; & ([scriptblock]::Create([IO.File]::ReadAllText($f))) } finally { Remove-Item $f -ErrorAction SilentlyContinue } }
```

These URLs install the latest stable release. The script itself is version-pinned: subsequent asset downloads use its exact version, never a moving latest-version alias. To install a specific version, or an explicit prerelease, use its version-specific URL instead, for example:

```sh
curl -fsSL https://github.com/nathanfallet/teamcode/releases/download/0.3.0/install.sh | sh
```

```powershell
& { $f = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName() + '.ps1'); try { Invoke-WebRequest -UseBasicParsing 'https://github.com/nathanfallet/teamcode/releases/download/0.3.0/install.ps1' -OutFile $f; & ([scriptblock]::Create([IO.File]::ReadAllText($f))) } finally { Remove-Item $f -ErrorAction SilentlyContinue } }
```

The installer makes `teamcode` available by name, without administrator rights (see [Command on PATH](#command-on-path)). Shell profiles, personal Claude/Codex configuration and saved TeamCode authentication are not rewritten. Then connect to your own deployment:

```sh
teamcode login --server https://teamcode.example.org
teamcode run claude -- --dangerously-skip-permissions
teamcode run codex -- exec "Explain this repository"
teamcode service install
```

Agent installation and optional user-service registration remain explicit steps. Installing TeamCode does not start a proxy, enroll provider accounts or create a team login. Re-running the installer reuses its managed installation. A saved login causes the native installer to check compatibility with that self-hosted server before activation.

## Requirements and locations

No platform needs Java, Gradle, Python or Node to install TeamCode. POSIX bootstrap needs the system shell, curl and `sha256sum` or `shasum`; macOS selects ARM64 on Apple Silicon even when the shell uses Rosetta. Linux binaries additionally require glibc, libcurl, `libcrypt.so.1` and OpenSSL libcrypto 3 or a compatible libcrypto 4. Windows bootstrap uses PowerShell/.NET and a private current-user NTFS directory. Windows ARM64 and 32-bit operating systems are not advertised targets.

| System | Default managed root |
| --- | --- |
| macOS | `~/Library/Application Support/TeamCode`, or `$XDG_DATA_HOME/TeamCode` |
| Linux | `~/.local/share/teamcode`, or `$XDG_DATA_HOME/teamcode` |
| Windows | `%LOCALAPPDATA%\TeamCode` |

Set `TEAMCODE_INSTALL_ROOT` to an absolute private directory before invoking the script to override the root. The native installer refuses an unrelated nonempty layout. Configuration remains under the existing XDG/home `.config/teamcode` location. POSIX bootstrap refuses root; system-service provisioning is a separate operation.

## Command on PATH

After installation, `teamcode` works by name:

| System | What the installer adds |
| --- | --- |
| macOS, Linux | A symbolic link `~/.local/bin/teamcode` to the **stable launcher** (`<root>/bin/teamcode`), never to a versioned payload, so updates and `update --rollback` need no relinking. The directory is created if missing. |
| Windows | The launcher directory (`%LOCALAPPDATA%\TeamCode\bin` by default) appended to the **current user's** PATH in the registry (`HKCU\Environment`), keeping its value type, then a `WM_SETTINGCHANGE` broadcast. Open a new terminal to see it. |

Neither needs `sudo` or administrator rights, and `/usr/local/bin` or `/usr/bin` are never used. `~/.local/bin` is where many distributions already put user commands; when it is not in your PATH, the installer says so and prints the exact line to add to your shell profile, for example:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

The installer never edits shell profiles. Re-running the installer changes nothing when the link already points at the launcher or the PATH entry exists (the check ignores case and trailing separators, and expands `%VAR%` entries). It never overwrites a file or link it did not create: if `~/.local/bin/teamcode` is something else, installation finishes, nothing is replaced, the installer explains and exits with an error; remove or rename that file and run it again. A link left by a previous installation in another `--root` is treated the same way. `teamcode install --no-path` (not available through the bootstrap scripts) skips this step.

There is no `uninstall` command yet. To remove what was added, delete `~/.local/bin/teamcode` (macOS/Linux) or remove the launcher directory from the user PATH in *Edit environment variables for your account* (Windows).

## Reviewed or offline installation

You may first download and inspect the script. For offline bootstrap, download the version's script, `manifest.json`, `manifest.sig` and your target asset into one directory. Set `TEAMCODE_INSTALL_BUNDLE` to that directory:

```sh
TEAMCODE_INSTALL_BUNDLE=/absolute/path/to/release sh /absolute/path/to/release/install.sh
```

```powershell
$env:TEAMCODE_INSTALL_BUNDLE = 'C:\Downloads\teamcode-release'
& 'C:\Downloads\teamcode-release\install.ps1'
```

The offline source is copied into a private temporary snapshot and checked against the script's embedded lengths/hashes. It cannot select another version or trust root. Without a saved login, installation needs no central server; an existing login retains the ordinary compatibility check. See [native distribution](distribution.md) for update/rollback.

## Trust and failures

The initial command trusts the install script served over GitHub HTTPS. At build time, `scripts/release-bootstrap.py` authenticates a private manifest snapshot against the pinned publisher root and checks exact hashes, lengths and native headers of all five assets. Each generated script embeds its version and the exact binary/manifest/signature hashes and lengths. Downloaded bytes must match before the first native executable starts. The native installer then verifies the publisher signature with its compiled roots and applies existing compatibility, locking, staging, selection and recovery rules. Bootstrap does not introduce another installation-state format or parse team/provider configuration.

POSIX disables `.curlrc`, allows HTTPS-only redirects, limits redirects/time/file size and verifies the final exact length/hash. A scoped file-size resource limit additionally bounds disk use on older curl versions with incomplete unknown-length enforcement. PowerShell uses a credential-free HTTP client, an HTTPS GitHub/CDN allowlist, at most five redirects and a 120-second cancellation deadline spanning response/stream reads; encoded, oversized and truncated responses fail. Neither downloader receives a team/provider credential. Offline files undergo the same identity checks.

Private downloads are removed on normal success/failure. A download/hash failure cannot start the executable. Once native installation starts, its transaction/recovery rules apply; a bootstrap failure does not prove activation never began. POSIX preserves a failing native installer's exit code; PowerShell reports failure. Neither script stops unrelated processes or replaces an arbitrary PATH executable.

## Acceptance

Ordinary tests cover signed-bundle generation/refusal and shell flow using executable shims: four targets, Rosetta, literal arguments, tampering, root refusal, offline mode, HTTP failure and cleanup. The PowerShell HTTP fixture exercises the actual helper with an in-memory .NET handler: HTTPS/host restrictions, redirect limits, bounded unknown-length bodies, exact sizes and encoded/error refusal. It makes no network or provider call.

`scripts/smoke-bootstrap.py --bundle SIGNED_RELEASE_DIRECTORY` runs the generated script and actual native executable on its target host. It checks clean installation, that `teamcode` runs by name through the new link or PATH entry (in a temporary `HOME`; the Windows job restores the CI user's PATH afterwards), the compiled version, idempotence, saved self-hosted URL/login/device and agent-file preservation, corrupt-byte refusal and cleanup. On Windows it uses both Windows PowerShell and PowerShell 7 when available. Full validation invokes it in the existing signed-installation matrix; no paid runner is added to ordinary pushes. Accepted scripts travel unchanged in the native release artifact. Actual public-download installation and all five-target results remain gates until their evidence passes.

TeamClaude's `src/updater.js` is the reference for preserving source/manual installations and explicit update behavior. Its global npm replacement does not bootstrap a standalone Kotlin/Native executable. TeamCode delegates to its managed native installer instead of adding npm or JVM requirements.
