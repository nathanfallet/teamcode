# Devices and authentication sessions

The dashboard's **Devices** page lists the signed-in member's registered CLI installations and active authentication sessions. Administrators can open the same page for a member from **Team → Devices and sessions**. A member cannot inspect, rename or revoke another member's devices or sessions. Browser forms require the dashboard origin and CSRF token; API calls require a bearer credential and ignore dashboard cookies.

## Native CLI

```sh
teamcode login --server https://teamcode.example.org --device "Developer workstation"
teamcode whoami
teamcode devices list
teamcode devices rename DEVICE_ID --name "Support laptop"
teamcode auth sessions list
teamcode auth sessions revoke SESSION_ID
teamcode devices revoke DEVICE_ID
```

`devices list` and `auth sessions list` accept `--member MEMBER_ID` for administrative inspection. Renaming or revoking another member's device/session requires administrator access on the server. Device removal signs out every session attached to that device, including a copied credential. Session revocation signs out only that session. Removing the current device/session requires a new password login; the CLI does not open a browser or silently reauthenticate.

Authentication sessions are login credentials. They are separate from agent conversations, runs, session affinity and guidance sessions. The top-level [`teamcode sessions list`](agent-sessions.md) reads the separate local agent-launch registry.

## Installation identity and registration

On password login, the CLI creates a random UUID in `$XDG_CONFIG_HOME/teamcode/installation-id`, falling back to `~/.config/teamcode/installation-id`. It is non-secret and survives logout and server changes. The existing `config.json` still contains only the server origin and revocable access token; logout removes that credential file. Neither file contains a provider credential or the user's password. Login, logout and `teamcode configure` change `config.json` under the advisory lock `config.lock` in the same directory (an empty file, never deleted; a command waits up to 10 seconds for another, then refuses). Login revokes the session its new configuration replaced on the same server, so two sign-ins, even concurrent ones, leave no orphan session; logout removes the file only if it still holds the session it revoked. The format, the paths, the private writes and the lock live in the `:config` module, so the desktop app reads and writes the same files with the same code.

The identity is published completely before another process can read it, without replacing an existing identity. Concurrent first logins use the same identifier. Files use owner-only permissions/DACLs; malformed or symbolic-link identity files are rejected without overwriting them. Native creation extends the same private-file publication scheme used for configuration: a synced temporary file, then a no-replacement link on POSIX or no-replacement move on Windows. JVM tests use the corresponding filesystem primitive. Existing configuration writes retain atomic replacement.

The server registers the installation only after verifying the member's password, keyed by **member + installation UUID**. The same UUID under another member does not share a device or permissions. Repeat logins create independently revocable sessions on the same device and preserve its server ID and dashboard-edited name. The display label supplied with login is used only for a newly registered device. There are at most 50 registered devices and 50 retained authentication sessions per member. Remove an unused device in the dashboard before enrolling another when the device limit is reached. Names may contain 1–120 characters without control characters.

A device is a logical installation, not hardware attestation. Copying a configuration can share its existing session/device association; it cannot grant a fresh member allowance. A password login after device removal registers the installation again with a new server device ID. Password replacement/reset, role changes and account disablement retain the existing session-revocation behavior. Device names/history do not confer authorization.

