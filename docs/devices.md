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

On password login, the CLI creates a random UUID in `$XDG_CONFIG_HOME/teamcode/installation-id`, falling back to `~/.config/teamcode/installation-id`. It is non-secret and survives logout and server changes. The existing `config.json` still contains only the server origin and revocable access token; logout removes that credential file. Neither file contains a provider credential or the user's password.

The identity is published completely before another process can read it, without replacing an existing identity. Concurrent first logins use the same identifier. Files use owner-only permissions/DACLs; malformed or symbolic-link identity files are rejected without overwriting them. Native creation extends the same private-file publication scheme used for configuration: a synced temporary file, then a no-replacement link on POSIX or no-replacement move on Windows. JVM tests use the corresponding filesystem primitive. Existing configuration writes retain atomic replacement.

The server registers the installation only after verifying the member's password, keyed by **member + installation UUID**. The same UUID under another member does not share a device or permissions. Repeat logins create independently revocable sessions on the same device and preserve its server ID and dashboard-edited name. The display label supplied with login is used only for a newly registered device. There are at most 50 registered devices and 50 retained authentication sessions per member. Remove an unused device in the dashboard before enrolling another when the device limit is reached. Names may contain 1–120 characters without control characters.

A device is a logical installation, not hardware attestation. Copying a configuration can share its existing session/device association; it cannot grant a fresh member allowance. A password login after device removal registers the installation again with a new server device ID. Password replacement/reset, role changes and account disablement retain the existing session-revocation behavior. Device names/history do not confer authorization.

**Operating system.** A login may also send `platform` (`macos`, `windows`, `linux`, `ios` or `android`) and `osVersion` (1 to 64 printable characters, only with a platform; otherwise `400 invalid_platform` or `invalid_os_version`). They are recorded on the device and returned by the device API as `platform` and `osVersion`, so a client can say which machine a [remote session](cloud-sessions.md#create-request-and-view) runs on. A login without them, from an older client, keeps what was recorded before. The native CLI sends its platform; existing devices show none until their next login.

Removing a device keeps the remote sessions that ran on it, with their transcript, but without a host device.

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

Device/session reads require completed password setup. Another member's inaccessible object ID returns 404 on mutations. Administrative reads remain team-scoped. The `team.devices` capability advertises these operations. Registration adds optional `installationId` to the password-login request; requests without it retain the legacy login behavior.

Groups, additional roles, scoped CI/service credentials, the agent-run registry and complete audit browsing remain separate V1 work. This feature does not replace those requirements.
