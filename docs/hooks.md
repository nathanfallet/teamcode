# Team hooks (V2)

Team hooks are scripts that the members' agents run on agent events, distributed by TeamCode like [skills](skills.md): published from CI, approved (pinned) by an administrator, assigned per member, and installed by `teamcode run` on the member's computer and by the runner in [cloud sessions](cloud-sessions.md#team-mcp-servers-and-skills). Typical uses: a `Stop`/`SubagentStop` hook that refuses to let an agent stop without saying where it stands, a `PreToolUse` audit hook, a `SessionStart` hook that loads team context.

Unlike a skill, a hook **executes code** on the member's computer, with the member's rights: it can read what the member can read, including the TeamCode login in the private configuration directory. Every rule below follows from that.

## Model

| Concept | Rule |
| --- | --- |
| Hook | Team-scoped, named like a skill (`[a-z0-9]+(-[a-z0-9]+)*`, at most 64 characters). Created by its first publication. At most 100 per team. |
| Version | Immutable label with the skill catalog's rules. Its definition, files and digest never change. At most 200 per hook. |
| Definition | `events` (one or more of `PreToolUse`, `PostToolUse`, `UserPromptSubmit`, `Stop`, `SubagentStop`, `SessionStart`, `SessionEnd`, `PreCompact`, `Notification`), an optional `matcher` (1–256 characters, passed to the agent unchanged, for example a tool name for `PreToolUse`), `command` (the path of an executable file of the artifact) and `timeoutSeconds` (1–600, default 60). |
| Artifact | 1–50 files, at most 1 MiB, with the skill catalog's path rules (relative, portable, no links, no hidden or parent segments). The `command` file must be published with mode `executable`. No `SKILL.md` is required. |
| Pinned version | The single approved version. **Only an interactive administrator pins** (dashboard or API); publication never pins. A hook without a pin delivers nothing. |
| Access decision | Per member: *yes* (`allow`), *no* (`deny`) or not assigned. *Yes* delivers; *no* never does; *not assigned* delivers only when the hook's audience is *all members*. |
| Audience | Per hook: *members set to yes* (default) or *all members* (`allMembers`): every member without an explicit decision, including members who join later, receives the pinned version, with the [skills' precedence](skills.md#access-precedence). See [Delivering to all members](#delivering-to-all-members). |
| Member approval | On their computer, each member approves or refuses each delivered version (`teamcode hooks`). `teamcode run` installs only approved versions. |

The command is not a shell line: it is a file of the artifact, run with no argument, with the event's JSON on standard input exactly as the agent defines it ([Claude Code hooks](https://code.claude.com/docs/en/hooks), [Codex hooks](https://learn.chatgpt.com/docs/hooks)). The client builds the command line itself, as the quoted absolute path of the installed file, so a definition cannot inject shell syntax. Exit codes and JSON output mean what the agent says they mean; for example, exit code 2 from a `Stop` hook makes Claude Code continue with the hook's standard error as the reason.

### Digest

```
sha256( "hook" \0 <events, sorted, comma-separated> \0 <matcher or empty> \0 <command> \0 <timeout> \n
        then, for each file sorted by path:  <path> \0 <mode> \0 <size> \0 <sha256 hex of content> \n )
```

rendered as `sha256:<64 lowercase hex>`. The definition is part of the digest, so a client verifies both what it will run and when. The server recomputes it on every download (`hook_integrity`, 500, on a mismatch) and the CLI recomputes it before writing anything.

## Who can publish, and why pinning stays interactive

Publication needs an interactive administrator login or a service key with the dedicated `hooks:publish` scope, which only a key owned by an administrator can receive (the owner's role is rechecked on every write). It is separate from `skills:publish`: a leaked skills key must not be able to put code on members' computers.

A `hooks:publish` key **cannot pin**, unlike a `skills:publish` key, and cannot unpin, assign members, change the audience of a pinned hook or read the catalog. A leaked hook key can therefore add versions, which deliver nothing, but never choose what runs on a workstation: an administrator reads the version's definition and files on the dashboard (**Read the files**, verified against the digest) and pins it. This is a deliberate difference from [skills](skills.md#pinning-from-ci).

```sh
teamcode keys create --name hooks-ci --scopes hooks:publish --no-expiry --json
```

```http
POST /api/v1/hooks/{name}/versions
Authorization: Bearer tcsk_...
Content-Type: application/json

{
  "version": "1.0.0",
  "definition": { "events": ["Stop", "SubagentStop"], "command": "bin/require-status.sh", "timeoutSeconds": 30 },
  "files": [
    { "path": "bin/require-status.sh", "mode": "executable", "contentBase64": "IyEvYmluL3No..." }
  ]
}
```

- `201 Created`: a new immutable version, not pinned. `200 OK` with `created: false`: the same version with the same digest (CI retries are safe and append no audit event).
- `409 hook_version_conflict`: the label exists with different content or definition. `409 hook_limit` / `hook_version_limit` at the bounds.
- `400 invalid_hook_definition`, `invalid_hook_artifact`, `invalid_hook_path`, `invalid_hook_name`, `invalid_hook_version`; `413` above 1 MiB of content (the body is bounded at 2 MiB).
- `403 credential_scope` for a key without `hooks:publish`, `403 forbidden` for a member or a non-administrator key, `403 hook_audience_requires_administrator` for a key asking for `allMembers` on a pinned hook (nothing is stored).

The GitHub Actions example of [skills](skills.md#example-publishing-from-a-github-repository) applies with this body and route.

### Delivering to all members

A hook whose audience is *all members* runs for every member without an explicit decision, including members who join later; an explicit *No* still excludes a member, and each member still approves each version on their computer. An administrator sets it on the hook's page (**Access → Audience**) or with `PUT /api/v1/hooks/{name}/audience` and `{ "allMembers": true }`. A publication can ask for it with `"allMembers": true`:

- From an interactive administrator, always.
- From a `hooks:publish` key, **only while the hook has no pinned version** (in practice its first publications), or when it is already for all members (a retry). Nothing runs before an administrator pins a version, and the hook's page shows the audience next to the code, so the administrator's interactive pin approves both. On a pinned hook that is not already for all members, the publication is refused whole with `403 hook_audience_requires_administrator`: a key never widens what already runs on workstations. Publish without the flag, or ask an administrator.
- `"allMembers": false`, or no field, leaves the audience as it is.

No new scope is needed for this, unlike [skills](skills.md#delivering-to-all-members): a hook key never delivers anything by itself, so the pin, which stays interactive, remains the single approval. Each actual change appends `hook.audience.all_members` or `hook.audience.assigned_only` (subject: hook ID; `credentialId`: the key that asked for it, empty for an administrator). Servers advertise `team.hooks.all-members.v1`.

## Administration

**Dashboard → Team hooks** (administrators; members are redirected to their delivered hooks) lists the hooks. A hook's page shows each version's definition, digest, size and publisher, and lets an administrator read any version's files, pin a version (update or rollback), unpin it (stop delivery), set the audience (*Members set to Yes* or *All members, except those set to No*) and set each member's decision.

| Operation | Route (interactive administrator) |
| --- | --- |
| Catalog | `GET /api/v1/hooks` |
| Versions | `GET /api/v1/hooks/{name}/versions` |
| Read a version's definition and files, pinned or not | `GET /api/v1/hooks/{name}/versions/{version}` |
| Pin (update/rollback) | `PUT /api/v1/hooks/{name}/pin` with `{ "version": "..." }` |
| Unpin | `DELETE /api/v1/hooks/{name}/pin` |
| Audience | `PUT /api/v1/hooks/{name}/audience` with `{ "allMembers": true \| false }` |
| Decisions | `GET /api/v1/hooks/{name}/assignments`, `PUT /api/v1/hooks/{name}/assignments/{memberId}` with `{ "decision": "allow" \| "deny" }`, `DELETE` to clear |

| Delivery | Route | Credential |
| --- | --- | --- |
| Own manifest | `GET /api/v1/hook-delivery` | Interactive login, or a key with `hooks:read` |
| A member's manifest | `GET /api/v1/members/{memberId}/hook-delivery` | Administrator |
| Artifact | `GET /api/v1/hook-delivery/{name}/{version}` | Interactive login, or a key with `hooks:read` |

Only the currently effective pinned version is served. Servers advertise `team.hooks.v1` in `GET /api/v1/capabilities`.

## On the member's computer

```sh
teamcode hooks list            # delivered hooks and your decision for each version
teamcode hooks show stop-guard # its definition and files, verified against the digest
teamcode hooks approve stop-guard               # the version show printed last
teamcode hooks approve stop-guard --digest sha256:...  # or the version you reviewed elsewhere
teamcode hooks refuse stop-guard
teamcode run claude --no-team-hooks   # one launch without them
```

- **Nothing runs without the member's approval.** `teamcode run` reads the manifest; a delivered version the member has not approved is named on standard error ("awaiting your approval") and not installed. An approval applies to one digest: a new version, or any change to its files or definition, asks again. A refusal is silent until a new version arrives. Decisions stay on the computer (`hooks/approvals.json` in the private configuration directory) and are not reported to the server.
- **Approving what was read.** `approve` records the version `show` printed last on this computer (`hooks/shown.json`), or the one named with `--digest`. A different version delivered in between is refused, never approved unread; without either, `approve` asks to read the hook first. `show` and `list` print every character that can hide, overwrite or reorder text in a terminal (control characters other than line feed and tab, carriage return, bidi overrides and isolates, zero-width and other format characters, line separators, tag characters) as `<U+XXXX>`; a file with other control characters is still reported as binary.
- **Installation.** Approving downloads the version, verifies every file and the digest, and writes it to `hooks/installed/<name>/<digest>/` in the private configuration directory (owner-only, executables `chmod 700`). A version directory is never modified, so an agent already running keeps the version it started with. Later launches reuse it without downloading, after checking every file against the list in its marker and that list against the approved digest: a file changed after installation (by an agent, for example) makes the next launch download and verify the version again.
- **Claude Code.** The approved hooks join TeamCode's session-only plugin (`--plugin-dir`, the plugin that carries [guidance](agent-guidance.md)), as command hooks on their events. `--no-guidance` keeps the team hooks; `--bare` and `--no-team-hooks` leave them out. Claude Code's managed hook policies (`disableAllHooks`, managed-only hooks) still apply and can prevent them.
- **Codex.** The approved hooks are added as invocation-only `-c hooks.<event>=[...]` overrides, merged in the same array as the guidance hook for `UserPromptSubmit` and `PreToolUse`. Codex then asks the member to trust them in `/hooks`, as for guidance: two consents, TeamCode's for the version and Codex's for the command. Codex has **no `Notification` event**: a hook listening only to it is not added for Codex (named on standard error); its other events (`PreToolUse`, `PostToolUse`, `UserPromptSubmit`, `Stop`, `SubagentStop`, `SessionStart`, `SessionEnd`, `PreCompact`) have the same names and are supported by Codex CLI 0.163.0. With explicit `-c hooks...` arguments, TeamCode adds none, as for guidance.
- **When something is absent.** A server without `team.hooks.v1`, an unreadable manifest or a failed download or verification leaves the affected hooks out with a notice and never blocks the launch. A hook file deleted while an agent runs makes that hook fail as a non-blocking hook error, as the agent defines it. Windows is not supported yet: `teamcode run` installs no team hook there and says so.

## Cloud sessions

The runner installs the owner's hooks at every Claude Code start, with the owner's current decisions, like skills: it downloads each delivered version (with the execution credential's `hooks:read`), verifies the digest, and installs the files in the agent's home under `~/.teamcode/hooks/<name>/` through `teamcode cloud install-hooks`, under the agent's UID; a hook no longer delivered is removed. The agent can write its home, so every start puts back the verified files rather than reusing the installed ones. Claude Code is started with `--settings` naming them. There is **no member approval in a pod**: the pod is team infrastructure, not the member's computer, and the administrator's pin plus the owner's *yes* are the approval. The pod log carries counts only.

**Codex cloud sessions run no team hook.** Codex runs only hooks a person trusted in `/hooks`, and TeamCode never bypasses that trust (`--dangerously-bypass-hook-trust`), so the runner does not add them.

## Waiting for a person

Separately from team hooks, TeamCode's own hooks report whether a launch's agent works or waits for a person, from `Stop` and `Notification` (Claude Code) and `Stop` (Codex), without content. They are not team hooks: nothing is published, pinned or approved, and a team `Stop` hook runs beside them in the same event. See [Waiting for a person](agent-sessions.md#waiting-for-a-person). Cloud sessions already know their turns and permission waits, so their pods run no such hook.

## Audit

Catalog writes append metadata-only events in the same team-locked transaction: `hook.created`, `hook.version.published` (subject: version ID; `credentialId`: the publishing key, empty for an administrator), `hook.pinned` (subject: version ID), `hook.unpinned`, `hook.audience.all_members`, `hook.audience.assigned_only` (subject: hook ID), `hook.access.allowed`, `hook.access.denied`, `hook.access.cleared` (subject: the assignment ID). No content, path, digest, label or definition is written to the journal.

## Persistence and upgrade

Four additive tables, `hooks`, `hook_versions`, `hook_files` and `hook_assignments`, in one reviewed migration (`V20261021190000`) checked against the Exposed definitions by `HookPersistenceTest`. The audience is the `hooks.all_members` column (`BOOLEAN DEFAULT FALSE`, migration `V20261023191500`): existing hooks keep running only for members set to *Yes*. Every replica must run this version before the catalog is used. The execution credential of cloud sessions created by an older server lacks `hooks:read`: such an execution starts without team hooks until its next credential.

## What is planned

- **Approval status reported to the server**, so an administrator sees which members approved or refused a version (as skill installation status).
- **Retiring** a hook or a version, and pruning old version directories on the computer.
- **Windows** support (hook commands run by the agents' shells there) and **Codex cloud sessions**, which need a trust path that does not bypass Codex's.
- **Group decisions**, with the skill catalog's precedence rule.
