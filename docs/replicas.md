# Stateless server replicas

Application replicas share one MySQL database and one credential encryption key. No sticky sessions or Redis are required. The local CLI proxy still maintains its own machine configuration and per-process loopback capability; that is not server coordination state.

| State | Durable coordination |
| --- | --- |
| First administrator | Team row lock and committed creation; only the creator receives the temporary password |
| Sessions, revocation and team roles | Hashed session tokens, effective ownership and current member state read from MySQL; ownership/recovery changes serialize under the team lock |
| Sign-in throttling | Shared database-clock windows of 30 attempts/minute per source address across API/dashboard/replicas, and persisted failure backoff per username and per known device ([details](team-roles.md#sign-in-throttling)) |
| Admission, weighted budgets and concurrency | Team lock, fresh authorization, atomic reservation across member/group/account/pool constraints |
| Agent launches | Shared credential-bound lifecycle, original reservation links, atomic lifetime accounting, run policy and current delivery metadata; no sticky sessions |
| Groups and delegated managers | Shared scoped assignments, current authorization, cumulative caps and original reservation/group snapshots; no process cache |
| Active requests | Unexpired SQL reservations, not per-pod counters |
| Completed requests | Exactly-once settlement increments a durable team counter for observed valid terminal provider usage, including a client closing before HTTP EOF; this is not a delivery acknowledgement and historical traffic is not reconstructed |
| Quotas and forecasts | Database observations/history, stale-data handling and expiring directives |
| Scheduled work | Database-clock owner leases with compare-and-set release; account polling order survives restart |
| Credentials | AES-GCM envelopes, account row lock, credential versions and committed refresh intent |
| Policy and account edits | Database transactions; no process cache |
| Model routes | Team-scoped SQL rules, version-checked updates/deletes and current authorization |
| Hosted stdio MCP processes | Not server state: one [MCP host](mcp.md) Deployment owns them as a cache, every replica reaches it through its Service, and each request carries everything it needs; a host restart only loses the cache. Their recorded states (`mcp_stdio_states`) are written by one replica a minute, by compare-and-set |

Only request buffers, open sockets, immutable configuration and local CPU-concurrency protection remain in memory. The in-memory allowance reference exists only in test sources and is not packaged in the server. Admission windows, session expiry and worker leases use the shared database clock. Model routing reads shared versioned SQL rules. Future session affinity and opaque provider state must follow the same database-backed model.

## Failure behavior

Reservations outlive the maximum 10-minute upstream request (15-minute lease). A killed pod's active slot expires, but its uncertain reserved usage stays charged. Settlement is idempotent and cannot refund a previous uncertain settlement. Reservation details have 90-day retention; completion counters are not tied to that retention.

A row lock alone cannot guarantee safe rotating OAuth refresh after a process crash. TeamCode commits `refresh_pending` before sending a refresh request. Other replicas briefly wait for the owner; they never reuse the token to take over an uncertain refresh. The bounded owner saves the replacement with a credential-version check. Failure or abandoned intent requires verified reimport/reconnect. Handover cannot clear uncertain intent, and import cannot overwrite an active refresh. A successful verified import after uncertainty clears the marker without overwriting operator metadata.

Scheduled quota polling uses one database lease per account and persistent last-start times, with at most eight candidates per worker pass. Maintenance/directive generation has a separate lease. A stale worker cannot release a successor's lease. These leases reduce duplicate observational work; they are not used to authorize inference or rotating refresh. All such decisions use durable transactional invariants.

`/ready` checks database connectivity; `/health` checks the HTTP process. Database failures fail authentication/admission closed and make readiness return 503. No degraded in-memory admission mode exists. Existing streams are not replayed or moved between pods. A rollout allows streams to drain, but a crash or node loss can interrupt them.

## Evidence

`ReplicaPersistenceTest` uses two independent Hikari pools against MySQL 8.4. It covers concurrent migrations/bootstrap, cross-replica session revocation, competing admissions, exactly-once completion/usage, abandoned reservations, single refresh ownership, interrupted-refresh recovery, import/refresh conflicts, worker lease reclamation, stale-owner fencing, persistent polling order and a per-address login budget shared by both pools.

`scripts/smoke-replicas.py --jar app/build/libs/app-all.jar` starts two actual server processes against its own isolated database. It checks concurrent startup, one bootstrap password, shared sessions, members and groups, scoped manager revocation and pause enforcement, password revocation on the other replica, abrupt process termination/restart, readiness versus liveness during database outage, recovery and logout revocation. It stops only its own children and test database and never contacts real providers.


Conversation/account placement also lives in SQL. `conversation_affinities` stores bounded, expiring hashes scoped to the authenticated team/member, and its update shares the admission transaction. Replica restart does not erase a live hint; no local cache is required. See [session affinity](session-affinity.md) for eligibility, concurrency, route invalidation and remaining acceptance limits.

`DatabaseRestoreTest` separately creates a quiesced vendor dump, stops its source MySQL and restores into a different instance. It compares full schema/data and verifies management HTTP access, encrypted account credentials, pre-checkpoint revocations and unchanged quota charges without relying on process memory. See [backup/recovery behavior and limits](operations.md); a restored historical checkpoint does not include subsequent writes or revocations.

Temporary daily quota exceptions and their grant/revocation history are shared database state. Admission and queued retries evaluate their deadlines against the database clock, without an expiration worker; pool changes revoke them transactionally. A crashed replica cannot extend an exception or reset its charges.
