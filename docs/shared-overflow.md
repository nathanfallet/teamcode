# Shared subscription overflow

A member can borrow physical subscription capacity from a deliberately shared reserve after their home accounts become unavailable. This does not grant additional daily credits, raise concurrency limits or consume another member's protected accounts. The default is disabled both on members and pools.

## Administration

In **Dashboard → Capacity**, create a named pool budget and enable **Shared overflow reserve**. Assign the intended provider accounts to that pool in account settings. A reserve must have no primary member assignments, including disabled members. The shared default home pool cannot be a reserve. At most 16 named reserves can be shared.

Enable **Allow shared overflow accounts** on each authorized member. Keep their primary pool and weight unchanged. Accounts in any other ordinary pool remain private to that pool. A reserve can contain Claude Max accounts, Codex accounts or both; selection always uses the resolved target's provider. Cross-provider conversion remains separately experimental and disabled by default.

The management API uses the existing endpoints with additive default-false fields:

- `PUT /api/v1/pools/budgets`: `{"poolId":"reserve","dailyUnits":1000000,"sharedOverflow":true}`.
- `PATCH /api/v1/members/MEMBER_ID/policy`: `{"dailyUnits":0,"maxConcurrent":2,"poolId":"developers","weight":5,"allowSharedOverflow":true}`.

The typed client provides `savePoolBudget`, `poolBudgets` and `saveMemberPolicy`; own capacity includes `allowSharedOverflow`. These are whole-policy writes: include the existing limits when updating them. Configuration requires a current interactive administrator login. Service/CI keys cannot change it. The dashboard protects writes with origin and CSRF validation; changes use the existing policy and pool-budget audit events.

## Admission and accounting

An eligible conversation pin is retained, including a pin to an authorized reserve. Otherwise home accounts take precedence over shared accounts, even when an overflow account has a higher configured priority. Within the selected tier, priority, active requests, conversation load and applicable provider utilization retain the existing ordering. Disabled accounts, cooldowns, saturated model/provider windows, excluded retry accounts and the two-request physical limit remain enforced.

Every admission checks the member's effective weighted allowance, logical home budget, all group and launch limits, and the physical source's remaining budget under the same SQL team lock. A request borrowing 100 credits spends 100 member credits and is constrained by both pool budgets. It is not charged twice to the member. Pool totals overlap by design; do not sum home and reserve views to infer team token usage. A member with no remaining allowance cannot borrow merely because a reserve is idle. Use an explicit temporary allocation override when appropriate; it still cannot bypass pool, group or launch limits.

Reservations snapshot both the logical home and physical source. Actual usage replaces the same estimate in both budget views; missing usage retains it. Later member/account moves, revocation or pool reclassification do not rewrite that attribution. Request-specific reconciliation preserves both identifiers in retained history and updates the same charge. Legacy reservations with no source value use their original logical pool. Revoking access prevents new requests; already admitted work finishes and remains charged.

## Shared scheduling and advice

Queue turns are scoped by the selected physical pool and provider, so borrowers with different primary pools compete in the same member rotation. Queue metadata records a physical scheduling hint; renewal recomputes it from current shared configuration and request metadata. The body remains in its cancellable request and is never persisted. Admission rechecks every policy and source budget before consuming a turn and creating the reservation atomically. Requests blocked by a member/group/run/pacing limit stay ineligible for other members' turns until their next successful admission check. Interactive priority, background aging, pending bounds and expiring leases remain unchanged.

Forecast advice includes only authorized, funded reserves. It follows the home tier or an eligible active conversation pin, so an idle reserve cannot conceal pressure on the home account actually serving a session. When active conversations on one target use different pins, aggregate advice retains the most restrictive account advice. Current target/provider/model filtering and persisted recovery hysteresis remain in force. Advice cannot predict the exact cost of a future request or reserve capacity for its recipient; admission is authoritative.

Upgrade all replicas before enabling overflow. Additive defaults preserve ordinary isolated pools. Legacy queue rows fall back to their original home scope and expire naturally; retain the new columns on rollback. Real-load scheduling and forecast calibration remain separate release acceptance work.

## Reference

TeamClaude's route account allowlists, eligibility checks and pin fallback (`src/account-manager.js`, `setRoutes`, `_routeAccountsView`, `setRoutePin`) are the behavioral reference for authorized candidates and retaining eligible pins. TeamCode adds explicit team reserve authorization, immutable dual attribution, source budgets and shared SQL queue turns instead of process-local routing state. This is physical spare-capacity borrowing, not a promise to restore subscription quota already consumed elsewhere.
