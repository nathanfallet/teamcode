# ADR 0002: API-equivalent pricing and automatic shared pool size

Date: 2026-10-21. Status: proposed, not implemented. Builds on the [raw token usage ledger](../capacity.md#raw-token-usage).

## Context

Admission charges **credits**: uncached input + cache reads + cache writes + 5 × output. The shared (home) pool has a daily credit budget that an administrator types by hand, and members share it by weight. Two problems follow.

- Credits do not track what a subscription meters. Agent traffic is mostly cache reads, which a provider prices around a tenth of uncached input, while credits count them at full weight. A member whose sessions reuse a long cache pays far more credits per unit of real cost than one who does not.
- The hand-typed pool budget has no relation to the capacity of the connected subscriptions. Set too low, members are refused while accounts sit idle; set too high, only the quota windows stop admission. The pacing controller (SLOW_DOWN) is in observation-only mode in production for a related reason: it projected exhaustion and imposed a concurrency ceiling while several accounts still had headroom.

The raw ledger now stores the four token types per day, member, launch and model, and per account and hour. That is what an API-equivalent cost and a capacity estimate need.

## Decision

Three steps, each behind an explicit setting. With every setting at its default, admission, charges, budgets and pacing behave exactly as today.

### 1. A dated API price table (no admission change)

- Table `api_prices`: `team_id`, `provider`, `model` (an exact model identifier or a family prefix such as `claude-opus-4`), `effective_from_day` (UTC day), and four prices in micro-dollars per million tokens: `input`, `cache_write`, `cache_read`, `output`. The price of a row for a day is the most recent row whose `effective_from_day` is on or before that day; an exact model wins over the longest matching prefix. A new price is a new row, so past days keep the price that applied to them.
- Edited in **Dashboard → Settings → API prices** and `GET`/`PUT /api/v1/policy/api-prices` (interactive administrators; `GET` with `usage:read`). Every change is audited (`capacity.api_price_changed`). Never read from an environment variable. TeamCode ships no built-in prices: list prices change, and a stale default would silently mislead. A later convenience may offer to fill the table from a dated public list, as an explicit administrator action.
- `GET /api/v1/usage/tokens` gains `apiEquivalentMicros` per row (null when no price covers the model and day) and the dashboard shows the equivalent per member and launch. Unpriced models are listed so the administrator can complete the table.

This step is read-only for admission and can ship on its own.

### 2. Automatic shared pool size (setting "Automatic shared pool size", off by default)

A checkbox in **Dashboard → Settings**, stored in `pacing_settings` (`auto_pool_size BOOLEAN DEFAULT FALSE`), audited. Unchecked, the hand-typed budget applies exactly as today. Checked, TeamCode computes the **home pool's** daily budget from the connected subscriptions; named pools and shared overflow reserves stay manual in this version. **The split between members does not change**: each member's allowance remains the pool budget × weight / total weight of the enabled members, capped by their optional hard cap, with temporary overrides, groups and launch budgets applied as today. Only the pool total moves.

Estimate per account. For each account of the home pool that can serve (`READY` or `ACCESS_ONLY`, not disabled, not `RECONNECT_REQUIRED`, not cooling down):

1. Pair two observations of the same shared window (`five_hour`, `seven_day`) in the same window period (same reset), at least 5 utilization points apart, from `quota_history`.
2. Sum the API-equivalent of that account's tokens settled between the two observations (`account_token_usage` hours × the price table).
3. One sample = API-equivalent ÷ utilization delta = the window's full capacity in API-equivalent. Keep the median of the samples of the last 7 days (the retention of `quota_history` today; longer needs a retention change first), with at least 3 samples; otherwise the account has no estimate. Usage is bucketed by settlement hour, so a sample is only accurate to the hour: pairs should span several hours.

One computation of headroom. The pacing rewrite (aggregated projection over every usable account: each account's remaining quota until its own reset, compared with the team's burn rate) and the automatic pool read the **remaining capacity of the usable accounts** from one domain function, `AccountHeadroomUseCase.usable` (shipped with the raw ledger): per usable account, each current window's remaining share below the pressure threshold until its reset. The automatic pool does not compute it a second time: it reads that same function (same account eligibility, same threshold, same resets) and only multiplies each account's remaining points by its estimated window capacity in API-equivalent. Daily capacity of an account = min(weekly remaining points × weekly capacity ÷ days left before the weekly reset (at least one), five-hour capacity × threshold × 24 ÷ 5), where threshold is the team's quota pressure threshold. An account whose relevant window is already at the threshold has no remaining points and contributes nothing until its reset. Pool capacity = sum over the usable accounts.

Unit. Admission still counts credits in this step, so the API-equivalent capacity converts to credits with the team's observed ratio over the last 7 days of the ledger (credits charged ÷ API-equivalent of the same requests, priced models only). The capacity is estimated in API-equivalent rather than directly in credits because the API-equivalent tracks what the provider meters: a shift in the cache mix moves credits but barely moves the cost, so samples taken in API-equivalent stay consistent from one day to the next.

Stored value and admission. A scheduled job (`SqlScheduledWork`, one owner across replicas, every 10 minutes) writes `auto_daily_units` and `auto_computed_at` next to the manual `daily_units` in `pool_budgets`. `readMemberCapacity` uses the automatic value when the setting is on and the value is less than an hour old; otherwise the manual value (stale job, no estimate, no prices for the models in use: the dashboard says which). To avoid oscillation, a new value moves at most ±20% per hour from the previous one. The dashboard shows both values, the per-account estimates with their sample counts, and why an account contributes nothing.

### 3. API-equivalent as the charge unit (later, separate setting)

Optional switch "Charge in API-equivalent" so that every allowance (member, pool, group, launch, override) is counted in API-equivalent: the settled charge becomes the request's priced tokens and the reservation estimate prices the existing token estimate at the model's input and output prices. That fixes the cache-read unfairness between members, but reinterprets every stored budget, so it needs its own conversion screen and is not part of step 2. Until then, step 2's ratio keeps the existing unit.

## Pacing

Pacing stays in observation-only mode until step 2 is on. Its projection (being rewritten separately as an aggregate over the usable accounts) and the automatic pool share one remaining-capacity computation, so they cannot disagree about which accounts have headroom. Then the projection uses what admission uses:

- the accounts that can actually serve: exclude accounts at or above the pressure threshold on a relevant window and `RECONNECT_REQUIRED` accounts, instead of falling back to every candidate when none is available (today `available.ifEmpty { candidates }` in `RoutePacingUseCase`);
- the automatic pool: a scope is projected to exhaust when its burn rate in credits, extended to the end of the UTC day, exceeds what remains of the member's share of the automatic pool, or when an account window is projected to reach the threshold before its reset;
- a SLOW_DOWN only **spaces** admissions (the interval from the affordable share, as the progressive levels do today) and never sets a concurrency ceiling: the advice's `maxConcurrent` stays the member/group/launch limit, zero when none. PAUSE (a window already at the threshold) keeps its rule.

The concurrency ceiling that SLOW_DOWN imposes today, and the queue it produced, are being fixed separately; this design does not change that code.

## Options set aside

- **Size the pool directly in credits** from `reservations` (credits per utilization point, no price table): no new data, but the samples move with the cache mix, and the team still has no cost figure.
- **Fixed capacity per plan** (for example "a Max plan is worth N per week"): simple, but providers change plan limits without notice and per-model limits differ; the estimate learns them instead.
- **Rebalance between members automatically** (equal shares or demand-weighted shares): The project owner ruled it out on 2026-10-21. Weights stay the administrator's decision.
- **Environment variables for prices**: rejected; prices are dated team data, edited and audited in the dashboard.

## Risks

- **Estimation noise.** Utilization is reported in whole points and windows reset; few samples on a new or idle account. Mitigated by the 5-point minimum delta, the 7-day median, the 3-sample minimum and the fallback to the manual budget. An account without an estimate contributes nothing; with every account unestimated, the manual budget applies.
- **Over-sizing.** If the estimate is too high, members are admitted until the quota windows exclude accounts at the threshold, which is today's protection anyway. The ±20%/hour step and the threshold keep the overshoot bounded.
- **Under-sizing** refuses work that accounts could serve, the failure that put pacing in observation mode. Mitigated by computing only from usable accounts and showing the estimate on the dashboard; an administrator can uncheck the setting at once.
- **Prices.** Missing or wrong prices skew the ratio. Unpriced models are excluded from the ratio and listed; a day with no priced traffic keeps the last ratio.
- **Model-specific windows** (a weekly Opus limit) are not part of the pool estimate; admission keeps excluding an account per model at the threshold as today.
- **Rollout.** New columns are additive with defaults that keep today's behavior; an older replica ignores the automatic value and uses the manual budget, so upgrade every replica before checking the setting.
- **History.** The estimator needs `account_token_usage` history, collected since the raw ledger shipped; the setting is useful only after about a week of traffic.
