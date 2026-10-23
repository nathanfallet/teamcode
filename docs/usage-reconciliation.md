# Unknown usage review and reconciliation

A disconnected or malformed stream can omit final usage. TeamCode retains its conservative reservation instead of treating missing usage as zero. An expired lease releases concurrency, not credits. **Dashboard → Usage review** lists unknown charges and expired active leases for an interactive owner/administrator. Members, scoped group managers and service/CI keys cannot read or change this administrative review, including administrator-owned keys.

## Evidence and authority

Reconciliation is an administrator's attestation of request-specific provider evidence. The server does not independently retrieve or authenticate a provider usage record from its reference. Use a provider usage record or provider confirmation for that exact request/account; a quota percentage, elapsed time, guessed output length or an expired lease cannot establish actual usage. Without sufficient evidence, leave the request pending and its conservative charge intact.

Enter the verified total **team credits**, not a subscription percentage or monetary amount: input/cache tokens count once and output tokens five times. Responses input already includes cached input; do not add its cached detail twice. Both the displayed charge and provider correlation ID are checked against the current row before correction. A stale view returns HTTP 409 and requires another review.

The fixed provider transport captures bounded `request-id`/`x-request-id` metadata before streaming. Conflicting, duplicate, unsafe or oversized values remain unknown; providers can override that correlation contract through `ProviderTransport.requestId`. User headers/bodies cannot select this stored upstream identifier. Older requests or failures before response headers can have no correlation ID. The TeamCode request UUID, account, member and timestamps remain available for matching independent evidence. No prompts, transcripts, response bodies or credentials are collected for reconciliation. Evidence/reference/reason fields are plain administrative metadata, so use nonsecret identifiers rather than content or token-bearing URLs.

TeamClaude's `src/server.js:openRequestLog` was inspected as the reference: it uses a process-wide file sequence and optional streaming request/response logs. The team adaptation retains globally unique reservation UUIDs and shared SQL correlation metadata; it does not copy content logs into the server journal or rely on a process-local counter.

## Accounting and races

Corrections are limited to `UNCERTAIN` reservations or `ACTIVE` reservations whose database-clock lease has expired. A live lease and a known `SETTLED` charge cannot be manually replaced. This is a final correction, not a repeatable budget refund or editing interface for known usage.

Admission, settlement, provider-correlation writes and manual reconciliation share the team lock. The transaction replaces the reservation charge, snapshots the original member/account/group IDs/pool/day/run and evidence, adjusts the linked run's lifetime total by exactly the same delta and appends `usage.reconciled` to the audit journal. Original reservation/group links remain; moving memberships or pools cannot change attribution. Corrections can raise or lower a charge, and affect its original billing day. A historical correction does not create credits on today's member/pool ledger. Provider percentage observations and actual subscription capacity are not reset.

Repeated identical corrections return the original record without another delta or audit event. A different second correction returns HTTP 409. A simultaneous normal settlement and manual correction have one winner; a late `settle` callback cannot overwrite a reconciled row. Completion counters and admission counts remain unchanged: verified billing does not prove a complete response was delivered. Active streams are never replayed or preempted by this feature.

## Retention and recovery

Ordinary **resolved** reservation details retain their existing 90-day cleanup. Unknown/expired-active charges now remain reviewable beyond that period until resolved; retention and restart never silently erase their evidence or refund their run total. Such rows can keep a run's detail record referenced until reconciliation and subsequent cleanup.

Original group identifiers are copied into a retained association table and exposed as `groupIds`, independently of current membership. The reconciliation snapshot/journal survives deletion of a resolved detailed reservation. History has indexed immutable time/UUID keyset pages of at most 50; pending pages merge two separately indexed state ranges with at most 51 selected rows each. Neither view buffers the complete ledger. Unresolved metadata and reconciliation history have no automatic deletion/export policy yet; size and operate the database accordingly. This does not claim performance acceptance for an arbitrarily large backlog.

The database backup includes pending charges, captured provider IDs, exact corrections and evidence. Restore with the original encryption key and existing ownership/revocation rules. Upgrade every server replica before relying on review/retention behavior; older binaries do not expose these operations and can still apply their previous cleanup policy. Already-deleted legacy details cannot be reconstructed from guessed usage.

## API and typed client

All endpoints require a current interactive owner/administrator:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/usage/pending?member=MEMBER_UUID&before=REQUEST_UUID` | Pending metadata, optional member/cursor filters |
| GET | `/api/v1/usage/reconciliations?member=MEMBER_UUID&before=REQUEST_UUID` | Retained correction history |
| GET | `/api/v1/usage/reservations/{id}` | Pending review or its retained correction |
| POST | `/api/v1/usage/reservations/{id}/reconcile` | Final operator-attested correction |

Example correction:

```json
{"expectedUnits":20000,"actualUnits":1400,"source":"PROVIDER_RECORD","reference":"provider record req_example","reason":"Verified terminal usage","expectedProviderRequestId":"req_example"}
```

Use `PROVIDER_RECORD` or `PROVIDER_CONFIRMATION`. `expectedProviderRequestId` must match the displayed ID, including null when none was captured. Expected/actual credits are bounded to 0–100,000,000. Reference and reason are trimmed single-line fields of 1–240 characters. The request UUID is a resource identifier, not a bearer capability. Responses expose metadata only and are private/no-store; browser writes require origin/CSRF validation and render escaped text.

The typed client exposes `pendingUsage`, `reconciledUsage`, `usageReview` and `reconcileUsage`, using API contracts only. Capability discovery advertises `capacity.usage-reconciliation.v1`. There is no API for uploading untrusted client usage as an automatic refund, and this feature does not grant a service key administration authority.

## Verification

Domain tests cover finality, expired leases, stale credit/correlation snapshots and evidence bounds. Independent-pool MySQL tests cover concurrent corrections and normal settlement, immutable original attribution after membership/pool moves, exact positive/negative run deltas, duplicate/conflicting corrections, revoked/scoped authorization, retained unknown rows and history after detail cleanup, and stable pending/history pages.

Provider/gateway tests verify bounded correlation capture before streaming and rejection of ambiguous values. Typed SDK/dashboard tests cover paths, member/service-key denial, escaped evidence and CSRF. An actual two-replica HTTP/loopback-provider test omits final usage, observes the retained charge and captured provider ID, corrects through the other replica, and proves there is no provider replay or invented completion. Full database dump/restore includes pending/reconciled rows and administrative HTTP scopes. Packaged-server smoke checks the new management routes and authorization across restart. This documentation alone is not acceptance evidence.
