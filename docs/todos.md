# Session todo and the Stop hook

Design: the artifact "La todo de session et le Stop hook" (4 November 2026). Each part below says what is implemented and what is planned.

A session keeps one todo: a free-text `reference` (usually a link to the plan) and items, each with a complete description and a typed end condition. An item is done only when its checks pass, never because the agent says so. Items are added, never deleted without a person's agreement.

## What is implemented

| Part | Status |
| --- | --- |
| Domain model and rules (`domain/.../todos`) | implemented |
| Storage, routes, `todo:session` scope | planned |
| `teamcode_todo` MCP server and checks run on GitHub | planned |
| `teamcode cloud todo-stop` Stop hook | planned |
| Reference at wake-up and after compaction | planned |
| Server-side reminder (Codex, safety net) | planned |
| Companion Todo view | planned |
| Session for a local `teamcode run` | planned |

### Domain rules (`TodoRules`)

- One list per session, active at once (`create`, `requireNoList`). At most 50 items; ids are slugs of at most 40 characters; a description is 80 to 4 000 characters; a list refuses unknown or circular dependencies, an item without a typed check, and a guessed PR number or version (`guessed_number`).
- Check types: `pr_merged`, `pr_closed`, `release_contains`, `ci_green`, `command`, `artifact_published`, `wait_answered`, `attested`. A check never names a PR number: the PR is found by the marker `TodoRules.marker` returns.
- `next`: the first `todo` item whose dependencies are done. A dropped dependency counts as settled, so a dropped item never stalls the list.
- `start`: one item `doing` at a time, except an item given to a background agent.
- `add`: marked `discovered` with its reason, placed after the item in progress and after its own dependencies.
- Name, description and conditions are frozen; `note` only appends. `requestDrop` puts an item in `drop_requested`; only `resolveDrop` with the owner's yes drops it. Nothing is deleted.
- `complete`: done only when every check passed; a failed or unknown check (unreachable, not linked yet) refuses, and the proof of what was seen is kept.
- `settle`: once every item is done or dropped, a final verification runs; an item whose check now fails is reopened, otherwise the list is done.
- `stop`: the stall counter. A blocked stop with no item finished, no item added and no note since the previous blocked stop adds one; at 3 (`STALL_LIMIT`) the hook asks the owner.
