# Companion app

The companion app is a TeamCode client for iOS, Android and the desktop (macOS, Windows, Linux). It follows and steers the cloud sessions a member (or an administrator) can see, and opens artifacts. Nothing runs in it: every session lives on the server. It is a Kotlin Multiplatform module that depends only on the typed client, like the CLI (`companion -> client -> api`).

| Part | Status |
| --- | --- |
| `:api` and `:client` for Android (`androidLibrary`, minSdk 26) and iOS (`iosArm64`, `iosSimulatorArm64`), published with the other targets | Implemented |
| Shared SSE reader for the session stream and the caller-wide feed | Implemented |
| `followTeamEvents` in the typed client (`GET /api/v1/cloud/events/stream`, `cloud.events.v1`) | Implemented |
| App skeleton: modules, sign-in, accounts, settings | Implemented |
| Sessions: live list, transcript, reconnection | Planned |
| Acting on sessions: send, interrupt, pause and resume, new session | Planned |
| Artifacts: gallery, viewer, links | Planned (needs the artifact routes) |
| Store and desktop distribution | Planned |

## Client contract

The app reuses `TeamCodeClient` as is: the SDK still owns no operating system keychain and receives a token provider (`token: suspend () -> String?`); the HTTP engine comes from the caller's factory (Darwin on iOS, OkHttp on Android, a JVM engine on the desktop).

**Event streams.** `followCloudEvents(id, after, onEvent)` follows one session's transcript and `followTeamEvents(after, types, onGap, onEvent)` the caller-wide feed (`after` has no default: the caller decides whether it resumes). Both read their response with the same internal SSE reader: lines bounded at 256 Ki characters and the data of one event at 1 Mi characters, comments (keep-alives), `id:` and `retry:` skipped (resumption uses the `seq` in the event), multi-line `data:` joined, frames without a name (or with an empty one) ignored. A stream that closes throws `TeamCodeException` with status `0` and no code: reconnect from the last `seq`. An event the client cannot decode, a line over the line bound or an event over the data bound throws status `0` with code `malformed_event` (`TeamCodeClient.MALFORMED_EVENT`): reconnecting would replay it, so a caller stops or updates instead of retrying (`teamcode cloud events --follow` stops with a message naming `--after` to skip it, and `attach` detaches); the decoder's own exception is not kept, since its message quotes the payload. Bytes that are not UTF-8 are decoded with replacement characters. A network failure, when opening the stream or in the middle of it, surfaces as the engine's own exception, as before, and an exception from the caller's handler is rethrown as is.

| Call | Ends with | Throws |
| --- | --- | --- |
| `followCloudEvents` | `ENDED` after `event: end` (the session ended and every event was sent), `DENIED` after `event: denied` | `TeamCodeException` with status `0` when the stream closes, with the API error code for a refused request |
| `followTeamEvents` | `DENIED` after `event: denied` (the credential stopped being valid); the feed never ends otherwise | The same |

`followTeamEvents` sends `after` as `Last-Event-ID` (`null` starts with what happens from now on, so events published between two connections opened with `null` are never delivered; `0` replays every retained event) and `types` as `?types=a,b`. When the events after the cursor were purged, `onGap(oldestSeq)` is called before the events that follow, so the caller reloads what it shows. Event types are an open list: an unknown type reaches `onEvent` like the others; `gap` and `denied` are the feed's control frames and never event types. To resume after a network failure, call it again with the last `seq` handled: the server's sequence is gap-free, so nothing is lost or seen twice.

**Builds.** The Android targets need an Android SDK only for their own tasks (`ANDROID_HOME` or `local.properties`); JVM, native CLI and server tasks configure and run without one. Publishing the iOS targets, like the macOS ones, needs a Mac.

## App

`:companion` is the shared app (Compose Multiplatform); `:companion-android` and the Xcode project `companion-ios/` are thin shells around it, and the desktop entry point lives in its `jvmMain`. Inside, the code is split by layer, checked by `scripts/check-module-graph.py`:

