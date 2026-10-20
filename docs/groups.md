# Groups and delegated management

**Dashboard → Groups** lets an interactive team administrator create groups, set their daily credit and concurrency caps, archive/restore them, and assign members and group managers. The deployment still has one team; groups do not create tenants. There is no public registration or email dependency.

Group roles are scoped assignments, independent of global [`OWNER`/`ADMIN`/`MEMBER` roles](team-roles.md):

| Actor | Group access |
| --- | --- |
| Owner or team administrator | All groups, full roster, budgets, archive/restore, assignments and group roles, admission pause/resume |
| Group `MANAGER` | Own active groups, their full roster and aggregate usage, pause/resume admission for existing members |
| Group `MEMBER` | Own active groups, aggregate usage and their own assignment |
| Nonmember | No group visibility or control |
| Service/CI key | No group management or roster access, including administrator-owned keys |

A manager cannot create groups, change budgets, archive groups, add/remove/promote members, change global roles/passwords, or change pools. Removing or demoting a manager takes effect on the next operation, including through another server replica; their ordinary login remains valid. Pausing their inference admission does not prevent signing in to review the group or resuming access. Every operation validates current shared authentication and scoped assignments.

## Independent cumulative caps

A member can belong to several groups. **Every active membership applies** in addition to the member's weighted share/hard cap, pool budget/isolation, physical subscription slots, model/effort policy and provider windows. Groups neither grant account/pool access nor change individual weights or scheduling turns. For example, a developer in a 100,000-credit Engineering group and a 20,000-credit Project group must fit both remaining group budgets and their own allowance.

- `dailyUnits` is a shared daily credit cap, using the same conservative reservations and actual-usage settlement as member/pool accounting. Zero adds no group daily cap.
- `maxConcurrent` limits the group's total unexpired active requests across its members and all replicas. Zero adds no group concurrency cap.
- `paused` belongs to the member's assignment in that group. A pause in any active group prevents their new admissions. Already running work finishes normally; no process or active stream is killed.
- Credits reset at midnight UTC. Expired leases release concurrency but retain uncertain reserved credits. Completed/uncertain charges remain subject to existing retention.

Admission checks and reservation creation run under the existing team lock. Each reservation records its original group IDs, UTC day and lease deadline in `reservation_groups`; actual units/state remain in `reservations`. Settlement updates that single source, including zero-cost failures and conservative missing usage. Removing/moving a member does not move, refund or erase original group charges. Rejoining the group sees those charges. Member and original pool spend also remain unchanged.

Only an administrator can archive a group. Archiving deactivates its constraints on new admissions and hides it from members/managers; history and assignments remain. Restoring it reactivates current assignments and their pause states, with earlier same-day group charges intact. This is an explicit administrative release of the cap, not a refund. Assignment writes to archived groups are refused; administrators can still remove old assignments.

Group concurrency waits through the shared bounded queue and yields the turn while blocked. Group budget exhaustion is an immediate `group_allowance` HTTP 429; an assignment pause is `group_paused` HTTP 403. Neither condition can be bypassed by account/provider fallback. The forecast controller uses group budget/pause availability and caps its advice at the strictest member/group ceiling. Group policy/assignment changes invalidate previous recovery context. Observation-only mode suppresses predictive enforcement/delivery, while hard group caps and pauses remain enforced.

Management is bounded to 64 groups per team including archived groups, 16 assignments per member including archived groups, and 200 members per group. Invalid caps are rejected: daily credits 0–100,000,000,000 and concurrency 0–128. These are deployment guardrails, not configurable allocations.

## API and typed client

Use an interactive bearer login for API management, or the HttpOnly dashboard cookie for browser forms. All dashboard writes validate the exact origin and CSRF token. Names/usernames are escaped in HTML. Responses are private/no-store.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/v1/groups` | List accessible groups / create a group |
| GET / PUT | `/api/v1/groups/{id}` | Read detail / replace group policy |
| PUT / DELETE | `/api/v1/groups/{id}/members/{memberId}` | Assign or update group role/pause / remove assignment |
| PATCH | `/api/v1/groups/{id}/members/{memberId}/pause` | Manager/admin admission control |

Create/replace policy: `{"name":"Engineering","dailyUnits":100000,"maxConcurrent":4,"archived":false}`. Assignment: `{"role":"MANAGER","paused":false}`; an existing assignment is updated. Pause: `{"paused":true}`. Unknown role values fail. Detail returns `{ "group": ..., "members": [...] }`; list returns group summaries. An ordinary member's detail contains only their own assignment. Capability: `team.groups.v1`.

`TeamCodeClient` exposes `groups`, `group`, `createGroup`, `saveGroup`, `assignGroupMember`, `removeGroupMember` and `pauseGroupMember`. It validates path identifiers locally. `capacity()` also includes the current member's active `groups` constraints, pause states and aggregate usage; `usage:read` service keys can inspect those own capacity fields without accessing a group roster. No separate CLI group command is currently published.

Audit events `group.created`/`group.updated` use the group ID. Assignment, removal and pause/resume events use `groupId/memberId` as `subjectId`; the generated migration widens that metadata field to 100 characters. Each successful write and its audit event commit together; denied writes append nothing. No credentials, prompts, request bodies or policy values enter these events.

## Upgrade and evidence

Deploy the group-aware application to **every replica** before relying on these caps: older binaries do not enforce group policy or record group snapshots. Existing pre-upgrade reservations retain their member/pool accounting; group history is not reconstructed retroactively. Keep the additive tables and widened audit column when rolling back; an older application does not provide equivalent group enforcement.

`GroupAdmissionTest` covers cumulative caps, zero semantics and policy validation. `GroupPersistenceTest` exercises two independent MySQL/Hikari pools, scoped permissions/revocation, original charges after moves/removal/archive/restore, actual settlement, lease expiry, concurrent races, paused queued work and observation-mode hard protection. `GroupRoutesTest` checks the typed SDK, dashboard escaping, CSRF/origin validation and service-key rejection. `DatabaseRestoreTest` includes nonempty group budgets, roles, paused assignments and original reservation charges in its actual dump/restore and restored HTTP access. The packaged two-process smoke checks shared group state, management, pause enforcement and restart persistence. No real provider requests are used by these fixtures.