**Operating system.** A login may also send `platform` (`macos`, `windows`, `linux`, `ios` or `android`) and `osVersion` (1 to 64 printable characters, only with a platform; otherwise `400 invalid_platform` or `invalid_os_version`). They are recorded on the device and returned by the device API as `platform` and `osVersion`, so a client can say which machine a [remote session](cloud-sessions.md#create-request-and-view) runs on. A login without them, from an older client, keeps what was recorded before. The native CLI sends its platform; existing devices show none until their next login.

Removing a device terminates the remote sessions that ran on it (`terminated_reason = device_removed`); they keep their transcript, without a host device, and their directory on the machine is never touched.

## Shared persistence and revocation

Exposed stores device metadata and a nullable device reference/creation time on login tokens. Existing tokens remain valid after the additive migration, with no invented device or creation timestamp. Older clients and browser sessions appear separately and remain individually revocable. Upgrade the server and sign in with the current CLI to register an installation; configuring an existing bearer token does not prove a new installation identity.

Registration serializes on the member row. Device/session mutations revalidate the actor and target team, then serialize with login on the target member. Removing a device deletes its attached sessions and registration atomically. Foreign-key protection also prevents orphan device tokens. Session metadata never exposes bearer credentials, credential hashes or installation UUIDs. Explicit registration, rename, device removal and targeted session revocation produce metadata-only audit events.

All allowances, weighted shares, reservations and spend remain keyed by member. Adding, removing, reconnecting or copying devices cannot reset or multiply those limits. Active authenticated inference revalidates its original session every 500 ms through the shared cancellation store, including older/direct clients without a control UUID. Revocation on another replica therefore stops pending/streaming work without relying on local caches. A revoked committed stream closes; it never receives a replacement JSON error or replayed inference. Unknown dispatched usage retains the existing conservative settlement policy.

## API

| Operation | Route |
| --- | --- |
| Own devices | `GET /api/v1/devices` |
| Member devices (owner or administrator) | `GET /api/v1/members/{memberId}/devices` |
| Rename a device | `PATCH /api/v1/devices/{id}` with `{ "name": "..." }` |
| Revoke/remove a device | `DELETE /api/v1/devices/{id}` |
| Own authentication sessions | `GET /api/v1/auth/sessions` |
| Member authentication sessions (owner or administrator) | `GET /api/v1/members/{memberId}/auth/sessions` |
| Revoke one authentication session | `DELETE /api/v1/auth/sessions/{id}` |
| A device's folders and daemon (owner only) | `GET /api/v1/devices/{id}/folders` |
| A folder's conversations to continue (owner only) | `GET /api/v1/devices/{id}/folders/{folderId}/resumable` |
| Daemon: wait for sessions to host | `POST /api/v1/devices/current/daemon/poll` |
| Daemon: answer a listing of conversations | `POST /api/v1/devices/current/daemon/listings/{id}` |
| Daemon: publish the folders | `PUT /api/v1/devices/current/folders` |
| Daemon: refuse a session | `POST /api/v1/devices/current/daemon/sessions/{id}/refuse` |
| Daemon: going offline | `DELETE /api/v1/devices/current/daemon` |

Device/session reads require completed password setup. Another member's inaccessible object ID returns 404 on mutations. Administrative reads remain team-scoped. The `team.devices` capability advertises these operations. Registration adds optional `installationId` to the password-login request; requests without it retain the legacy login behavior.

## Device daemon

With `cloud.sessions.remote.daemon.v1`, a device's TeamCode daemon (the local service, signed in with the CLI's `config.json`) connects to the server so that its owner can start a [remote session](cloud-sessions.md#sessions-started-by-a-devices-daemon) on it from any other client.

- **Presence.** Each poll marks the daemon seen (`devices.daemon_seen_at`, at most one write per 5 s); the device is online for 60 s after its last poll and offline at once after `DELETE /devices/current/daemon`. `GET /devices` returns `daemon {online, lastSeenAt, version}`, or null when the daemon never connected or said it stopped.
- **Folders.** The daemon publishes the folders its owner allowed, as a whole (`PUT /devices/current/folders`, at most 64): `id` is the daemon's own key for the folder (1 to 64 characters from `A-Z a-z 0-9 _ -`), `name` its last segment, `label` how the owner recognizes it (for example `~/git/teamcode`, at most 512 characters). The server never receives a path it relies on, and removing the device removes its folders. Only the device's owner reads them (`GET /devices/{id}/folders`); anyone else, an administrator included, gets `404 device_not_found`.
- **Features.** Each poll may carry `features`, what this daemon version supports (at most 8, `invalid_features` otherwise); the server keeps the last list (`devices.daemon_features`) and returns it in `daemon.features`. `resume.v1`: the daemon lists a folder's Claude Code conversations and continues one in a remote session.
- **Conversations of a folder (`cloud.sessions.remote.resume.v1`).** The server never reads nor keeps a transcript. `GET /devices/{id}/folders/{folderId}/resumable` writes a short request (`device_daemon_listings`: device, folder, page size, cursor, expiry 30 s), and the daemon receives it in its next poll answer (`listings`, at most 4). It reads the folder's transcripts on the device (`~/.claude/projects` or `CLAUDE_CONFIG_DIR`, only the ones whose `cwd` is that folder, at most 200 files opened per page) and answers `POST /devices/current/daemon/listings/{id}` with, for each conversation, its id, a title (the last name the member gave it, else its summary, else the start of its first message; one line, at most 80 characters), its last write and its number of messages; or with a refusal reason when the folder no longer passes its checks. The member's request waits for that answer up to 10 s (a primary-key read every 250 ms, no transaction held), then the row is deleted, answered or not (`504 device_no_answer`). The answer is stored encrypted with the server's credential key until then, and is never logged. Only the device's owner lists them (`404 device_not_found` otherwise, administrators included); a daemon that did not announce `resume.v1` gets `409 daemon_resume_unsupported`. A title may hold anything the member typed: it leaves the device only for its owner. On the device itself (the desktop app's "On this device"), `teamcode remote resumable` reads the same list with the daemon's code without the server, and a session continuing one is still created through the daemon: with the daemon stopped, too old or on Windows, the CLI refuses it with the reason ([cloud sessions](cloud-sessions.md)).
- **Who.** The daemon routes take an interactive login bound to the device: a service key, a browser session or a login without an installation gets `422 daemon_requires_device`, and each call acts on the caller's own device only.
- **The CLI's daemon.** `teamcode service` runs it on macOS and Linux, one per device (`daemon.lock`), with the CLI's own login; `teamcode folders` chooses what it publishes. A cloud session's pod never runs it. See [services](services.md#device-daemon).

Groups, additional roles, scoped CI/service credentials, the agent-run registry and complete audit browsing remain separate V1 work. This feature does not replace those requirements.
