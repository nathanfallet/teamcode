# TeamCode contributor instructions

- All repository content must be English. Root package and Gradle group: `me.nathanfallet.teamcode`.
- Squash-merge pull requests; do not create merge commits or rebase-merge PRs.
- Remove temporary diagnostic branches and their clean, owned worktrees when the investigation is finished; preserve findings and verification evidence in `docs/status.md`.
- V2 may be developed independently in separate branches/worktrees by the owner's other agent. Do not merge V2 into `main` before V1 acceptance or modify those worktrees/branches.
- Current execution scope is V1 only. The owner will implement V2 separately; its roadmap records ideas and does not authorize V2 implementation under the active V1 goal.
- Standalone public project. No ControlResell dependencies, registries, secrets, infrastructure or CI.
- Use TeamClaude as the behavioral reference. Before implementing account enrollment, provider routing, agent launchers or service integration, inspect the corresponding implementation and regression tests in the sibling `teamclaude` checkout. Reuse its established behavior where applicable; document adaptations required by native Kotlin, team authorization or shared persistence. Do not invent a parallel mechanism without first checking the reference.
- Keep `cli -> client -> api`. The CLI must never depend on server domain or infrastructure modules.
- Server layers: `app -> presentation + infrastructure + domain + api`; `presentation -> domain + api`; `infrastructure -> domain`. Domain and API do not depend on other project modules.
- Use the Exposed DSL for all database queries, mutations and locks, including test fixtures. Do not write raw SQL or call `exec` with SQL strings. Keep HikariCP for connection pooling and Flyway for migration execution; generate new migrations from Exposed table definitions and review their output. Preserve already-published Flyway migrations unchanged.
- Every new or changed query on a request path (gateway, admission, dashboard reads, polling loops) needs `EXPLAIN ANALYZE` evidence in its PR, run on MySQL 8.4 with a table seeded to production scale (hundreds of thousands of `reservations` rows, not an empty schema). Paste the plan before and after, and add or adjust an index when it shows a filter applied after an index lookup, a table scan or a temporary table. Queries that run inside the team lock or on every polling tick count by cost times frequency, not per execution.
- Put business rules in use cases and services, never in HTTP routes or CLI commands. Use constructor injection and Koin in the server composition root.
- Native CLI: no JVM at runtime. Test the actual native binary; JVM tests alone do not validate TLS, streaming or process behavior.
- Do not run overlapping Gradle tasks concurrently in the same checkout/output directories. Batch them in one invocation or use isolated owned worktrees.
- Never log credentials, prompts or response bodies. The only exception is the initial administrator temporary password, printed once after first-run creation commits, as explicitly requested by the project owner. Never print it again on restart. No real provider calls in normal tests or PR CI.
- Extend providers through `ProviderTransport` and `OAuthEnrollmentProvider`; extend incoming HTTP formats through `InboundProtocol` and request/stream formats through `ProtocolAdapter`. Keep team policy and account selection shared. Document capability limits and test real protocol fixtures before claiming support.
- Preserve coroutine cancellation. Bound request bodies, queues, retries and network waits. Never replay a stream after committed output.
- Update `docs/status.md` with evidence when a milestone changes. Never present a planned command as implemented.
- Never stop processes or Gradle daemons started by someone else. Never kill by process name.

- Keep ordinary push/PR CI on Linux (JVM, Helm and Docker). Paid native matrices, including every macOS job, run only on version tags or explicit manual `full_validation=true`. Batch native changes before requesting that matrix; do not dispatch it per commit. Keep full native acceptance mandatory for versioned CLI releases.
