package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

/** Subscription accounts, capacity, budget overrides, usage review, groups, agent runs, guidance, forecasts and policies. */
internal fun ApiCatalog.capacityOperations() {
    accountOperations()
    allowanceOperations()
    groupOperations()
    runOperations()
    guidanceOperations()
    policyOperations()
}

private fun ApiCatalog.accountOperations() {
    get("/api/v1/accounts") {
        operationId = "listAccounts"; tag(ApiTags.ACCOUNTS)
        summary = "List the subscription accounts"
        description = "The team's provider accounts with their state: `READY` and `ACCESS_ONLY` serve requests, " +
            "`PENDING_HANDOVER` waits for `POST /api/v1/accounts/{id}/handover`, `RECONNECT_REQUIRED` must be added " +
            "again. No credential is ever returned. Administrators and `team:read` keys only."
        responses { ok<List<AccountView>>("The accounts.") }
    }
    post("/api/v1/accounts") {
        operationId = "enrollAccount"; tag(ApiTags.ACCOUNTS)
        summary = "Add or reconnect a subscription account"
        description = "Sends provider credentials obtained by `teamcode accounts add` or `import`. The server verifies " +
            "the subscription with the provider before storing them encrypted; credentials of a known account update it. " +
            "`freshSignIn: true` (a sign-in the CLI just ran) makes the account active at once; an import waits for a " +
            "handover. Retry with the same `operationId` after a network failure. A provider account is identified by its " +
            "provider, account and organization: adding it again updates the same account, never a second one. " +
            "Administrators, or every member when `GET /api/v1/policy/account-enrollment` says `members`: a member sends " +
            "only fresh sign-ins and replaces only the sign-in of an account they added or one that needs reconnecting " +
            "(`account_enrollment_forbidden`, `account_import_forbidden`, `account_in_use`)."
        body<EnrollAccountRequest>("The provider tokens, their expiry, a unique operation identifier (16 to 64 characters) and the provider (`anthropic` or `codex`).")
        responses {
            ok<EnrollAccountResponse>("An existing account was updated (`created: false`).")
            ok<EnrollAccountResponse>("A new account was created.", HttpStatusCode.Created)
            badRequest("Invalid credentials, operation identifier or provider (`invalid_credentials`, `invalid_operation`, `unsupported_provider`).")
            conflict("The credentials changed during the enrollment (`enrollment_conflict`): retry with a new operation.")
            error(HttpStatusCode.UnprocessableEntity, "The provider did not confirm a supported subscription (`provider_verification_failed`).")
        }
    }
    get("/api/v1/policy/account-enrollment") {
        operationId = "getAccountEnrollmentPolicy"; tag(ApiTags.ACCOUNTS)
        summary = "Who can add accounts"
        description = "`policy` is `administrators` (the default) or `members`, and `allowed` says whether the caller may " +
            "add an account with `teamcode accounts add`. Any ready interactive member can ask, so the CLI checks before " +
            "opening the provider sign-in."
        responses { ok<AccountEnrollmentPolicyView>("The setting and the caller's permission.") }
    }
    put("/api/v1/policy/account-enrollment") {
        operationId = "saveAccountEnrollmentPolicy"; tag(ApiTags.ACCOUNTS)
        summary = "Change who can add accounts"
        description = "`members` lets every member add a fresh sign-in and reconnect an account that needs it; account " +
            "settings, imports and handovers stay with administrators. A change is audited. Administrators only."
        body<AccountEnrollmentPolicyView>("The new `policy`; `allowed` is ignored.")
        responses {
            ok<AccountEnrollmentPolicyView>("The saved setting.")
            badRequest("Neither `administrators` nor `members` (`invalid_account_enrollment_policy`).")
        }
    }
    patch("/api/v1/accounts/{id}") {
        operationId = "updateAccount"; tag(ApiTags.ACCOUNTS)
        summary = "Rename, disable, prioritize or move an account"
        description = "Sets the display name, the disabled flag, the priority and the pool. Administrators only."
        parameters { pathId("id", "The account's identifier.") }
        body<UpdateAccountRequest>("The account's settings; every field is replaced.")
        responses {
            ok<AccountView>("The updated account.")
            notFound("No such account (`not_found`).")
        }
    }
    post("/api/v1/accounts/{id}/handover") {
        operationId = "handoverAccount"; tag(ApiTags.ACCOUNTS)
        summary = "Take over the refresh of an account"
        description = "TeamCode becomes the only refresher of the account's credentials, and the account starts serving. " +
            "Stop every other tool that refreshes them first (TeamClaude, another proxy). Administrators only."
        parameters { pathId("id", "The account's identifier.") }
        responses {
            ok<AccountView>("The account, now `READY`.")
            notFound("No such account (`not_found`).")
            conflict("The account must be reconnected first (`provider_reconnect`), or no longer waits for a handover (`handover_not_pending`).")
        }
    }
    post("/api/v1/accounts/{id}/access-only") {
        operationId = "useAccountAccessOnly"; tag(ApiTags.ACCOUNTS)
        summary = "Use an account without refreshing it"
        description = "For a trial while another tool still owns the refresh: TeamCode uses the current access token " +
            "and never refreshes it. Import fresh credentials when it expires. Administrators only."
        parameters { pathId("id", "The account's identifier.") }
        responses {
            ok<AccountView>("The account, now `ACCESS_ONLY`.")
            notFound("No such account (`not_found`).")
        }
    }
}