| Package | Holds | May import |
| --- | --- | --- |
| `domain` | Models (`Account`, `ServerInfo`, `SignInResult`...), repository and service ports, use cases (inspect a server, sign in, change a temporary password, sign out, refresh, rename the device) | Nothing from the other layers, nor `api` or `client` |
| `infrastructure` | `TeamCodeClient` per account, the typed client mapped to domain errors, settings-backed repositories, one credential store per platform | `domain`, `api`, `client` |
| `presentation` | Theme (the dashboard's palette), navigation, screens and view models | `domain` only |
| `di` | Koin modules; each platform adds its own (`platformModule()`) | Everything |

**Sign-in.** The user enters a server address; the app normalizes it (HTTPS required except on loopback), reads its public information and refuses a server that is not TeamCode or whose API it does not speak. It then signs in with `POST /api/v1/auth/login` and the installation id, so a new sign-in from the same device replaces its previous session instead of adding one. A temporary password leads to the password change (at least 14 characters), after which the app signs in again; if that sign-in fails, the change has already revoked the temporary session, so the app returns to the sign-in form and asks for the new password. Several accounts, on one or several servers, can be signed in; one is active.

**Credentials.** Accounts (server, member, device name) are stored in the platform settings; tokens never are. Tokens go to the platform's secure storage: the iOS Keychain (`AfterFirstUnlockThisDeviceOnly`, never synced), an AES-256-GCM key in the Android Keystore (the account id as associated data; backups disabled), and the operating system keyring on the desktop (Keychain, Credential Manager, Secret Service). When the desktop has no keyring, or it refuses a write, tokens stay in memory and the app asks to sign in at each launch, never writing them to disk.

**Desktop.** Artifacts open in the browser; there is no embedded browser.

**Build and run.**

| Platform | Command |
| --- | --- |
| Desktop | `./gradlew :companion:run`; packages with `:companion:packageDmg`, `packageMsi` or `packageDeb` on the matching host |
| Android | `./gradlew :companion-android:installDebug` (needs the Android SDK with platform 37) |
| iOS | Open `companion-ios/TeamCode.xcodeproj` in Xcode and run; the build phase calls `:companion:embedAndSignAppleFrameworkForXcode`. A contributor outside the TeamCode Apple team sets `TEAM_ID` in `companion-ios/Configuration/Config.xcconfig` and their team in Xcode |

The Android app is `me.nathanfallet.teamcode` (minSdk 26, targetSdk 36, compiled against 37 as OkHttp 5.5 requires); the iOS app has the same identifier and requires iOS 16. Release signing and store delivery come with the distribution work; nothing in the repository holds a key or a certificate.

## Evidence

- `EventStreamTest` (common, run on JVM, the Android host, iOS simulator and macOS): the team feed in order with keep-alives and `retry:` skipped, an unknown type delivered, cursor, `types` and bearer sent; no cursor without `after`; `gap` reported before the events after it; a feed cut in the middle of an event resumed from the last handled `seq` without loss or duplicate; a refused feed carrying its error code; invalid cursors and types refused before any request; the session stream still ending with `ENDED` and `DENIED`, joining data lines and ignoring unnamed frames, and throwing when closed; the line and data bounds, both `malformed_event`; invalid UTF-8 replaced, not fatal; fields without a space or a value.
- `FollowEventsTest` (CLI, JVM): an unreadable event stops the follow loop after one request (instead of reconnecting forever) without exiting, so `attach` still detaches.
- Local runs: `:client:jvmTest :client:testAndroidHostTest :client:iosSimulatorArm64Test :client:macosArm64Test :api:iosSimulatorArm64Test`, and `compileTestKotlinLinuxX64` of both modules. Pull request CI runs `:client:jvmTest` and `:client:testAndroidHostTest` on Linux; the iOS tests are not part of any CI job yet (no paid macOS runner per pull request), so they are run locally before merging.
- App (`:companion`): `AuthUseCasesTest` (origins normalized and only HTTPS or loopback accepted, another API version refused, the token stored but never the password, one account per member and server, a temporary password changed then signed in again, sign-out revoking then forgetting even offline, a revoked token signing the account out once, an offline refresh keeping it signed in, device rename), `SettingsRepositoriesTest` (accounts and active account across restarts, damaged preferences, installation id drawn once, theme), `RemoteAuthRepositoryTest` (capabilities, a server that is not TeamCode, login with the installation id, failures translated without the server's message, the token read at each call, device rename) in common code on the JVM and the Android host; `KeyringCredentialStoreTest` and `LoginScreenTest` (Compose UI test) on the desktop; `TokenCipherTest` on the Android host; `KeychainCredentialStoreTest` on the iOS simulator, which skips itself when the bare test binary has no Keychain (`errSecNotAvailable`). PR CI runs the JVM and Android host tests and assembles the Android app; the iOS app is built locally with Xcode.
