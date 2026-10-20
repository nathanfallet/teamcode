# Session guidance and delivery

TeamCode sends capacity advice at model boundaries so an agent can reduce optional work when the observed quota burn is high, or increase useful parallel work when fresh capacity supports it. Admission independently enforces budgets and concurrency. An advisory message never authorizes more quota or changes an agent's tool permissions.

## Claude integration

```sh
teamcode run claude -- --dangerously-skip-permissions
teamcode run claude --no-guidance -- --dangerously-skip-permissions
```

By default, the launcher creates a temporary plugin under the private configuration directory's `runs/` subdirectory and passes it with `--plugin-dir`. Existing command arguments, plugins and settings remain in place. The plugin contains only an HTTP hook configuration; its authentication references an environment variable and no credential is written into the plugin. The local capability stays scoped to the launched proxy or authenticated user service. The launcher's normal cleanup removes its own plugin directory. An abrupt launcher termination may leave a credential-free directory behind; no cleanup stops other processes.

The hooks run at `UserPromptSubmit` and `PreToolUse`, using Claude's supported [HTTP hook schema](https://code.claude.com/docs/en/hooks#http-hook-fields) and [session-only plugin loading](https://code.claude.com/docs/en/cli-reference). TeamCode requests a four-second hook timeout. Its local handler bounds input at 64 KiB and central lookup at 2.5 seconds, with a separate short receipt deadline. Oversized, unsupported or unavailable guidance returns an empty object. The handler sends no allow/deny decision and does not execute tool arguments.

Only the session ID and delivery stage reach the TeamCode management API. The local proxy discards prompts, tool inputs, working directories and transcript paths from hook payloads. The server hashes the session ID using the same identity as inference affinity, scopes lookup to the authenticated member and requires recent admitted activity. The first request of a new session may precede any advice; the controller must first collect a valid context and quota history.

Explicit `--bare` skips automatic guidance. Claude's managed hook restrictions, HTTP allowlists and disabled hooks remain authoritative and can prevent delivery. The launcher does not override those choices. Failure to prepare the optional plugin reports a short notice and continues with server-side limits.

## Codex integration

```sh
teamcode run codex --
teamcode run codex --no-guidance -- exec 'Your task'
```