private fun ApiCatalog.allowanceOperations() {
    get("/api/v1/capacity") {
        operationId = "getCapacity"; tag(ApiTags.CAPACITY)
        summary = "The caller's allowance today"
        description = "The caller's effective daily allowance (in units: input tokens plus five times output tokens), " +
            "what is charged or reserved so far, active and queued requests, group limits, an active budget override and " +
            "when the day resets (00:00 UTC)."
        responses { ok<CapacityView>("The caller's capacity.") }
    }
    get("/api/v1/members/{id}/capacity") {
        operationId = "getMemberCapacity"; tag(ApiTags.CAPACITY)
        summary = "A member's allowance today"
        description = "The same view as `GET /api/v1/capacity`, for any member. Administrators and `team:read` keys only."
        parameters { pathId("id", "The member's identifier.") }
        responses {
            ok<CapacityView>("The member's capacity.")
            notFound("No such member (`member_not_found`).")
        }
    }
    patch("/api/v1/members/{id}/policy") {
        operationId = "updateMemberPolicy"; tag(ApiTags.CAPACITY)
        summary = "Set a member's capacity policy"
        description = "Sets the member's pool, weight (their share of the pool's daily budget; 0 grants nothing), hard " +
            "cap (`dailyUnits`, 0 for none), concurrency (`maxConcurrent`, 0 to 32; 0, the default, sets no per-member " +
            "limit, while group and launch limits still apply) and shared-overflow eligibility. Existing spend is kept. " +
            "Administrators only."
        parameters { pathId("id", "The member's identifier.") }
        body<PolicyRequest>("The member's policy.")
        responses {
            ok<CapacityView>("The member's capacity under the new policy.")
            badRequest("A negative or out-of-range value (`invalid_policy`).")
            notFound("No such member (`member_not_found`).")
            conflict("The pool is a shared overflow reserve and cannot be a primary pool (`overflow_primary_pool`).")
        }
    }
    get("/api/v1/pools/accounts") {
        operationId = "listPoolAccounts"; tag(ApiTags.CAPACITY)
        summary = "List the pool accounts the caller can use"
        description = "The subscription accounts of the caller's pool and of the shared overflow pools it opted into, " +
            "with their current provider windows and whether they can serve now. `unavailableReason` is one of `disabled`, " +
            "`pending_handover`, `reconnect_required`, `cooling_down` or `quota_exhausted`. Interactive administrators and " +
            "`team:read` keys see every account with its name and email; members and other service keys see neutral labels."
        responses { ok<List<PoolAccountStatusView>>("The accounts, by priority.") }
    }
    get("/api/v1/usage/tokens") {
        operationId = "listTokenUsage"; tag(ApiTags.CAPACITY)
        summary = "Raw token usage"
        description = "The provider's own token counts per UTC day, member, agent launch (`runId`, null outside a " +
            "registered launch), provider and model: uncached input, cache writes, cache reads and output (reasoning " +
            "included), never converted into credits or money. Requests that ended without provider usage are counted in " +
            "`unmeteredRequests` and not in the token sums. A member reads their own rows; an interactive administrator " +
            "or a `team:read` key reads one member's (`member`) or the team's (`all=true`). Page with `after` = `nextAfter`."
        parameters {
            queryText("from", "First UTC day, `YYYY-MM-DD` (six days before `to` by default).")
            queryText("to", "Last UTC day, `YYYY-MM-DD`, included (today by default). At most 366 days in all.")
            queryText("member", "A member's identifier (administrators, `team:read`).")
            queryFlag("all", "Every member's rows (administrators, `team:read`).")
            queryText("after", "The `nextAfter` of the previous page.")
        }
        responses {
            ok<TokenUsagePageView>("Up to 500 rows ordered by day, member, launch, provider and model.")
            badRequest("An invalid date range (`invalid_window`), `all` with `member` (`invalid_usage_query`) or cursor (`invalid_cursor`).")
            notFound("The member is not in the team (`not_found`).")
        }
    }
    get("/api/v1/pools/budgets") {
        operationId = "listPoolBudgets"; tag(ApiTags.CAPACITY)
        summary = "List the pool budgets"
        description = "The daily budget of each pool and whether it lends its unused credit (shared overflow). Administrators and `team:read` keys only."
        responses { ok<List<PoolBudgetView>>("The pools.") }
    }
    put("/api/v1/pools/budgets") {
        operationId = "savePoolBudget"; tag(ApiTags.CAPACITY)
        summary = "Create or update a pool budget"
        description = "Sets one pool's daily budget and shared-overflow flag. Administrators only."
        body<PoolBudgetView>("The pool and its budget.")
        responses {
            ok<PoolBudgetView>("The saved pool.")
            badRequest("An invalid pool name or budget (`invalid_policy`).")
            conflict("A reserve with primary members cannot be shared, or 16 overflow pools exist (`overflow_primary_pool`, `overflow_pool_limit`).")
        }
    }
    get("/api/v1/pools/size") {
        operationId = "getPoolSize"; tag(ApiTags.CAPACITY)
        summary = "The automatic home pool size"
        description = "Whether the home pool is sized from the subscriptions (`autoPoolSize` on `PUT /api/v1/policy/pacing`), " +
            "the manual and automatic budgets, the one admission uses now, and the estimate behind it: each home pool account's " +
            "daily capacity in API-equivalent micro-dollars and its learned windows. Administrators and `team:read` keys only."
        responses { ok<PoolSizeView>("The sizing.") }
    }
    get("/api/v1/policy/api-prices") {
        operationId = "getApiPrices"; tag(ApiTags.CAPACITY)
        summary = "The team's API price table"
        description = "Dated prices per provider and model (or family prefix), in micro-dollars per million tokens, used for the " +
            "API-equivalent of the token ledger and the automatic pool size. Members and `usage:read` keys may read it."
        responses { ok<ApiPriceTableView>("The table.") }
    }
    put("/api/v1/policy/api-prices") {
        operationId = "saveApiPrices"; tag(ApiTags.CAPACITY)
        summary = "Replace the API price table"
        description = "Replaces every row. Keep earlier rows so past days keep their price. Interactive administrators only; audited."
        body<ApiPriceTableView>("The whole table.")
        responses {
            ok<ApiPriceTableView>("The saved table.")
            badRequest("An invalid provider, model, date or price, a duplicate row or more than 200 rows (`invalid_api_price`).")
        }
    }
    post("/api/v1/policy/api-prices/load-defaults") {
        operationId = "loadDefaultApiPrices"; tag(ApiTags.CAPACITY)
        summary = "Load Anthropic's current list prices"
        description = "Adds Anthropic's pay-as-you-go list prices for the current Claude models, as read from the public pricing " +
            "page on the date the rows carry (cache writes at the five-minute rate). A row already present for the same model " +
            "and date is kept, so loading twice changes nothing. No price applies until an administrator loads or enters it. " +
            "Interactive administrators only; audited (`capacity.api_prices_defaults_loaded`)."
        responses {
            ok<ApiPriceTableView>("The table with the list prices added.")
            badRequest("The table would exceed 200 rows (`invalid_api_price`).")
        }
    }
    get("/api/v1/quotas") {
        operationId = "listQuotas"; tag(ApiTags.CAPACITY)
        summary = "Provider quota windows"
        description = "The latest observed utilization (0 to 1) of each account's provider windows (five-hour, weekly, " +
            "per model), with their reset times. Administrators and `team:read` keys only."
        responses { ok<List<QuotaView>>("The windows.") }
    }
    get("/api/v1/members/{memberId}/budget-overrides") {
        operationId = "listBudgetOverrides"; tag(ApiTags.BUDGET_OVERRIDES)
        summary = "A member's budget overrides"
        description = "The member's overrides, newest first, with the active one. Page with `before` = `nextBeforeId`. " +
            "Administrators only."
        parameters {
            pathId("memberId", "The member's identifier.")
            queryText("before", "The `nextBeforeId` of the previous page.")
        }
        responses {
            ok<BudgetOverridePageView>("A page of overrides.")
            notFound("No such member (`member_not_found`).")
        }
    }
    post("/api/v1/members/{memberId}/budget-overrides") {
        operationId = "grantBudgetOverride"; tag(ApiTags.BUDGET_OVERRIDES)
        summary = "Grant a temporary daily allowance"
        description = "Replaces the member's total daily allowance until the deadline (at most the next 00:00 UTC). " +
            "0 stops new requests. `replacesId` must name the active override to replace it. The reason is audited. " +
            "Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        body<BudgetOverrideRequest>("The allowance, its duration in minutes and the reason.")
        responses {
            ok<BudgetOverrideView>("Granted.", HttpStatusCode.Created)
            badRequest("An invalid allowance, duration or reason (`invalid_budget_override`).")
            notFound("No such member (`member_not_found`).")
            conflict("The active override changed or expired: refresh and name it in `replacesId` (`budget_override_changed`), or the history limit is reached (`budget_override_limit`).")
        }
    }
    post("/api/v1/members/{memberId}/budget-overrides/{id}/revoke") {
        operationId = "revokeBudgetOverride"; tag(ApiTags.BUDGET_OVERRIDES)
        summary = "Revoke a budget override"
        description = "Ends the override now; the member's normal allowance applies again. Administrators only."
        parameters {
            pathId("memberId", "The member's identifier.")
            pathId("id", "The override's identifier.")
        }
        body<RevokeBudgetOverrideRequest>("The audited reason.")
        responses {
            ok<BudgetOverrideView>("The revoked override.")
            notFound("No such override (`not_found`).")
            conflict("The override already expired (`budget_override_expired`).")
        }
    }
    get("/api/v1/usage/pending") {
        operationId = "listPendingUsage"; tag(ApiTags.USAGE)
        summary = "Requests with unknown usage"
        description = "Requests whose provider usage was never received (an interrupted stream): they stay charged at " +
            "their estimate until reconciled. Page with `before` = `nextBeforeId`. Administrators only."
        parameters {
            queryText("member", "Only this member's requests.")
            queryText("before", "The `nextBeforeId` of the previous page.")
        }
        responses { ok<PendingUsagePageView>("A page of requests.") }
    }
    get("/api/v1/usage/reconciliations") {
        operationId = "listReconciliations"; tag(ApiTags.USAGE)
        summary = "Reconciled usage"
        description = "Past manual reconciliations, newest first. Administrators only."
        parameters {
            queryText("member", "Only this member's reconciliations.")
            queryText("before", "The `nextBeforeId` of the previous page.")
        }
        responses { ok<ReconciledUsagePageView>("A page of reconciliations.") }
    }
    get("/api/v1/usage/reservations/{id}") {
        operationId = "getReservation"; tag(ApiTags.USAGE)
        summary = "One request's usage"
        description = "A request still pending, or its reconciliation. Administrators only."
        parameters { pathId("id", "The reservation's identifier.") }
        responses {
            ok<UsageReviewView>("The pending request or its reconciliation.")
            notFound("No such reservation (`not_found`).")
        }
    }
    post("/api/v1/usage/reservations/{id}/reconcile") {
        operationId = "reconcileReservation"; tag(ApiTags.USAGE)
        summary = "Settle a request's usage"
        description = "Replaces the estimate with the actual usage, read from the provider (`PROVIDER_RECORD` or " +
            "`PROVIDER_CONFIRMATION`, with its reference). `expectedUnits` must match the current charge. Administrators only."
        parameters { pathId("id", "The reservation's identifier.") }
        body<UsageReconciliationRequest>("The expected and actual units, the evidence and the reason.")
        responses {
            ok<ReconciledUsageView>("Reconciled.")
            badRequest("Invalid units or evidence (`invalid_reconciliation`).")
            notFound("No such reservation (`not_found`).")
            conflict("The request is active or already final (`usage_not_pending`), its charge changed (`usage_changed`), or it was already reconciled (`usage_already_reconciled`).")
        }
    }
}

