# Temporary daily quota overrides

An interactive owner or administrator can temporarily replace a member's normal daily credit allocation from **Dashboard → Quotas and capacity → Quotas and pools → Temporary quota overrides** or the typed API/client. Every grant requires a single-line reason, an explicit total daily allocation and a duration of 1–1440 minutes. The deadline is the earlier of that duration and the next midnight UTC. Zero stops new admissions; it does not mean unlimited credits.

The permanent weight, individual hard cap and concurrency settings are retained. While active, the override replaces the weighted/capped daily allocation, bounded by the pool's total budget. Capacity responses expose both `dailyUnits` (effective) and `baseDailyUnits` (normal), plus the active override's ID, requested daily units and deadline. A `usage:read` key can read its own capacity without administrative grant/revocation reasons. Ordinary members, group managers and all service/CI keys cannot grant, revoke or inspect override history, even when the key belongs to an administrator.

## Accounting and protection

An override changes the allowed total for the current UTC day. It never adds credits repeatedly, erases charges or refunds an active/uncertain reservation. For example, a member with a normal allocation of 100 and 80 already charged receives at most 220 additional credits under a temporary total of 300. Expiry/revocation restores the current normal policy; if usage already exceeds it, later requests wait until a reset or another authorized policy decision. Existing streams finish normally and settle against their original reservations.

The group/run budgets and pauses, member/group/run concurrency, pool total, model/effort restrictions, account eligibility and provider windows still apply. An increased exception can consume unspent shared-pool budget that other members would otherwise use; it is an explicit administrative allocation decision. Dedicated account pools remain the physical protection against other members consuming protected subscription capacity. Exceptions cannot move accounts or grant access to another pool.

Grant/replacement/revocation, pool changes and admissions share the team transaction/lock. Both direct and queued attempts read the effective allowance; an exception expiring while a request waits cannot authorize its later dispatch. The controller reads the same effective allocation and invalidates its recovery context when the active exception changes. Observation-only predictive control still enforces these hard budgets. Deadlines use the database clock at each read, without an expiration worker, in-memory balance or sticky replica.

A pool change revokes the member's active exception atomically. Moving back does not resurrect it. Changing the permanent weight or individual cap within the same pool does not silently cancel an active exception; its temporary allocation remains explicit, and the updated normal allocation takes effect afterward. Replacing an exception requires its currently active ID in `replacesId`. Concurrent/stale forms return HTTP 409; refresh before deciding again. Repeated revocation of the same already-revoked ID preserves the original revocation and cannot revoke a newer grant.

## History and audit

Grant identity, creator, reason, allocation, pool, creation/deadline and replacement linkage are retained. Revocation appends its actor, time and reason rather than deleting the original record. The administrator journal records `budget_override.granted`, `budget_override.superseded`, `budget_override.revoked` and `budget_override.pool_changed`, with a `memberId/overrideId` subject pointing to the retained record. Natural expiry is determined from its recorded deadline and emits no periodic journal event. Do not put credentials, prompts or customer data in reasons.

History uses immutable creation-time/UUID keyset pages of at most 50. At most 5,000 records per member and 100,000 per team are retained; reaching either limit rejects new grants. Automatic history deletion/export is not implemented. These bounds and the journal do not claim a complete compliance archive.

## API and SDK

All management operations require a current interactive administrator login:

| Method | Path | Request |
| --- | --- | --- |
| GET | `/api/v1/members/{memberId}/budget-overrides?before=OVERRIDE_ID` | Optional older-page cursor |
| POST | `/api/v1/members/{memberId}/budget-overrides` | `{"dailyUnits":300,"durationMinutes":60,"reason":"Support incident","replacesId":null}` |
| POST | `/api/v1/members/{memberId}/budget-overrides/{id}/revoke` | `{"reason":"Incident resolved"}` |

History returns `overrides`, `activeOverrideId` and `nextBeforeId`. Grant/revoke return the full administrative record. Use `replacesId: "CURRENT_OVERRIDE_UUID"` for a replacement, or null when no exception is active. Expired/replaced IDs cannot authorize a new replacement. Numeric allocation bounds are 0–100,000,000,000; reasons are trimmed, 1–240 characters, without embedded control characters. Public capabilities advertise `capacity.budget-overrides.v1` when management is available.

The typed client exposes `budgetOverrides`, `grantBudgetOverride` and `revokeBudgetOverride`; the CLI remains dependent only on client/API modules. Browser actions validate the exact origin and CSRF token and escape all reasons/metadata. The feature is additive and uses a reviewed Exposed-generated migration. Upgrade every server replica before relying on exceptions; older servers enforce the normal policy. Rolling back a binary does not preserve override behavior, and the additive history must be retained.

## Verification

`BudgetOverrideTest` checks UTC deadlines, duration/credit/reason bounds and scope/lifecycle semantics. `BudgetOverridePersistenceTest` uses independent MySQL pools for current-role/scoped authorization, expiry without a worker, exact retained charges, concurrent grants and reservations, stale replacement forms, idempotent revocation, history pagination, queued expiry, controller behavior, observation mode and group/run/pool/account-placement enforcement. `BudgetOverrideRoutesTest` exercises typed SDK/resource paths, own capacity metadata, escaped history, browser origin/CSRF and service-key/member refusals.

Full database dump/restore includes active and revoked exceptions and their exact history, and restored HTTP capacity/history/scopes. The packaged two-process smoke checks cross-replica creation, own summary privacy, escaped dashboard history, persistence through exact-owned-process restart and cross-replica revocation. This documentation alone is not acceptance evidence.
