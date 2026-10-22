# Native distribution

TeamCode targets macOS Apple Silicon/Intel, Linux ARM64/x64 and Windows x64. The CLI is a Kotlin/Native executable and needs no JVM. This page describes the versioned build/release path. `teamcode install`, managed `teamcode update` and offline `--rollback` are implemented with real macOS acceptance. Complete five-target installation/service-update acceptance and first tagged publication remain V1 gates.

## Build identity

Gradle uses `-PteamcodeVersion=0.1.0-alpha.1` (or the `ORG_GRADLE_PROJECT_teamcodeVersion` environment variable). The default remains `0.1.0-SNAPSHOT`. Tags and versions use the same unprefixed value, for example `0.1.0`; a leading `v` is rejected, and versions use bounded semantic version numbers and optional prerelease identifiers, without build metadata. Invalid values fail before compilation.

The API module generates `BuildInfo.VERSION` from the Gradle project version. Both native `teamcode version` and the server capabilities `serverVersion` use it. It is a build input, not a runtime environment override. A fixture-server smoke verifies that the actual native executable and packaged server report the requested version.

## Build artifacts and publication

Ordinary main pushes and pull requests run JVM/replica tests, Helm checks and the multi-architecture Docker build on Linux. Main pushes publish the server image after JVM and Helm pass, independently of native CLI validation. They do not schedule macOS, Windows, native cross-build, bundle-signing or native installation jobs.

The image is built by the Ktor Gradle plugin (Jib), not a Dockerfile: `./gradlew :app:buildImage -PimagePlatforms=arm64` writes `app/build/jib-image.tar` for `docker load` (a tarball holds one platform), and `:app:publishImage` pushes the amd64/arm64 manifest with `DOCKER_HUB_USERNAME`/`DOCKER_HUB_PASSWORD` in the environment. `-PimageTags=a,b` sets the tags (default `latest`).

Full native validation runs only for unprefixed version tags such as `0.1.0` or an explicit **Actions → Build → Run workflow → full_validation** selection (unchecked by default). For example, `gh workflow run build.yml --ref main -f full_validation=true` opts into the paid runners; omit that input for the regular Linux-only checks. Batch native changes and request this matrix deliberately before a release candidate, rather than on every commit. A selected full validation must pass its signed installation matrix before Docker publication.

A manual full validation can additionally set `validation_version=0.1.0` to compile that exact stable candidate before a tag exists. With `publish_release=false` (the default), it produces acceptance artifacts without creating a tag/release or pushing an image. Snapshot candidates exercise automatic prerelease refusal instead. Ordinary push/PR builds remain snapshots, and tag builds always use the exact unprefixed tag.