private fun ApiCatalog.groupOperations() {
    get("/api/v1/groups") {
        operationId = "listGroups"; tag(ApiTags.GROUPS)
        summary = "List the groups"
        description = "Administrators see every group; a manager sees the groups they manage. With today's usage."
        responses { ok<List<GroupView>>("The groups.") }
    }
    post("/api/v1/groups") {
        operationId = "createGroup"; tag(ApiTags.GROUPS)
        summary = "Create a group"
        description = "A group adds cumulative daily (`dailyUnits`) and concurrency caps over its members; 0 adds no " +
            "limit. Administrators only."
        body<GroupPolicyRequest>("The group's name and caps.")
        responses {
            ok<GroupView>("Created.", HttpStatusCode.Created)
            badRequest("Invalid name or caps (`invalid_group`).")
            conflict("The team's group limit is reached (`group_limit`).")
        }
    }
    get("/api/v1/groups/{id}") {
        operationId = "getGroup"; tag(ApiTags.GROUPS)
        summary = "A group and its members"
        description = "The group with its roster. Administrators, or the group's managers."
        parameters { pathId("id", "The group's identifier.") }
        responses {
            ok<GroupDetailView>("The group.")
            notFound("No such group (`not_found`).")
        }
    }
    put("/api/v1/groups/{id}") {
        operationId = "updateGroup"; tag(ApiTags.GROUPS)
        summary = "Change a group"
        description = "Replaces the name and caps. `archived: true` releases the group's limits; restoring it keeps " +
            "today's charges. Administrators only."
        parameters { pathId("id", "The group's identifier.") }
        body<GroupPolicyRequest>("The group's name, caps and archived flag.")
        responses {
            ok<GroupView>("The updated group.")
            badRequest("Invalid name or caps (`invalid_group`).")
            notFound("No such group (`not_found`).")
        }
    }
    put("/api/v1/groups/{id}/members/{memberId}") {
        operationId = "assignGroupMember"; tag(ApiTags.GROUPS)
        summary = "Add a member to a group, or change their group role"
        description = "Adds or updates a membership: `role` is `MEMBER` or `MANAGER` (managers pause and resume the " +
            "group's members). Administrators only."
        parameters {
            pathId("id", "The group's identifier.")
            pathId("memberId", "The member's identifier.")
        }
        body<GroupAssignmentRequest>("The group role and paused flag.")
        responses {
            ok<GroupDetailView>("The group.")
            badRequest("Unknown group role (`invalid_group_role`).")
            notFound("No such group or member (`not_found`, `member_not_found`).")
            conflict("The group is archived (`group_archived`), or a roster limit is reached (`group_limit`).")
        }
    }
    delete("/api/v1/groups/{id}/members/{memberId}") {
        operationId = "removeGroupMember"; tag(ApiTags.GROUPS)
        summary = "Remove a member from a group"
        description = "Ends the membership; today's charges stay with the group. Administrators only."
        parameters {
            pathId("id", "The group's identifier.")
            pathId("memberId", "The member's identifier.")
        }
        responses {
            ok<SuccessResponse>("Removed.")
            notFound("No such group or membership (`not_found`).")
        }
    }
    patch("/api/v1/groups/{id}/members/{memberId}/pause") {
        operationId = "pauseGroupMember"; tag(ApiTags.GROUPS)
        summary = "Pause or resume a member in a group"
        description = "A paused member's new requests are refused while the membership is paused. Administrators, or the group's managers."
        parameters {
            pathId("id", "The group's identifier.")
            pathId("memberId", "The member's identifier.")
        }
        body<GroupPauseRequest>("Whether the member is paused.")
        responses {
            ok<GroupDetailView>("The group.")
            notFound("No such group or membership (`not_found`).")
        }
    }
}

