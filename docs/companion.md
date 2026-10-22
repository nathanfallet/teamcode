# Companion app

The companion app is a TeamCode client for iOS, Android and the desktop (macOS, Windows, Linux). It follows and steers the cloud sessions a member (or an administrator) can see, and opens artifacts. Nothing runs in it: every session lives on the server. It is a Kotlin Multiplatform module that depends only on the typed client, like the CLI (`companion -> client -> api`).

| Part | Status |
| --- | --- |
| `:api` and `:client` for Android (`androidLibrary`, minSdk 26) and iOS (`iosArm64`, `iosSimulatorArm64`), published with the other targets | Implemented |
| Shared SSE reader for the session stream and the caller-wide feed | Implemented |
| `followTeamEvents` in the typed client (`GET /api/v1/cloud/events/stream`, `cloud.events.v1`) | Implemented |
| App skeleton: modules, sign-in, accounts, settings | Planned |
| Sessions: live list, transcript, reconnection | Planned |
| Acting on sessions: send, interrupt, pause and resume, new session | Planned |
| Artifacts: gallery, viewer, links | Planned (needs the artifact routes) |
| Store and desktop distribution | Planned |

## Client contract

The app reuses `TeamCodeClient` as is: the SDK still owns no operating system keychain and receives a token provider (`token: suspend () -> String?`); the HTTP engine comes from the caller's factory (Darwin on iOS, OkHttp on Android, a JVM engine on the desktop).

**Event streams.** `followCloudEvents(id, after, onEvent)` follows one session's transcript and `followTeamEvents(after, types, onGap, onEvent)` the caller-wide feed (`after` has no default: the caller decides whether it resumes). Both read their response with the same internal SSE reader: lines bounded at 256 Ki characters and the data of one event at 1 Mi characters, comments (keep-alives), `id:` and `retry:` skipped (resumption uses the `seq` in the event), multi-line `data:` joined, frames without a name (or with an empty one) ignored. A closed stream, a broken transport or an over-long line throws `TeamCodeException` with status `0` and no code: reconnect from the last `seq`. An event the client cannot decode, or one over the data bound, throws status `0` with code `malformed_event` (`TeamCodeClient.MALFORMED_EVENT`): reconnecting would replay it, so a caller stops or updates instead of retrying. A connection that cannot be opened (offline, DNS, TLS) surfaces as the engine's own exception, and an exception from the caller's handler is rethrown as is.

| Call | Ends with | Throws |
| --- | --- | --- |
| `followCloudEvents` | `ENDED` after `event: end` (the session ended and every event was sent), `DENIED` after `event: denied` | `TeamCodeException` with status `0` when the stream closes, with the API error code for a refused request |
| `followTeamEvents` | `DENIED` after `event: denied` (the credential stopped being valid); the feed never ends otherwise | The same |

`followTeamEvents` sends `after` as `Last-Event-ID` (`null` starts with what happens from now on, so events published between two connections opened with `null` are never delivered; `0` replays every retained event) and `types` as `?types=a,b`. When the events after the cursor were purged, `onGap(oldestSeq)` is called before the events that follow, so the caller reloads what it shows. Event types are an open list: an unknown type reaches `onEvent` like the others. To resume after a network failure, call it again with the last `seq` handled: the server's sequence is gap-free, so nothing is lost or seen twice.

**Builds.** The Android targets need an Android SDK only for their own tasks (`ANDROID_HOME` or `local.properties`); JVM, native CLI and server tasks configure and run without one. Publishing the iOS targets, like the macOS ones, needs a Mac.

## Evidence

- `EventStreamTest` (common, run on JVM, the Android host, iOS simulator and macOS): the team feed in order with keep-alives and `retry:` skipped, an unknown type delivered, cursor, `types` and bearer sent; no cursor without `after`; `gap` reported before the events after it; a feed cut in the middle of an event resumed from the last handled `seq` without loss or duplicate; a refused feed carrying its error code; invalid cursors and types refused before any request; the session stream still ending with `ENDED` and `DENIED`, joining data lines and ignoring unnamed frames, and throwing when closed; the data bound; fields without a space or a value.
- Local runs: `:client:jvmTest :client:testAndroidHostTest :client:iosSimulatorArm64Test :client:macosArm64Test :api:iosSimulatorArm64Test`, and `compileTestKotlinLinuxX64` of both modules. Pull request CI runs `:client:jvmTest` and `:client:testAndroidHostTest` on Linux; the iOS tests are not part of any CI job yet (no paid macOS runner per pull request), so they are run locally before merging.
