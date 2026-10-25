# MCP stdio host (`mcp`)

`mcp` is the server-side component that runs team MCP servers published as **stdio** packages (npm, PyPI) for the [MCP gateway](mcp-gateway.md). The two pages cover different things:

- [MCP gateway](mcp-gateway.md): the agent-facing endpoint `/mcp/<server>`, authorization per member and per call, tool policies, limits and the journal. Agents only ever talk to the gateway, for every kind of server.
- This page: the internal host behind the gateway for stdio servers. It starts one process per key, speaks stdio JSON-RPC with it, and answers the gateway over an internal HTTP protocol. Agents never reach it, and nothing runs on a member's workstation or in a session pod.

It is the Gradle module `:mcp` and the image `nathanfallet/teamcode-mcp`, published with the same tags as the server image.

## Status

| Part | State |
| --- | --- |
| Host: supervisor, stdio transport, host protocol v1, limits, scrubbing, drain on SIGTERM | Done (`:mcp`) |
| Image `nathanfallet/teamcode-mcp` (amd64, arm64), its contract test with real npm and PyPI servers | Done (image job of `build.yml`) |
| Variant model `transport: stdio` (launcher, package, arguments, secret variables) | Done ([variants](variants.md#hosted-mcp-commands)) |
| Helm chart component `mcp` (Deployment, Service, NetworkPolicy, generated token Secret), on by default | Done ([deployment](#deployment-helm)) |
| Gateway → host wiring (`HostMcpUpstream`, warm-up from `/api/v1/mcp/servers`, `retain` sweep), capability `mcp.gateway.stdio.v1` | Done ([gateway](mcp-gateway.md#hosted-stdio-servers)) |
| State in the dashboard, team events `mcp.stdio.*`, companion and CLI views | Planned |

The server calls the host when it has its address and token (`TEAMCODE_MCP_URL`, `TEAMCODE_MCP_TOKEN`, set by the chart): administrators can then declare hosted commands, and agents list and call them through the gateway like any MCP server. [The gateway page](mcp-gateway.md#hosted-stdio-servers) describes the server side: process keys, deadlines, warm-up, the retain sweep and how host errors reach the agent.

## Architecture

The server runs several stateless replicas; a stdio server is a stateful process (one `initialize`, one session on stdin and stdout). The host is its single owner:

- One host (a Deployment with one replica) owns every process. Any server replica reaches it through the Service with the host token.
- Each request carries everything the host needs: the key, the definition (launcher, package, arguments, variables), the secrets and the operation. The host keeps nothing on disk beyond per-key caches, so a restart only loses a cache: the next operation starts the process again.
- One process per key: per team server for a `SHARED` variant, per member for a `PER_MEMBER` one. The server computes the key (SHA-256 of team, variant and version, plus member and assignment time for `PER_MEMBER`), so a secret rotation yields a new key and a new process.
- `initialize` runs once per process; concurrent operations are multiplexed on its stdin by JSON-RPC id.
- The host sees no database, no encryption key and no Kubernetes token. The gateway keeps every authorization decision.

## Host protocol v1

Internal HTTP on port 8090, released with the server at the same version. Every `/v1/…` request carries `Authorization: Bearer <TEAMCODE_MCP_TOKEN>` (compared in constant time; refused with 401 `unauthorized`) and `TeamCode-Host-Protocol: 1` (another value or none: 400 `unsupported_protocol`, with a message asking to deploy both at the same version). Bodies are JSON, at most 2 MiB (413).

| Method and path | Body | Answer |
| --- | --- | --- |
| `POST /v1/ops` | `{key, spec, op: "list" \| "call", tool?, arguments?, deadlineMs, maxResponseBytes}` | 200 `{result \| error, cold, state}` |
| `POST /v1/warm` | `{key, spec}` | 202 `{key, state}`; starts the process without waiting |
| `POST /v1/retain` | `{keys: […]}` | 200 `{stopped}`; stops every process whose key is not listed and removes its directory, except one started less than a minute ago (an assignment newer than the caller's read) |
| `POST /v1/stop` | `{key}` | 200 `{stopped}`; the administrator's "Restart": stops the process and lifts a backoff |
| `GET /v1/status` | | 200: version, launchers, and per key the state, pid, counters, last failure, cold start time, memory and the last 64 KiB of scrubbed stderr |
| `GET /health` | | 200 once the JVM serves (no token) |
| `GET /ready` | | 200 when not draining and the launcher commands (`npx` and `node`, `uvx`) are present, 503 otherwise (no token) |

`spec` is `{launcher, package, args, env, secrets, declaredAt}`: `launcher` is `npm` or `pypi`, `package` the exactly pinned package, `args` the arguments after it, `env` the non-secret variables, `secrets` the secret variables, and `declaredAt` the variant's creation time in epoch milliseconds. Secrets travel only in this request and live only in the process environment; `${NAME}` in an argument is replaced by the secret `NAME` at launch.

`result` is `{"tools": […]}` for `list` (every page, at most 16 pages and 256 tools, cached by the host until the server sends `notifications/tools/list_changed`; the gateway still applies the variant's tool policy each time) and the server's `CallToolResult` for `call`. Every secret value of the process is scrubbed from both, keys included. `cold` says whether the operation waited for a process start. `state` is `idle`, `starting`, `ready`, `failed` (the last start failed) or `backoff`.

Error codes:

| Code | Meaning |
| --- | --- |
| `start_failed` | The process did not start: launcher missing, package not found (for example a version newer than the declaration date), early exit, no `tools` capability, unsupported protocol version, or no `initialize` answer within 45 s. Its stderr is kept for `/v1/status` |
| `crashed` | The process ended during the operation (exit, memory limit, stdin stall, invalid output, revocation) |
| `timeout` | No answer within `deadlineMs`. The host sends `notifications/cancelled` and keeps the process. If the deadline expires during a start, the start goes on for the next operation |
| `too_large` | The answer exceeds `maxResponseBytes` |
| `busy` | `maxInFlightPerProcess` requests were already running for the whole deadline |
| `backoff` | More than 3 failures in 10 minutes: no start for 10 minutes, or until `/v1/stop` or a new definition |
| `capacity` | `maxProcesses` processes run and none is idle |
| `protocol` | The process answered `tools/list` or `tools/call` with something that is not MCP |
| `tool_error` | The process answered `tools/call` with a JSON-RPC error (scrubbed message) |
| `invalid_spec` | The definition breaks a host rule (below); the server validates the same rules when an administrator declares the variant |
| `bad_request` (400) | Malformed request, key that is not 64 lowercase hexadecimal characters, unknown `op`, out-of-range deadline or size |
| `shutting_down` (503) | The host is stopping |

A closed HTTP connection during `/v1/ops` is a cancellation: the gateway closes it when the agent disconnects, and the host sends `notifications/cancelled` to the process. A `tools/call` is never replayed by the host.

## Launching a process

No shell is involved: the host builds the argv and passes it to `execve` through `ProcessBuilder`, under `tini` (PID 1 of the image, which reaps orphans) and `prlimit`.

| Launcher | Command |
| --- | --- |
| `npm` | `prlimit --nproc=1024 --nofile=1024 -- npx --yes -- @scope/name@1.2.3 args…` |
| `pypi` | `prlimit --nproc=1024 --nofile=1024 -- uvx --exclude-newer <declaredAt> [--default-index <index>] name==1.2.3 args…` |

`npx` runs the package's default binary and `uvx` the command named after the package; a server whose command has another name needs a later `bin` field.

The environment is built from scratch: `PATH` (fixed, `/usr/local/bin:/usr/bin:/bin`), `HOME`, `TMPDIR` and `LANG`, the launcher's own settings, then the variant's variables and secrets. Nothing of the host's own environment passes (its token, `JAVA_*`, the pod's service links). Each key has its own directory `/var/mcp/<key>/` with `home`, `work` (the working directory), `tmp`, and the npm cache or the uv cache, tools and Python, so a package cannot poison another key's cache.

Rules, checked again by the host (`invalid_spec`):

- A launcher of `TEAMCODE_MCP_LAUNCHERS`.
- An exact version: npm `name@1.2.3` or `@scope/name@1.2.3` (semantic version, prerelease and build allowed; no range, tag, path, URL or Git); PyPI `name==1.2.3`, extras allowed (no range, wildcard, marker or URL).
- At most 32 arguments of at most 512 characters, without control characters; `${NAME}` must name a secret.
- Variable names of `A-Z 0-9 _`, never the names the variant model reserves for hosted commands (`PATH`, `HOME`, `LD_*`, `TEAMCODE_*`, proxies, certificate bundles, and the `NPM_CONFIG_`, `UV_`, `PIP_`, `NODE_`, `PYTHON`, `COREPACK_`, `SSL` and `CURL_` prefixes), plus `GLIBC_TUNABLES`, so a variant cannot move the registry, the cut-off date, the install scripts, TLS checks or the loader. The host checks again what the server already refused: its list is a superset of the domain's. Lower-case `npm_config_*` names are refused by the name rule. Values without NUL.
- At most 16 secrets, 16 KiB in total, each at least 8 characters long so scrubbing it never mangles ordinary output.

### Supply chain

- **npm**: `npm_config_before=<declaredAt>` resolves the package and all its transitive dependencies as they were on the declaration day, so a version published afterwards (a hijacked release of a dependency, say) is never taken; `npm_config_ignore_scripts=true` disables install scripts. Both were verified on `npx` (npm 11.6): `cowsay@1.6.0` with `before=2020-01-01` fails with `No matching version found … with a date before`; `cowsay@1.4.0` with `before=2019-06-01` resolves `ansi-regex` 3.0.0 instead of the later 3.0.1; a package whose `postinstall` writes a file does not run it with the setting, and does without it. Both the opt-in `RealPackagesTest` and the image contract test refuse `@modelcontextprotocol/server-everything@2.0.0` declared before its publication.
- **PyPI**: `uvx --exclude-newer <declaredAt>` plays the same role (verified: `cowsay==6.1` with a 2020 date has no solution).
- Neither checks the archive's integrity against a lock: the registry is trusted for the pinned version. `TEAMCODE_MCP_NPM_REGISTRY` and `TEAMCODE_MCP_PYPI_INDEX` point at a mirror.

## The stdio transport

| Subject | Rule |
| --- | --- |
| Framing | One UTF-8 JSON-RPC message per line in both directions, written compactly (never a newline inside) |
| Non-JSON stdout | Ignored and counted (many servers print a banner); more than 100 such lines in a minute restart the process |
| stderr | Read continuously into a 64 KiB in-memory buffer, scrubbed when read, never parsed as an error, never logged or stored |
| `initialize` | Revisions 2025-11-25, 2025-06-18 and 2025-03-26, empty client capabilities, then `notifications/initialized`. A server without the `tools` capability fails to start |
| Server notifications | `tools/list_changed` clears the cached list; `message` and `progress` go to the stderr buffer; others are ignored |
| Server requests | `sampling/createMessage`, `elicitation/create`, `roots/list` and any other request get `-32601` at once, so the server never waits |
| Ids | The host numbers its own requests per process; an answer with an unknown id is dropped |
| Size | A line over 4 MiB is dropped unparsed; the call whose `id` appears at the start or the end of the line fails at once as `too_large` (otherwise it times out); three in 10 minutes restart the process. An answer over the request's `maxResponseBytes` fails as `too_large` |
| Back-pressure | One writer per process with a bounded queue; a write blocked for 5 s means the process stopped reading: it is restarted and the running calls fail |
| Concurrency | At most `maxInFlightPerProcess` (16) requests per process; one start per key, which concurrent operations share |
| Stop | Close stdin, wait 2 s, `SIGTERM` to the process and every descendant, wait 5 s, `SIGKILL`. A failure (memory, stall, invalid output) skips the stdin step |

## Lifecycle

| Moment | Rule |
| --- | --- |
| Start | On the first `/v1/ops` or `/v1/warm` of a key, within 45 s (downloads included) |
| Idle | Stopped after 15 minutes without an operation, and in any case after 24 hours (started again on demand) |
| Capacity | At most `maxProcesses` (16); a new key stops the idle process used the longest ago, never a busy one (`capacity` otherwise) |
| Memory | The resident memory of each process tree is read every 5 s (`/proc`); over `processMemoryMiB` (512) the process is stopped |
| Failures | Exits, memory, stalls, invalid output and failed starts count; more than 3 in 10 minutes put the key in backoff for 10 minutes |
| Revocation | The gateway refuses calls at once; the server's maintenance sends `/v1/retain` with the valid keys, and the others stop |
| SIGTERM | `/ready` fails at once, requests are still answered for 5 s and running ones for up to 75 s (longer than the 60 s call timeout), then every process is stopped |

The process `RLIMIT_NPROC` set by `prlimit` counts every thread of the pod's user, not one process (Linux applies it per real UID). 1024 bounds a fork bomb without starving 16 Node.js servers of about a dozen threads each; the pod's memory limit is the hard bound.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `TEAMCODE_MCP_TOKEN` | required | Shared with the server; at least 32 characters. The chart generates it |
| `TEAMCODE_MCP_PORT` / `TEAMCODE_MCP_HOST` | 8090 / 0.0.0.0 | Listening address |
| `TEAMCODE_MCP_DATA_DIR` | `/var/mcp` | Per-key directories (an `emptyDir` in the chart) |
| `TEAMCODE_MCP_LAUNCHERS` | `npm,pypi` | Allowed launchers |
| `TEAMCODE_MCP_NPM_REGISTRY` / `TEAMCODE_MCP_PYPI_INDEX` | npm's and PyPI's | Mirrors |
| `TEAMCODE_MCP_MAX_PROCESSES` | 16 | Processes at once |
| `TEAMCODE_MCP_MAX_IN_FLIGHT_PER_PROCESS` | 16 | Concurrent requests per process |
| `TEAMCODE_MCP_PROCESS_MEMORY_MIB` | 512 | Memory per process tree |
| `TEAMCODE_MCP_IDLE_MINUTES` | 15 | Idle stop |
| `TEAMCODE_MCP_STDIO_START_TIMEOUT_MS` | 45000 | Start, downloads included |
| `TEAMCODE_MCP_DRAIN_SECONDS` | 75 | Wait for running operations on SIGTERM; the pod's termination grace period must be longer |
| `TEAMCODE_MCP_RETAIN_GRACE_SECONDS` | 60 | A retain sweep spares a process started this recently (0–3600; tests use 0) |
| `TEAMCODE_MCP_PATH` | `/usr/local/bin:/usr/bin:/bin` | The only `PATH` processes see |
| `TEAMCODE_MCP_PRLIMIT` | `prlimit` | `none` runs without it (development machines) |

## Security

- The host has no database access, no encryption key, no Kubernetes token. It receives the secrets of one process per request and keeps them in memory only (the process environment, the scrubber).
- Never logged: arguments after substitution, environment, inputs, outputs, stderr. The host logs one JSON event per line on stdout (`started`, `exited`, `killed`, `oversize`, `backoff`, `start_failed`) with the key's first 12 characters, the launcher and the package.
- Risk accepted by the plan: processes share the pod and its UID, so a malicious pinned package could read its neighbours' environment through `/proc` and send it out. The mitigations are the administrator-only declaration, exact versions frozen at the declaration date, no install scripts, separate caches and the pod's isolation (no database, key or cluster access; the chart's NetworkPolicy). A pod per key is the planned evolution if that is not enough.
- The pod the chart runs: UID 10002, read-only root file system, no capability, `seccompProfile: RuntimeDefault`, no service account token, writable `/var/mcp` and `/tmp` only, ingress from the server pods only, egress to the public Internet only.

## Deployment (Helm)

The chart's `mcp` block (`helm/teamcode/values.yaml`) is on by default: a plain `helm upgrade` deploys the host and generates its token, with nothing to create first. `mcp.enabled: false` renders none of it, and the release is then exactly what it was without the block.

| Object | Name | What it is |
| --- | --- | --- |
| Deployment | `<release>-mcp` | One replica, `nathanfallet/teamcode-mcp` at the server's tag (`mcp.image.tag`, else `image.tag`, else the chart's `appVersion`), rolling with `maxSurge: 1` and `maxUnavailable: 0`, 90 s of termination grace for the 75 s drain. Probes: startup and liveness on `/health`, readiness on `/ready`. No PodDisruptionBudget: with one replica it would block node drains |
| Service | `<release>-mcp` | ClusterIP on 8090; the server reads it as `TEAMCODE_MCP_URL=http://<release>-mcp:8090` |
| ServiceAccount | `<release>-mcp` | No Role or RoleBinding, `automountServiceAccountToken: false` |
| Secret | `<release>-mcp` | Key `token`: 48 random alphanumeric characters, generated at install, mounted by the host and the server as `TEAMCODE_MCP_TOKEN` (optional on the server side, so a server pod still starts while the Secret is missing), never printed |
| NetworkPolicy | `<release>-mcp` | Below |

The pod runs as UID 10002 with a read-only root file system, no capability, no privilege escalation, `seccompProfile: RuntimeDefault`, `enableServiceLinks: false`, and two `emptyDir` volumes: `/var/mcp` (`mcp.dataSizeLimit`, 3Gi: the per-key directories, npm and uv caches, a Python per PyPI server) and `/tmp` (512Mi). Requests: 250m CPU, 512Mi of memory, 1Gi of ephemeral storage; limits: 2Gi of memory, 4Gi of ephemeral storage. The memory limit bounds the JVM and every process tree together, while `processMemoryMiB` only stops one tree: with the [measured](#measurements) trees (about 360 MiB through `npx`, 200 MiB through `uvx`), 2Gi holds about four busy servers, not `maxProcesses` (16) of them, and going over it kills the whole pod. Raise `mcp.resources.limits.memory` (or lower `mcp.maxProcesses`) before declaring more. Its labels (`app.kubernetes.io/name: teamcode-mcp`) never include the server's selector labels, so the server's Service, its PodDisruptionBudget and the bundled MySQL's NetworkPolicy never select it.

**Token.** The chart reads the existing Secret back with Helm's [`lookup`](https://helm.sh/docs/chart_template_guide/function_list/#lookup) on every upgrade and keeps its value; the Secret carries `helm.sh/resource-policy: keep`, so `helm uninstall` (or `mcp.enabled: false`) leaves it in place. `lookup` sees nothing under `helm template` and `helm install|upgrade --dry-run=client`, which therefore show a new value each time; `--dry-run=server` and a real `helm upgrade` read the existing one. A chart rendered outside the cluster (Argo CD, Flux with `helm template`) would generate a new token at every sync: set `mcp.token.existingSecret` (and `mcp.token.key`, `token` by default) to a Secret of your own, of at least 32 characters, instead. To rotate, delete the Secret and run `helm upgrade`: both Deployments carry the annotation `checksum/mcp-token`, so the server and the host roll together with the new value. For an `existingSecret`, the annotation follows its value as `lookup` reads it, so changing it then running `helm upgrade` rolls both too; rendered outside the cluster it cannot see the value, so restart both Deployments after a rotation (`kubectl rollout restart deployment/<release> deployment/<release>-mcp`). A release upgraded with `--reuse-values` from a chart without the block keeps no host; `--set mcp.enabled=true` on top fails the render with a message, because the rest of the block is missing: use `--reset-then-reuse-values` or your values file.

**NetworkPolicy** (`mcp.networkPolicy`, on by default, enforced by CNIs that implement NetworkPolicy):

- ingress: this release's server pods only (the server's selector labels), on TCP 8090;
- egress: DNS (port 53, UDP and TCP), then every public address on every port: `0.0.0.0/0` and `::/0` minus the ranges of the [cloud session policy](cloud-sessions.md#session-network-policy) (RFC 1918, carrier-grade NAT, loopback, link-local and cloud metadata, Azure's WireServer, unique-local, multicast and reserved, plus their NAT64 and 6to4 forms). The database, the cluster's pods, services and nodes, and a private API server are out of reach; a managed cluster whose API endpoint is public needs it in `blockedCidrs` (the pod has no service account token either way);
- `blockedCidrs` adds ranges (an IPv4 range is also blocked in its NAT64 and 6to4 forms; a malformed CIDR fails the render), `extraEgress` appends rules verbatim (an internal npm or PyPI mirror, a proxy), and `lockdown: true` removes the Internet rule, leaving DNS and `extraEgress` only. As for sessions, an extra rule must name its destinations in `to`, and an `ipBlock` that reaches link-local (`169.254.0.0/16`, `fe80::/10`) without excepting it fails the render.

`scripts/check-helm.py` checks the default render (pod security, labels disjoint from the server's, server variables, one generated token shared by the Secret and both checksums, the policy's ranges against `SessionNetworkPolicy.kt`), an existing token kept and a missing one regenerated (the helper fed fixtures, since `lookup` needs a cluster), `mcp.enabled=false`, `existingSecret`, null sub-blocks, the operator's network settings, a release reused from a chart without the block, and the render-time refusals (grace, malformed CIDRs, link-local extra rules); CI validates the default, disabled and `existingSecret` renders with kubeconform.

## Image

`mcp/Dockerfile`, built after `./gradlew :mcp:buildFatJar` with `mcp/` as the context: `eclipse-temurin:21-jre-noble` (the server's JRE line), Node.js 24 checked against the Node.js release keyring like the [session image template](cloud-session-image.md), uv pinned by version and SHA-256, `tini`, `prlimit`, the host jar, UID 10002, a JVM heap capped at 384 MiB (`-Xmx384m`, serial GC, exit on out-of-memory; the children never inherit it). No Python: `uvx` installs one per key. Downloads happen in a separate stage, so the final image has neither `curl` nor `gpgv`.

The image job of `.github/workflows/build.yml` checks it before the server image is pushed: an amd64 build checked by `scripts/smoke-mcp-image.py` and an arm64 build on every run, so a broken host image fails the job before anything is published; then, after the server image, the amd64 and arm64 build is pushed to `nathanfallet/teamcode-mcp` with the server image's tags (`latest` and `sha-COMMIT` on main, `VERSION` and `sha-COMMIT-VERSION` for a release) only where the server image is pushed. The contract test runs the image as the chart will (UID 10002, read-only, no capability), checks the probes, the token and the protocol header, then lists and calls `@modelcontextprotocol/server-everything@2.0.0` through `npx` and lists `mcp-server-fetch==2026.10.24` through `uvx`.

## Operating and troubleshooting

- `GET /v1/status` (token required; the server reads its launchers when an administrator declares a hosted command; the dashboard will show the rest) gives each key's state, last failure, exit code, cold start time, memory and scrubbed stderr.
- `start_failed` with `npm error notarget No matching version found … with a date before` in stderr: the pinned version was published after the variant was declared. Declare a new variant.
- `backoff`: read the stderr in the status, fix the cause, then restart the key (`/v1/stop`).
- `capacity`: raise `maxProcesses` (and the pod's memory limit) or lower `idleMinutes`.
- A host restart (rollout, OOM) loses the processes; the next operation starts them again, and an operation running at that moment fails.

## Measurements

From `RealPackagesTest` (`TEAMCODE_MCP_REAL_PACKAGES=1 ./gradlew :mcp:test --tests '*RealPackagesTest*'`), on a macOS ARM64 development machine on 25/10/2026; the image job prints the same figures on Linux amd64:

| Server | First start (download) | Restart (cached) | Warm call | Resident memory of the tree |
| --- | --- | --- | --- | --- |
| `@modelcontextprotocol/server-everything@2.0.0` (npm) | 5.8 s | 1.4 s | 2 ms (`echo`) | 363 MiB (`npm exec` and the server) |
| `mcp-server-fetch==2026.10.24` (PyPI, Python downloaded by uv) | 23.1 s | 0.6 s | network-bound (`fetch`) | 195 MiB |

The 45 s start timeout covers a first PyPI start with its Python download; the warm-up sent from `/api/v1/mcp/servers` exists so that the agent's first `tools/list` does not wait for it.

## Tests

`./gradlew :mcp:test` runs the host against a Node.js fixture server (`mcp/src/test/resources/stdio-fixture-server.js`) installed as `npx` and `uvx`, so every test goes through the production launch path: `initialize` once and 16 multiplexed calls, paginated listing, the `uvx` argv, `ping` from the server answered, any other server request refused with `-32601`, a stdout banner, `list_changed`, crash and restart then backoff and administrator restart, deadline and connection-close cancellation (`notifications/cancelled` received), `busy`, `too_large` and oversized lines, non-JSON flood, stdin stall, secrets scrubbed from results, errors, stderr, status, events and every log line, an environment holding nothing of the host's, token and protocol refusals, the stop sequence on a server that ignores `SIGTERM` and its grandchild, graceful exit on stdin close, drain then shutdown, idle stop and capacity eviction, memory limit, failed starts and warm-up, launcher resolution on the process `PATH`, an unexpected start error answered with a generic 500, a server that closes its stdin, grandchildren left after the server exits, a memory probe that throws, and a redefinition while the previous definition is still starting. Node.js must be on `PATH` (or `TEAMCODE_MCP_TEST_NODE`).