private fun ApiCatalog.runOperations() {
    post("/api/v1/runs") {
        operationId = "startRun"; tag(ApiTags.RUNS)
        summary = "Register an agent launch"
        description = "Called by `teamcode run` when it starts Claude or Codex: the launch's requests are then " +
            "attributed to it and its policy applies. The launcher generates the identifier and heartbeats it. " +
            "`labels` (`team.runs.labels.v1`) are the cloud sessions' model: at most 16 non-secret pairs, lowercase keys of up to 63 characters, values up to 256."
        body<StartRunRequest>("The launch's UUID, the agent (`claude` or `codex`), the workload (`interactive` or `background`) and optional labels.")
        responses {
            ok<RunLeaseView>("Registered.", HttpStatusCode.Created)
            badRequest("Invalid identifier, agent, workload (`invalid_run`) or label (`invalid_label`).")
            conflict("The identifier is already used (`run_conflict`), or the registry limit is reached (`run_limit`).")
        }
    }
    get("/api/v1/runs") {
        operationId = "listRuns"; tag(ApiTags.RUNS)
        summary = "List agent launches"
        description = "The caller's launches, newest first. Administrators, and `team:read` keys, can read one member's (`member`) or every " +
            "member's (`all=true`); a `usage:read` key stays limited to its owner's. Page with `before` = `nextBeforeId`. Every launch carries its `labels`."
        parameters {
            queryText("member", "A member's identifier (administrators, `team:read`).")
            queryFlag("all", "Every member's launches (administrators, `team:read`).")
            queryText("before", "The `nextBeforeId` of the previous page.")
            queryList("label", "Only launches with these labels, each as `key=value` (`team.runs.labels.v1`).")
        }
        responses {
            ok<RunPageView>("A page of launches.")
            badRequest("An invalid label filter (`invalid_label`).")
        }
    }
    get("/api/v1/runs/{id}") {
        operationId = "getRun"; tag(ApiTags.RUNS)
        summary = "One agent launch"
        description = "The launch with its usage, policy and recent conversations (models, pacing delivered)."
        parameters { pathId("id", "The launch's UUID.") }
        responses {
            ok<RunDetailView>("The launch.")
            notFound("No such launch for this caller (`run_not_found`).")
        }
    }
    post("/api/v1/runs/{id}/heartbeat") {
        operationId = "heartbeatRun"; tag(ApiTags.RUNS)
        summary = "Keep a launch alive"
        description = "Sent by the launcher at least every 90 seconds; without it the launch becomes `STALE`."
        parameters { pathId("id", "The launch's UUID.") }
        responses {
            ok<RunLeaseView>("The launch's lease.")
            notFound("No such launch (`run_not_found`).")
            conflict("The launch has ended (`run_ended`).")
        }
    }
    post("/api/v1/runs/{id}/end") {
        operationId = "endRun"; tag(ApiTags.RUNS)
        summary = "End a launch"
        description = "Records the agent's exit; later requests of the launch are refused."
        parameters { pathId("id", "The launch's UUID.") }
        body<EndRunRequest>("The agent's exit code, if known.")
        responses {
            ok<RunLeaseView>("The ended launch.")
            notFound("No such launch (`run_not_found`).")
        }
    }
    put("/api/v1/runs/{id}/policy") {
        operationId = "setRunPolicy"; tag(ApiTags.RUNS)
        summary = "Limit a launch"
        description = "Sets the launch's own budget (`budgetUnits`, 0 for none), concurrency (0 for none) and paused " +
            "flag, on top of the member's limits. Administrators only."
        parameters { pathId("id", "The launch's UUID.") }
        body<RunPolicyView>("The launch's policy.")
        responses {
            ok<AgentRunView>("The launch under its new policy.")
            badRequest("Negative limits (`invalid_run_policy`).")
            notFound("No such launch (`run_not_found`).")
        }
    }
}

