package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object MemberPolicies : Table("member_policies") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "member_policies_ibfk_1")
    val dailyUnits = long("daily_units").default(0)
    // Zero disables the member concurrency limit.
    val maxConcurrent = integer("max_concurrent").default(0)
    val poolId = varchar("pool_id", 36).nullable()
    val weight = integer("weight").default(1)
    val allowSharedOverflow = bool("allow_shared_overflow").default(false)
    override val primaryKey = PrimaryKey(memberId)
}
object PoolBudgets : Table("pool_budgets") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "pool_budgets_ibfk_1")
    val poolId = varchar("pool_id", 36)
    val dailyUnits = long("daily_units")
    val sharedOverflow = bool("shared_overflow").default(false)
    /** The automatic home pool size (ADR 0002), written by the scheduled job; [dailyUnits] stays the administrator's manual value. */
    val autoDailyUnits = long("auto_daily_units").nullable()
    val autoComputedAt = long("auto_computed_at").nullable()
    override val primaryKey = PrimaryKey(teamId, poolId)
}
object Reservations : Table("reservations") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "reservations_ibfk_1")
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "reservations_ibfk_2")
    val dayNumber = long("day_number")
    val units = long("units")
    val state = varchar("state", 16)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    val poolId = varchar("pool_id", 36).default("")
    val sessionKey = char("session_key", 64, "ascii_bin").nullable()
    val runId = reference("run_id", AgentRuns.id, onDelete = ReferenceOption.NO_ACTION).nullable()
    val providerRequestId = varchar("provider_request_id", 128).nullable()
    // Null on legacy rows means the logical pool was also the physical source.
    val sourcePoolId = varchar("source_pool_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index("member_daily", false, memberId, dayNumber)
        index("active_account", false, accountId, state, expiresAt)
        index("pool_daily", false, teamId, poolId, dayNumber)
        index("source_pool_daily", false, teamId, sourcePoolId, dayNumber)
        index("team_active", false, teamId, state, expiresAt)
        index("reservation_session", false, memberId, sessionKey, state, expiresAt)
        index("run_active", false, runId, state, expiresAt)
        index("reconciliation_pending", false, teamId, state, createdAt, id)
    }
}
object QuotaWindows : Table("quota_windows") {
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "quota_windows_ibfk_1")
    val windowName = varchar("window_name", 80)
    val utilization = double("utilization")
    val observedAt = long("observed_at")
    val resetsAt = long("resets_at").nullable()
    val windowStartedAt = long("window_started_at").default(0)
    override val primaryKey = PrimaryKey(accountId, windowName)
}
object QuotaHistory : Table("quota_history") {
    val id = long("id").autoIncrement()
    val accountId = varchar("account_id", 36)
    val windowName = varchar("window_name", 80)
    val utilization = double("utilization")
    val observedAt = long("observed_at")
    val resetsAt = long("resets_at").nullable()
    val sampleMinute = long("sample_minute")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("quota_sample", accountId, windowName, sampleMinute)
        index("quota_recent", false, accountId, windowName, observedAt)
    }
}
object QuotaModelLimits : Table("quota_model_limits") {
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE)
    val modelName = varchar("model_name", 128)
    val limitSlug = varchar("limit_slug", 64)
    val observedAt = long("observed_at")
    override val primaryKey = PrimaryKey(accountId, modelName)
}
object QuotaSnapshots : Table("quota_snapshots") {
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE)
    val modelWindowsObservedAt = long("model_windows_observed_at")
    override val primaryKey = PrimaryKey(accountId)
}
object GatewayTotals : Table("gateway_totals") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "gateway_totals_ibfk_1")
    val completed = long("completed").default(0)
    override val primaryKey = PrimaryKey(teamId)
}
/**
 * Raw provider tokens per UTC day, member, run, provider and model, added once per settled reservation. Independent of
 * the credit ledger and of reservation retention; an empty [runKey] means no registered run.
 */
object TokenUsageTable : Table("token_usage") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val dayNumber = long("day_number")
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val runKey = varchar("run_key", 36).default("")
    val provider = varchar("provider", 32)
    val model = varchar("model", 128)
    val requests = long("requests").default(0)
    val unmeteredRequests = long("unmetered_requests").default(0)
    val inputTokens = long("input_tokens").default(0)
    val cacheCreationTokens = long("cache_creation_tokens").default(0)
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val outputTokens = long("output_tokens").default(0)
    override val primaryKey = PrimaryKey(teamId, dayNumber, memberId, runKey, provider, model)
    init {
        index("token_usage_member", false, teamId, memberId, dayNumber)
    }
}
/**
 * The same raw tokens per subscription account, UTC hour, provider and model: the history a capacity estimate pairs
 * with the account's quota observations. Internal; no route reads it yet.
 */
object AccountTokenUsageTable : Table("account_token_usage") {
    val accountId = reference("account_id", Accounts.id, onDelete = ReferenceOption.CASCADE)
    val hourNumber = long("hour_number")
    val provider = varchar("provider", 32)
    val model = varchar("model", 128)
    val requests = long("requests").default(0)
    val unmeteredRequests = long("unmetered_requests").default(0)
    val inputTokens = long("input_tokens").default(0)
    val cacheCreationTokens = long("cache_creation_tokens").default(0)
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val outputTokens = long("output_tokens").default(0)
    override val primaryKey = PrimaryKey(accountId, hourNumber, provider, model)
}

/** A team's dated API price table, in micro-dollars per million tokens (ADR 0002). */
object ApiPricesTable : Table("api_prices") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 32)
    val model = varchar("model", 128)
    val effectiveFromDay = long("effective_from_day")
    val inputMicros = long("input_micros")
    val cacheWriteMicros = long("cache_write_micros")
    val cacheReadMicros = long("cache_read_micros")
    val outputMicros = long("output_micros")
    override val primaryKey = PrimaryKey(teamId, provider, model, effectiveFromDay)
}
