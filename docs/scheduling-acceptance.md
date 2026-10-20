# Controlled HTTP scheduling acceptance

`FairSchedulingHttpTest` exercises the production `PoolGateway`, shared admission queue, authorization, credential source and usage settlement through real HTTP connections. Two embedded Netty replicas use independent HikariCP/Exposed pools connected to one owned MySQL 8.4. Their upstream is a separate owned loopback HTTP/SSE fixture implementing `ProviderTransport`; no real provider, subscription quota or operator database is used.

## Scenario

Three members have daily weights **5:5:1**, sharing a 1,100,000-unit pool. Their computed allowances must be 500,000 / 500,000 / 100,000 units. All have the same two-request concurrency limit: weights allocate daily credits, not scheduling turns. One physical account provides two slots.

The heavy member occupies both slots with actual held SSE responses, then submits eight background requests across the two replicas. A ninth pending request from that member is refused with HTTP 503, without provider dispatch; the other members can still enqueue three interactive requests each. While the 14 requests wait, they receive no quota rejection and reserve no support credits. An additional support request is submitted through a raw HTTP socket and reset before response headers; its queue entry disappears, it consumes no credits and it never reaches the upstream.

Releasing one original stream exposes exactly one slot at a time. The six interactive requests must dispatch in member rotation, preserving each member's FIFO order, before the unaged background requests. Each response must contain the complete expected SSE bytes. The last held original stream is then released. Assertions require:

- 16 complete streams, one refused overflow and one cancelled pending request;
- a measured peak of 15 queued requests and at most two simultaneous upstream streams;
- exactly one upstream call per successful request, with no call for the refused/cancelled requests;
- an empty queue, zero active reservations and 16 completed requests after settlement;
- exact final charges of 60 / 18 / 18 units, read through the other replica's pool.

The test uses normal polling and real database/HTTP timing. Its output records time from client launch to synthetic upstream dispatch. Held streams and the 500 ms queue polling interval intentionally contribute to those values. They are fixture diagnostics, not gateway-added latency, provider latency, production throughput or an SLO. The six interactive admissions are controlled to occur before the 30-second background-aging threshold; long-running/background-aging behavior has separate domain and MySQL fixtures.

## Reproduction and negative controls

With Docker and JDK 21 available:

```sh
./gradlew :app:test --tests '*FairSchedulingHttpTest'
```

Local negative controls independently removed member-turn ordering and interactive priority from `SelectQueueTurnUseCase`, while retaining the actual HTTP/database path. Each mutation failed the final interactive-order assertion; compilation failures were not accepted as negative evidence. The original source was restored before the final regression run. These controls show that the HTTP fixture detects both scheduling regressions rather than merely checking success responses.

See [current implementation evidence](status.md) for the observed result and timings. The app integration suite runs on ordinary Linux CI; this test does not enable native or paid macOS/Windows jobs.

## Remaining acceptance

This is bounded, controlled end-to-end contention evidence. It does not establish sustained real-agent fairness, production polling cost, streaming memory bounds with a slow reader, mixed provider/model/pool load, forecast accuracy, soft borrowing, missing-usage reconciliation or production SLOs. Those V1 requirements remain open. Two replicas here share a test JVM; the separate packaged-server [replica smoke](../scripts/smoke-replicas.py) covers independent processes, startup/restart, shared mode configuration and revocation.
