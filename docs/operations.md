# Backup and recovery

TeamCode's durable state is MySQL plus the exact provider-credential encryption key. Application pods have no state to copy. Keep encrypted database backups, the matching encryption-key version and the deployed image/schema version in your recovery records. Store key material in your secret manager separately from the dump. Password hashes, sessions and account metadata make the whole database sensitive even though provider credentials are encrypted.

When [artifacts](artifacts.md) are on, their files are in the team's S3-compatible bucket and their versions, sharing and settings in MySQL. Back the bucket up with your provider's tools (replication or versioning) on the same schedule as the database. A database restored to an earlier point than the bucket only leaves files that nothing references, which are not served; a bucket restored to an earlier point than the database can miss files of the newer versions, which then fail to load until they are republished. The bucket's key is stored in MySQL, sealed with the same credential key. See [setting up a bucket](artifacts.md#setting-up-a-bucket) for the minimal permissions.

## Create a recovery checkpoint

For a quiesced checkpoint, stop new inference/account changes, drain accepted streams and finish credential-refresh work before taking the database snapshot. Prevent application rollouts/migrations during the dump. The accepted fixture uses MySQL 8.4's vendor tools; application and test fixture mutations still use Exposed, with no hand-written SQL.

From a trusted administration environment with MySQL client tools, use a protected destination and interactive password entry or your secret manager's private client option file. Do not put a password value in arguments or logs. For a database named `teamcode`:

```sh
umask 077
mysqldump --host=DB_HOST --user=BACKUP_USER --password \
  --single-transaction --no-tablespaces --set-gtid-purged=OFF \
  --hex-blob --column-statistics=0 \
  --result-file=/private/backup/teamcode.sql teamcode
```

`--single-transaction` provides a consistent InnoDB snapshot; concurrent DDL can invalidate it. Permissions and GTID requirements depend on the server configuration. These flags are for a standalone logical recovery, not a replication/PITR configuration. Follow the [MySQL 8.4 dump documentation](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html) for your backup user's privileges and topology. Encrypt and authenticate the backup before moving it to durable storage. A successful dump command alone does not prove recoverability.

## Restore in isolation

1. Provision an empty isolated MySQL database and dedicated application user. Keep its application ingress inaccessible to ordinary clients.
2. Keep the original application stopped before activating the restored copy. Two copies must not refresh or consume the same subscription accounts.
3. Verify the backup's integrity, then load the vendor-generated dump into that empty database. For example, with interactive credentials:

   ```sh
   mysql --host=RESTORE_DB_HOST --user=RESTORE_USER --password \
     --binary-mode=1 teamcode < /private/backup/teamcode.sql
   ```

4. Supply the exact matching `encryption-key` and an image compatible with the restored schema. Startup validates/applies Flyway migrations; Helm rollback does not undo them. A restored team must not generate another bootstrap administrator password.
5. Check `/ready`, owner login and recovery protection, members/groups/devices, model/effort policy, controller observation/enforcement mode, routing, pool budgets, weighted allowances, spent/reserved usage, scoped API keys and the audit journal. Compare them to the checkpoint record before enabling traffic.
6. Reconcile provider credentials and access changes since the checkpoint. A backup can predate an OAuth rotation, password change or revocation. Reconnect affected provider accounts through the CLI; revoke/reissue restored sessions and service keys as necessary through the supported administration APIs/CLI. Do not clear an uncertain refresh marker to retry a possibly consumed refresh token. Retain administrator access while performing recovery.
7. Enable traffic only after those checks, then verify native-provider behavior with a deliberately small request. Existing streams cannot resume from a database backup and must not be replayed automatically.

The backup has a point in time: writes and revocations after it are not recovered by a plain dump. Select backup frequency and, where required, managed point-in-time recovery to meet your RPO. This repository does not yet provide scheduled backups, binlog/PITR acceptance, encryption-key rotation/re-encryption or a production recovery-time SLO. A bundled MySQL PVC is not a backup; see the [Helm deployment guide](../helm/teamcode/README.md).

## Automated acceptance and its limits

```sh
./gradlew :app:test --tests '*DatabaseRestoreTest'
```

