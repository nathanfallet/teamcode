# Skill catalog (V2, server side)

The skill catalog lets administrators distribute reusable agent skills to team members. This page covers the server: catalog, publication, access decisions, delivery and installation status. The native CLI synchronization is **planned** and not part of this slice; see [What is planned](#what-is-planned).

A skill is instructions and files loaded by an agent. It is not executed by the server and it is not an access-control boundary: a member can copy what they received. Protect tools, credentials and data at the service that owns them (the planned MCP gateway or the backing service).

## Model

| Concept | Rule |
| --- | --- |
| Skill | Team-scoped, named `[a-z0-9]+(-[a-z0-9]+)*`, at most 64 characters. Created by its first publication. |
| Version | Immutable, case-sensitive label of 1–64 characters `[0-9A-Za-z][0-9A-Za-z.+_-]*` (binary collation, so `v1` and `V1` are distinct). Its files and digest never change. A retired version cannot be republished. Labels carry no ordering: "update" and "rollback" are both explicit pins, by an administrator or by a publisher with `pin`. |
| Artifact | 1–200 files, at most 5 MiB of content in total, with `SKILL.md` at the root. Stored in MySQL (`MEDIUMBLOB` per file). |
| Pinned version | The single approved version. An administrator pins it, or the publisher does with `pin: true` (see [Pinning from CI](#pinning-from-ci)). Publication without `pin` never pins. A skill without a pin delivers nothing. |
| Access decision | Per member: **yes** (`allow`), **no** (`deny`) or not assigned. *Yes* delivers; *no* never does; *not assigned* delivers only when the skill's audience is *all members*. There are no variants. |
| Audience | Per skill: **members set to yes** (default) or **all members** (`allMembers`). With *all members*, every member without an explicit decision receives the pinned version, including members who join later, and an explicit *no* still excludes a member. Set by an administrator on the dashboard or the API, or at publication (see [Delivering to all members](#delivering-to-all-members)). |
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

Today only member decisions exist: an explicit *yes* delivers, an explicit *no* never does, and no decision delivers only when the skill's audience is *all members*. Groups arrive with V1 team administration; when they do, `SkillAccessPolicy` combines them without changing the member rule:

1. An explicit member decision wins.
2. Otherwise, any group *no* wins over a group *yes*.
3. Otherwise, a group *yes* delivers.
4. With no decision at all, the audience decides: *all members* delivers, *members set to yes* delivers nothing.

*All members* is therefore the weakest rule: it never overrides a decision someone made. It is evaluated on every request, so a member created after the skill was published receives it at once, with no assignment row.

The rule is implemented and unit-tested now; the store passes no group decisions until groups exist.

## Publishing from CI

Publication goes through the API. It needs either an interactive administrator login or a service key with the dedicated `skills:publish` scope. That scope can only be granted to a key owned by an administrator, and the owner's role is rechecked against shared state on every write. A publishing key can also pin the versions it publishes (see [Pinning from CI](#pinning-from-ci)), and, with the additional `skills:assign` scope, deliver them to all members (see [Delivering to all members](#delivering-to-all-members)); it cannot unpin, assign members, retire or read the catalog.

```sh
# Once, from an administrator login: a key that can only publish skills.
teamcode keys create --name skills-ci --scopes skills:publish --no-expiry --json
```

```http
POST /api/v1/skills/{name}/versions
Authorization: Bearer tcsk_...
Content-Type: application/json

{
  "version": "1.4.0",
  "pin": false,
  "allMembers": false,
  "files": [
    { "path": "SKILL.md", "mode": "file", "contentBase64": "LS0tCm5hbWU6..." },
    { "path": "scripts/check.sh", "mode": "executable", "contentBase64": "IyEvYmluL3No..." }
  ]
}
```

- `201 Created`: a new immutable version, with its digest. `version.pinned` and `skill.pinnedVersion` report the pin state after the call.
- `200 OK` with `created: false`: the same version was already published with the same digest. CI retries are safe and append no audit event (with `pin: true`, a retry pins the version if it is not pinned yet; see below).
- `409 skill_version_conflict`: the version exists with different content. Publish a new version.
- `409 skill_version_retired`: the version exists but was retired; publish a new version.
- `409 skill_retired`: the skill was removed from the catalog; restore it first.
- `400` for invalid names, versions, paths, modes or base64; `413` above the size bound (the request body itself is bounded at 8 MiB).
- `403 credential_scope` for a key without `skills:publish` (or without `skills:pin` when `pin` is set, or without `skills:assign` when `allMembers` is set), `403 forbidden` for a member or a non-administrator key.

Limits: 500 skills per team, 200 versions per skill.

### Pinning from CI

A pipeline that should deliver what it publishes, without an administrator pinning each version by hand, adds `"pin": true` to the publication request. The key needs the `skills:pin` scope besides `skills:publish` (both on an administrator's key): `skills:publish` alone stores versions and never changes what members receive.

```sh
# Once, from an administrator login: a key that publishes and pins.
teamcode keys create --name skills-ci --scopes skills:publish,skills:pin --no-expiry --json
```

- The version is stored and pinned in one transaction, so delivery switches to it exactly when the publication commits.
- It is idempotent. Republishing the same version with the same digest and `pin: true` returns `200 OK` with `created: false` and pins the version unless it is already the pinned one: when another version is pinned (a rollback by re-running an older pipeline) and also when the skill is unpinned. A pipeline that pins therefore undoes an administrator's unpin on its next run, and a stale re-run of an older pipeline rolls delivery back to its version. To stop delivery for good against such a pipeline, retire the version (a retired version is never pinned again), remove the skill from the catalog, or set the members' decisions to *No*. Different content under the label is still `409 skill_version_conflict`, and nothing is pinned.
- A retired version or a retired skill is refused as for publication (`409 skill_version_retired`, `409 skill_retired`).
- The same key may call `PUT /api/v1/skills/{name}/pin` to pin any published, non-retired version, for example a rollback without republishing. Unpinning stays with administrators.
- Each actual pin appends `skill.pinned` with `credentialId` set to the key's id, so the journal shows that the key, not an administrator in the dashboard, changed the delivered version. Interactive pins leave `credentialId` empty, as before. The key's `skill.created` and `skill.version.published` events carry its id too.
- This gives the key the power to choose the delivered version: a leaked `skills:pin` key can pin any published, non-retired version for every member with *Yes*. Keep it in the CI secret store, prefer an expiring key where rotation is practical, and revoke it at once if it leaks. Skills can carry scripts that members' agents run, which is why pinning is a scope of its own: a leaked key with `skills:publish` only adds versions that deliver nothing until someone pins them (audit L3).
- **Upgrading.** Before `skills:pin` existed, `skills:publish` also pinned. A pipeline that pins with an older key now gets `403 credential_scope` (the publication is refused whole, nothing is stored): create a key with `skills:publish,skills:pin` and replace the secret. Servers that require the scope advertise `team.skills.pin-scope.v1`.
- Access decisions still apply: a pinned version reaches only members with an explicit *yes*.

Servers that support the field advertise `team.skills.publish-pin.v1` in `GET /api/v1/capabilities` (readable with any key). An older server ignores the unknown field and publishes without pinning; a client detects this from `version.pinned: false` in the response and should report it instead of assuming delivery.

Leave `pin` out where a human must approve each version: publication without it never pins.

### Delivering to all members

By default a pinned skill reaches only members set to *Yes*, so every new skill would need each member assigned by hand. A skill whose **audience** is *all members* reaches every member without an explicit decision, including members who join later. An administrator sets it on the skill's page (**Access → Audience**) or with `PUT /api/v1/skills/{name}/audience` and `{ "allMembers": true }`; a pipeline sets it at publication with `"allMembers": true`, next to `"pin": true`:

```sh
# Once, from an administrator login: a key that publishes, pins and delivers to everyone.
teamcode keys create --name skills-ci --scopes skills:publish,skills:pin,skills:assign --no-expiry --json
```

```json
{ "version": "1.4.0", "pin": true, "allMembers": true, "files": [ ... ] }
```

- The audience changes in the publication's transaction, so the version is never visible pinned for the old audience only. Like `pin`, it is idempotent: a retry, or a later publication with the flag, changes nothing once the skill is for all members. Re-running a pipeline with the flag also sets it on skills published before the option existed.
- `"allMembers": false`, or no field, leaves the audience as it is: a publication never narrows it. Narrowing it back to *members set to yes* is an administrator's (dashboard or `PUT .../audience` with `false`), and a pipeline that keeps sending `true` widens it again on its next run, as `pin` undoes an unpin.
- An explicit *No* still excludes a member, whatever the audience. Use it for a member who must not receive the skill.
- Each actual change appends `skill.audience.all_members` or `skill.audience.assigned_only` (subject: skill ID), with `credentialId` set to the key's id when a key made it.

**Why a separate `skills:assign` scope.** `skills:pin` already lets a key choose the delivered version, but only for members an administrator set to *Yes*. Delivering to all members widens *who* receives a skill, and skills can carry executable scripts that agents run on members' computers. Keeping it in its own scope means a leaked publishing key cannot push a new skill to every workstation unless the administrator who created the key chose that. Like `skills:publish`, `skills:assign` is valid only on an administrator's key (the owner's role is rechecked on every write), and it grants nothing without `skills:publish`. The audience route itself stays interactive: no key can call it.

There is no team-wide default ("every new skill goes to all members"): it would grant every future skill from any publisher without anyone choosing it per skill or per key. The publication flag gives the same result for a pipeline that wants it, and the choice stays visible on the key's scopes and in the journal.

Servers that support the field advertise `team.skills.all-members.v1`. An older server ignores it; a client detects this from `skill.allMembers: false` in the response.

### Example: publishing from a GitHub repository

Publication is API-only by design. TeamCode has no GitHub connection (no App, no OAuth, no server-side pull). A repository that holds skills publishes them itself from its own workflow, with a `skills:publish` key stored as a repository secret. This GitHub Actions workflow publishes `skills/review` when a `review-v*` tag is pushed:

```yaml
name: Publish review skill
on:
  push:
    tags: ['review-v*']
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build the publication request
        env:
          VERSION: ${{ github.ref_name }}
        run: |
          python3 - <<'PY' > "$RUNNER_TEMP/skill.json"
          import base64, json, os, pathlib
          root = pathlib.Path("skills/review")
          files = []
          for path in sorted(root.rglob("*")):
              if path.is_symlink():
                  raise SystemExit(f"Symbolic links cannot be published: {path}")
              if path.is_file():
                  files.append({
                      "path": path.relative_to(root).as_posix(),
                      "mode": "executable" if os.access(path, os.X_OK) else "file",
                      "contentBase64": base64.b64encode(path.read_bytes()).decode(),
                  })
          print(json.dumps({"version": os.environ["VERSION"].removeprefix("review-v"), "files": files}))
          PY
      - name: Publish to TeamCode
        env:
          TEAMCODE_URL: https://teamcode.example.org
          TEAMCODE_SKILLS_KEY: ${{ secrets.TEAMCODE_SKILLS_KEY }}
        run: |
          curl --fail-with-body --silent --show-error -X POST \
            -H "Authorization: Bearer $TEAMCODE_SKILLS_KEY" \
            -H "Content-Type: application/json" \
            --data-binary "@$RUNNER_TEMP/skill.json" \
            "$TEAMCODE_URL/api/v1/skills/review/versions"
```

A re-run of the same tag succeeds without creating anything (`200`, `created: false`). Changed content under an existing label fails the job (`409`). The published version is only delivered after it is pinned: by an administrator, or by the workflow itself with `"pin": True` added to the request it builds and a key that also holds `skills:pin` ([Pinning from CI](#pinning-from-ci)). The example key has no expiry (administrators only); to prefer rotation, use `--days 90` instead and replace the key before it lapses. Revoke it like any other service key when the pipeline is retired.

## Administration

**Dashboard → Skills** lists the catalog. A skill's page shows its versions with their digest, size and publisher, and lets an administrator:

- **Pin** a version: update, or rollback when pinning an earlier one. The delivered bytes are the immutable ones published under that label.
- **Unpin**: stop delivery without touching versions or decisions.
- **Retire a version**: it can no longer be pinned. The pinned version cannot be retired; pin another one first.
- **Remove from catalog** (retire the skill): unpins, stops delivery and refuses new publications. Versions and decisions remain. **Restore** reopens publication without pinning anything.
- Set the **audience**: *Members set to Yes* or *All members, except those set to No*. The catalog lists each skill's audience.
- Set each member's decision: *Yes*, *No* or *Not assigned* (shown as *Default (receives it)* when the audience is all members). Each member row links to the member's delivered skills and per-device installation status.

Members opening **Skills** are redirected to their own delivered skills.

| Operation | Route (administrator) |
| --- | --- |
| Catalog | `GET /api/v1/skills` |
| Versions | `GET /api/v1/skills/{name}/versions` |
| Pin (update/rollback) | `PUT /api/v1/skills/{name}/pin` with `{ "version": "..." }`; also open to an administrator's `skills:publish` key |
| Unpin | `DELETE /api/v1/skills/{name}/pin` |
| Remove from catalog / restore | `DELETE /api/v1/skills/{name}` / `POST /api/v1/skills/{name}/restore` |
| Retire a version | `DELETE /api/v1/skills/{name}/versions/{version}` |
| Audience | `PUT /api/v1/skills/{name}/audience` with `{ "allMembers": true \| false }` |
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

Installation reports upsert the status of up to 500 named catalog skills for the device bound to the login session (`409 device_required` otherwise; service keys have no device). Unknown names are refused, and a reported version must be published for that skill (with a matching digest when one is given), so a status never names unknown content. Reports are kept when access is removed, as history; a removed device deletes its reports. They are not audited, because they describe workstation state rather than an administrative change.

## Audit

Catalog writes append metadata-only events in the same team-locked transaction: `skill.created`, `skill.version.published` (subject: version ID), `skill.pinned` (subject: version ID; `credentialId`: the `skills:publish` key that pinned, empty for an administrator's pin), `skill.unpinned`, `skill.retired`, `skill.restored` (subject: skill ID), `skill.version.retired` (subject: version ID), `skill.audience.all_members`, `skill.audience.assigned_only` (subject: skill ID; `credentialId`: the key that set it at publication, empty for an administrator), and `skill.access.allowed`, `skill.access.denied`, `skill.access.cleared` (subject: the assignment ID, a stable row of `skill_assignments` naming both the skill and the member; clearing a decision keeps the row). No file content, path, digest or label is written to the journal. Repeated identical writes (same pin, same decision, idempotent publication) append nothing.

## Persistence and upgrade

Five additive tables: `skills`, `skill_versions`, `skill_files`, `skill_assignments`, `skill_installations`, created by one Flyway migration generated from the Exposed definitions and reviewed (the generator emitted one file per table, each repeating the `skills` DDL; they were merged into a single file). Existing data is untouched. The audience is the `skills.all_members` column (`BOOLEAN DEFAULT FALSE`, migration `V20261023191500`, shared with `hooks`): existing skills keep delivering only to members set to *Yes*, and a replica that predates it ignores the column. Create a `skills:assign` key only once every replica runs this version: an older replica does not know the scope and cannot authenticate the key. Every replica must run this version before the catalog is used, because older replicas do not serve the new routes.

Authorization and input validation live in `ManageSkillCatalogUseCase` and the publication/delivery use cases; rules that depend on shared state (retired skill, pinned version, limits) are enforced by the store inside its transaction. Administrative writes lock the team row briefly; a version's content insert holds only its skill row lock, so a 5 MiB upload does not delay team-wide admission. Concurrent publications of the same version on two replicas converge on one record (tested). Publication commits the catalog entry before the version: if the version step then fails (revoked key, conflict, database error), the skill remains with no version. It delivers nothing, counts toward the 500-skill limit, and can be retired; a retry reuses it. No content is cached in memory between requests.

## Cloud sessions

A cloud session installs the owner's effective skills for its agent every time the agent starts, under the agent's own UID, with the same digest checks and the same preservation of personal skills; see [cloud sessions](cloud-sessions.md#team-mcp-servers-and-skills). The execution's `tcex_` credential holds `skills:read` for this.

## What is planned

- **Native CLI synchronization** before `teamcode run`: an isolated, agent-compatible local installation of the effective pinned versions, preserving personal skills and reporting naming conflicts through the installation route. It will come after the current V1 CLI work.
- **Typed SDK methods** in the `client` module for these routes.
- **Group decisions**, using the precedence above.
- **Pruning** of old, retired versions; versions are currently kept forever (bounded at 200 per skill).
