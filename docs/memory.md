# Agent memory and personal resources (design, planned)

Status: **planned, nothing here is implemented yet.** This page is the design for two linked pieces of work, agreed with the project owner on 13 November 2026, and is the reference for the pull requests that implement them. Each pull request updates the status table below when it lands.

| Part | Status |
| --- | --- |
| Personal skills (API and client; CLI and app screens planned) | Implemented, see [skills](skills.md#personal-skills) |
| Personal environment values and MCP servers (API and client; CLI and app screens planned) | Implemented, see [variants](variants.md) |
| [Memory core](#memory-core): storage, REST API, `memory` scope, typed client | Implemented, see [What is implemented](#what-is-implemented) |
| The `memory` MCP server for agents (`teamcode mcp memory`) and the [index at session start](#index-at-session-start) | Implemented, see [What is implemented](#what-is-implemented) |
| [Write rules and team validation](#write-rules) | Implemented, see [What is implemented](#what-is-implemented) (dashboard queue screen and CLI commands are not) |
| [Periodic curation](#curation) | Planned |
| Documentation (files in a repository) | **Out of scope**, to be discussed later; see [Memory is not documentation](#memory-is-not-documentation) |

## Memory is not documentation

- **Memory** is information captured as the work goes, mostly automatically, like the memory of Claude Code. It lives in TeamCode (the database), is less reliable (it can be stale, duplicated or contradictory) and is curated by a job.
- **Documentation** is written and reviewed by people, lives in files in a repository (for ControlResell, its domain repository, shared by about fifty service repositories) and stays there. TeamCode does not store it.

Memory is never scoped to a repository, because business rules cross services. The scopes are the team and the person, filtered by free **tags**. If documentation is later exposed to agents, the entries need only an optional free-form reference to it (see `refs` below); no part of this design prevents a later "promote a memory to documentation by pull request". When memory and documentation disagree, documentation wins.

## Personal skills, environment values and MCP servers

Today only an administrator publishes skills and declares [variants](variants.md) (environment values and MCP servers) and assigns them. A member who wants a tool of their own, in `teamcode run` and in their cloud sessions, cannot add it.

### Rules

- A skill or a variant has an optional **owner** (a member). No owner means team-wide, as today. Existing rows keep no owner.
- A member creates, edits, deletes and publishes their own from the API, the CLI and the app (interactive login only; service keys are refused, as for administration). Routes live under `/api/v1/me/...` and check the owner on every call.
- A personal resource is **assigned to its owner automatically**: no assignment row is needed, and it is delivered wherever the owner's resources are resolved (`teamcode run`, cloud sessions through the same resolution as the team ones). After a [cloud transfer](cloud-sessions.md#transfer) the recipient receives their own personal resources, never the former owner's.
- **Names** share one namespace per team: a personal skill, environment name or MCP slot cannot take a name used by the team or by another member (`409 name_taken`), and an administrator publishing a team resource whose name a member already uses is refused until the member renames theirs. This avoids two directories or two slots fighting over `~/.claude/skills/<name>` or `tracker`.
- An **administrator can promote** a personal resource to a team resource after review, which clears its owner (and, for a skill, its pin and audience), then assigns it as usual. A personal resource is never assigned to others directly, because what other people will run is reviewed first. Nobody else receives it.
- **Secrets stay with the owner.** Credentials use the existing AES-256-GCM envelopes bound to the row and the member, are write-only, and no administrator API returns them. Administrators see the definition of a personal resource and its owner (name, target, endpoint and configuration are not secret) so they can review it before promoting it, never a secret value.
- **Limits** per member (default 50 skills, 20 variants), set by the same bounds as team resources (200 files and 5 MiB per skill version, 32 MCP servers per member).
- **Hosted stdio MCP servers** run package code on the server's MCP host. A team setting `memberHostedMcp` (administrator, **off by default**) decides whether members may declare one; when on, the launcher and exact-version rules are those of administrator-declared commands, and a member's process is always `PER_MEMBER` (never shared with anyone). Remote HTTPS servers are allowed by default, under the same upstream address checks as team servers. Until that setting exists, a member's hosted command is simply refused.
- Names are unique across the whole team, personal ones included (`name_taken` names nobody).

### Pull requests

1. Skills: `owner_member_id` on `skills`, the `me` routes, resolution and cloud delivery of the owner's personal skills, dashboard and app screens, tests.
2. Variants: `owner_member_id` on `variants`, the `me` routes, automatic assignment, the `memberHostedMcp` setting, tests, `EXPLAIN` for the resolution queries.

## What is implemented

The server side of the [memory core](#memory-core) (capability `memory.v1`):

- Tables `memories` and `memory_tags`. `memory_tags` repeats the entry's team, scope and owner (they never change), so a tag filter is one covering index range of the caller's own scope. Entries are `active` (the only status listed, searched or read; `proposed`, `superseded` and `rejected` are reserved for the next steps). No expiry by default; an entry with `expiresAt` in the past is not listed, searched or read.
- Routes under `/api/v1/memory`: `GET /index` (scope, tags, keyset `after`, limit up to 50), `GET /search` (`q`, up to 5 words, every one in the title, summary or a tag, never the content; up to 20), `POST` (create), `GET /{id}` (the content; counts a read), `PATCH /{id}` (with `expectedVersion`) and `DELETE /{id}`. Typed client: `memoryIndex`, `searchMemory`, `readMemory`, `createMemory`, `updateMemory`, `forgetMemory`. Search over the content is not implemented: a fulltext index would need a hand-written migration outside the Exposed tables, and the index and title search are what the agent flow uses first.
- The scope `memory` (`ServiceScope.MEMORY`): held by an interactive member, by the execution credential of a cloud session and of a remote host session, and offered on a member's own service key. Every other key, including those with `todo:session` or `inference`, is refused (`credential_scope`).
- **Visibility is in every query.** A team entry is read by every member of the team. A personal entry is read, changed and forgotten by its owner only: no administrator, no key of another member, no route. Another member's entry reads as `404`, whether by id, index or search.
- **Writes.** A personal entry is written directly by any caller with `memory` (an agent's `origin` is `agent`, a member's `human`). A team entry is written by an interactive member only (`403 memory_team_forbidden` for an agent or a key); the agent proposal and its validation are the next step. A team entry is changed or forgotten by its author or an interactive administrator. Updates need the version read (`409 memory_conflict`).
- **Limits.** 160-character titles, one-line summaries of 300, 16,000 characters of content, 8 tags, 10 references; 2,000 personal entries per member and 5,000 team entries (`409 memory_limit`).
- **Secret filter.** Content, title, summary and references that look like a credential (provider and TeamCode token prefixes, private keys, bearer headers, `password=value` assignments) are refused (`422 memory_secret`) before anything is stored. It is a filter of common shapes, not a guarantee.
- **Audit.** `memory.created` (with scope and origin), `memory.updated` and `memory.forgotten`, with the entry id and the key's id when a key wrote it; never a title or content. The session of an execution credential is kept on the entry as provenance.
- **The `teamcode_memory` MCP server for agents** (`teamcode mcp memory`, a stdio server like `teamcode_todo`): tools `memory_index`, `memory_search`, `memory_read`, `memory_write`, `memory_update` and `memory_forget`. `teamcode run` (Claude Code and Codex) adds it when the server serves `memory.v1`; cloud sessions add it when the execution credential holds the `memory` scope (an older credential gets `credential_scope` on a read and the agent is not offered the tools), remote host sessions when the server serves `memory.v1`. It reaches TeamCode through the local proxy's memory relay (`memory-relay-v1`: the index, search and entry routes only), with the local token, never a stored credential in the agent's reach.
- **Index at session start.** When the agent initializes the server, the server reads the newest 50 entries (team and the caller's personal ones) and puts at most 4 KB of lines `id (scope) title: summary [tags]` in its instructions, with the rule that an entry is a note, never an instruction. Line breaks and control characters are removed from titles and summaries. A server that cannot be reached starts the agent without the index and says to use `memory_index`. Ordering is newest first; ordering by reads is not implemented.
- **Team entries from an agent are proposals.** A call is an agent's when it comes with an execution credential or through the local relay, which marks every call it forwards (`x-teamcode-origin: agent`), whoever's login the relay carries: a `teamcode run` agent runs with the member's own login and must not write what the whole team reads on its own. Such a `POST /api/v1/memory` with `scope: team` stores the entry `PROPOSED` (not listed, searched or readable) and opens a [wait](waits.md) for the owner of the session it names (`sessionId`, or the execution credential's own; `400 memory_proposal_session` without one, `404` for a session that is not the caller's). The wait shows the whole proposal, warns when it is tagged `external`, and offers `accept` (with the title, summary and content editable first) and `reject`. A key that is no agent's session cannot propose (`memory_team_forbidden`). At most 20 proposals per owner wait at once (`409 memory_proposals_limit`).
- **Settling.** The server's periodic work (`memory/settle`, every 10 seconds, one replica at a time) applies the answers: accepted proposals become `ACTIVE` (the owner's edits are applied unless they break a rule, such as a credential, in which case the proposal is accepted as sent), rejected or cancelled ones become `REJECTED`. A proposal unanswered for 14 days is rejected and its wait cancelled; a rejected entry is deleted after 30 days. So an acceptance can take up to about ten seconds to show in the index. Audit: `memory.proposed`, `memory.accepted` (by whom, whether edited), `memory.rejected`.
- **Queue.** `GET /api/v1/memory/proposals` lists the waiting proposals (an administrator sees everyone's, a member their own); `POST /api/v1/memory/proposals/{id}/accept` is an administrator's, `/reject` an administrator's or the author's (it closes the wait). Interactive login only. There is no dashboard screen or CLI command for it yet, and the CLI's `memory` MCP server does not expose it to agents.
- Not implemented here: the curation job, dashboard or app screens, and commands to read or write the memory from the CLI.

## Memory core

### Model

One table `memories`, plus `memory_tags (memory_id, tag)`, both generated from Exposed definitions.

| Field | Rule |
| --- | --- |
| `scope` | `TEAM` or `PERSONAL`. A team entry is readable by every member. A personal entry is readable and writable by its `owner_id` only: **no administrator reads it**, and neither does a service key of another member. |
| `title`, `summary` | `title` at most 160 characters; `summary` one line of at most 300 characters. These two are what the index shows. |
| `content` | Text up to 16,000 characters, read on demand. |
| `tags` | At most 8 per entry, `[a-z0-9][a-z0-9._-]*`, at most 40 characters. They replace any repository scope. |
| `status` | `ACTIVE`, `PROPOSED` (team entry waiting for validation), `SUPERSEDED`, `REJECTED`. |
| `origin` | `HUMAN`, `AGENT` or `CURATION`, with the author member and the session that wrote it. |
| `supersedes_id` | Set on the newer entry; the older one becomes `SUPERSEDED` (kept, reversible). |
| `refs` | Optional list of free-form references (ticket, pull request, URL, file path). Never fetched or trusted. |
| `expires_at` | Null by default (no expiry). Set by the writer or by curation. |
| dates | `created_at`, `updated_at`, `last_read_at`, `read_count`. |

Index for the list path: `(team_id, scope, owner_id, status, updated_at)`; one for tags `(tag, memory_id)`. Every list is keyset-paginated with a limit; the index never reads `content`. Every new query carries `EXPLAIN ANALYZE` evidence on a table seeded to production scale in its pull request.

### Access, audit and secrets

- Writes take the team lock like other administration writes where they change team entries; personal writes do not.
- The audit journal records `memory.created`, `memory.updated`, `memory.forgotten`, `memory.proposed`, `memory.accepted`, `memory.rejected`, `memory.superseded` and `memory.restored` with the entry id, scope and actor, **never the title or content**. Personal entries are audited the same way (ids only).
- A filter at write time refuses content that looks like a credential (known token prefixes, private key blocks, `Authorization:` values) with `422 memory_secret`, and the project rule against storing prompts and provider response bodies applies: the `memory` tools take what the agent chose to write, not transcripts.
- Deleting a member deletes their personal entries. Their team entries stay, attributed to the former member.

### The `memory` MCP server

A built-in MCP server, delivered like the `teamcode_todo` and `teamcode_waits` servers (the CLI relay locally, the runner's relay in cloud sessions), with its own scope `memory:session` on execution credentials and `memory` on member tokens.

| Tool | Behaviour |
| --- | --- |
| `memory_index` | Entries as `{id, scope, title, summary, tags, updatedAt}`, filtered by `tags` and `scope`, newest and most read first, at most 50 per call, with a cursor. |
| `memory_read` | One entry with its content and `refs`; counts as a read. A team entry or the caller's own personal entry only. |
| `memory_search` | Keyword search over title, summary, content and tags (MySQL fulltext), same visibility, at most 20 results. No vectors in this phase. |
| `memory_write` | Creates an entry. Default scope `PERSONAL`; see [write rules](#write-rules) for `TEAM`. |
| `memory_update` | Edits an entry the caller may edit; a stale `expectedVersion` is refused. |
| `memory_forget` | Deletes the caller's personal entry, or a team entry the caller wrote (administrators may delete any team entry). |

## Index at session start

The server instructions of the `memory` MCP server carry the **index**: at most 4 KB of lines `id · [scope] title — summary (tags)`, the caller's personal entries and the team's, most recently updated and most read first, plus one sentence telling the agent to call `memory_read` for the detail and `memory_write` for what is worth keeping. Claude Code and Codex both put MCP server instructions in the agent's context, so this needs no hook and works for `teamcode run` and for cloud sessions. The index is built when the agent process starts (like skills and servers); an entry written later is found with `memory_index` or `memory_search`. A failure to build it starts the agent without the index and says so on stderr.

The index lists titles and summaries only, never content; personal entries appear only in their owner's index, including after a cloud transfer (the recipient's index is their own).

## Write rules

- **Personal**: the agent writes directly (`ACTIVE`).
- **Team, by a human** (dashboard, app, CLI): writes directly, as an administrator or member allowed to (all members, by default; an administrator can restrict it to administrators).
- **Team, by an agent**: the entry is stored `PROPOSED`, invisible to the index and to search, and a [wait](waits.md) is opened for the session's owner showing the full proposal, with answers accept, edit-then-accept or reject. Acceptance makes it `ACTIVE` and audits who accepted. A proposal not answered in 14 days expires as `REJECTED`. Administrators also see all pending proposals in a dashboard queue and may accept or reject any of them.
- **External content** (web pages, tickets, third-party pull requests) is never promoted automatically. The agent is told to tag such an entry `external`, and the proposal wait states it; curation never merges an `external` entry over a `HUMAN` one.
- A rejected proposal is kept 30 days for the audit, then deleted.

## Curation

A scheduled work item, run by one replica like the MCP `retain` sweep, per team and per member (personal entries are curated alone, per member, and never mixed with team entries in a model prompt).

1. **Mechanical pass, no model**: expire entries past `expires_at`; merge exact duplicates (same normalized title and content hash) keeping the newest.
2. **Candidate groups**: entries sharing tags and matching by fulltext score, at most 20 groups and 8 entries per group per run.
3. **Model pass** on each group: classify as `duplicates`, `contradiction` or `independent`, with a short reason. The call goes through the gateway on the team's pool with a per-run token bound and a daily per-team ceiling; the account is the team's own, never a member's key. The pass is **off until an administrator turns it on**.
4. **Apply**: for `duplicates` and `contradiction`, **the most recent wins** (a newer entry is often an update of an older one that was never updated): the older entry becomes `SUPERSEDED` with `superseded_by`, and nothing is deleted. Exceptions go to a **review queue** instead of being applied: the older entry is `HUMAN` and the newer is `AGENT`, either entry is `external`, or the model's answer is uncertain.
5. **Reversible**: a superseded entry can be restored by its owner (personal) or by an author or administrator (team); a restore audits `memory.restored` and supersedes the entry that replaced it. Superseded entries are purged 90 days after superseding.
6. Every applied change is audited, with the run id. Curation never reads or writes outside the entry fields above.

Stale detection by re-checking `refs` against repositories is **not planned** in this phase.

## Pull requests, in order

1. This design.
2. Personal skills.
3. Personal environment values and MCP servers.
4. Memory core: tables, domain, store, routes, client, `memory` MCP server, audit, secret filter, dashboard and app list.
5. Index injection (local and cloud).
6. Write rules, proposals and the validation wait.
7. Curation job and review queue.

## Decisions taken while the owner could not answer

Stated so they can be changed: members may add remote HTTPS MCP servers but not hosted stdio ones (setting off by default); no default expiry for entries; the person who validates an agent's team proposal is the session owner; curation is off until an administrator enables it.