The test creates its own two temporary MySQL 8.4 containers and private dump files. It cannot be pointed at an operator database. It populates the source through production repositories using Exposed, quiesces writes, dumps it with `mysqldump`, stops the source, restores into a different instance and compares a second canonical full-database dump byte for byte. This includes schema, Flyway history and every stored row in the fixture.

It also checks production HTTP management routes after restoration, the 5:5:1 member allocation, active/uncertain quota charges, completion counters, pool budgets, routing/model/effort settings, devices, owner flags, group budgets/roles/pauses, API-key scopes, audit history and revocations made before the checkpoint. Provider credentials decrypt only with the matching original key; an interrupted refresh remains fenced. A synthetic gateway/verifier fails if inference or enrollment is attempted. No provider transports or background provider workers are constructed. Backups/diagnostics are owner-only and removed after the test; credentials and SQL contents are never printed.

The fixture is small and quiesced. Its dump/restore timings are diagnostic measurements, not a production RTO or throughput claim. Live-load backup consistency, large-dataset performance, actual cluster storage/ingress, secret-manager recovery, post-checkpoint revocations and PITR need separate operational acceptance.

Unknown request charges and [operator-attested reconciliation](usage-reconciliation.md) are included in MySQL backups. Restoring an expired active lease or unknown stream never refunds it. Pending charges remain available beyond ordinary resolved-detail retention; confirmed corrections preserve original member/group/pool/day/run attribution and their audit history. Provider quota percentages cannot prove per-request usage. Reconcile only from request-specific evidence, after reviewing the exact request/account, rather than clearing the ledger during recovery.

## Rolling upgrade and rollback

This procedure combines behavior documented in the [Helm guide](../helm/teamcode/README.md), [groups](groups.md) and [team roles](team-roles.md) with conservative operator rules (marked as such). It has not been exercised against a real cluster by this repository; the mixed-version window is an operator rule, not an accepted scenario.

### Before upgrading

1. Take a recovery checkpoint as described above and record the running image tag (pin `image.tag` to an exact version such as `0.1.0` rather than following `latest`), the chart version and which Secret holds the `encryption-key` (a reference, not the value).
2. Read the release notes for migrations. Published migrations are additive and are never edited; Flyway applies new ones when the first upgraded pod starts and serializes concurrent starters.
3. Keep the same `encryption-key` Secret. There is no key rotation or re-encryption procedure, so a different key makes stored provider credentials unreadable.

### Rolling out

The chart rolls with `maxUnavailable: 0`, a pod disruption budget keeping one replica and a 660-second termination period so accepted streams can drain; streams cannot move between pods and are never replayed. During the rollout the old and new versions run against the same database. Operator rule: treat that window as supported only for features that the older version ignores safely:

- Enforcement features introduced by a release (for example group caps, ownership restrictions or new run limits) are enforced only by upgraded replicas. Do not rely on them, and do not enable or configure them, until every replica runs the new version.
- Do not perform administration that depends on a new feature (creating groups, caps or overrides) while old replicas are still serving traffic.
- A first upgrade from a release without ownership promotes one existing enabled administrator to owner and revokes that account's sessions and service keys (see [team roles](team-roles.md)). Re-issue automation keys of that account afterwards.
- Sign-in throttling moved from one team-wide budget to per-address budgets and per-device backoff ([team roles](team-roles.md#sign-in-throttling)); older replicas keep the former budget until they are replaced.
- Wait until `/ready` is healthy on all replicas and no old pod remains before declaring the upgrade complete.

### Rolling back

`helm rollback` or pinning the previous image tag restores the previous application code only. It does not reverse database migrations, so keep the additive schema. Before rolling back, read the release notes of every release since the target for migrations that change existing columns, and test the previous image against a restored copy of the database; only additive changes are expected to be tolerated. Older replicas do not enforce newer policies (they may see owners as administrators and ignore group snapshots), so a rollback is not equivalent authorization behavior; re-check ownership, group and run policy after rolling back and before restoring traffic.

If the schema is incompatible with the previous release, do not edit it by hand. Restore the pre-upgrade checkpoint into an isolated database as described above, with the original encryption key, and accept the loss of writes made after that checkpoint (including revocations; revoke them again).

Operator rule: never run two TeamCode instances that refresh the same subscription accounts at once.
