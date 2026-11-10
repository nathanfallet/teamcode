# Notifications

TeamCode tells a member when something needs them or is done: an agent waits for their answer, the agent of their session finished or failed its turn, their session failed. The notification is one thing; how it reaches the member is a **channel**. Push to the companion apps is the first channel; Slack and Discord messages are planned as other channels behind the same switch, so "an agent waits for you" can arrive as a push, a chat message or both, depending on the member's settings.

## Status

| Piece | Status |
| --- | --- |
| Events, channels and the router: kinds, per-member switches, a worker that turns journal events into deliveries with retries (`notifications.v1`) | Implemented |
| `GET`/`PUT /api/v1/notifications/settings`: a member's own switches | Implemented |
| Push channel: FCM tokens on devices (`notifications.push.v1`), end-to-end encrypted content, the relay client and a team's own Firebase project, chosen per team on the dashboard | Implemented |
| The TeamCode Push relay (`push` module, Kotlin/Native, image and Cloud Run service in `push/`; see [push/README.md](../push/README.md)) | Implemented |
| The relay deployed at `push.teamcode.nathanfallet.me` (Cloud Run, `europe-west1`, Firebase project `teamcode-companion`) | Implemented |
| Companion apps: Android and iOS push (FCM token and device key registered with every signed-in account's server, payload decrypted on the device, a tap opens the session), desktop native notifications from the event feed | Implemented. Not yet tried on devices against the deployed relay; see [Apps](#apps) |
| Slack and Discord channels: linking a member's chat account, one message per notification | Planned |
| Push provider on the dashboard (Settings → Push notifications) and `GET`/`PUT /api/v1/notifications/push/settings`, with a test push | Implemented |
| Members' switches in the companion apps and on the dashboard | Planned |
| Notifications for a bare `teamcode run` waiting for a person (its `waiting_for_human` activity has no journal event yet) | Planned |

A server without the encryption key (`TEAMCODE_ENCRYPTION_KEY`) has no push channel: it keeps the settings routes and sends nothing. With it, the push channel is offered, and each team's pushes go nowhere until an administrator chooses a provider.

## What notifies whom

Notifications come from the [team event journal](cloud-conversations.md), the same ordered feed the event stream and [webhooks](webhooks.md) read. Nothing is sent from inside a request: the journal is written in the transaction that changes the state, and a worker reads it afterwards.

| Kind (`kind`) | Journal event | Who | Text |
| --- | --- | --- | --- |
| `waiting` | `cloud.wait.created` of a wait a person resolves, still open when the worker reads it (`ask_human`, a workflow question, a blocked todo) | The wait's assignee | The session's name, the wait's title |
| `waiting` | `cloud.session.waiting` (a permission request or a question of the agent) | The session's owner | "The agent asks for your permission." / "The agent has a question for you." |
| `turn_completed` | `cloud.turn` with `phase: completed` | The session's owner | "The agent finished its turn." |
| `turn_failed` | `cloud.wait.created` of the `turn_failed` wait (it offers the retry) | The session's owner | "The agent's turn failed." |
| `session_failed` | `cloud.session.ended` with `state: failed` | The session's owner | "The session failed." |

Waits resolved by a timer or an event ask nobody and never notify. A session someone terminated does not notify. The question or the tool input of a permission request is never put in a notification. The text is short English; a client may render the kind in its own language from `sessionId` and `waitId` instead.

## Settings

Every kind is on for every channel the server offers, until the member turns it off. Switches are per member, per channel and per kind (`member_notification_settings`; no row means on). A member changes only their own, with an interactive login; a service key has no person to notify.

```http
GET /api/v1/notifications/settings
{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":true}, ...]}

PUT /api/v1/notifications/settings
{"channel":"push","kind":"turn_completed","enabled":false}
```

`channels` lists what this server offers; it is empty while none is configured. A channel the server does not offer, or an unknown kind, is refused (`invalid_notification_setting`, 400).

## Delivery

A worker (`notifications/deliver`, every second, one replica at a time) does two things:

1. **Fan-out.** For each team whose journal moved past its notification cursor (`notification_cursors`), it reads the next events of the kinds above (at most 200), the waits and session names they refer to (one statement each), the members each channel can reach, and the switches turned off. It writes one delivery per event, member and channel (`notification_deliveries`, unique on those four) and moves the cursor, in one transaction guarded by the cursor's previous value: two replicas never queue a page twice. A team seen for the first time starts at its journal's head, so nothing from before notifications existed is sent.
2. **Attempts.** It claims up to 50 due deliveries with a one-minute lease, sends them together, each within 15 seconds, and records the outcome. A retryable failure (unreachable, 5xx, 429, timeout) is tried again after 10 s, 30 s, 2 min and 10 min; five attempts at most. A refusal for good (for example a push token the provider no longer knows) fails at once.

A delivery still pending after an hour is given up: a late "the agent waits for you" is noise. Finished deliveries, with their text, are deleted after a day (`notifications/maintain`). Errors are short codes, never a provider's answer; titles, bodies and tokens are never logged.

## Channels

A channel implements `NotificationChannel`:

- `id`: stored with settings and deliveries (`push`, later `slack`, `discord`);
- `reachable(teamId, memberIds)`: which members it can reach now, in one read (members with a device that registered a push token, members who linked a chat account);
- `deliver(notifications)`: sends a batch, each notification to every destination of its member on this channel, reading the destinations once for the batch, and answers delivered, retry or failed per notification.

The channel owns its destinations and how a notification looks there; the router decides what is sent and to whom, the same for every channel. The composition root (`Application.kt`) lists the channels this server offers.

## Push

### Devices

The iOS and Android apps register the installation they run on, with the session of their login (an app login has a device; a browser session and a service key have none):

```http
PUT /api/v1/devices/current/push
{"token":"<FCM registration token>","publicKey":"<P-256 public key, SubjectPublicKeyInfo DER, base64>","platform":"ios"}

DELETE /api/v1/devices/current/push
```

The app sends both at every start and when FCM rotates the token; a new value replaces the old one on the device's row (`devices.push_token`, `push_public_key`, `push_token_updated_at`). The dashboard and the API only say whether a device has push on, never its token. Only a device known as `ios` or `android` is accepted; a desktop device is refused (`push_unsupported`): desktop apps notify from the event feed. The platform is the one the login reported (the apps send it at sign-in). For a device whose login reported none, as every companion login before 1.7.1, the `platform` of this request is recorded instead; a platform the login reported is never replaced. Pushes go only to devices that still have a signed-in session; a token the provider no longer knows is cleared, unless the app already replaced it.

### Push payload

The text of a notification exists in clear only on the server and on the device. Each push is encrypted for the device's key (ECIES on P-256):

```
base64( 0x01 | ephemeral public key (65 bytes, uncompressed point) | nonce (12 bytes) | AES-256-GCM ciphertext and 16-byte tag )
key = HKDF-SHA256(ECDH(ephemeral private, device public), salt = ephemeral point | device point, info = "teamcode-push-v1", 32 bytes)
```

The plaintext is the JSON of `PushPayload` (API module): `v`, `id`, `origin` (the server's public URL), `memberId`, `kind` (a notification kind, or `test`), `title`, `body`, `sessionId`, `waitId`, `at`. `origin` and `memberId` tell an app signed in to several servers or accounts which one it is for. The collapse key the provider sees is a hash of the team and the notification's collapse key: pushes about the same wait replace one another without saying which wait.

### Apps

The apps are built with the Firebase project of the published apps (`teamcode-companion`): `companion-android/google-services.json` and `companion-ios/TeamCode/GoogleService-Info.plist`. These files name the project and its client API key, which Firebase does not treat as a secret. A build without them runs without push.

- **Registration** (shared code, `SyncPushUseCase`): once FCM gives a token, every signed-in account whose server announces `notifications.push.v1` receives `PUT /devices/current/push` with the token and the device's public key. This happens at each start of the app, when FCM rotates the token, and when an account signs in. A failure waits for the next of those. Signing out sends `DELETE /devices/current/push` first (best effort, `ClearPushUseCase`). The demo account is never registered: its features do not include push.
- **Android**: the key pair is made with the JCA on first use. The private key is kept in the Keystore-sealed credential store (`push-device-key`, excluded from backups like the tokens). Firebase is initialized from the four values of `google-services.json`, read at build time, without the Google Services plugin. `TeamCodeMessagingService` decrypts the data message and finds the signed-in account its `origin` and `memberId` name. It shows nothing for an account signed out on the phone. Otherwise it posts one notification per session, so a newer one replaces the older. Android 13 and later ask for the notification permission once, after the first sign-in.
- **iOS**: CryptoKit makes the key pair. The private key is kept in its own Keychain access group (`<team>.me.nathanfallet.teamcode.push`), the only group the notification service extension lists; the accounts' tokens stay in the app's default group. The `NotificationService` extension (Swift, without Firebase) decrypts the payload and replaces the generic alert's text. The app asks for the alert permission when it first registers with a server that offers push. A tap on a notification opens `teamcode://notification?origin=…&member=…&session=…`: the session, the waits of the account for a wait outside a session, or the sign-in when that account is signed out.

The payload's decryption is tested against bytes produced by the server's `PushPayloads.encrypt`: on the JVM for Android (`PushCipherTest`), and with CryptoKit for the extension's code.

## Desktop

The desktop app has no push: it shows native notifications from the caller-wide event feed it already follows (`WatchActivityUseCase`, sharing `SessionFeed`'s connection). It keeps what the server would push to the member, by the same rules:

- the agent of their session waits for them (`cloud.session.waiting`);
- a wait a person resolves is assigned to them (`cloud.wait.created`, read from the first page of open waits);
- the agent finished its turn (`cloud.turn` completed);
- the agent's turn failed (the `turn_failed` wait);
- their session failed (`cloud.session.ended` failed).

Other members' sessions on an administrator's feed are left out. The notifications are shown by the menu bar icon, only while the window is hidden or in the background. Activating the icon within a minute of one opens what it was about. Without a menu bar icon (some Linux desktops) there are no notifications. The member's switches (`/notifications/settings`) are for server channels and do not apply here; the text is the app's own, in its language.

### What the provider sees

The relay and the team's own Firebase project send the same FCM message: the token, the data `{"p": <encrypted payload>, "v": "1"}`, and

- **iOS**: a generic visible alert ("TeamCode", "New activity in TeamCode") with `mutable-content: 1`, `apns-collapse-id` and an expiry of one hour; the notification service extension decrypts the payload and replaces the text. If it cannot (an old app, no time), the generic alert shows.
- **Android**: a high-priority data message with the collapse key and a one-hour TTL; the app decrypts and shows it.

### Providers

An administrator chooses the team's provider on the dashboard (Settings → Push notifications) or with `GET`/`PUT /api/v1/notifications/push/settings`:

| Provider | For | What is stored |
| --- | --- | --- |
| `off` (default) | | Nothing is sent; devices still register |
| `relay` | The published TeamCode apps (App Store, Google Play), whose Firebase project only the relay holds | The relay URL, default `https://push.teamcode.nathanfallet.me` (https, no path, no link-local or metadata address) |
| `firebase` | Apps the team builds and signs itself with its own Firebase project | A Google service account JSON key with the Firebase Cloud Messaging API Admin role, sealed with the server's key (`push-firebase:<team>`); its `token_uri` must be Google's. Choosing another provider deletes it |

"Send a test to my phone" (`POST /api/v1/notifications/push/test`) sends a `test` notification to the administrator's own phones at once and shows each installation's result. Every save is audited (`push.settings_updated`, `push.firebase_key_saved`).

### Relay protocol (`push.v1`)

The relay sees an instance, a token and an encrypted blob: never a member, a session or the text. The `PushRelay` models of the API module are the wire format; `RelayPushSender` (infrastructure) speaks it and a test of the app module holds the two together.

- **Identity.** At the first push the server makes an Ed25519 key pair, shared by every replica (`push_relay_instances`, the private key sealed with the server's key) and registers its public key: `POST /v1/instances {"publicKey": "<raw 32 bytes, base64url>"}` → `{"instanceId": "..."}`. The same key gets the same id; the relay answers the key itself, so the server must not assume anything about the id's form.
- **Signature.** Every other request carries `TeamCode-Instance`, `TeamCode-Timestamp` (Unix seconds, at most 300 s off) and `TeamCode-Signature`: Ed25519 over `POST\n<path>\n<timestamp>\n<sha256 of the body, lowercase hex>`, base64url without padding.
- **Send.** `POST /v1/send {"token", "platform": "ios"|"android", "payload", "collapseKey", "ttlSeconds"}` → `{"status": "sent"|"unregistered"|"retry"|"rejected", "retryAfterSeconds"?, "error"?}`.
- **The relay's side** ([push/README.md](../push/README.md)). The instance id is the public key itself, so the relay keeps no registry. Per minute it enforces three quotas: per instance (600), per token (20) and in total (6000); going over gets `429` with `Retry-After`. A request copied within the skew window is not sent twice. An instance listed in `TEAMCODE_PUSH_BLOCKED_INSTANCES` gets `403`. The body is limited to 8 KiB, and the token must be at most 512 characters. The payload must be base64 of at most 3000 characters. The collapse key must be lowercase hex, and the TTL at most one day.
- **Outcomes.** `sent` delivers; `unregistered` clears the token; `retry`, 408, 429 and 5xx are retried; `401` (the relay no longer knows the key) registers the same key again at the next push; `403` (the instance is blocked) and other refusals fail.
