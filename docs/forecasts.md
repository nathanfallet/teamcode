# Quota forecasts and live updates

Interactive administrators can open **Dashboard → Quotas and capacity → Forecasts** (`/dashboard/forecasts`) and select a subscription account. `GET /api/v1/forecasts?account=ACCOUNT_ID` returns the same observed series and window estimates; omitting `account` selects the first listed account. The typed SDK exposes `TeamCodeClient.forecasts(accountId = ...)`. Ordinary members and service/CI keys cannot read these views, including administrator-owned service keys. Every read revalidates shared authorization and account/team ownership. Responses are private and use `Cache-Control: no-store`.

The dashboard updates automatically using a same-origin, cookie-authenticated SSE connection. The default interval is 15 seconds; Pause/Resume controls and manual refresh remain available. Hidden tabs and page navigation close the stream, and returning to the visible page starts a fresh one. A pending account selection is preserved across updates. The JSON API remains a read-only snapshot endpoint. Charts remain available in controller observation mode and never enable predictive enforcement or send an agent directive.

## Measurements and projections

Each card is one provider-observed window on the selected account. The current percentage, observation/reset timestamps, learned percentage points per hour, pressure estimate and sample coverage remain distinct. Percentages from different accounts or plans are never added, and an account window estimate is not a promise of model access or the member's controller decision. Current routing, model/effort policy, pools, account health, weighted allowance and scoped hysteresis still determine actual advice/admission.

The solid curves contain actual measurements from the latest hour. The server retains at most one sample per minute/window; historical retention remains seven days. Resets, utilization decreases, duplicate timestamps and gaps longer than five minutes split the curve. Hover a point for its measured value and UTC time. The same domain segmentation and window estimator are used for predictive control: learning uses only the newest continuous segment, with at least three samples spanning a minute and a known reset. A clean segment after a decrease or measurement gap can start learning again without averaging the earlier segment into its rate.

Dashed curves are conditional projections, stopping at the earliest of the pressure threshold, reset or one hour into the future. The pressure threshold is the team's [quota pressure threshold](capacity.md#quota-pressure-threshold) (95% by default, adjustable from 50% to 100% by administrators; the API returns it as `pressureThreshold`); `EXHAUSTED` in the window API means that pressure threshold has been measured, not that a provider has necessarily rejected the account. Provider-window admission exclusion uses the same value. The UI formats the pressure ETA approximately to the minute; raw timestamps remain available to API clients.

Unknown resets, missing rates, old/future readings and absent measurements are explicit. No observed window is fabricated with 0% utilization. A valid zero learned speed is displayed separately from an unknown speed. An empty account list and an account without readings have their own empty states. Sample count/span describes coverage, not a fabricated statistical confidence score. A window that resets first is labeled accordingly; its beyond-reset pressure extrapolation is not presented as an event in the current window.

## Bounds and shared persistence

The selector lists at most 64 account labels, plus an explicitly selected account if it lies outside that subset. A response contains at most 128 current windows and 8,192 history rows; `truncated: true` reports a bound being reached. The service reads only account labels/status, observed windows and history, with no credential envelope, provider identity fields or decryption key. Retired model windows are absent; reintroduced windows cannot reuse samples from their retired incarnation.

The service holds the team lock while reading current windows and their history boundaries. Parameterized Exposed queries then use fixed per-window time ranges on the existing `quota_recent(account_id, window_name, observed_at)` index. Reads stay compatible with independent server replicas and use the database clock, without process-local forecast caches. No schema change is needed for this view.

