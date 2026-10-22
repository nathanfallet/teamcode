# Audit history

Interactive administrators can open **Dashboard → Audit** (`/dashboard/audit`) or call `GET /api/v1/audit`. Ordinary members and service/CI keys cannot read the journal, including administrator-owned service keys. Authentication is revalidated against shared MySQL state on every read.

## Records and pagination

Each event contains only `id`, `actorId`, `action`, `subjectId` and `occurredAt` (Unix milliseconds from the database clock). Actor and subject are stable resource identifiers. Trusted server operations use actor `system`. There are no credential values, request bodies, prompts, response bodies, provider account email addresses or before/after configuration values in these records. The browser escapes every text field and API/browser responses use `Cache-Control: no-store`.

The API returns at most 50 events, newest identifier first. Response shape (event list abbreviated):

```json
{
  "events": [
    {
      "id": 100,
      "actorId": "member-uuid",
      "action": "account.enrolled",
      "subjectId": "account-uuid",
      "occurredAt": 1792416000000
    }
  ],
  "nextBeforeId": 100
}
```

`nextBeforeId` is present only when an older page exists; otherwise it is null or omitted. Pass it to `GET /api/v1/audit?before=100`. The typed SDK exposes `audit(beforeId = null)`. Cursors must be single positive 64-bit integers; there is no unbounded page-size parameter or offset scan. A `(team_id, id)` index supports the team-scoped range query. New events do not shift an already-read page; refresh the latest page to see events committed while browsing. The journal is not a consistent-snapshot export across pages.

## Current coverage

- Ownership grants/removal and one-time legacy ownership migration (`member.owner_granted`, `member.owner_removed`, `member.owner_migrated`); [recovery and role rules](team-roles.md).
- First administrator creation (`member.bootstrap`), successful login/logout, password replacement/reset, member creation and access changes.
- Device registration/rename/revocation and explicit authentication-session revocation.
- Service/CI credential creation (`service_key.created`, or `service_key.created_no_expiry` for a key without expiry, which only an administrator creates; the event's actor is the creator and, for a key created for another member, its related member is the owner) and revocation, including self-revocation through logout.
- Account enrollment and credential replacement (`account.enrolled`, `account.credentials_updated`, and `account.reconnected` when the account needed reconnecting; the actor is whoever ran `accounts add`, a member when [every member may add accounts](capacity.md#who-can-add-accounts)), operator metadata configuration, access-only activation and refresh-ownership handover.
- Who can add accounts (`accounts.enrollment_policy_changed`, subject `team:previous->new`).
- Member allowance/concurrency/weight policy, pool budgets, model/effort/experimental settings and routing rule changes.
- Group creation/policy/archive changes and assignments/removal/pause/resume. Assignment event subjects use `groupId/memberId`; the metadata field supports 100 characters. Group writes and their audit event commit together under the team lock. See [groups](groups.md).
- Local agent-launch start/end and run policy changes (`run.started`, `run.ended`, `run.policy_updated`); no per-heartbeat journal entry.
- Controller observation/enforcement changes (`pacing.observation_enabled`, `pacing.enforcement_enabled`) and quota pressure threshold changes (`pacing.quota_pressure_changed`).
- Automatic shared pool size on/off (`pool_size.automatic_enabled`, `pool_size.manual_enabled`) and API price table changes (`capacity.api_price_changed`, and `capacity.api_prices_defaults_loaded` when an administrator loads Anthropic's list prices; the subject records the previous and new row counts); see [Automatic shared pool size](capacity.md#automatic-shared-pool-size).
- Artifact storage and settings (`artifacts.storage_saved`, `artifacts.storage_tested` with subject `passed` or `failed`, `artifacts.storage_deleted`, `artifacts.settings_updated`, and `artifacts.public_sharing_changed` with subject `old->new`); see [artifacts](artifacts.md#storage-and-settings).
- Artifacts (`artifact.created`, `artifact.version_published`, `artifact.access_changed`, `artifact.member_added`, `artifact.member_removed`, `artifact.deleted`, `artifact.publish_refused`, `artifact.admin_viewed`, and by the system `artifact.retention_expired` and `artifact.storage_deleted`); see [artifacts](artifacts.md#audit).

Account writes and their audit event commit in the same team-locked transaction. The write names either an authenticated principal or an explicit trusted system operation. Current interactive administrator permissions are checked again after provider identity verification and before any write or idempotency replay is returned. Revocation during a remote verification therefore prevents the later mutation. Repeating the same enrollment operation does not append a duplicate event; conflicting or denied writes append none. Repeating an unchanged handover state also appends none.

Existing events remain readable after the additive index migration; historical unaudited operations are not reconstructed. Upgrade every replica before relying on complete attribution and account-write authorization fencing, because older binaries do not enforce this new path.

This is an administrative/authentication journal, not the inference request ledger. Existing request/settlement metadata and directive receipts remain separate. Automatic refresh, every failed login and every automatic session expiry/rotation are not individually journaled here. Cascading session revocations are represented by their triggering password/member/device event. Temporary budget overrides have linked retained grant/revocation history and metadata-only journal events; retention/export and operational audit coverage remain V1 acceptance work; this page does not claim a complete compliance archive.

## Reference and verification

TeamClaude's `src/config-ops.js`, `test/import-credentials.test.js` and `test/server-account-controls.test.js` remain the reference for account metadata controls and local credential import. TeamCode retains verified identity-based updates and metadata preservation while adding authenticated team attribution, shared transactions and cross-replica revocation checks. TeamClaude's local configuration hooks do not provide this shared team journal.

`AccountAuditPersistenceTest` uses real MySQL and two independent pools to exercise enrollment/reimport/idempotency, metadata/handover events and revocation during delayed provider verification. `AuditPersistenceTest` checks bounded pagination across concurrent appends, team isolation, current role/scope/expiry checks and restart-compatible migrations. `AuditRoutesTest` covers the typed SDK, administrator-only API/dashboard access, malformed cursors, escaped content and private-cache headers. The packaged-server/native-team smoke additionally checks journal persistence across server restart and native logout. These fixtures make no real provider requests.

Temporary daily allocations record `budget_override.granted`, `.superseded`, `.revoked` and `.pool_changed` with a `memberId/overrideId` subject. Administrative [override history](budget-overrides.md) retains allocation, expiry, actors and reasons; the shared journal does not copy those user-supplied reasons. Expiry follows the recorded deadline without a periodic event.

`usage.reconciled` records the request/reservation UUID. Its [retained reconciliation record](usage-reconciliation.md) holds original attribution/charge, verified-credit attestation, provider correlation, evidence reference, reason, actor and time. The journal does not copy evidence content; inference bodies and credentials are never collected. An identical retry emits no second event, and quota percentages or lease expiry never trigger an automatic refund.
