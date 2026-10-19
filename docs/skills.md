# Skill catalog (V2, server side)

The skill catalog lets administrators distribute reusable agent skills to team members. This page covers the server: catalog, publication, access decisions, delivery and installation status. The native CLI synchronization is **planned** and not part of this slice; see [What is planned](#what-is-planned).

A skill is instructions and files loaded by an agent. It is not executed by the server and it is not an access-control boundary: a member can copy what they received. Protect tools, credentials and data at the service that owns them (the planned MCP gateway or the backing service).

## Model

| Concept | Rule |
| --- | --- |
| Skill | Team-scoped, named `[a-z0-9]+(-[a-z0-9]+)*`, at most 64 characters. Created by its first publication. |
| Version | Immutable label of 1–64 characters `[0-9A-Za-z][0-9A-Za-z.+_-]*`. Its files and digest never change. Labels carry no ordering: "update" and "rollback" are both explicit pins. |
| Artifact | 1–200 files, at most 5 MiB of content in total, with `SKILL.md` at the root. Stored in MySQL (`MEDIUMBLOB` per file). |
| Pinned version | The single approved version an administrator chose. Publication never pins. A skill without a pin delivers nothing. |
| Access decision | Per member: **yes** (`allow`), **no** (`deny`) or not assigned. Only *yes* delivers. There are no variants. |
| Installation status | Reported per registered CLI device: `installed`, `failed`, `conflict` or `removed`. It never grants or revokes access. |

### Paths and file types

Paths are relative, use `/` separators, have at most 8 segments and 255 characters. Each segment matches `[A-Za-z0-9_][A-Za-z0-9._+-]*`, does not end with a dot and is not a Windows reserved device name (`CON`, `NUL`, `COM1`…). This excludes `..`, `.`, absolute paths, drive letters, backslashes, hidden files and spaces. Paths are unique ignoring case, and a path cannot be both a file and a directory, so the artifact installs identically on macOS, Linux and Windows.

Each file has a mode, `file` or `executable`. The upload format has no link type; a publisher that declares `symlink` is refused with an explicit error. Publish the target file instead.

### Digest

Every version has a canonical digest that clients can recompute from the downloaded files:

```
sha256( for each file sorted by path:  <path> \0 <mode> \0 <size> \0 <sha256 hex of content> \n )
```

rendered as `sha256:<64 lowercase hex>`. The server recomputes it from stored content on every download and refuses to serve a mismatch (`skill_integrity`, 500).

### Access precedence

Today only member decisions exist: an explicit *yes* delivers, an explicit *no* or no decision delivers nothing. Groups arrive with V1 team administration; when they do, `SkillAccessPolicy` combines them without changing the member rule:

1. An explicit member decision wins.
2. Otherwise, any group *no* wins over a group *yes*.
3. Otherwise, a group *yes* delivers.
4. With no decision at all, nothing is delivered.

The rule is implemented and unit-tested now; the store passes no group decisions until groups exist.

## Publishing from CI

Publication goes through the API. It needs either an interactive administrator login or a service key with the dedicated `skills:publish` scope. That scope can only be granted to a key owned by an administrator, and the owner's role is rechecked against shared state on every write. A publishing key cannot pin, assign or read the catalog; an administrator approves what is delivered.

```sh
# Once, from an administrator login: a key that can only publish skills.
teamcode keys create --name skills-ci --scopes skills:publish --days 30 --json
```

```http
POST /api/v1/skills/{name}/versions
Authorization: Bearer tcsk_...
Content-Type: application/json

{
  "version": "1.4.0",
  "files": [
    { "path": "SKILL.md", "mode": "file", "contentBase64": "LS0tCm5hbWU6..." },
    { "path": "scripts/check.sh", "mode": "executable", "contentBase64": "IyEvYmluL3No..." }
  ]
}
```

- `201 Created`: a new immutable version, with its digest.
- `200 OK` with `created: false`: the same version was already published with the same digest. CI retries are safe and append no audit event.
- `409 skill_version_conflict`: the version exists with different content. Publish a new version.
- `409 skill_retired`: the skill was removed from the catalog; restore it first.
- `400` for invalid names, versions, paths, modes or base64; `413` above the size bound (the request body itself is bounded at 8 MiB).
- `403 credential_scope` for a key without `skills:publish`, `403 forbidden` for a member or a non-administrator key.

Limits: 500 skills per team, 200 versions per skill.

## Administration

**Dashboard → Skills** lists the catalog. A skill's page shows its versions with their digest, size and publisher, and lets an administrator:

- **Pin** a version: update, or rollback when pinning an earlier one. The delivered bytes are the immutable ones published under that label.
- **Unpin**: stop delivery without touching versions or decisions.
- **Retire a version**: it can no longer be pinned. The pinned version cannot be retired; pin another one first.
- **Remove from catalog** (retire the skill): unpins, stops delivery and refuses new publications. Versions and decisions remain. **Restore** reopens publication without pinning anything.
- Set each member's decision: *Yes*, *No* or *Not assigned*. Each member row links to the member's delivered skills and per-device installation status.

Members opening **Skills** are redirected to their own delivered skills.

| Operation | Route (administrator) |
| --- | --- |
| Catalog | `GET /api/v1/skills` |
| Versions | `GET /api/v1/skills/{name}/versions` |
| Pin (update/rollback) | `PUT /api/v1/skills/{name}/pin` with `{ "version": "..." }` |
| Unpin | `DELETE /api/v1/skills/{name}/pin` |
| Remove from catalog / restore | `DELETE /api/v1/skills/{name}` / `POST /api/v1/skills/{name}/restore` |
| Retire a version | `DELETE /api/v1/skills/{name}/versions/{version}` |
| Decisions | `GET /api/v1/skills/{name}/assignments`, `PUT /api/v1/skills/{name}/assignments/{memberId}` with `{ "decision": "allow" \| "deny" }`, `DELETE` to clear |

## Delivery to a member

`ResolveEffectiveSkillsUseCase` returns the member's effective skills: non-retired skills with a non-retired pinned version that the access policy allows. The planned CLI synchronization and cloud sessions (resolving their effective member on creation, resume and transfer) call it; the routes below expose it.

| Operation | Route | Credential |
| --- | --- | --- |
| Own manifest | `GET /api/v1/skill-delivery` | Interactive login, or a key with `skills:read` |
| A member's manifest | `GET /api/v1/members/{memberId}/skill-delivery` | Administrator, or the member themself |
| Artifact | `GET /api/v1/skill-delivery/{name}/{version}` | Interactive login, or a key with `skills:read` |
| Report installations | `PUT /api/v1/skill-delivery/installations` | Interactive login bound to a registered CLI device |
| Own installations | `GET /api/v1/skill-delivery/installations` | Interactive login |
| A member's installations | `GET /api/v1/members/{memberId}/skill-installations` | Administrator |

The manifest lists `name`, `version`, `digest`, `sizeBytes` and `fileCount` for each effective skill. The artifact returns each file's `path`, `mode`, `size`, `sha256` and `contentBase64`, plus the digest. Only the currently effective pinned version is served: any other version, or a skill whose decision was removed, returns 404. Authorization is evaluated on every request against shared MySQL state, so a change on one replica applies to the next request on any replica.

Installation reports upsert the status of up to 500 named catalog skills for the device bound to the login session (`409 device_required` otherwise; service keys have no device). Unknown names are refused. Reports are kept when access is removed, as history; a removed device deletes its reports. They are not audited, because they describe workstation state rather than an administrative change.

## Audit

Catalog writes append metadata-only events in the same team-locked transaction: `skill.created`, `skill.version.published` (subject: version ID), `skill.pinned` (subject: version ID), `skill.unpinned`, `skill.retired`, `skill.restored` (subject: skill ID), `skill.version.retired` (subject: version ID), and `skill.access.allowed`, `skill.access.denied`, `skill.access.cleared` (subject: the affected member ID). No file content, path, digest or label is written to the journal. Repeated identical writes (same pin, same decision, idempotent publication) append nothing.

## Persistence and upgrade

Five additive tables: `skills`, `skill_versions`, `skill_files`, `skill_assignments`, `skill_installations`, created by one Flyway migration generated from the Exposed definitions and reviewed (the generator emitted one file per table, each repeating the `skills` DDL; they were merged into a single file). Existing data is untouched. Every replica must run this version before the catalog is used, because older replicas do not serve the new routes.

Administrative writes lock the team row briefly; a version's content insert holds only its skill row lock, so a 5 MiB upload does not delay team-wide admission. Concurrent publications of the same version on two replicas converge on one record (tested). No content is cached in memory between requests.

## What is planned

- **Native CLI synchronization** before `teamcode run`: an isolated, agent-compatible local installation of the effective pinned versions, preserving personal skills and reporting naming conflicts through the installation route. It will come after the current V1 CLI work.
- **Typed SDK methods** in the `client` module for these routes.
- **Group decisions**, using the precedence above.
- **Cloud sessions** resolving the effective member's skills on creation, resume and transfer.
- **Pruning** of old, retired versions; versions are currently kept forever (bounded at 200 per skill).

### Open question: GitHub synchronization

The roadmap leaves open whether the catalog is populated by GitHub synchronization, CI publication through the API, or both. This slice implements **CI publication only**: it needs no GitHub credentials on the server and keeps the approved artifact independent from repository history. GitHub synchronization remains **planned**; if it is added, it should produce the same immutable versions through the same publication use case, so pinning, rollback and delivery are unchanged.
