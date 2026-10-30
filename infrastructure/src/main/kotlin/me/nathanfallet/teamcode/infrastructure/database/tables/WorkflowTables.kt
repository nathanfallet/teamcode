package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/**
 * One row per workflow definition (docs/workflows.md). [definition] is the whole definition as JSON (nodes, inputs, limits);
 * nothing in it is queried, so it stays out of the indexes. [version] grows with every save and is the optimistic check.
 */
object Workflows : Table("workflows") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val name = varchar("name", 120)
    val version = integer("version")
    val definition = mediumText("definition")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val updatedById = varchar("updated_by_id", 36).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("workflow_name", teamId, name)
        // The keyset listing of the team: equality column, then the sort.
        index("workflow_team", false, teamId, id)
    }
}

/** Who may edit the team's workflows; the row is also the lock that serializes saves and deletes (call loops, names, the cap). */
object WorkflowTeamSettings : Table("workflow_settings") {
    val teamId = varchar("team_id", 36)
    val editorMode = varchar("editor_mode", 8)
    val editorIds = text("editor_ids")
    override val primaryKey = PrimaryKey(teamId)
}

/**
 * One row per run (docs/workflows.md). [definition] is the snapshot the run executes; [credentialId] the server-held token
 * the run acts with as its starter, removed when the root run ends. [leaseUntil] is the claim of the engine's pass.
 * [parentVisitId] is unique: a sub-workflow node starts its child once, whatever replica retries.
 */
object WorkflowRuns : Table("workflow_runs") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val workflowId = varchar("workflow_id", 36)
    val workflowName = varchar("workflow_name", 120)
    val workflowVersion = integer("workflow_version")
    val definition = mediumText("definition")
    val starterId = reference("starter_id", Members.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "workflow_runs_starter")
    val status = varchar("status", 12)
    val inputs = text("inputs")
    val profileId = varchar("profile_id", 36).nullable()
    val sessionId = varchar("session_id", 36).nullable()
    val depth = integer("depth")
    val parentRunId = varchar("parent_run_id", 36).nullable()
    val parentVisitId = varchar("parent_visit_id", 36).nullable()
    val cancelRequested = bool("cancel_requested")
    val failure = varchar("failure", 100).nullable()
    val output = mediumText("output").nullable()
    val credentialId = varchar("credential_id", 36).nullable()
    val idempotencyKey = varchar("idempotency_key", 128).nullable()
    val leaseUntil = long("lease_until")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val finishedAt = long("finished_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        // A member's runs, newest first (equality columns, then the sort); it leads with the starter so the foreign key uses it.
        index("wfrun_starter", false, starterId, teamId, createdAt, id)
        index("wfrun_starter_status", false, starterId, teamId, status, createdAt, id)
        index("wfrun_starter_workflow", false, starterId, workflowId, createdAt, id)
        index("wfrun_team", false, teamId, createdAt, id)
        index("wfrun_team_status", false, teamId, status, createdAt, id)
        index("wfrun_team_workflow", false, teamId, workflowId, createdAt, id)
        // The engine's claim: unfinished runs whose lease ran out.
        index("wfrun_claim", false, status, leaseUntil)
        uniqueIndex("wfrun_idempotency", starterId, idempotencyKey)
        uniqueIndex("wfrun_parent_visit", parentVisitId)
    }
}

/** One pass through a node of a run; numbered per run. */
object WorkflowVisits : Table("workflow_visits") {
    val id = varchar("id", 36)
    val runId = reference("run_id", WorkflowRuns.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "workflow_visits_run")
    val seq = integer("seq")
    val nodeId = varchar("node_id", 64)
    val kind = varchar("kind", 10)
    val status = varchar("status", 10)
    val outcome = varchar("outcome", 64).nullable()
    val output = mediumText("output").nullable()
    val sessionId = varchar("session_id", 36).nullable()
    val waitId = varchar("wait_id", 36).nullable()
    val childRunId = varchar("child_run_id", 36).nullable()
    val dispatchedAt = long("dispatched_at").nullable()
    val startedAt = long("started_at")
    val finishedAt = long("finished_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("wfvisit_run_seq", runId, seq) }
}
