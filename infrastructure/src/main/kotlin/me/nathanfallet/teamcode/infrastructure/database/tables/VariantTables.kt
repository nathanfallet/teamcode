package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Named environment/MCP variants. Secret values are AES-GCM envelopes bound to the variant identity; a hosted command keeps its secret variables in the same envelopes. */
object Variants : Table("variants") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 16)
    val target = varchar("target", 128)
    val name = varchar("name", 120)
    val binding = varchar("binding", 16)
    val version = integer("version")
    val endpoint = varchar("endpoint", 2048).nullable()
    val config = text("config")
    val sharedSecret = text("shared_secret").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    /** How an MCP variant is reached: `http` (its endpoint, and the value of every older row) or `stdio` (a hosted command). */
    val transport = varchar("transport", 16).default("http")
    /** A hosted command's launcher (`npm` or `pypi`), and its package then arguments as a JSON array. Null otherwise. */
    val command = varchar("command", 16).nullable()
    val args = text("args").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("variant_team_slot_name", teamId, kind, target, name)
        // The MCP host's retain sweep reads every hosted command (transport stdio) of the installation once a minute, and
        // the dashboard one team's.
        index("variant_transport_team", false, transport, teamId)
    }
}

/** At most one selected variant per member and slot; kind/target repeat the variant's slot to enforce that uniqueness. */
object VariantAssignments : Table("variant_assignments") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 16)
    val target = varchar("target", 128)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val variantId = reference("variant_id", Variants.id, onDelete = ReferenceOption.CASCADE).index("variant_assignment_variant")
    val assignmentSource = varchar("source", 16)
    val memberSecret = text("member_secret").nullable()
    val assignedAt = long("assigned_at")
    override val primaryKey = PrimaryKey(memberId, kind, target)
    init { index("variant_assignment_team", false, teamId, memberId) }
}

/** Explicit opt-outs that win over bulk (and later group) assignment for one member and slot. */
object VariantExclusions : Table("variant_exclusions") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 16)
    val target = varchar("target", 128)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(memberId, kind, target)
    init { index("variant_exclusion_team", false, teamId, memberId) }
}
