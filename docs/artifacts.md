# Artifacts

An artifact is a page an agent publishes for people to read: an HTML (or Markdown) page with its images, CSS, JavaScript and data files. Agents publish from a local `teamcode run` or from a cloud session through a stdio MCP server, `teamcode mcp artifacts`; people read them behind the TeamCode login at `<server>/artifact/<id>`. Files are stored in an S3-compatible bucket that an administrator connects on the dashboard; versions, sharing and quotas are in MySQL.

This page is the contract and the record of what is implemented. It builds on [audit](audit.md), [service credentials](service-keys.md), [stateless replicas](replicas.md), the [MCP gateway](mcp-gateway.md) and [cloud sessions](cloud-sessions.md).

## Status

| Part | State |
| --- | --- |
| Contract (this page); tables and migrations `V20261022150000*` for the whole feature | Implemented (PR 1) |
| `ArtifactObjectStore` port and its S3 adapter: SigV4 written in-house on the Ktor client, PutObject, GetObject with Range, HeadObject, ListObjectsV2, DeleteObjects; DNS check of the endpoint | Implemented (PR 1) |
| [Storage and settings](#storage-and-settings): admin API, typed client, **Dashboard → Settings → Artifacts**, connection test, sealed secret, [availability](#when-artifacts-are-active), audit | Implemented (PR 1) |
| [Upload and publication API](#publication-api), versions, optimistic concurrency, quotas, secret scan, sharing, deletion with proof, retention and garbage collection, `artifacts:*` scopes, [CLI `teamcode artifacts`](#cli) with the shared file reader; migrations `V20261022180000*` | Implemented (PR 2) |
| [Serving](#serving-and-access) under `/artifact/<id>/<ticket>/<path>`, view tickets, CSP sandbox on every response, the viewer and the gallery on the dashboard, public links (`artifacts.v1`) | Implemented (PR 3) |
| `teamcode mcp artifacts`, the loopback relay of `teamcode run`, injection into Claude Code and Codex, `GET /api/v1/artifacts/policy` (`artifact-relay-v1`) | Planned (PR 4) |
| Cloud sessions: `artifacts:write` on the execution credential, injection by the runner, session transfer | Planned (PR 5) |
| Usage per member on the dashboard, filters by origin, links from session and run pages, operations guide | Planned (PR 6) |

`artifacts.v1` appears in `GET /api/v1/capabilities` since PR 3, when artifacts can be published, read and served; the agent parts (PR 4 and 5) will advertise their own capabilities, as [a planned command must not be advertised as implemented](architecture.md).

## When artifacts are active

Artifacts work only while all three hold: a bucket is stored, its current version passed the connection test, and an administrator turned them on. `GET /api/v1/artifacts/settings` reports `active` and, otherwise, `inactiveReason`:

| `inactiveReason` | Meaning | Fix |
| --- | --- | --- |
| `artifact_storage_missing` | No bucket is configured | Settings → Artifacts → Storage |
| `artifact_storage_unverified` | The bucket changed since its last passed test, or its last test failed | Test the connection |
| `artifacts_disabled` | The bucket works but the switch is off | Turn artifacts on |

Turning them on is refused with `409 artifact_storage_missing` without a bucket and `409 artifact_storage_unverified` without a passed test. Saving the bucket again (even a key rotation) or a failed test makes them inactive until a test passes; removing the bucket turns the switch off. While artifacts are inactive, every publication, read and sharing route of PR 2 answers `409` with the same reason as its error type (deletion still works, so an artifact can always be removed); as each later part lands: the publication policy answers `canPublish: false` with the reason (PR 4), `teamcode run` and the runner do not inject `teamcode mcp artifacts`, the CLI says the storage is not configured, `/artifact/*` answers `404`, and the dashboard shows the Artifacts menu only to administrators, pointing to Settings → Artifacts.

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
| Endpoint | `https://host[:port]`, no path, credentials, query or fragment. Link-local, unspecified and cloud metadata hosts are refused in every numeric spelling (`artifact_storage_host_not_allowed`); private and loopback addresses are allowed for MinIO. The host each request goes to (`<bucket>.<host>` when virtual-hosted) is resolved again before every request and refused if any answer is such an address; as for clusters, the address the client dials afterwards is not pinned. |
| Region | `[a-z0-9-]`: `auto` for R2, `gra`/`sbg` for OVH, `us-east-1` for MinIO. |
| Bucket | 3–63 lowercase letters, digits, dots and dashes. TeamCode never creates a bucket. |
| Prefix | Optional, up to 8 `[a-z0-9._-]` segments separated by `/`, no `.` or `..` segment. |
| Path-style | For MinIO and some OVH endpoints; virtual-hosted (`<bucket>.<host>`) otherwise. |
| Access key id, secret | The secret is sealed with the server's credential key (AES-256-GCM, context `artifact-storage:<teamId>`) and never returned or shown again: an empty field keeps it. Changing the endpoint or the bucket requires a new secret (`artifact_storage_secret_required`). |
| Server-side encryption | `none` or `AES256` (`x-amz-server-side-encryption` on every write; R2 ignores it). |

A save names the `version` it replaces (`409 artifact_storage_conflict` otherwise, 0 for the first save). Once any artifact may have files in the bucket (any artifact except a deleted one whose folder was proven empty), the endpoint, bucket and prefix cannot change (`409 artifact_storage_in_use`) and the storage cannot be removed; the keys can still be rotated. Moving artifacts to another bucket is not supported yet.

**Connection test.** Writes `<prefix>/<team>/.teamcode-probe-<uuid>`, reads it back and compares its SHA-256, lists the prefix, deletes it and checks it is gone. Each step reports its HTTP status and the S3 error code (`AccessDenied`, `NoSuchBucket`, `SignatureDoesNotMatch`, ...) or a short local code (`unreachable`, `timeout`, `dns`, `host_not_allowed`, `digest_mismatch`, `not_listed`, `not_deleted`), never a response body. The whole test is bounded to 80 seconds and runs once at a time (`409 artifact_storage_test_running`); a write that got no answer is deleted anyway. The result is recorded only for the storage version that was tested: if the storage was saved meanwhile, the test answers `ok: false` with a last step `record` / `storage_changed` and records nothing. The key needs `s3:PutObject`, `s3:GetObject`, `s3:ListBucket` and `s3:DeleteObject` on the prefix and nothing else.

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

## Publication

### Agents (planned, PR 4)

Agents call the stdio MCP server `teamcode mcp artifacts`, which `teamcode run` (Claude Code and Codex) and the cloud runner add next to the gateway's servers. It runs as the agent (in a cloud session, the agent's UID in the agent's container), reads the files itself with the [same reader as the CLI](#file-reader) and sends them through the launcher's loopback proxy with the local token only; the proxy adds the member's device credential locally, or the execution's `tcex_` credential in the cloud. It has no TeamCode credential of its own. Outside `teamcode run` it uses the member's stored CLI login (the CLI's `config.json`, readable by its owner only, see [devices](devices.md); `origin_kind = api`); in a cloud container, which holds neither, it refuses to start without the proxy.

| Tool | Arguments | Result |
| --- | --- | --- |
| `publish` | `file_path` (the page: `.html`, `.htm` or `.md`); `files` `{published path → local path, or null to remove}`; `url`/`artifact_id` to update; `base_version`; `title`, `description`, `icon`; `access` (`personal` or `team`, never `public`) | `{id, url, version, access, filesUploaded, filesReused, warnings}`; on a conflict the live version is written to the scratch directory for the agent to merge |
| `share` | the artifact; `member` (id, username or linked GitHub login); `role` (`viewer` or `editor`); `remove` | The invited members. The owner and invited editors may invite; never changes the access level |
| `list` | `scope` (`mine`, `team`, `session`), `limit` ≤ 50, `before` | `{id, title, url, version, access, ownerId, updatedAt}` |
| `read` | the artifact, `version?` | The manifest, with the files written to the scratch directory, not into the answer; another member's artifact is marked untrusted data |

An agent never makes an artifact public and never deletes one: a person does, on the dashboard or with `teamcode artifacts access … public` and `delete --yes`. The returned URL is always `<server>/artifact/<id>`.

### Publication API

Implemented in PR 2. Every route needs a member credential; the [scopes](#scopes) say which keys reach which route.

| Route | Purpose |
| --- | --- |
| `POST /api/v1/artifacts/uploads` `{artifactId?, files:[{sha256,size}]}` | Announce the distinct files of the next version → `201 {uploadId, artifactId, missing, chunkBytes, expiresAt}`. Without `artifactId` a draft artifact is created; `quotaArtifactsPerMember` counts published artifacts and is checked here and at the first commit. A member holds at most 16 open uploads and 20 unpublished drafts (`429 artifact_uploads_busy`). `missing` lists what the artifact's folder does not hold yet |
| `PUT /api/v1/artifacts/uploads/{upload}/blobs/{sha256}/{n}` | One chunk (`application/octet-stream`, at most 1 MiB, under the chart's 2 MiB ingress limit) → `{complete, received, total}`. Chunks may be sent in any order and in parallel (they are counted under a lock on the upload) and wait in MySQL, the only bus between replicas; the last one assembles the file, checks its size and SHA-256 (`422 artifact_blob_mismatch`; the client sends the file again), scans it, and sends it to the bucket |
| `POST /api/v1/artifacts/{id}/versions` `{uploadId, entry, files:{path:{sha256,size}}, baseVersion, title?, description?, icon?, access?}` | Commit → `201 {artifact, version, replayed:false}`; the same commit within 10 minutes (same credential, base version and manifest digest) answers `200` with the same version and `replayed:true` |
| `GET /api/v1/artifacts?scope=&member=&session=&run=&limit=&before=` | `mine`, `shared` (invited), `team` (everything the caller reads, default) or `all` (administrators), most recently changed first, at most 50, `nextBefore` to continue |
| `GET /api/v1/artifacts/{id}`, `GET …/versions`, `GET …/versions/{n}` | The artifact (with the caller's `relation`: `owner`, `editor`, `viewer`, `team` or `admin`), its kept versions, one version's manifest |
| `GET …/versions/{n}/file?path=` | One file, as `application/octet-stream` with `Content-Disposition: attachment` and `X-TeamCode-Sha256`: the API never serves a page as HTML on the server's origin (that is the [content prefix](#serving-and-access)) |
| `PUT /api/v1/artifacts/{id}/access` `{access, publicFollowsLatest, publicVersion?}` | Change the access level; interactive only. Either `publicFollowsLatest: true` without a version, or `false` with `publicVersion` (`400 invalid_artifact_access` otherwise) |
| `GET/POST /api/v1/artifacts/{id}/members`, `DELETE …/members/{member}` | Who is invited; invite `{member, role}` (a member id, else a username, else a linked GitHub login, in that order; a login linked to several members is refused; `viewer` or `editor`); remove |
| `DELETE /api/v1/artifacts/{id}` | Delete; interactive only. `204` |

**Commit.** Under the team's lock and a row lock on the artifact, so that replicas agree:

- `baseVersion` must be the live version (0 for a draft): otherwise `409 artifact_conflict`, whose body `{error, current}` carries the live manifest for the publisher to merge.
- Every file must be either sent by this upload or already in the artifact's folder (`409 artifact_blob_missing` otherwise). The entry is an `.html`, `.htm` or `.md` file of the manifest.
- Content types come from a fixed list by extension; anything else is `application/octet-stream`. `capabilities` is refused (`400 artifact_capabilities_unsupported`) until serving exists, and so is `access: public` (`403 artifact_public_interactive`).
- `whoCanPublish: admins` refuses members (`403 artifact_publish_forbidden`); only the owner and invited editors publish (`403 artifact_not_writable`); `quotaPublishesPerHour` is counted from the audit log, so pruned versions still count (`429 artifact_rate_limited`, `Retry-After`); `quotaMemberBytes` and `quotaTeamBytes` are checked on what the folder would hold (`409 artifact_quota_exceeded`).
- Versions beyond `maxVersionsPerArtifact` are pruned, never the version a public link is pinned to; blobs no kept version uses are marked for collection.
- `expires_at` is set from the publication time and the retention that applies (`personalRetentionDays` or `sharedRetentionDays`), and when the access level changes, from the time of the change (so a change never expires an artifact at once). A retention change therefore applies to artifacts published afterwards.
- Blobs the upload stored that the version does not use are marked for collection, so they never sit uncounted in the folder.
- A copy of the manifest is written to `versions/<n>.json` next to the blobs, best effort.

**Secret scan.** Every file that decodes as UTF-8 without a NUL byte is a text file and is scanned at assembly with the detectors of the transcript mask (provider API keys, GitHub, TeamCode, AWS and Slack tokens, JWTs, private keys, credentials in URLs; not the name-based assignment pattern, which pages legitimately contain). `Bearer`, `Basic` and `Token` values count only on the same line and only when they look like a credential: for `Basic`, base64 of a `user:password` pair; for `Bearer` and `Token`, at least 12 characters with letters and digits, at least 20 with both cases, or at least 16 digits, that are neither a path, nor an identifier made of lowercase words and numbers, nor a camel-case name. Prose such as `Basic cross-browser support`, `Basic input/output` or `token refresh_interval`, a path after `Bearer` and a Markdown heading underlined below `Token` are not refused. The [transcript mask](cloud-conversations.md) uses the same credential shapes; it keeps its own, wider rule for authorization values. The patterns are bounded and anchored so a crafted file is scanned in linear time. A hit refuses the file before it reaches the bucket: `422 artifact_secret_detected` with `{error, sha256, kind}`, never the value, and an audit event `artifact.publish_refused`. A refused file (a secret, or a file too large) ends its upload at once, and a new artifact's draft that no other upload uses goes with it, so retries never use up the open uploads and drafts a member may hold; only a size or hash mismatch is sent again in the same upload. Other files are binary and limited to 15 MiB (`413 artifact_file_too_large`); text files to 16 MiB.

**Who may do what.**

| Action | Who |
| --- | --- |
| Read a personal artifact | The owner, invited members, administrators (each version read of another member's artifact audited `artifact.admin_viewed`). Others get `404`, as if it did not exist |
| Read a team or public artifact | Every active member |
| Publish a version, invite or remove members | The owner and invited editors |
| Change access, delete | The owner or an administrator, interactively. Making public follows `publicSharing`: `owners_of_artifacts` the owner, `admins` an administrator, `off` nobody (`403 artifact_public_sharing_off`) |

A version published by an invited editor stays the owner's artifact and counts in the owner's quota.

**Deletion and garbage collection.** A worker (`artifacts/maintain`, every 10 minutes, one replica at a time) does all of it, each step and each team on its own: a bucket that fails is retried at the next run, behind the work that succeeds.

- An upload expires after an hour; blobs it sent that no version uses are marked for collection, unless another live upload announced them.
- A draft (never committed) older than 24 hours is deleted.
- An artifact past its `expires_at` is deleted (`artifact.retention_expired`, actor `system`).
- A blob stored in the bucket whose upload expired or was removed before it could be recorded is marked for collection too, unless the artifact holds it or another live upload announced it.
- Marked blobs are deleted from the bucket, then forgotten. While an artifact has marked blobs, a new upload to it answers `409 artifact_busy` (retry after 60 seconds), so a blob is never collected under a publication that counts on it.
- A deleted artifact's folder is deleted 5 minutes after the deletion, past any upload still in flight: the prefix is listed and deleted, then listed again; only an empty listing records `storage_deleted_at`, removes the artifact's rows and audits `artifact.storage_deleted`. Otherwise it is retried at the next pass. Until then the storage cannot be moved.

### Scopes

| Scope | Reaches |
| --- | --- |
| `artifacts:read` | List and read artifacts, versions, files and members |
| `artifacts:write` | `artifacts:read`, plus uploads, commits, invitations and removals |

`team:read` also reads every route of `artifacts:read`. No key changes an access level or deletes an artifact, whatever its scopes: those two routes need an interactive sign-in. The cloud execution credential gets `artifacts:write` in PR 5.

### File reader

One reader in the CLI (`ArtifactSources`) reads every file to publish, for `teamcode artifacts publish` now and for `teamcode mcp artifacts` in PR 4. It runs as the user (or agent) who launched it and reads only below its roots (the working directory, and a scratch directory for the MCP server):

- A relative path resolves against the first root; an absolute path must lie inside one. `..`, an empty path and NUL bytes are refused.
- Every component below the root is opened without following links: `openat` with `O_NOFOLLOW` component by component on macOS and Linux (`O_DIRECTORY` for directories, then `fstat` on the leaf), `FILE_FLAG_OPEN_REPARSE_POINT` on Windows with reparse points refused and every handle kept open without delete sharing, `NOFOLLOW_LINKS` on the JVM. A link refuses the file even when it points inside the root.
- Regular files only, at most 16 MiB read.
- Refused by name: `.git/`, `.ssh/`, `.aws/`, `.teamcode/`, `.gnupg/`, `.env*`, `*.pem`, `*.key`, `id_rsa*`, `id_ed25519*`, `id_ecdsa*`, `.npmrc`, `.netrc`, `.pypirc`, `.claude/.credentials.json`, `.codex/auth.json` and, in a cloud session, `/workspace/.home`.
- A refusal names the path and the rule, never the content.

Published paths follow the [skill catalog](skills.md) rules (up to 8 segments of letters, digits, `.`, `_`, `+` and `-`, no hidden or Windows-reserved segment). Limits, checked again by the server: 15 MiB per binary file, 16 MiB per page or text file, 255 files and 64 MiB per publication, 511 files and 256 MiB per version.

### CLI

| Command | Does |
| --- | --- |
| `teamcode artifacts list [--scope mine\|shared\|team\|all] [--member] [--session] [--run] [--limit] [--json]` | List |
| `teamcode artifacts show <id or URL> [--versions] [--json]` | Details, files of the current version, invited members |
| `teamcode artifacts publish <page> [--file PATH or PUBLISHED=PATH]… [--remove PUBLISHED]… [--artifact <id or URL>] [--base-version N] [--title] [--description] [--icon] [--access personal\|team] [--json]` | Publish a new artifact, or a new version of one: the base version's other files are kept without being sent again, and only files the server lacks are sent. The page is published under its file name and each `--file PATH` at its path relative to the page's directory, so the page's relative links keep working (a file outside that directory needs `PUBLISHED=PATH`). A new artifact's title defaults to the page's `<title>` or first `# ` heading; a new version keeps the current title unless `--title` is given. A conflict tells which version is live |
| `teamcode artifacts pull <id or URL> [--version N] --out DIR` | Download a version into an empty or new directory, each file checked against its SHA-256 and created, never overwritten |
| `teamcode artifacts access <id or URL> personal\|team\|public [--pin-version N]` | Change the access level (interactive sign-in) |
| `teamcode artifacts share <id or URL> <member> [--role viewer\|editor] [--remove]` | Invite or remove a member |
| `teamcode artifacts delete <id or URL> --yes` | Delete (interactive sign-in) |

The CLI uses the login stored by `teamcode login` in its `config.json`, like every other command. The typed client has `artifacts`, `artifact`, `artifactVersions`, `artifactVersion`, `artifactFile`, `startArtifactUpload`, `putArtifactChunk`, `commitArtifactVersion` (throwing `ArtifactConflictException` with the live version), `setArtifactAccess`, `artifactMembers`, `shareArtifact`, `unshareArtifact` and `deleteArtifact`.

**Session transfer.** An artifact belongs to the member who created it; a session is only its origin. When a cloud session changes owner, the recipient is added as an `editor` (reason `session_transfer`) of every artifact created or updated in that session, in the transaction that accepts the transfer, so its agent can read and republish them. Publications of the new epoch belong to the recipient.

## Serving and access

`<server>/artifact/<id>[?v=n]` is the only URL anyone sees or shares, whatever the access level: the viewer page (title, author, version, sharing, and the content in an iframe). Everything below it, `<server>/artifact/<id>/<ticket>/<path>`, is content, served by the server itself and authorized by the signed view ticket in the path only (the session cookie is ignored there), with this header on every response and every status (200, 206, 304, 404, 416, 500 and framework errors), set by one interceptor for the whole prefix:

```text
Content-Security-Policy: sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox;
  default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:
    https://cdnjs.cloudflare.com https://cdn.jsdelivr.net/npm/ https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com;
  style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com;
  img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self';
  form-action 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Permissions-Policy: camera=(), microphone=(), geolocation=(), usb=(), payment=(), display-capture=()
Access-Control-Allow-Origin: *
X-Robots-Tag: noindex, nofollow
```

The sandbox gives the content an opaque origin even when opened directly: no cookie, no storage, no access to the dashboard's session. Its relative `fetch` calls therefore come from the opaque origin `null`, which `Access-Control-Allow-Origin: *` lets read files of the same version; access is in the path's ticket, never in a cookie, so this opens nothing else. The dashboard and the viewer refuse requests with `Sec-Fetch-Dest: iframe` and send `frame-ancestors 'none'`; the viewer's own policy is `default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`. Responses under the content prefix other than a file (errors, redirects) are `Cache-Control: no-store, no-transform`.

**Residual risk.** A page can still open WebRTC connections (`RTCPeerConnection` is not covered by `connect-src` or `Permissions-Policy` in current browsers), and it can open popups that escape the sandbox, which agents' pages need for ordinary links. Neither carries the viewer's session: the content never has a cookie or the dashboard's origin. A public artifact is published content that is not verified; its viewer says so to anyone who is not a member.

**Viewer.** `GET /artifact/<id>[?v=n]` reads with the dashboard session, whose cookie is set for both `/dashboard` (`HttpOnly`, `SameSite=Strict`) and `/artifact` (`HttpOnly`, `SameSite=Lax`, so a link opened from another site finds the member signed in; that path only answers `GET`, forms post to `/dashboard`). A member who may read the artifact sees its title, author, version and date, a newer-version notice while following the latest (polled every 10 seconds while the page is visible, never reloading the frame), panels for versions, files, sharing (access, the version a public link shows, invited members) and deletion (the exact title to confirm), as their rights allow. Anyone else gets the public link's version, if public, with a banner that it is not verified and without the author; otherwise a visitor who is not signed in is sent to `/dashboard/login?next=/artifact/<id>`, and a member who may not read it gets `404`, so whether an artifact exists is never revealed. Signing in continues only to an `/artifact/<id>[?v=n]` path; a member already signed in on the dashboard gets the `/artifact` cookie from the same session, without signing in again.

**Tickets.** The viewer mints a ticket for one version, sealed so the artifact's page, which can read its own URL, learns nothing of the viewer: `<ciphertext>.<mac>` in base64url. The payload `<version>:<member>:<login session>:<expiry>` is authenticated by an HMAC-SHA256 over the artifact id and the payload, then encrypted with AES-CTR under the MAC's first 16 bytes as a synthetic IV, so the same ticket always gives the same URL. Both keys are derived by HKDF-SHA256 from `TEAMCODE_ENCRYPTION_KEY` (labels `teamcode artifact-view` and `teamcode artifact-view encryption`), so the key that seals secrets is never used as is. A member's ticket lasts 8 hours, a public link's 1 hour. Each replica checks a ticket against MySQL at most every 30 seconds (10,000 tickets cached): the login session alive and interactive, the member enabled, access still granted, artifacts still active, and for an anonymous ticket the artifact still public, public sharing on, and the version still the one its link serves. Signing out, removing a member, turning artifacts off or making the artifact personal stops its content within 30 seconds. Expiries are rounded down to 10 minutes, so reloading the viewer reuses the same content URLs for a while. Content is `Cache-Control: private, max-age=30, no-transform`, no longer than the recheck, with `ETag` the file's SHA-256 (`-page` for an entry page) and `304`; one byte range is served as `206` (even when it covers the whole file, as media players expect), several or an unsatisfiable one as `416` with `Content-Range: bytes */<size>`. A viewer left open past its ticket's lifetime needs a reload.

**Entry page.** An HTML entry page with its own doctype (after whitespace and comments) is sent byte for byte; one without gets the skeleton agents expect (doctype, viewport, a small reset, and `utf-8` when the page is UTF-8). A Markdown entry page is rendered on the server into escaped HTML (headings, paragraphs, lists, quotes, code, emphasis, and links to `http`, `https`, `mailto` or relative targets); a Markdown file that is not the entry page is served as `text/plain`. Other files are served with the type recorded at publication.

**Gallery.** **Dashboard → Artifacts** lists what the member can read, theirs, those shared with them, and for administrators every artifact of the team, newest first, with access, author, version, origin and date. While artifacts are inactive, `/artifact/*` answers `404` to everyone, the menu shows only to administrators and leads them to Settings → Artifacts, and members get `404` on the gallery.

| Access | Who reads | Who changes content | Who changes access or deletes |
| --- | --- | --- | --- |
| `personal` (default) | The owner, invited members, administrators (audited `artifact.admin_viewed`) | The owner and invited editors | The owner, administrators |
| `team` | Every active member | The owner and invited editors | The owner, administrators |
| `public` | Anyone with the link | The owner and invited editors | Making public: the owner, interactively (as `publicSharing` allows); going back: the owner or an administrator |

**Tickets for apps.** `POST /api/v1/artifacts/{id}/tickets` with `{version?}` (the current one when omitted) answers `{artifactId, version, contentUrl, expiresAt}`: `contentUrl` is `<server>/artifact/<id>/<ticket>/`, the same member ticket the viewer page mints (8 hours, rounded down to 10 minutes, checked again against MySQL at most every 30 seconds, ended by signing out), for the [companion app](companion.md) to show in its own web view. Interactive logins only (no service key: the route is not listed for keys, and the service refuses them with `403 credential_scope`); anything the member may not read, a missing version and artifacts turned off are `404`. An administrator's ticket for a personal artifact is audited `artifact.admin_viewed`, as the viewer page is, through the same version read. At most 60 tickets per login session and minute on each replica (`429 artifact_ticket_rate_limited` with `Retry-After`). Advertised as `artifacts.tickets.v1`. On a phone or tablet (`iPhone`, `iPad` or `Android` in the user agent), the viewer page shows members an **Open in app** link, `teamcode://open?url=<the viewer URL>`, which the app resolves to its own ticket. No query is added: the route makes the same reads as the viewer page.

## Audit

Never any content. Storage and settings (PR 1): `artifacts.storage_saved`, `artifacts.storage_tested` (subject `passed` or `failed`), `artifacts.storage_deleted`, `artifacts.settings_updated`, `artifacts.public_sharing_changed` (subject `old->new`). Artifacts (PR 2), subject the artifact id unless said otherwise, target its owner: `artifact.created`, `artifact.version_published` (`<id>/<n>`), `artifact.access_changed` (`<id>:old->new`), `artifact.member_added` (`<id>:<role>:invited`), `artifact.member_removed`, `artifact.deleted`, `artifact.publish_refused` (`<id>:<code>`, never the content), `artifact.admin_viewed` (`<id>/<n>`), and with the actor `system`: `artifact.retention_expired` and `artifact.storage_deleted` (written only once the folder was listed empty).

## Evidence

PR 1: `SigV4Test` (the four worked examples of the S3 SigV4 documentation, RFC 3986 encoding), `S3ObjectStoreTest` (mock engine: signed virtual-hosted and path-style requests recomputed from what was sent, encryption header, range, missing objects, error code kept without the body, link-local and metadata answers refused after DNS before any request, pagination, DeleteObjects body escaped with its Content-MD5, a listing with a DOCTYPE refused), `S3GatewayTest` (a real S3-compatible server, Versity S3 Gateway in Testcontainers: put, get, single range and 416, head, encoded keys, paginated listing, deletion leaving the prefix empty, `SignatureDoesNotMatch` and `NoSuchBucket` codes, and the dashboard's connection test end to end), `ArtifactAdministrationTest` (who may act, endpoint, bucket, prefix and region rules, settings bounds, the test's steps, failures and recording, availability), `ArtifactStoragePersistenceTest` (MySQL 8.4, two pools: the reviewed migrations match every artifact table, sealed secret bound to the team, versions across replicas, rotation, secret required to move, verification cleared by a save or a failed test, `artifact_storage_in_use`, enabling refused without a tested bucket, removal turning artifacts off, audit), `ArtifactSettingsRoutesTest` (API and dashboard for administrators only, the secret never returned or shown, test and switch, form origin check), `ApiDocsTest`.

PR 2: `ArtifactRulesTest` (chunking, announcements, manifests and paths, digest, text detection and the secret kinds, details), `ServiceKeyAuthorizationTest` (`artifacts:read` nested in `artifacts:write` and `team:read`, neither reaching access or deletion), `ArtifactPublicationPersistenceTest` (MySQL 8.4, two pools, an in-memory bucket: two replicas committing on the same base where exactly one wins and the other gets the live manifest, replays, a blob trusted only from this upload or the folder, chunks out of order and duplicated, size and hash mismatches, a secret refused before the bucket, sharing and the 404 of a personal artifact, admin reads audited, access and `publicSharing`, `whoCanPublish`, byte quotas and the hourly limit, pruning that keeps the pinned public version, `artifact_busy` while blobs are collected, expired uploads, drafts, retention, deletion proven by an empty listing and retried otherwise), `ArtifactRoutesTest` (the HTTP contract, keys by scope, access and deletion refused to every key before reaching the store, files as attachments, inactive storage), `ApiDocsTest`; in the CLI `ArtifactRulesCliTest` (names refused, published paths, titles, references), `ArtifactSourcesTest` (native, real system calls: a link as the file, as a directory and inside the root, `..`, absolute paths outside, missing files, directories, credentials by name, refused prefixes, the 16 MiB bound) and `ArtifactPublisherTest` (only missing files sent, in 1 MiB chunks, base files kept and removed).

PR 3: `ArtifactViewingTest` (tickets bound to their artifact and key, forged, expired; the HKDF derivation against an independently computed value; byte ranges), `ArtifactViewerRoutesTest` (the content policy and companion headers on 200, 206, 304, 404, 416, 500 and a framework error; a ticket that is forged, of another artifact, expired, or whose grant was revoked, stopping within the 30-second recheck; the dashboard never served under the content prefix; `Sec-Fetch-Dest: iframe` refused; sign-in redirects, `next` limited to the viewer, both cookie paths; public links for visitors with the banner and without the author; `404` while inactive and the menu only for administrators, the gallery sending them to the settings; the forms with their origin check and the delete confirmation; `artifacts.v1`; Markdown escaped), `ArtifactPublicationPersistenceTest` (MySQL 8.4: a ticket's login session, access, public pin, public sharing off, availability, sign-out, a disabled member and deletion).

MinIO no longer publishes server images, so the S3-compatible test server is the Versity S3 Gateway; real AWS, OVH and R2 buckets are not part of CI.