The launcher adds invocation-only command hooks for `UserPromptSubmit` and `PreToolUse`, using the [Codex hook protocol](https://learn.chatgpt.com/docs/hooks). On first use, review and trust the TeamCode definitions in Codex's `/hooks` browser. TeamCode does not bypass hook trust, enable disabled hooks or override managed-only policies. Untrusted hooks remain inactive; hard server limits still apply. This integration is verified with Codex CLI 0.163.0; use `--no-guidance` with clients that do not support these hooks.

Each hook invokes the native `teamcode agents hook --config /absolute/path/to/config.json` command. It reads at most 64 KiB of redirected stdin, requires EOF within one second, validates the event/session and discards all other fields. Lookup has a 2.5-second deadline and acknowledgment a separate 750-millisecond deadline; the Codex hook timeout is five seconds. Malformed input, missing configuration, expired advice and outages emit `{}` without diagnostics. A terminal is never read. The command uses the same SDK and durable delivery contract as Claude's local HTTP hook.

Only the executable/configuration paths appear in the hook definition. No credentials, transient port or token appear in the command. The definition therefore stays stable across launches and token rotation. POSIX paths are shell-quoted; Windows invokes an encoded PowerShell script to preserve literal Unicode paths. The launcher adds the Windows PowerShell directory under `SystemRoot` to the child process PATH, so hooks also work when a terminal supplies only a minimal PATH; no machine or user environment setting is changed. The hook reads the private configuration itself, so a Codex daemon does not need to inherit an ephemeral proxy credential.

Existing hooks from active configuration files are merged by Codex; TeamCode does not edit those files. Overrides are inserted in the leaf command scope so `exec --ignore-user-config` retains TeamCode's invocation configuration. If the caller explicitly supplies `-c hooks...`/`--config hooks...`, the launcher preserves those overrides and reports that automatic guidance was skipped: Codex replaces arrays within one CLI layer. Add `teamcode agents hook` to that custom configuration to opt in without losing existing hooks. `--no-guidance` always disables TeamCode's automatic additions.

## Scoped state and receipts

Each client session has its own directive version, expiry and recovery hysteresis. The dashboard/member endpoint retains an aggregate view. Several targets within the same session use their most constrained advice. Sessions belonging to the same member can therefore receive different advice, while still sharing that member's hard quota and concurrency ceiling.

- `POST /api/v1/directives/current` with `sessionId` reads scoped advice; the existing GET reads the member aggregate.
- `POST /api/v1/directives/claim` with `sessionId` and `stage` atomically obtains a 15-second delivery claim for a fresh scoped version.
- `POST /api/v1/directives/receipt` acknowledges the exact directive, session, stage and claim ID. Revoked sessions and replaced/expired claims cannot acknowledge another delivery.

Two replicas or simultaneous hooks get at most one live claim for a version and stage. Once acknowledged, the version is suppressed. If a response or acknowledgment is lost, the claim expires and a later hook may repeat the message. This is retryable emission with deduplication, not exactly-once model processing. `RECEIVED_BY_CLI` and `EMITTED_TO_HOOK` are independent; neither claims agent compliance. All authoritative delivery state is in MySQL.

Delivery rows are bounded to 2,000 per member and 20,000 per deployment, with bounded cleanup after scoped directives expire. At the cap, new claims return no message; inference continues under hard limits. Raw session IDs and tool content are not persisted. The session table is separate from the existing one-row-per-member directive table, preserving old readers during rolling upgrades. New reservation metadata is additive.

## Other integrations and boundaries

```sh
teamcode agents watch --session SESSION_ID
teamcode agents hint --session SESSION_ID --format json
teamcode agents hint --session SESSION_ID --format claude
```

Watch uses the received-by-CLI stage; hint uses emitted-to-hook. Manual bridges must supply the session ID actually attached to inference. The native `teamcode agents hook` command also accepts a complete Codex/Claude hook JSON payload on stdin and preserves the supported event name in its output.

Claude and Codex subagents can share their parent's session ID. They currently share that session's aggregate advice and delivery record; the first matching hook receives a changed version. Independent fan-out to every subagent requires an additional consumer identity and remains explicit work. A receipt does not prove that every subagent received the instruction. Inference without a session header retains member-level limits and advice, but cannot receive automatic scoped messages.

## Verification

`RouteDirectivePersistenceTest` uses actual MySQL and independent connection pools to exercise distinct slow/fast sessions, scoped pacing alongside hard member limits, atomic claims, receipt deduplication, lost-claim recovery, owner isolation and token revocation. HTTP tests cover authentication and claim DTOs; local proxy tests prove that tool input does not leave the workstation and failures do not expose diagnostics.

The normal native launcher smoke inspects the generated plugin, calls the actual native hook endpoint, preserves literal user arguments and verifies cleanup. Windows acceptance includes the Unicode plugin path and loopback hook route in its npm launcher/service scenarios.

For the real Claude client without consuming a subscription:

```sh
python3 scripts/smoke-claude-guidance.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --claude /absolute/path/to/the/actual/claude/executable
```

This isolated fixture supplies deterministic inference and directive responses. It proves real hook loading, correlation between hook/inference session IDs, UserPromptSubmit/PreToolUse context injection, a harmless Bash tool round trip, receipts and plugin cleanup. It does not establish forecast accuracy under real quota consumption or prove model compliance with pacing. See [status](status.md) for completed runs and pending platform CI.

For the real Codex client, without consuming a subscription:

```sh
python3 scripts/smoke-codex-guidance.py \
  --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \
  --codex /absolute/path/to/the/actual/codex/executable
```

The isolated fixture runs a real `exec_command` tool round trip through deterministic Responses streams. It verifies both hook events, session correlation, receipts, preserved user-file hooks, literal executable/configuration paths, refusal to run untrusted hooks, `--no-guidance`, `--ignore-user-config` and preservation of explicit CLI hooks. Only this fixture uses the hook-trust bypass with its two known synthetic hooks and an empty temporary home; the product never adds that flag.

`scripts/smoke-hook.py` tests the native stdin bridge on every desktop CI platform, including exact byte limits, held-open stdin, lookup deadlines, deduplication and silent errors. Launcher acceptance also executes the actual generated hook command, including the encoded PowerShell command on Windows. The full real-client fixtures currently run on POSIX; their evidence does not imply a full real Codex Windows acceptance run.

## Observation mode

The administrator can enable [observation-only pacing](capacity.md#observation-only-controller-mode). Forecasts remain visible in the dashboard and informational preview API, but delivery reads/claims return no directive and predictive admission throttles are suspended. This applies to scoped and aggregate guidance, including manual hints and watchers. Hard team/provider limits remain enforced. Advice already emitted or in flight cannot be recalled.
