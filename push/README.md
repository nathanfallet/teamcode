# TeamCode Push relay

The relay sends the push notifications of the published TeamCode apps. Their Firebase project, and the APNs key behind it, belong to the relay alone. A TeamCode server that chose the relay (Settings → Push notifications) signs each request and sends an encrypted blob. The relay sees an instance, an FCM token and that blob: never a member, a session or the text. The protocol, `push.v1`, is described in [docs/notifications.md](../docs/notifications.md#relay-protocol-pushv1) and its wire models are the `PushRelay` models of the `api` module.

It is a Kotlin/Native server (`linuxX64`), with no JVM and no database. It depends on `api` only. FCM is reached through [flareon](https://github.com/nathanfallet/flareon), with the credentials of the Cloud Run service account read from the metadata server, so there is no key file.

## Choices

- **No registry.** An instance's id is its Ed25519 public key (raw, base64url). `POST /v1/instances` checks the key and answers it as the id. Any replica can check any signature, and a restart or a redeploy forgets nothing. The alternatives:
  - A Firestore registry adds a database, a second credential path and a read per push, for no extra guarantee: registration is open by design, since every self-hosted server registers on its own.
  - Signed tickets would still need the public key at each request.
- **Manual cut-off.** List an instance id in `TEAMCODE_PUSH_BLOCKED_INSTANCES` and redeploy: its requests get `403` and the server stops retrying.
- **Quotas.** Per minute, there are three:
  - per instance (`TEAMCODE_PUSH_LIMIT_INSTANCE`, default 600);
  - per token (`TEAMCODE_PUSH_LIMIT_TOKEN`, default 20);
  - for the whole relay (`TEAMCODE_PUSH_LIMIT_TOTAL`, default 6000).

  A request counts only when all three accept it; a refusal is `429` with `Retry-After`, and the server retries. With the replay guard (a signed request is sent once), they live in memory: the service runs one instance (`maxScale: 1`), far more than the apps need.
- **No "token belongs to instance" check.** One phone signed in to two TeamCode servers sends the same FCM token from both, so binding a token to the first instance that used it would break that case. The per-token quota bounds what another instance can do with a token it learnt. The blob is encrypted for the device, so such a sender can only make the generic "New activity in TeamCode" alert appear, at most 20 times a minute.
- **Nothing is logged** about a request: not its token, its payload or its instance. Errors are short codes, and an FCM answer is reduced to `sent`, `unregistered` (404), `retry` (401, 408, 429, 5xx, unreachable) or `rejected`.

## Build and check

```sh
./gradlew :push:jvmTest :push:macosArm64Test           # the tests, on the JVM (CI) and natively
./gradlew :push:linkReleaseExecutableLinuxX64          # the binary that is deployed
docker build --platform linux/amd64 -f push/Dockerfile -t teamcode-push:dev push
python3 scripts/smoke-push-relay.py teamcode-push:dev  # the real binary: protocol, signatures, limits
```

## Deploy (Cloud Run)

Not run by any pipeline: [`cloudrun.yaml`](cloudrun.yaml) is the service, and an operator deploys it. To set it up:

1. In the Firebase project of the published apps, create a service account `teamcode-push` with the **Firebase Cloud Messaging API Admin** role only, and upload the APNs authentication key in the Firebase console (Cloud Messaging → Apple app configuration).
2. Pick the image: CI publishes `docker.io/nathanfallet/teamcode-push` with the server and MCP images (`latest` and `sha-<commit>` from `main`, the version from tags; amd64 only). Cloud Run pulls it through an Artifact Registry remote repository of Docker Hub, a cache that holds nothing of its own: `gcloud artifacts repositories create dockerhub --repository-format=docker --location REGION --mode=remote-repository --remote-docker-repo=DOCKER-HUB`. Pointed at `docker.io` directly, Cloud Run fails with "Container import failed". Prefer a `sha-` or version tag to `latest`, so a redeploy is a deliberate change.
3. Replace the placeholders in `cloudrun.yaml`, then run `gcloud run services replace push/cloudrun.yaml --region REGION` and allow unauthenticated invocations (the relay checks its own signatures).
4. Map the domain: `gcloud beta run domain-mappings create --service teamcode-push --domain push.teamcode.nathanfallet.me`, then add the CNAME it gives to `ghs.googlehosted.com` in Cloudflare, DNS only (not proxied).

Domain mappings exist only in some Cloud Run regions; the published relay runs in `europe-west1` (project `teamcode-companion`). The gen2 environment needs at least 512 MiB of memory. Cloud Run's front end keeps paths ending in `z` for itself: `/healthz` answers the startup and liveness probes inside the service, but returns Google's 404 from outside. To check a deployed relay from outside, use `POST /v1/instances`: a malformed key gets the relay's 400.