private fun ApiCatalog.guidanceOperations() {
    get("/api/v1/directives/current") {
        operationId = "getCurrentDirective"; tag(ApiTags.GUIDANCE)
        summary = "The caller's current pacing directive"
        description = "Advice computed from the quota forecasts: `pace` is `NORMAL`, `SLOW_DOWN`, `CAN_INCREASE` or " +
            "`PAUSE`, with a suggested concurrency ceiling (`maxConcurrent`, 0 for no ceiling) and a minimum interval " +
            "between admissions (`minIntervalMillis`, 0 for none). `directive` is null when there is no advice. Advice " +
            "never grants more allowance."
        responses { ok<DirectiveResponse>("The directive, or null.") }
    }
    post("/api/v1/directives/current") {
        operationId = "getSessionDirective"; tag(ApiTags.GUIDANCE)
        summary = "The pacing directive for an agent session"
        description = "The directive for one agent session (identified as in inference requests), without claiming its delivery."
        body<DirectiveScope>("The agent's session identifier.")
        responses { ok<DirectiveResponse>("The directive, or null.") }
    }
    post("/api/v1/directives/claim") {
        operationId = "claimDirective"; tag(ApiTags.GUIDANCE)
        summary = "Claim a directive for delivery"
        description = "Used by the agent hooks: returns the directive to show at this stage of the session, once, with " +
            "a claim to acknowledge."
        body<DirectiveClaimRequest>("The session identifier and the delivery stage.")
        responses { ok<DirectiveClaimResponse>("The directive and its claim, or nulls.") }
    }
    post("/api/v1/directives/receipt") {
        operationId = "acknowledgeDirective"; tag(ApiTags.GUIDANCE)
        summary = "Acknowledge a directive"
        description = "Records that the directive reached the agent at the given stage."
        body<DirectiveReceipt>("The directive, the stage and, for a claimed delivery, the session and claim.")
        responses {
            ok<SuccessResponse>("Recorded.")
            conflict("The directive expired or was replaced (`directive_expired`), or the claim did (`claim_expired`).")
        }
    }
    get("/api/v1/directives/preview") {
        operationId = "previewDirective"; tag(ApiTags.GUIDANCE)
        summary = "Preview the current directive"
        description = "The team's pacing settings and the directive the caller would receive now. Informational: " +
            "nothing is delivered. Administrators only."
        responses { ok<PacingPreviewResponse>("The settings and the directive.") }
    }
    get("/api/v1/policy/pacing") {
        operationId = "getPacingSettings"; tag(ApiTags.GUIDANCE)
        summary = "The team's pacing settings"
        description = "Whether predictive pacing only observes, and the quota pressure threshold (50 to 100 %) above " +
            "which an account is excluded until its reset. Administrators only."
        responses { ok<PacingSettingsView>("The settings.") }
    }
    put("/api/v1/policy/pacing") {
        operationId = "savePacingSettings"; tag(ApiTags.GUIDANCE)
        summary = "Change the pacing settings"
        description = "An omitted `quotaPressurePercent` or `autoPoolSize` keeps the stored value. Hard quotas and limits stay enforced in " +
            "observation mode. Administrators only."
        body<PacingSettingsView>("The settings.")
        responses {
            ok<PacingSettingsView>("The saved settings.")
            badRequest("The threshold is not a whole percentage from 50 to 100 (`invalid_quota_pressure`).")
        }
    }
    get("/api/v1/forecasts") {
        operationId = "getForecasts"; tag(ApiTags.FORECASTS)
        summary = "Quota history and forecasts"
        description = "Measured utilization of one account's windows and when each is forecast to reach the pressure " +
            "threshold. Without `account`, the list of accounts and the first one's windows. Administrators only."
        parameters { queryText("account", "The account to show (once).") }
        responses {
            ok<ForecastSnapshotView>("The snapshot.")
            badRequest("`account` was sent more than once (`invalid_account`).")
            notFound("No such account (`not_found`).")
        }
    }
}

