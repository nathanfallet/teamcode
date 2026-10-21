# Controlled HTTP scheduling acceptance

`FairSchedulingHttpTest` exercises the production `PoolGateway`, shared admission queue, authorization, credential source and usage settlement through real HTTP connections. Two embedded Netty replicas use independent HikariCP/Exposed pools connected to one owned MySQL 8.4. Their upstream is a separate owned loopback HTTP/SSE fixture implementing `ProviderTransport`; no real provider, subscription quota or operator database is used.

## Scenario

Three members have daily weights **5:5:1**, sharing a 1,100,000-unit pool. Their computed allowances must be 500,000 / 500,000 / 100,000 units. Only the heavy member has a concurrency limit (two requests); the others keep the unlimited default. One physical account serves everyone, and accounts have no parallelism limit of their own.

The heavy member fills its limit with two actual held SSE responses, then submits background requests across the two replicas. A raw-socket request from the same member is queued and reset before response headers: its queue entry disappears, it consumes no credits and it never reaches the upstream. With eight background requests waiting, a ninth is refused with HTTP 503 and is not dispatched. None of the waiting requests receives a quota rejection.

The other members then submit three interactive requests each. All six reach the busy account at once, while the heavy member's eight requests stay queued: a busy account never makes other members wait. Releasing one original stream then frees exactly one slot of the heavy member's limit at a time, and its eight requests dispatch one by one (a member blocked by its own limit polls for the freed slot; its requests are not ordered among themselves). Each response must contain the complete expected SSE bytes. The last held original stream is then released. Assertions require:

- 16 complete streams, one refused overflow and one cancelled pending request;
- a measured peak of eight queued requests and eight simultaneous upstream streams on one account;
- exactly one upstream call per successful request, with no call for the refused/cancelled requests;
- an empty queue, zero active reservations and 16 completed requests after settlement;
- exact final charges of 60 / 18 / 18 units, read through the other replica's pool.

The test uses normal polling and real database/HTTP timing. Its output records time from client launch to synthetic upstream dispatch. Held streams and the 500 ms queue polling interval intentionally contribute to those values. They are fixture diagnostics, not gateway-added latency, provider latency, production throughput or an SLO. Cross-member turn rotation and interactive priority have domain (`AdmissionQueueTest`) and MySQL (`AdmissionQueuePersistenceTest`, `SharedOverflowPersistenceTest`) fixtures; since accounts no longer have a parallelism limit, this HTTP fixture has no shared slot for members to take turns on.

## Reproduction and negative controls

With Docker and JDK 21 available:

```sh
./gradlew :app:test --tests '*FairSchedulingHttpTest'
```

Earlier negative controls, which removed member-turn ordering and interactive priority from `SelectQueueTurnUseCase`, applied to the previous shared two-slot account scenario; that scenario no longer exists.

See [current implementation evidence](status.md) for the observed result and timings. The app integration suite runs on ordinary Linux CI; this test does not enable native or paid macOS/Windows jobs.

## Remaining acceptance

This is bounded, controlled end-to-end contention evidence. It does not establish sustained real-agent fairness, production polling cost, streaming memory bounds with a slow reader, mixed provider/model/pool load, forecast accuracy, soft borrowing, missing-usage reconciliation or production SLOs. Those V1 requirements remain open. Two replicas here share a test JVM; the separate packaged-server [replica smoke](../scripts/smoke-replicas.py) covers independent processes, startup/restart, shared mode configuration and revocation.