A local MySQL 8.4 diagnostic uses [Exposed's `explain` API](https://jetbrains.github.io/Exposed/api/exposed-jdbc/org.jetbrains.exposed.v1.jdbc/-jdbc-transaction/index.html) on the actual production query, including 4,096 retained out-of-range samples. The initial join scanned approximately 4,100 entries to return 18 rows (4.04 ms in that fixture). The final fixed-range query reads 18 entries through `quota_recent` and returns the same 18 rows (0.117 ms). These are isolated fixture measurements, not production throughput, lock-contention measurements or SLOs.

## Stream lifecycle and authorization

`GET /dashboard/forecasts/stream?account=ACCOUNT_ID` is a browser endpoint using the existing HttpOnly dashboard cookie, never credentials in a query string or provider credentials. Each update calls the shared forecast service again, revalidating the current session, administrator role and selected account ownership against MySQL. A read authorized before a concurrent revocation may already be in flight; subsequent reads cannot reuse cached authorization. Permission loss ends the committed stream with a redacted terminal event, and the browser clears private chart content and account options. Removing an account ends that selection's live feed without granting access to another account.

Connections have a five-minute coroutine deadline, a four-MiB encoded-frame bound and normal database timeouts. They advertise a five-second reconnect interval and disable reverse-proxy response buffering with `X-Accel-Buffering: no`. A disconnected call cancels its polling; no database connection is held while waiting for the next interval or writing to a slow browser. Reconnection reads a fresh full snapshot, so it can reach any replica without sticky sessions, an in-memory event journal or Redis. Connection timing options are server-side, not caller query parameters.

Temporary disconnection marks the existing view as potentially out of date. Fatal/permission errors stop reconnecting and require refreshing/signing in. The browser never interprets an unavailable feed as unused quota. JavaScript-disabled clients retain the initial snapshot and manual refresh controls. Upgrade every replica before relying on live updates; an old pod lacks the stream/script routes and falls back to its snapshot behavior.

## Local preview and acceptance

Generate a standalone synthetic preview through the real server renderer:

```sh
./gradlew :presentation:previewForecastDashboard
```

Open `presentation/build/preview/forecasts.html` in a browser. The preview is explicitly marked synthetic; it uses no server login, operator database or provider. The preview remains static. The production page uses the same server-rendered SVG and escaped labels, enhanced by one small own-origin script; it has no external chart service or frontend package dependency. Only the forecast HTML page permits own-origin scripts and connections. Inline scripts remain forbidden, and other dashboard pages retain their prior restrictive policy.

`ForecastContinuityTest` reproduces the stale-gap/rate-relearning defects and verifies segmented histories and conservative future readings. `ForecastPersistenceTest` uses actual MySQL and independent pools for empty states, rate/reset/freshness behavior, 4,096 old rows, model-cap retirement/reintroduction, output bounds, foreign-team isolation and revoked/scoped/member denial. Its execution plan comes from the production query itself. `ForecastRoutesTest` covers the typed SDK, admin-only API/dashboard access, strict account queries, private headers and hostile labels. `ForecastChartTest` checks parseable accessible SVG, segment boundaries, unknown/stale projection suppression, reset clipping, locale-independent coordinates and empty data. The restoration HTTP fixture and packaged two-process smoke also exercise the endpoint.

`ForecastLiveRoutesTest` covers bounded event framing, fresh reads within one response, terminal redaction, private headers and scoped CSP/script access. `ForecastLiveHttpTest` uses actual Netty connections and independent MySQL pools to update an already open stream through another replica, revoke its session and verify that a peer reset stops polling.

For opt-in real-browser acceptance with Node 22+ and Chrome installed:

```sh
./gradlew :app:smokeForecastBrowser
```

Set `TEAMCODE_CHROME_BINARY` if Chrome is installed elsewhere. The task creates owned tmpfs MySQL/Netty fixtures and an isolated Chrome profile; an ephemeral test session travels through a private pipe, never command arguments or browser URLs. It tests actual CSP/script execution, escaped labels, connection rotation/reconnect, pause/resume, pending selection preservation, hidden-tab suspension and revocation through another database pool. Output is `app/build/preview/forecasts-live.png`, with a metadata-only test log beside it. All fixture servers, containers and the exact owned browser are closed afterward. It never reuses a personal browser profile or connects to a model provider. This optional task is not added to ordinary CI or any native matrix.

Sustained real-agent forecast accuracy, outside-spend attribution, wider browser acceptance and the other V1 requirements remain open.