private fun ApiCatalog.policyOperations() {
    get("/api/v1/policy/models") {
        operationId = "getModelPolicy"; tag(ApiTags.POLICY)
        summary = "The team's model policy"
        description = "Allowed and denied model patterns, allowed efforts, the thinking-token ceiling and whether " +
            "experimental cross-provider translation is on. Readable by every member."
        responses { ok<ModelPolicyView>("The policy.") }
    }
    put("/api/v1/policy/models") {
        operationId = "saveModelPolicy"; tag(ApiTags.POLICY)
        summary = "Change the model policy"
        description = "Replaces the whole policy. Requests to a denied or unlisted model, or with a refused effort, " +
            "are refused. Administrators only."
        body<ModelPolicyView>("The policy.")
        responses {
            ok<ModelPolicyView>("The saved policy.")
            badRequest("An invalid pattern, effort or thinking limit (`invalid_policy`, `invalid_thinking`).")
        }
    }
    get("/api/v1/routes") {
        operationId = "listRoutes"; tag(ApiTags.POLICY)
        summary = "List the model routes"
        description = "Ordered rules that replace a requested model (by pattern, such as `claude-opus-*`) with " +
            "provider targets tried in order. Administrators only."
        responses { ok<List<RouteRuleView>>("The routes.") }
    }
    put("/api/v1/routes/{id}") {
        operationId = "saveRoute"; tag(ApiTags.POLICY)
        summary = "Create or update a model route"
        description = "`id` must equal the path's identifier. `version` is the current version (0 to create). Administrators only."
        parameters { pathId("id", "The route's identifier.") }
        body<RouteRuleView>("The route.")
        responses {
            ok<RouteRuleView>("The saved route.")
            badRequest("Mismatched identifier, or an invalid pattern or target (`invalid_route`).")
            conflict("The route changed since `version` was read (`route_conflict`).")
        }
    }
    delete("/api/v1/routes/{id}") {
        operationId = "deleteRoute"; tag(ApiTags.POLICY)
        summary = "Delete a model route"
        description = "Requires the route's current version. Administrators only."
        parameters { pathId("id", "The route's identifier.") }
        body<RouteDeleteRequest>("The route's current version.")
        responses {
            ok<SuccessResponse>("Deleted.")
            notFound("No such route (`not_found`).")
            conflict("The route changed since `version` was read (`route_conflict`).")
        }
    }
}
