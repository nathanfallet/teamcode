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
