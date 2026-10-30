# Webhooks

Webhooks send the events of the [event feed](cloud-conversations.md#event-feed) (cloud sessions, local runs and [hosted MCP servers](mcp.md#operating-and-troubleshooting)) to your own services as signed HTTP requests, retried until the receiver answers. They are for a service that cannot hold a stream open, such as a support app notified when an agent stalls, or an alert when a hosted MCP server fails (`mcp.stdio.failed`, `mcp.unreachable`). The server advertises them as `team.webhooks.v1`.

| Route | Purpose |
| --- | --- |
| `GET/POST /api/v1/webhooks` | List; add (`{url, types, description?}`, `201` with the signing secret) |
| `GET/PUT/DELETE /api/v1/webhooks/{id}` | Read; change (`{version, url, types, description?, enabled?}`, `409 stale_version`); delete with its log |
| `GET /api/v1/webhooks/{id}/deliveries` | The delivery log, newest first |
| `POST /api/v1/webhooks/{id}/test` | Queue one signed `webhook.test` delivery (`202`) |
| `POST /api/v1/webhooks/{id}/rotate-secret` | A new secret, shown once |
| Dashboard → Team → Webhooks | The same, with the delivery log and a test button |

**Who.** A webhook receives the whole team's events, so an administrator creates and manages it, in the dashboard or with an administrator's `admin:cloud` key (the subscription records the creating member and key in the audit trail). A member, a plain administrator's `cloud:*` key and a `team:read` key cannot read or change webhooks. At most 20 per team. A webhook starts at the newest event: nothing earlier is sent, and re-enabling after a pause does not replay the pause.

**Target.** An `https` URL without credentials or fragment, on the **public internet**: `localhost`, loopback, private (RFC 1918, unique-local IPv6), link-local, carrier-grade NAT, multicast, unspecified, reserved and cloud metadata addresses are refused (`400 webhook_host_not_allowed`), in numeric spellings too. A DNS name is resolved again at every delivery and refused if any answer is such an address, and the delivery connects to the address that was checked, so a name that changes its answer between the check and the connection (DNS rebinding) is refused too (`host_not_allowed` in the delivery log). Redirects are not followed.

Webhooks are managed by administrators and their `admin:cloud` keys, which are often stored in CI: a private target would let such a key send signed POSTs to the server's own network and read the status codes back, a port scanner (audit L1). For an internal receiver, the operator sets `TEAMCODE_WEBHOOK_PRIVATE_TARGETS=true` (Helm: `webhooks.allowPrivateTargets: true`), which allows loopback and private addresses again; link-local, unspecified and metadata addresses stay refused. A subscription created while the option was on stops delivering (`host_not_allowed`) when it is turned off; it can still be paused, relabeled or deleted on its URL, and moved to an allowed one, but not enabled again once paused.

## What is sent

```http
POST /teamcode HTTP/1.1
Content-Type: application/json
TeamCode-Delivery: 5f0c...      (one id per event and webhook, the same on every retry)
TeamCode-Event: cloud.turn
TeamCode-Timestamp: 1761069900
TeamCode-Signature: v1=7aa818774b12d07b487b77188a423aa42da8d52da13f1fface316ecdf0a91f53

{"id":"5f0c...","type":"cloud.turn","createdAt":1761069900123,"data":{"seq":42,"type":"cloud.turn","subject":"session","subjectId":"...","memberId":"...","at":1761069899000,"data":{"sessionId":"...","phase":"completed","eventSeq":17}}}
```

`data` is the feed's `TeamEventView`; `webhook.test` carries `{"message": ...}` instead. The body is fixed when the event is queued, so every retry sends the same bytes (only `TeamCode-Timestamp` and the signature are fresh). Transcript text is never in it.

**Verify the signature.** `TeamCode-Signature` is `v1=` plus the hex HMAC-SHA256 of `<TeamCode-Timestamp>.<raw body>` keyed with the webhook's secret (`whsec_...`, the whole string). Compute it over the raw bytes, compare in constant time, and reject a timestamp more than five minutes old. Delivery is at least once: deduplicate on `TeamCode-Delivery`.

```python
import hmac, hashlib, time
def valid(secret: str, timestamp: str, body: bytes, header: str) -> bool:
    expected = "v1=" + hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    return abs(time.time() - int(timestamp)) < 300 and hmac.compare_digest(expected, header)
```

The secret is stored sealed with the server's encryption key and shown once, at creation and at each rotation; a rotation signs everything still queued with the new secret.

## Retries

A `2xx` within 10 seconds is a delivery. A network error, a timeout, a `5xx`, `408` or `429` is retried, up to 8 attempts, waiting 10 s, 30 s, 2 min, 10 min, 30 min, 2 h and 6 h; any other answer (a `4xx`, a redirect) is final. A delivery still pending after 24 hours is given up (`expired`). Twenty final failures in a row disable the webhook (`too_many_failures`); the next successful delivery resets the count, and a test never counts. A test is attempted once. Deliveries of one webhook are not ordered: use `data.seq`, the team's sequence, when order matters.

## Delivery log

Each delivery shows its state (`pending`, `delivered`, `failed`), attempts, the next attempt, the last HTTP status and a short error code: `timeout`, `connect`, `tls`, `dns`, `host_not_allowed`, `secret_unreadable`, `expired` (a URL the client cannot parse fails as `connect`; deliveries logged by older servers may still show `invalid_url`). The receiver's answer is never read or stored. The log is kept 14 days.

## Storage

Migrations `V20261021220000*`: `webhook_subscriptions` (the secret sealed in the `webhook:<id>` context, the cursor into the journal) and `webhook_deliveries` (unique `(subscription, event sequence)`, so a replica or a restart never queues an event twice). Workers on every replica take deliveries under a lease in the database; a crashed worker's lease expires in a minute.
