# Artifacts

An artifact is a page an agent publishes for people to read: an HTML (or Markdown) page with its images, CSS, JavaScript and data files. Agents publish from a local `teamcode run` or from a cloud session through a stdio MCP server, `teamcode mcp artifacts`; people read them behind the TeamCode login at `<server>/artifact/<id>`. Files are stored in an S3-compatible bucket that an administrator connects on the dashboard; versions, sharing and quotas are in MySQL.

This page is the contract and the record of what is implemented. It builds on [audit](audit.md), [service credentials](service-keys.md), [stateless replicas](replicas.md), the [MCP gateway](mcp-gateway.md) and [cloud sessions](cloud-sessions.md).

## Status

| Part | State |
| --- | --- |
| Contract (this page); tables and migrations `V20261022150000*` for the whole feature | Implemented (PR 1) |
| `ArtifactObjectStore` port and its S3 adapter: SigV4 written in-house on the Ktor client, PutObject, GetObject with Range, HeadObject, ListObjectsV2, DeleteObjects; DNS check of the endpoint | Implemented (PR 1) |
| [Storage and settings](#storage-and-settings): admin API, typed client, **Dashboard → Settings → Artifacts**, connection test, sealed secret, [availability](#when-artifacts-are-active), audit | Implemented (PR 1) |
| Upload and publication API, versions, optimistic concurrency, quotas, secret scan, sharing, deletion with proof, retention and garbage collection, `artifacts:*` scopes, CLI `teamcode artifacts` | Planned (PR 2) |
| Serving under `/artifact/<id>/<ticket>/<path>`, view tickets, CSP sandbox on every response, the viewer and the gallery on the dashboard, public links (`artifacts.v1`) | Planned (PR 3) |
| `teamcode mcp artifacts`, the loopback relay of `teamcode run`, injection into Claude Code and Codex, `GET /api/v1/artifacts/policy` (`artifact-relay-v1`) | Planned (PR 4) |
| Cloud sessions: `artifacts:write` on the execution credential, injection by the runner, session transfer | Planned (PR 5) |
| Usage per member on the dashboard, filters by origin, links from session and run pages, operations guide | Planned (PR 6) |

Nothing is advertised yet: `artifacts.v1` appears in `GET /api/v1/capabilities` only once artifacts can be published and served (PR 3), as [a planned command must not be advertised as implemented](architecture.md).

## When artifacts are active

Artifacts work only while all three hold: a bucket is stored, its current version passed the connection test, and an administrator turned them on. `GET /api/v1/artifacts/settings` reports `active` and, otherwise, `inactiveReason`:

| `inactiveReason` | Meaning | Fix |
| --- | --- | --- |
| `artifact_storage_missing` | No bucket is configured | Settings → Artifacts → Storage |
| `artifact_storage_unverified` | The bucket changed since its last passed test, or its last test failed | Test the connection |
| `artifacts_disabled` | The bucket works but the switch is off | Turn artifacts on |

Turning them on is refused with `409 artifact_storage_missing` without a bucket and `409 artifact_storage_unverified` without a passed test. Saving the bucket again (even a key rotation) or a failed test makes them inactive until a test passes; removing the bucket turns the switch off. While artifacts are inactive, as each part lands: the publication policy answers `canPublish: false` with the reason (PR 4), `teamcode run` and the runner do not inject `teamcode mcp artifacts`, the CLI says the storage is not configured, `/artifact/*` answers `404`, and the dashboard shows the Artifacts menu only to administrators, pointing to Settings → Artifacts.

## Storage and settings

Everything is configured on **Dashboard → Settings → Artifacts** (`/dashboard/artifacts/settings`) or through the API below, by interactive administrators only, and stored in MySQL like the Kubernetes clusters and the GitHub App: there is no environment variable and no Helm value for any of it. Artifacts do not depend on cloud sessions being on.

| Route | Purpose |
| --- | --- |
| `GET/PUT /api/v1/artifacts/storage` | Read; save (`{version, endpoint, region, bucket, prefix, pathStyle, accessKeyId, secretAccessKey?, serverSideEncryption}`) |
| `DELETE /api/v1/artifacts/storage` | Remove the bucket and its secret; turns artifacts off; refused while artifacts exist |
| `POST /api/v1/artifacts/storage/test` | The connection test |
| `GET/PUT /api/v1/artifacts/settings` | Read (with `active`, `inactiveReason`); save every field with the `version` it replaces |

The typed client has `artifactStorage`, `saveArtifactStorage`, `deleteArtifactStorage`, `testArtifactStorage`, `artifactSettings` and `saveArtifactSettings`. Service keys never reach these routes, including an administrator's `admin:cloud` key.

| Field | Rule |
| --- | --- |
| Endpoint | `https://host[:port]`, no path, credentials, query or fragment. Link-local, unspecified and cloud metadata hosts are refused in every numeric spelling (`artifact_storage_host_not_allowed`); private and loopback addresses are allowed for MinIO. The host is resolved again before every request and refused if any answer is such an address. |
| Region | `[a-z0-9-]`: `auto` for R2, `gra`/`sbg` for OVH, `us-east-1` for MinIO. |
| Bucket | 3–63 lowercase letters, digits, dots and dashes. TeamCode never creates a bucket. |
| Prefix | Optional, up to 8 `[a-z0-9._-]` segments separated by `/`, no `.` or `..` segment. |
| Path-style | For MinIO and some OVH endpoints; virtual-hosted (`<bucket>.<host>`) otherwise. |
| Access key id, secret | The secret is sealed with the server's credential key (AES-256-GCM, context `artifact-storage:<teamId>`) and never returned or shown again: an empty field keeps it. Changing the endpoint or the bucket requires a new secret (`artifact_storage_secret_required`). |
| Server-side encryption | `none` or `AES256` (`x-amz-server-side-encryption` on every write; R2 ignores it). |

A save names the `version` it replaces (`409 artifact_storage_conflict` otherwise, 0 for the first save). Once any artifact exists, the endpoint, bucket and prefix cannot change (`409 artifact_storage_in_use`) and the storage cannot be removed; the keys can still be rotated. Moving artifacts to another bucket is not supported yet.

**Connection test.** Writes `<prefix>/<team>/.teamcode-probe-<uuid>`, reads it back and compares its SHA-256, lists the prefix, deletes it and checks it is gone. Each step reports its HTTP status and the S3 error code (`AccessDenied`, `NoSuchBucket`, `SignatureDoesNotMatch`, ...) or a short local code (`unreachable`, `timeout`, `dns`, `host_not_allowed`, `digest_mismatch`, `not_listed`, `not_deleted`), never a response body. The key needs `s3:PutObject`, `s3:GetObject`, `s3:ListBucket` and `s3:DeleteObject` on the prefix and nothing else.

**Settings.**

| Setting | Default | Range |
| --- | --- | --- |
| `enabled` | off | needs a tested bucket |
| `whoCanPublish` | `everyone` | `everyone`, `admins` |
| `publicSharing` | `owners_of_artifacts` | `owners_of_artifacts`, `admins`, `off` (every public link stops at once) |
| `quotaMemberBytes`, `quotaTeamBytes` | 2 GiB, 50 GiB | 1 MiB–100 TiB |
| `quotaArtifactsPerMember` | 500 | 1–100000 |
| `quotaPublishesPerHour` | 60 per member | 1–10000 |
| `personalRetentionDays`, `sharedRetentionDays` | none (kept) | 1–3650 days after the last publication |
| `maxVersionsPerArtifact` | 50 | 1–1000; older versions are pruned and their blobs collected |

A retention change applies to artifacts published afterwards, as for paused cloud sessions.

## Model and bucket layout

| Table | Content |
| --- | --- |
| `artifact_storage` | One row per team: endpoint, region, bucket, prefix, path-style, access key id, sealed secret, server-side encryption, `verified_at`, version |
| `artifact_settings` | The settings above, versioned |
| `artifacts` | Owner (the member it was published for), creating member and credential, origin (`local_run`, `cloud_session` or `api`, with run, session and epoch), title, description, icon, access (`personal`, `team`, `public`), public version policy, current version, state (`draft`, `active`, `deleted`), stored bytes, `generation` (compare-and-set), deletion and storage-deletion times |
| `artifact_members` | Members invited to a personal artifact: `viewer` or `editor`, reason `invited` or `session_transfer`, who added them |
| `artifact_versions` | Immutable, unique on `(artifact, number)`: entry page, canonical manifest digest, file count and bytes, creator, credential, base version, session and epoch |
| `artifact_version_files` | `(version, path) → sha256, size, content type, role` |
| `artifact_blobs` | The blobs in an artifact's folder, for byte counts and garbage collection |
| `artifact_uploads`, `artifact_upload_chunks`, `artifact_upload_blobs` | Uploads in progress, bound to their credential, in chunks of at most 1 MiB, expired after an hour; MySQL is the only bus between replicas |

```text
<prefix>/<team>/artifacts/<artifact>/blobs/<sha256>        content, immutable
<prefix>/<team>/artifacts/<artifact>/versions/<n>.json     copy of the manifest
```

One folder per artifact, content-addressed inside it: a republication sends only changed files, a version costs a manifest, deleting an artifact is deleting one prefix (listed again afterwards as proof), and no blob is shared between artifacts.

## Publication (planned, PR 2 and 4)

Agents call the stdio MCP server `teamcode mcp artifacts`, which `teamcode run` (Claude Code and Codex) and the cloud runner add next to the gateway's servers. It runs as the agent (in a cloud session, the agent's UID in the agent's container), reads the files itself and sends them through the launcher's loopback proxy with the local token only; the proxy adds the member's device credential locally, or the execution's `tcex_` credential in the cloud. It has no TeamCode credential of its own. Outside `teamcode run` it uses the member's stored CLI login (`origin_kind = api`); in a cloud container, which holds neither, it refuses to start without the proxy.

| Tool | Arguments | Result |
| --- | --- | --- |
| `publish` | `file_path` (the page: `.html`, `.htm` or `.md`); `files` `{published path → local path, or null to remove}`; `url`/`artifact_id` to update; `base_version`; `title`, `description`, `icon`; `access` (`personal` or `team`, never `public`) | `{id, url, version, access, filesUploaded, filesReused, warnings}`; on a conflict the live version is written to the scratch directory for the agent to merge |
| `share` | the artifact; `member` (id, username or linked GitHub login); `role` (`viewer` or `editor`); `remove` | The invited members. The owner and invited editors may invite; never changes the access level |
| `list` | `scope` (`mine`, `team`, `session`), `limit` ≤ 50, `before` | `{id, title, url, version, access, ownerId, updatedAt}` |
| `read` | the artifact, `version?` | The manifest, with the files written to the scratch directory, not into the answer; another member's artifact is marked untrusted data |

An agent never makes an artifact public and never deletes one: a person does, on the dashboard or with `teamcode artifacts access … public` and `delete --yes`. The returned URL is always `<server>/artifact/<id>`.

Upload API (also used by the CLI): `POST /api/v1/artifacts/uploads {artifactId?, files:[{sha256,size}]} → {uploadId, artifactId, missing}`; `PUT /api/v1/artifacts/uploads/{id}/blobs/{sha256}/{n}` in chunks of at most 1 MiB (under the chart's 2 MiB ingress limit), assembled at the last chunk, hash checked, sent to the bucket; `POST /api/v1/artifacts/{id}/versions {uploadId, entry, files, baseVersion, title?, description?, icon?, access?}` → the version, or `409 artifact_conflict` with the live manifest. A commit replayed within 10 minutes (same credential, artifact, base version and manifest) returns the same version.

Path rules: relative to the root (the working directory, and a scratch directory), or absolute inside one; no `..`, no NUL; every component opened with `O_NOFOLLOW` (`FILE_FLAG_OPEN_REPARSE_POINT` on Windows, reparse points refused); regular files only; `.git/`, `.ssh/`, `.aws/`, `.teamcode/`, `.env*`, `*.pem`, `id_rsa*`, `id_ed25519*`, `.npmrc`, `.netrc`, `.claude/.credentials.json`, `.codex/auth.json` and, in a cloud session, `/workspace/.home` are refused by name. Published paths follow the [skill catalog](skills.md) rules. Limits, checked again by the server: 15 MiB per binary file, 16 MiB per page or text file, 255 files and 64 MiB per publication, 511 files and 256 MiB per version, 120 seconds for the whole.

The server scans every text file with the transcript mask's detectors and refuses a version holding a secret (`422 artifact_secret_detected`, with the path and kind, never the value).

**Session transfer.** An artifact belongs to the member who created it; a session is only its origin. When a cloud session changes owner, the recipient is added as an `editor` (reason `session_transfer`) of every artifact created or updated in that session, in the transaction that accepts the transfer, so its agent can read and republish them. Publications of the new epoch belong to the recipient.

## Serving and access (planned, PR 3)

`<server>/artifact/<id>[?v=n]` is the only URL anyone sees or shares, whatever the access level: the viewer page (title, author, version, sharing, and the content in an iframe). Everything below it, `<server>/artifact/<id>/<ticket>/<path>`, is content: served on the same host, never on another one, authorized by the signed view ticket in the path only (the session cookie is ignored there), with this header on every response and every status (200, 206, 304, 404, 416, 500 and framework errors), set by one interceptor for the whole prefix:

```text
Content-Security-Policy: sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox;
  default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:
    https://cdnjs.cloudflare.com https://cdn.jsdelivr.net/npm/ https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com;
  style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com;
  img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self';
  form-action 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Permissions-Policy: camera=(), microphone=(), geolocation=(), usb=(), payment=()
```

The sandbox gives the content an opaque origin even when opened directly: no cookie, no storage, no access to the dashboard's session. The dashboard also refuses cookie requests with `Sec-Fetch-Dest: iframe` outside the viewer and sends `frame-ancestors 'none'` on its other pages.

| Access | Who reads | Who changes content | Who changes access or deletes |
| --- | --- | --- | --- |
| `personal` (default) | The owner, invited members, administrators (audited `artifact.admin_viewed`) | The owner and invited editors | The owner, administrators |
| `team` | Every active member | The owner and invited editors | The owner, administrators |
| `public` | Anyone with the link | The owner and invited editors | Making public: the owner, interactively (as `publicSharing` allows); going back: the owner or an administrator |

## Audit

Never any content. Implemented: `artifacts.storage_saved`, `artifacts.storage_tested` (subject `passed` or `failed`), `artifacts.storage_deleted`, `artifacts.settings_updated`, `artifacts.public_sharing_changed` (subject `old->new`). Planned: `artifact.created`, `.version_published`, `.access_changed`, `.member_added`, `.member_removed`, `.deleted`, `.storage_deleted`, `.retention_expired`, `.publish_refused`, `.admin_viewed`.

## Evidence

PR 1: `SigV4Test` (the four worked examples of the S3 SigV4 documentation, RFC 3986 encoding), `S3ObjectStoreTest` (mock engine: signed virtual-hosted and path-style requests recomputed from what was sent, encryption header, range, missing objects, error code kept without the body, link-local and metadata answers refused after DNS before any request, pagination, DeleteObjects body escaped with its Content-MD5, a listing with a DOCTYPE refused), `S3GatewayTest` (a real S3-compatible server, Versity S3 Gateway in Testcontainers: put, get, single range and 416, head, encoded keys, paginated listing, deletion leaving the prefix empty, `SignatureDoesNotMatch` and `NoSuchBucket` codes, and the dashboard's connection test end to end), `ArtifactAdministrationTest` (who may act, endpoint, bucket, prefix and region rules, settings bounds, the test's steps, failures and recording, availability), `ArtifactStoragePersistenceTest` (MySQL 8.4, two pools: the reviewed migrations match every artifact table, sealed secret bound to the team, versions across replicas, rotation, secret required to move, verification cleared by a save or a failed test, `artifact_storage_in_use`, enabling refused without a tested bucket, removal turning artifacts off, audit), `ArtifactSettingsRoutesTest` (API and dashboard for administrators only, the secret never returned or shown, test and switch, form origin check), `ApiDocsTest`.

MinIO no longer publishes server images, so the S3-compatible test server is the Versity S3 Gateway; real AWS, OVH and R2 buckets are not part of CI.
