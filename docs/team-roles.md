# Team roles and ownership

TeamCode uses administrator-created usernames/passwords, with no public signup or email delivery. A new deployment creates username `admin` with the effective role **OWNER**, logs its temporary password once after creation commits, and requires a password change. Restart never resets or reprints it.

| Identity | Authority |
| --- | --- |
| `OWNER` | All administration, plus granting/removing ownership and recovering an owner's password |
| `ADMIN` | Members, non-owner recovery, accounts (members may also add and reconnect them when [allowed](capacity.md#who-can-add-accounts)), policies, groups, devices, keys and operational views; no owner account changes or ownership grants |
| `MEMBER` | Own authorized resources and inference within assigned budgets/pools/model policy |
| Group `MANAGER` | An additional scoped assignment, not a global role; own group roster and admission pause/resume only |
| Service/CI key | Selected scopes on its member's resources; no interactive administration, even for an owner-owned key; an administrator's `team:read` key adds a read-only view of the team, never a write |

Owners remain subject to the same member/group/pool quotas and provider policy as everyone else. Ownership does not grant a separate inference budget. Global roles, scoped group roles and credential scopes are independent.

## Preserving recovery access

There must remain another **enabled owner who has changed their temporary password** before an owner can be disabled, demoted or have their password reset. A pending new owner does not satisfy this condition. The transaction returns `last_owner` HTTP 409 and makes no write when that condition fails. A sole owner can change their own password through the ordinary **Password** page, using the current password; the administrative reset operation cannot replace that flow.

Only a currently authenticated interactive owner can create/promote another owner, change any owner account, or reset an owner's password. Administrators receive `owner_required` HTTP 403 for these operations, including self-promotion. Service keys cannot perform them. Role/disable/password mutations share the team lock and revalidate current authentication. Two owners concurrently trying to relinquish their roles cannot both succeed.

Every global role or disabled-state write revokes the affected member's interactive sessions and service keys. Their devices, group assignments, reservations, weighted policies and historical charges remain. An ownership grant therefore requires the promoted member to sign in again; a copied old token cannot gain new owner authority.

## Dashboard and API

In **Team**, owners can select `OWNER`, `ADMIN` or `MEMBER` when creating/editing a member. Administrators see owner rows as read-only and cannot select `OWNER` for anyone. Server permissions enforce these rules independently of the controls. Browser writes require the exact dashboard origin and CSRF token.

The existing API contracts accept role `OWNER`:

- `POST /api/v1/members` with `{"username":"co-owner","role":"OWNER"}` creates a pending owner and returns its one-time temporary password.
- `PATCH /api/v1/members/{id}` with `{"role":"OWNER","disabled":false}` promotes an existing member and revokes their current credentials.
- `PATCH /api/v1/members/{id}` with `{"role":"ADMIN","disabled":false}` relinquishes ownership once another ready owner exists.
- `POST /api/v1/members/{id}/reset-password` keeps the owner and recovery restrictions above.

Login, identity and member responses expose `role: "OWNER"`. API contracts use a role string, so the existing native client can display it without a new runtime enum. There is no separate service-identity global role granting broad API powers.

To transfer ownership: promote the intended member, have them sign in/change their temporary password if needed, confirm their owner access, then demote the previous owner. Each write is atomic and audited. Multiple ready owners may be retained. There is no automatic owner deletion or administrator takeover.

Events `member.owner_granted` and `member.owner_removed` record successful ownership changes, alongside the normal member events. Bootstrap remains `member.bootstrap`; legacy upgrade records `member.owner_migrated` with actor `system`. Events contain identifiers, not passwords or before/after policy values. Denied changes append no success event.

## Existing deployments and rollback

The Exposed-generated migration adds `members.is_owner` (default false) and the team/owner/readiness index. The legacy base-role column remains `ADMIN`/`MEMBER`; new code derives effective ownership from the flag. Older binaries can read these rows during rollout instead of failing on an unknown database enum. **Every replica must be upgraded before relying on owner protections:** older versions still permit ordinary administrators to perform their previous member/recovery operations.

At the first upgraded bootstrap on an existing team with no owner flag, a team-locked transaction chooses one existing enabled administrator. It prefers administrators with a changed password, then username `admin`, then stable member ID. It marks that same account as owner, preserves its password/hash, devices, policies and ledgers, revokes its existing sessions/service keys, and records one migration event. The account signs in again using its existing password. No new password is generated or printed. Other administrators keep their roles and credentials. If no enabled legacy administrator exists, startup fails rather than assigning ownership to an ordinary member. An existing owner flag prevents this upgrade from running again; it is not a recurring recovery bypass.

Keep the additive schema when rolling back. Older applications see owners as administrators and do not provide the ownership restrictions, so a rollback is not equivalent authorization behavior. Restoring a group/owner-aware backup preserves owner flags and does not trigger another ownership grant or credential revocation. Keep ownership/recovery verification in the restoration checklist.

`MemberAccessTest` covers authority and last-owner rules. `OwnerPersistenceTest` uses independent MySQL pools for current permissions, competing demotions, pending-owner readiness, session/key revocation, retained quotas/groups and a real older-schema upgrade. `OwnerRoutesTest` covers API/browser controls and service-key/CSRF refusal. `DatabaseRestoreTest` checks restored ownership, while the packaged two-process smoke checks bootstrap ownership and last-owner refusals through the actual server. These fixtures use no real provider calls.