To publish a stable version in one paid matrix run, dispatch from `main` with `full_validation=true`, the exact `validation_version`, and `publish_release=true`. Request validation rejects another branch, an omitted full matrix, prefixed/prerelease/snapshot versions or mismatched metadata. After all existing checks pass, the workflow pushes the versioned image, re-verifies the accepted manifest/signature/source, creates the exact tag and publishes those same assets without rebuilding or resigning. The tag is created using the repository `GITHUB_TOKEN`; [GitHub does not start another push workflow for this token](https://docs.github.com/en/actions/concepts/security/github_token), avoiding a duplicate paid matrix. The dedicated publication concurrency group is not cancelled by ordinary main builds. An existing tag must name exactly the tested commit; tags/releases are never silently replaced. Direct tag pushes remain supported through the original full workflow.

During full validation, the Build workflow compiles optimized **release** executables and runs the existing native authentication, TLS/streaming, launcher, cancellation, guidance and service acceptance before staging assets. Runtime acceptance runs on actual target hosts, including macOS Intel and Linux ARM64. Linux ARM64 is cross-compiled on an x64 host, then downloaded and executed on the ARM64 runner; [Kotlin/Native does not support an ARM64 Linux compiler host](https://kotlinlang.org/docs/native-target-support.html#hosts). macOS Intel is cross-compiled on Apple Silicon to reduce compiler time, then downloaded and tested on a real Intel runner. No target is published based on cross-compilation alone. The pinned host labels are listed in [GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

Each target stages a raw binary and private build metadata. Staging checks the native ELF/Mach-O/PE architecture, size, actual `version` execution, source commit and SHA-256. The assembly job requires all five distinct targets from exactly one version/commit, verifies every hash/header again, rejects missing/unexpected files and produces:

- `teamcode-VERSION-macos-arm64`
- `teamcode-VERSION-macos-x64`
- `teamcode-VERSION-linux-arm64`
- `teamcode-VERSION-linux-x64`
- `teamcode-VERSION-windows-x64.exe`
- `manifest.json`: schema version, version/tag, source commit and target-specific filename/hash/size.
- `SHA256SUMS`: binary and manifest hashes.
- Published version releases additionally include `manifest.sig`, an RSA-PSS-SHA256 signature envelope over the exact manifest bytes, with a pinned key fingerprint.
- Signed bundles also carry `install.sh` and `install.ps1`, generated from the verified manifest/assets and accepted through the existing five-target installation matrix. See [one-command bootstrap and its trust/runtime boundaries](bootstrap.md).

Successful full-validation runs retain an unsigned `teamcode-release-bundle` Actions artifact for seven days. Tag pushes and explicitly selected full-validation manual runs additionally sign the exact assembled bundle in an isolated job, retaining `teamcode-signed-release-bundle`. Pull requests, including same-repository PRs, never execute that signing job or receive the publisher key. A signed snapshot is development evidence, not a stable release or a published GitHub version.

Each target additionally compiles a baseline version, `0.0.0-installation.gCOMMIT`, from the exact same source commit, after saving the requested release executable. Both builds are staged on their actual OS/CPU and assembled independently; baseline staging checks its real native `version`, header, size and hash too. Cross-compiled baselines are executed on the actual Intel/ARM64 host before staging. This baseline is an acceptance fixture, never a published GitHub release or a replacement for the requested version.

During full validation, before Docker publication, five actual target hosts install the independently signed baseline, update to the signed requested version, exercise its owned OS service, and roll back offline. The preparation helper rejects equal version labels, equal binary hashes, mixed source commits or incorrect target bytes. Service identity must announce the selected compiled version after each switch; fresh signed release notifications are checked through the actual baseline service without a public-network refresh. Baseline and requested-release artifacts/signatures remain separate; publication uses only the requested bundle. Native test jobs receive only public files. Pull requests run the Linux JVM/Helm/Docker checks; they do not compile native candidates or receive the publisher key. Full native acceptance can be requested manually on a trusted branch when needed.

Publishing an unprefixed tag such as `0.1.0` runs all acceptance again with that exact version and publishes a GitHub release only after the combined bundle, signed installation matrix, JVM/replica, Helm and image jobs pass. The publisher signature is verified again before upload; the exact bundle accepted by the native hosts is published without resigning or rebuilding. Missing/mismatched signing credentials prevent image/release publication in full-validation runs. Ordinary server image publication does not use the native publisher key. Assets are uploaded to a draft first; the release becomes public only after successful upload. Prerelease versions are marked as GitHub prereleases. Existing releases are not overwritten automatically; publication failure must be investigated instead of replacing assets silently.

Main pushes keep publishing the tested server image to `nathanfallet/teamcode:latest` and `sha-COMMIT`. Tag pushes publish `nathanfallet/teamcode:VERSION` and `sha-COMMIT-VERSION`, preserving `latest` and the main snapshot's commit tag. This prevents rebuilding a tagged version from silently replacing a snapshot image identified by the same source commit. Helm continues to default to `latest`. The Helm job packages the chart with the exact build version and validates Kubernetes schemas from that package for bundled/external databases. Its artifact is carried unchanged into the GitHub release as `teamcode-VERSION.tgz`, with matching chart/application versions. Users deploy it on their own infrastructure using the [chart installation guide](../helm/teamcode/README.md); choose `image.tag: "0.1.0"` to pin that server version.

## Manual installation boundary

For a published version, the [one-command bootstrap](bootstrap.md) selects and verifies the OS/CPU asset before delegating to the managed native installer. Manual download remains available: choose the binary for the actual OS/CPU and verify its SHA-256 against the release manifest/checksums before execution. Rename it to `teamcode` (`teamcode.exe` on Windows) and put it in a stable user-owned directory on PATH. POSIX downloads require executable permission (`chmod 755 teamcode`). Keep the same installed path when registering a user service. Never copy a personal TeamCode configuration into a release bundle.

Linux binaries use platform shared libraries, including glibc/libcurl and `libcrypt.so.1`; these are not advertised as fully static builds. OS/CPU support is accepted only after its actual runner checks pass. Apple Developer notarization and Windows Authenticode signing are not currently provided. The publisher signature is independent of platform executable signing. `SHA256SUMS` is a convenience; authenticated artifact identity comes from the signed manifest and its independently pinned release key. The native `verify-release` command verifies that signature using compiled public roots and optionally checks a binary against the authenticated manifest. The native installer verifies the signed manifest and exact executable bytes before staging a version; its five-target acceptance is a required part of the versioned release workflow. Linux verification requires OpenSSL libcrypto 3 (or a compatible libcrypto 4); a missing or incompatible library fails closed.

## Remaining V1 distribution acceptance

The standalone native verifier and its common signature/key-rotation suite passed on all five runtimes in [Build 39277828501](https://github.com/nathanfallet/teamcode/actions/runs/39277828501), before the managed installer was added. Installer/lifecycle acceptance remains separate:

- Complete five-target acceptance of the implemented private installation, immutable launcher, update/rollback, service restart and interrupted-transaction recovery.
- Preserve login/device identity and never rewrite personal Claude/Codex configuration during installation or updates.
- Drain the owned user service and handle executable replacement on Windows without stopping unrelated processes; restore working registration/binary on failure.
- Exercise fresh installation, update/rollback, offline/interrupted download, integrity refusal and restart on each target. Continue separate clean-workstation reboot/logon and pre-login service acceptance.
- Publish and download an actual prerelease through this pipeline before claiming versioned distribution is accepted end to end.

TeamClaude's `src/updater.js` and `test/updater.test.js` provide the reference for installation ownership, opt-out, bounded version checks and leaving source checkouts alone. Its npm replacement mechanism does not apply to native executable replacement. TeamCode retains native CLI/client/API boundaries and will adapt service drainage and rollback rather than invoking npm or overwriting arbitrary executables.

## Publisher signing key

The public RSA-3072 key is tracked at `release/trust/teamcode-release-2026.pem`. Its key ID is SHA-256 of the DER SubjectPublicKeyInfo. The corresponding private key is a dedicated `TEAMCODE_RELEASE_SIGNING_KEY` GitHub Actions secret, separate from all self-hosted server/team/provider credentials. Signing uses PSS with SHA-256, MGF1-SHA256 and a 32-byte salt. The signature envelope is schema version 1 and contains only algorithm, key ID and base64 signature. Publisher verification refuses another key, altered bytes, unsupported schema/algorithm or an existing signature output. Private material is written only to a private temporary file and deleted after signing; OpenSSL subprocesses do not inherit the key environment variable.

A private owner backup was created at `~/.config/teamcode-release-signing/rsa-3072-2026.pem` with owner-only permissions. It is never part of the repository, Docker image or release bundle. Fork maintainers must generate their own key, configure their own Actions secret and ship their own pinned public trust root; the self-hosted server cannot replace this trust root. Key rotation must first ship the next public trust root through an accepted release signed by an already trusted key, then change the publisher key. Common native tests exercise old/new/unknown keys, a bridge release trusting both roots, retirement of the old root and rejection of spoofed key IDs. A compromised root still requires an independently authenticated recovery channel; this implementation does not claim remote revocation of an already installed trust root.

The native verifier uses the same RSA-PSS/SHA-256 parameters through platform crypto: [Apple Security](https://developer.apple.com/documentation/security/seckeyalgorithm/rsasignaturedigestpsssha256), [Windows BCrypt](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptverifysignature) and [OpenSSL EVP](https://docs.openssl.org/3.4/man7/RSA-PSS/). The exact bytes must be verified against a shipped public key before parsing manifest contents or executing an asset. Apple imports a build-generated PKCS#1 public key; Linux and Windows import SubjectPublicKeyInfo. The JVM test implementation uses JCE with the same fixed parameters. No private key or user-provided trust-root override is compiled into the CLI. Cross-platform runtime acceptance is recorded separately in the status ledger.


## Native verification command

Verification works offline without a TeamCode login or configured server:

```sh
teamcode verify-release --manifest manifest.json --signature manifest.sig
teamcode verify-release --manifest manifest.json --signature manifest.sig \
  --binary teamcode-VERSION-linux-x64 --target linux-x64
```

Use the actual OS/CPU target and release asset filename. Providing only one of `--binary`/`--target` fails. The command authenticates the exact bounded manifest bytes against one of the build-generated roots before reading its fields. It then validates the version/tag, source commit, canonical asset names and bounded sizes. Binary verification streams the file through SHA-256 and requires an exact length/hash match; it never executes downloaded code. An additional future target in a schema-1 manifest does not prevent verification by an older supported client, but that client cannot select an unsupported target.

The signature cannot supply a new public key. Fork maintainers replace the PEM roots under `release/trust`, rebuild, and replace the public golden smoke fixture with metadata signed by their own publisher root. `scripts/generate-release-test-fixtures.py` separately creates disposable cryptographic vectors for algorithm/rotation tests; it never uses the publisher's private key. Common tests include wrong padding, salt length and MGF digest, modified bytes, retired/unknown keys, malformed authenticated metadata and exact binary hash/length rejection. `scripts/smoke-release-verification.py` exercises the compiled publisher root and safe failures through the actual native command using temporary Unicode paths.


## Checking published versions

`teamcode update --check` explicitly checks the latest stable GitHub release, downloads its canonical `manifest.json`/`manifest.sig` and authenticates the exact bytes against the compiled publisher roots before reporting a version. `--version VERSION` checks a specific version, including prereleases. A valid signature over a different version is refused. The check compares full SemVer precedence without integer overflow, and checking an older version never advertises it as an available upgrade. A missing stable release is reported separately from HTTP, signature or schema failure. No TeamCode login is read, and this command neither installs files nor restarts services. Managed installation/update/rollback are described below; their current platform-specific evidence is recorded in the status ledger.

The typed client owns a separate credential-free HTTP client; it never receives team/provider credentials. The [public GitHub release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) provides only a bounded version hint, not trust or arbitrary asset URLs. Canonical download paths are derived from the validated version/target. Downloads permit at most five redirects to HTTPS GitHub release/CDN hosts, with no embedded user credentials, nonstandard ports or fragment. API redirects are rejected. Metadata and binary bodies are bounded even without trustworthy Content-Length; binary transfer enforces the declared exact length and leaves SHA-256 authentication to the staging verifier. Encoded bodies and inconsistent lengths fail, and errors do not expose response bodies or signed CDN query strings. Checks/downloads have connection, socket and total deadlines; coroutine cancellation is preserved. The explicit check makes network requests each time. Background notifications are described below; [opt-in automatic installation](automatic-updates.md) uses a separate user job and the same signed native installer.

Signed golden fixtures have `-text` Git attributes. A Windows autocrlf checkout must preserve their authenticated bytes; the verifier deliberately rejects CRLF-translated payloads rather than repairing them. A regression test performs a real Git index checkout with autocrlf enabled and checks both successful unchanged-byte verification and rejection after newline translation.


## Background release notifications

Managed user installations check signed stable-release metadata alongside the local proxy service and `teamcode run`. A watcher wakes hourly; completed checks, including unavailable/absent-release results, are cached for 24 hours under the installation root. A separate OS lock prevents concurrent service/agent processes from issuing duplicate checks. Network work has a five-second total deadline and does not delay proxy admission or agent launch. Caller cancellation propagates and releases the lock; an interrupted check may be retried later. Source/manual copies and root processes never start the watcher.

```sh
teamcode update --notifications status
teamcode update --notifications off
teamcode update --notifications on
```

These settings apply to the managed installation, including its OS service, and preserve saved login/device identity. `--root ABSOLUTE_PATH` selects one explicitly. Any nonempty `TEAMCODE_DISABLE_AUTOUPDATE` disables the watcher in that process; OS managers do not generally inherit shell variables, so use the persisted setting to disable service checks. The explicit `update --check` remains available regardless of the notification preference.

The private cache retains exact manifest/signature bytes as bounded base64, not an unauthenticated latest-version string. Every read verifies the signature against this build's compiled roots before displaying a version. Corruption or a retired root discards the cached observation; refresh errors retain only previously authenticated metadata and report a fixed diagnostic without remote bodies. Backwards clock changes make an observation stale. A watcher reports each newer version once per process, through service logs or the launcher's stderr. It neither downloads executable payloads nor changes the selected version. [Opt-in automatic installation](automatic-updates.md) is implemented through a separate hourly user job. Its platform/runtime acceptance remains separate from notifications.

## Managed native installation and rollback

Starting with a trusted native executable built from this source, use:

```sh
teamcode install --version VERSION
# Or use a directory containing an already downloaded signed release:
teamcode install --bundle /absolute/path/to/release
```

The installer requires its source bundle to include `manifest.sig`, not just checksums. It chooses the current OS/CPU asset, authenticates its metadata, verifies exact size/SHA-256 in private staging and executes a bounded version/API compatibility probe before selection. A saved TeamCode login causes a capability check against that configured server; installation/update refuses an unavailable or incompatible server. A fresh installation requires no login. Rollback uses a previously accepted local payload and remains available with the central server offline.

The default application-data root is `~/Library/Application Support/TeamCode` on macOS, `$XDG_DATA_HOME/teamcode` or `~/.local/share/teamcode` on Linux, and `%LOCALAPPDATA%\TeamCode` on Windows. A configured XDG data home is also honored on macOS. Use `install --root ABSOLUTE_PATH` for an explicit private location. The command prints its absolute launcher path and makes `teamcode` available by name: a `~/.local/bin/teamcode` symbolic link to the stable launcher on macOS/Linux (with the exact `export PATH=...` line when that directory is not in PATH), or the launcher directory added to the user PATH on Windows. Nothing needs administrator rights, an existing foreign file or link is never replaced, and `install --no-path` skips it. Details in [bootstrap](bootstrap.md#command-on-path). Configuration/session credentials remain under the existing configuration directory. Installation refuses a nonempty unowned layout and does not replace an arbitrary executable or source checkout.

```sh
teamcode update --check
teamcode update
teamcode update --version VERSION
teamcode update --bundle /absolute/path/to/release
teamcode update --rollback
```

The check only reports authenticated metadata. A default update selects a newer stable release; explicit `--version` or `--bundle` permits a deliberate downgrade. Ordinary updates discover their own managed installation; `--root` may select one explicitly. A manually copied/source executable must first use `install`. Repeated installation reuses verified immutable bytes. A valid cached payload may be reused even if an unused source copy is damaged; corrupted installed bytes fail verification.

The root contains an immutable `bin` launcher, immutable `versions/MANIFEST_SHA256/` payloads and an atomically selected `current.json`. An owner-only local attestation binds each selected payload to its publisher-authenticated manifest and exact binary digest. Files/ownership/permissions and hashes are checked before dispatch; public roots still authenticate every newly introduced manifest. The original launcher remains untouched across upgrades, including Windows while it is executing. POSIX replaces the launcher process with the selected executable; Windows waits for its owned child. Previously installed payloads remain on disk while anything can still use them, so active/older processes never lose their executable. Unused payloads are collected only while no agent session runs (the installer holds the exclusive activity lock): the selected payload, the rollback target and the payload the local service runs are always kept, and a directory is renamed aside before deletion, so Windows, which refuses to rename a directory whose executable still runs, simply keeps it.

A persistent OS writer lock serializes installers. A separate activity lock is shared for the complete `teamcode run` lifetime, including quiet gaps, because the session depends on the local service.

### Updating while agent sessions run

`teamcode update` (and `--rollback`, and the automatic job) no longer waits for agent sessions to end:

1. It downloads, verifies, probes and **selects** the new payload at once. Payloads under `versions/` are immutable, so running sessions keep executing their own binary; new sessions and commands started through the launcher run the new one.
2. The local service is what sessions depend on, so its restart is **deferred** while any session holds the shared activity lease. `service.json` records the payload the service was last started from; it differs from `current.json` exactly while a restart is pending. The last session to end restarts the service (drained as usual, so no in-flight request is cut). If nothing is running then, the next `teamcode run`, `teamcode update` or automatic attempt applies it. A service already announcing the selected version (for example after `teamcode service restart`) or a stopped one is not restarted again.
3. The command says what happened, for example:

```text
Selected TeamCode 0.5.0. Launcher: /Users/me/Library/Application Support/TeamCode/bin/teamcode
2 running agent sessions keep running on 0.4.0.
New sessions and commands use 0.5.0. The local TeamCode service keeps running 0.4.0 and restarts on 0.5.0 when they end.
```

Without sessions, update stops and restarts the service immediately and restores the previous selection if the new service does not start, as before. A deferred restart is journaled the same way: a crash during it selects the payload the service ran and restarts it there, which any launcher, including older ones, recovers; a failed deferred restart does the same and is reported by the session or command that attempted it. The selection itself happens under a shared activity lease, so no launcher can mistake its journal for an abandoned one. Each session also keeps an exclusively locked `sessions/MANIFEST_SHA256-*.lock` record, used only to report how many sessions run which version.

Limits: a fix in the local service applies only once every session has ended, and overlapping sessions keep postponing it. Until then, sessions started on the new version use the old service when it offers the capabilities they need, and otherwise their own dedicated listener, as they already do for an incompatible service. Sessions started by a release before this behavior do not restart the service when they end; the next launch or update does.

POSIX flock descriptors and Windows file-region locks release automatically on process exit; lock files are never deleted to guess that a writer is stale. The immutable launcher in `bin/` is written once at installation and never replaced, so Windows never overwrites an executable that is running.

For a matching owned service, activation cooperatively drains/stops it, atomically changes the selected version, starts the service through the stable launcher and verifies its authenticated local identity/version. A previously stopped service stays stopped. A startup failure restores the old selection and attempts to restart the old service. `transaction.json` records the before/after selection before interruption-sensitive work; the next invocation recovers an interrupted transaction only after acquiring the exclusive lock. Failed recovery keeps the journal for retry and is not announced as success. A source/manual service registered to another executable is preserved; reinstall that service through the managed launcher before using service-aware updates.

Linux systemd rejects quotes/backslashes in its first executable token even after unquoting. For executable paths containing quotes, a backslash or a dollar sign, TeamCode uses the fixed `/usr/bin/env --` executable and passes the absolute TeamCode path as an argument. This preserves literal paths and the service PID without a shell. Ordinary executable paths retain their existing registration format. Full special-path user-manager acceptance is tracked per CI run.

`smoke-installation.py --binary PATH --first-bundle DIR --second-bundle DIR [--service] [--notification-bundle DIR]` accepts signed local fixtures without reading private signing keys. It exercises real installation/idempotence, the `teamcode` command by name after install, update and rollback (in a temporary `HOME`, refusing a foreign file at the link path), immutable launcher selection, update, offline rollback, interrupted-selection recovery, tampered-byte refusal, Unicode paths, private local attestations and input/configuration preservation. Its optional service mode checks actual OS registration, selected-version restart, fresh startup-log evidence, configuration preservation and rollback while the synthetic central server is offline. It also kills only its owned updater at the journal/drain checkpoint, completes the active synthetic response, and requires the next invocation to restore the previous selection/service. A stopped service remains stopped. Both POSIX and Windows run a quiet synthetic agent and prove update refuses to switch until it exits; the Windows fixture uses the supported npm installation layout. The optional notification bundle seeds fresh signed newer metadata and requires the actual service to report it while preserving cache bytes. No real provider calls are made. `installation-fixture.py --first-bundle DIR --second-bundle DIR --target TARGET --output DIR` prepares the two distinct compiled builds and never reads signing keys. Five-target runtime results and tagged-release/OS lifecycle gates are recorded separately in the status ledger.

## Kotlin libraries on Maven Central

The `api` and `client` modules are published to Maven Central for reuse in external services, as `me.nathanfallet.teamcode:api` and `me.nathanfallet.teamcode:client` (artifact ids are the module names) (Kotlin Multiplatform: JVM, macOS arm64/x64, Linux arm64/x64, Windows x64; the base artifact carries Gradle module metadata). The version is `teamcodeVersion`. The configuration follows kourier (Vanniktech `com.vanniktech.maven.publish`, empty javadoc jars, signed publications). Publication is manual, from a checkout of the exact release commit, with the Central and signing properties in `~/.gradle/gradle.properties` (`mavenCentralUsername`, `mavenCentralPassword`, `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`):

```sh
./gradlew --no-daemon -PteamcodeVersion=X.Y.Z :api:publishToMavenCentral :client:publishToMavenCentral
```

Always pass `-PteamcodeVersion`: without it the build version is `0.1.0-SNAPSHOT`. This uploads a deployment that is then released from the Central Portal. Check locally with `:api:publishToMavenLocal :client:publishToMavenLocal`.
