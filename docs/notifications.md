# Notifications

TeamCode tells a member when something needs them or is done: an agent waits for their answer, the agent of their session finished or failed its turn, their session failed. The notification is one thing; how it reaches the member is a **channel**. Push to the companion apps is the first channel; Slack and Discord messages are planned as other channels behind the same switch, so "an agent waits for you" can arrive as a push, a chat message or both, depending on the member's settings.

## Status

| Piece | Status |
| --- | --- |
| Events, channels and the router: kinds, per-member switches, a worker that turns journal events into deliveries with retries (`notifications.v1`) | Implemented |
| `GET`/`PUT /api/v1/notifications/settings`: a member's own switches | Implemented |
| Push channel: FCM tokens on devices, end-to-end encrypted content, the TeamCode Push relay (`push.teamcode.nathanfallet.me`) or a team's own Firebase project | Planned |
| Companion apps: Android and iOS push, desktop native notifications from the event feed | Planned |
| Slack and Discord channels: linking a member's chat account, one message per notification | Planned |
| Settings screen in the companion apps and on the dashboard | Planned |
| Notifications for a bare `teamcode run` waiting for a person (its `waiting_for_human` activity has no journal event yet) | Planned |

A server without a configured channel keeps the settings routes and sends nothing.

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
- `deliver(notification)`: sends to every destination of the member on this channel and answers delivered, retry or failed.

The channel owns its destinations and how a notification looks there; the router decides what is sent and to whom, the same for every channel. The composition root (`Application.kt`) lists the channels this server offers.
