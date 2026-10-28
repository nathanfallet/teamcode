package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * One row per automation (docs/automations.md). [secret] and [previousSecret] are sealed in the `automation:<id>`
 * context; [credentialId] is the owner credential, a [Tokens] row the owner's revocations delete (the link is then null).
 * [variables] is the JSON list of declared variables; [samplePayload] the JSON sample the editor and tests pre-fill.
 */
object Automations : Table("automations") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val ownerId = reference("owner_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "automations_owner")
    val name = varchar("name", 120)
    val triggerKind = varchar("trigger_kind", 16)
    val cron = varchar("cron", 200).nullable()
    val timezone = varchar("timezone", 64).nullable()
    val catchup = varchar("catchup", 8)
    val catchupGraceMinutes = integer("catchup_grace_minutes")
    val actionKind = varchar("action_kind", 16)
    val profileId = varchar("profile_id", 36).nullable()
    val model = varchar("model", 128).nullable()
    val effort = varchar("effort", 32).nullable()
    val maxRuntimeMinutes = integer("max_runtime_minutes")
    val prompt = mediumText("prompt")
    val variables = text("variables")
    val samplePayload = mediumText("sample_payload").nullable()
    val overlap = varchar("overlap", 8)
    val maxRunsPerDay = integer("max_runs_per_day")
    val enabled = bool("enabled")
    val disabledReason = varchar("disabled_reason", 64).nullable()
    val disabledBy = varchar("disabled_by", 36).nullable()
    val version = integer("version")
    /** The next slot of an enabled cron automation; null otherwise. */
    val nextRunAt = long("next_run_at").nullable()
    val consecutiveFailures = integer("consecutive_failures")
    val secret = text("secret").nullable()
    val previousSecret = text("previous_secret").nullable()
    val previousSecretUntil = long("previous_secret_until").nullable()
    val secretRotatedAt = long("secret_rotated_at")
    val credentialId = optReference("credential_id", Tokens.id, onDelete = ReferenceOption.SET_NULL, onUpdate = ReferenceOption.NO_ACTION, fkName = "automations_credential")
    /** The newest run, for the list's `lastRun`. */
    val lastRunId = varchar("last_run_id", 36).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
    init {
        index("automation_owner", false, teamId, ownerId, createdAt)
        index("automation_team", false, teamId, createdAt)
        // The scheduler's scan: enabled cron automations by slot.
        index("automation_due", false, enabled, triggerKind, nextRunAt)
    }
}

/**
 * One trigger of an automation. [deliveryKey] is unique per automation (`cron:<slot>`, `webhook:<key>`,
 * `body:<sha>:<window>`, `test:<member>:<key>`); [prompt] is the rendered prompt, kept only while the run waits for its
 * session. [nextAttemptAt] is the dispatcher's lease and retry time of a queued run.
 */
object AutomationRuns : Table("automation_runs") {
    val id = varchar("id", 36)
    val automationId = reference("automation_id", Automations.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "automation_runs_automation")
    val teamId = varchar("team_id", 36)
    val ownerId = varchar("owner_id", 36)
    val automationVersion = integer("automation_version")
    val runSource = varchar("source", 8)
    val deliveryKey = varchar("delivery_key", 200)
    val status = varchar("status", 16)
    val reason = varchar("reason", 64).nullable()
    val prompt = mediumText("prompt").nullable()
    /** JSON list of `{name, type, value, sensitive, present}`; a sensitive value is never stored. */
    val variables = text("variables")
    val variablesDigest = char("variables_digest", 64).nullable()
    val requestDigest = char("request_digest", 64).nullable()
    val sessionId = varchar("session_id", 36).nullable()
    val profileVersion = integer("profile_version").nullable()
    val scheduledFor = long("scheduled_for").nullable()
    val queuedAt = long("queued_at")
    val startedAt = long("started_at").nullable()
    val endedAt = long("ended_at").nullable()
    val nextAttemptAt = long("next_attempt_at")
    val cancelRequested = bool("cancel_requested")
    /** Whether the run's session was deleted to keep the automation's latest ones only. */
    val sessionReleased = bool("session_released")
    val initiatorId = varchar("initiator_id", 36).nullable()
    val credentialId = varchar("credential_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("automation_run_delivery", automationId, deliveryKey)
        // The history, the daily budget and the minute rate: an automation's runs by time.
        index("automation_run_history", false, automationId, queuedAt, id)
        // The overlap policy: an automation's active runs.
        index("automation_run_active", false, automationId, status)
        // The dispatcher: queued runs due, and working runs to follow.
        index("automation_run_due", false, status, nextAttemptAt)
        // The owner's automation cap.
        index("automation_run_owner", false, ownerId, status)
        index("automation_run_retention", false, sessionReleased, endedAt)
    }
}
