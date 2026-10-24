# TeamCode on Kubernetes

This chart follows the structure of the [pkg chart](https://github.com/guimauvedigital/pkg/tree/main/helm/pkg), adapted for TeamCode. It deploys two stateless server replicas and, optionally, a single persistent MySQL 8.4 instance. No Redis, sticky sessions, provider credential files or shared application volumes are required. Kubernetes 1.25+ and a default storage class are required for the bundled database.

## Install

Ordinary main-branch image publication runs after Linux JVM/database and Helm checks succeed. Version tags additionally require the full five-target native and signed installation acceptance; paid native jobs are skipped on ordinary pushes. Pushes to `main` publish to Docker Hub using the repository secret `DOCKER_HUB_PASSWORD` for the `nathanfallet` login; pull requests build without publishing. No registry token belongs in chart values.

The chart defaults to `nathanfallet/teamcode:latest` with `imagePullPolicy: Always`. The CI workflow builds and publishes this image for AMD64 and ARM64 after all verification jobs pass. New pods resolve the latest published image; publishing alone does not restart existing pods. Roll out the Deployment to pick up a new build. An immutable `sha-<commit>` tag can also be selected through `image.tag`. Configure an image pull secret if your Docker Hub repository is private.

Provision a Kubernetes Secret named `teamcode-secrets` in the target namespace using your secret manager. Required string values:

| Key | Value |
| --- | --- |
| `mysql-username` | Dedicated database user, for example `teamcode` (not root) |
| `mysql-password` | Strong database password |
| `encryption-key` | Base64 encoding of exactly 32 cryptographically random bytes, generated once |
| `mysql-root-password` | Independent root password, required only for bundled MySQL |

An encryption key can be generated with `openssl rand -base64 32`. Keep it in your secret manager and backups; never regenerate it on rollout. Helm does not generate secrets, embed them in release values or rotate them. Existing MySQL volumes do not change database passwords when Secret values change: perform database credential rotation explicitly.

Create a values file with your public origin and TLS ingress:

```yaml
publicUrl: https://teamcode.example.org
existingSecret: teamcode-secrets
image:
  tag: sha-REPLACE_WITH_COMMIT
replicaCount: 3
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: teamcode.example.org
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: teamcode-tls
      hosts: [teamcode.example.org]
```

```sh
helm upgrade --install teamcode ./helm/teamcode \
  --namespace teamcode --create-namespace -f values.yaml --wait --timeout 10m
helm test teamcode --namespace teamcode
```

Versioned GitHub releases include the chart package, with chart version and application version matching the unprefixed tag. Install the `0.1.0` archive without a source checkout:

```sh
helm upgrade --install teamcode \
  https://github.com/nathanfallet/teamcode/releases/download/0.1.0/teamcode-0.1.0.tgz \
  --namespace teamcode --create-namespace -f values.yaml --wait --timeout 10m
```

Users supply their own namespace, Secret, ingress and database settings; no operator cluster deployment is performed by the publisher. The chart continues to default to `latest`; set `image.tag: "0.1.0"` to pin the matching released server image.

The chart does not issue TLS certificates. Supply the TLS secret using your existing certificate management. For ingress controllers other than NGINX, configure equivalent streaming settings: no response buffering, 2 MiB request bodies, and at least 660-second read/send timeouts. Keep the public origin identical on every replica, without a trailing slash.

Behind a proxy or CDN such as Cloudflare, every TeamCode response (dashboard HTML and API JSON) carries `Cache-Control: no-store, no-transform`, so the proxy does not cache or rewrite it; the server sets this itself and no proxy rule is needed. Leave it unchanged: without `no-transform`, Cloudflare's Email Address Obfuscation replaces e-mail addresses in the dashboard with `[email protected]`.

The creating pod logs the initial `admin` temporary password exactly once. Find it with the command printed by Helm, sign in at `/dashboard`, and replace it. Administrators create team members; there is no public registration.

The chart carries no cloud, GitHub or Kubernetes setting, and none belongs in `extraEnv`, with one exception: `cloudSessions.networkPolicy`, the NetworkPolicy the server applies to every cloud session namespace. Sessions keep full internet access; only cluster-internal and metadata endpoints are blocked (no ingress; no pod, service, node, private or link-local address, cloud metadata or Kubernetes API server; DNS and the TeamCode server stay reachable). It is an operator setting on purpose, so no dashboard administrator can change it; a stricter `lockdown` exists, off by default, and [session network policy](../../docs/cloud-sessions.md#session-network-policy) lists the cases that need a value (a server reached through the cluster's own load balancer, private git hosts or registries). The session clusters' service accounts need `create` and `patch` on `networkpolicies` in each session namespace. Cloud sessions (on or off, runner image, Kubernetes clusters) and the team's GitHub App are configured by an administrator in the dashboard's **Settings** section and stored in the database; see [cloud sessions deployment](../../docs/cloud-sessions.md#deployment). The former `TEAMCODE_CLOUD_*` variables, if still set, only seed a database that stores no cloud runtime yet.

For local access without ingress, use `publicUrl: http://localhost:8080` and:

```sh
kubectl --namespace teamcode port-forward service/teamcode 8080:8080
```

## External database and availability

The bundled MySQL StatefulSet is a convenience, **not a highly available database**. A NetworkPolicy lets only this release's TeamCode server pods reach it, on 3306 (`mysql.networkPolicy.enabled: false` removes it). **Upgrading:** on a CNI that enforces NetworkPolicy, a backup job, exporter or any other pod that connects to the bundled MySQL directly stops reaching it; disable the policy or route it through the server pods' labels. For production database failover and managed backups, use an external MySQL 8.4 service:

```yaml
mysql:
  enabled: false
  jdbcUrl: jdbc:mysql://mysql.example.internal:3306/teamcode?sslMode=VERIFY_IDENTITY
```

The same Secret supplies its user/password. Configure trust material through `volumes`, `volumeMounts` and JVM options if the database certificate uses a private CA. Each application replica opens at most 16 database connections (`TEAMCODE_DATABASE_POOL_SIZE`, 2 to 64); size database connection limits (MySQL defaults to 151) for HPA maximum replicas plus rolling-update surge and migrations.

The database is authoritative for sessions, account credentials, refresh intent, allowance reservations, model policies, quotas, guidance, worker leases, login throttling and gateway counters. A dead pod does not erase spend or free an active reservation until its lease expires. In-flight streams cannot migrate to another pod and are never automatically replayed. See [replica behavior](../../docs/replicas.md).

Liveness uses `/health`; readiness uses `/ready`, which checks MySQL. Startup waits for Flyway and team bootstrap. During database outages, pods stay alive but leave the ready service endpoints. The chart permits 660 seconds for termination, including a 10-second endpoint-removal delay and a 600-second server quiet period (`TEAMCODE_SHUTDOWN_GRACE_MS`). Idle pods can also take that long to terminate; local development defaults to a one-second quiet period. It sets rolling `maxUnavailable: 0` and a PDB keeping one replica available. Node failures can still interrupt streams. Use `affinity` or `topologySpreadConstraints` to spread replicas across failure domains.

HPA is optional. CPU/memory utilization is an infrastructure signal, separate from provider quota pacing. Enabling it does not create more provider capacity. Database worker leases prevent HPA from multiplying provider quota polling. `replicaCount` is ignored when HPA is enabled. Set PDB values consistently if deliberately running only one replica.

## Backups and upgrades

Back up MySQL **and the exact encryption key**. Test restoration to an isolated installation before relying on backups. Provider refresh ownership must remain exclusive: do not activate a restored copy while the original still refreshes the same accounts.

The [recovery guide and automated restoration acceptance](../../docs/operations.md) describe a quiesced logical checkpoint, credential/access reconciliation and current limits. The acceptance test restores into a new MySQL instance and checks full schema/row equality, encrypted credentials, management HTTP routes and quota/authentication continuity; it does not validate your cluster or backup system.

Application pods have no persistent filesystem state. The MySQL StatefulSet's PVC is retained on uninstall by Kubernetes' default retention behavior; it is not a backup. Do not delete it to fix an application rollout.

Flyway serializes concurrent migrations. Review schema compatibility before rolling application versions or using Helm rollback; Helm does not reverse database migrations. Rotate encryption keys only with an explicit credential re-encryption procedure, which is not implemented yet. Updating an external Secret does not restart pods automatically: roll the Deployment after intentional configuration changes.

## Validation

```sh
python3 scripts/check-helm.py
helm template teamcode ./helm/teamcode --set publicUrl=https://teamcode.example.org
```

CI lints/renders bundled and external database configurations, validates Kubernetes schemas and builds the server image. `scripts/smoke-replicas.py` exercises two real JVM servers against an isolated MySQL without contacting providers. Helm rendering and two-process tests do not claim that your Kubernetes cluster, storage class, ingress or backup system has been tested.
