package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Remembered facts of a team (scope `team`, [ownerId] empty) or of one member (scope `personal`). Reads filter on
 * `(team, scope, owner, status)` and order by `updated_at`; content is only read by id.
 */
object Memories : Table("memories") {
    val id = varchar("id", 36)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val scope = varchar("scope", 16)
    /** The owning member of a personal entry; empty for a team entry, so one index serves both. */
    val ownerId = varchar("owner_id", 36).default("")
    val title = varchar("title", 160)
    val summary = varchar("summary", 300)
    val content = text("content")
    /** A JSON array of at most 10 short references. */
    val refs = text("refs")
    val status = varchar("status", 16)
    val origin = varchar("origin", 16)
    val authorId = varchar("author_id", 36)
    val sessionId = varchar("session_id", 36).nullable()
    val supersedesId = varchar("supersedes_id", 36).nullable()
    /** The wait asking the session's owner about a proposal; null for any other entry. */
    val proposalWaitId = varchar("proposal_wait_id", 36).nullable()
    /** Set on an entry curation superseded: the newer entry that replaced it. */
    val supersededById = varchar("superseded_by_id", 36).nullable()
    val expiresAt = long("expires_at").nullable()
    val version = integer("version")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastReadAt = long("last_read_at").nullable()
    val readCount = integer("read_count").default(0)
    override val primaryKey = PrimaryKey(id)
    init {
        index("memory_visible", false, teamId, scope, ownerId, status, updatedAt)
        // Settling proposals and purging rejected entries read by status across teams, oldest first.
        index("memory_status", false, status, updatedAt)
    }
}

/**
 * An entry's tags. `team_id`, `scope` and `owner_id` repeat the entry's (they never change), so a tag filter reads one covering
 * index range of the caller's own scope and finds the entries from there, instead of walking every entry of the scope.
 */
object MemoryTags : Table("memory_tags") {
    val memoryId = reference("memory_id", Memories.id, onDelete = ReferenceOption.CASCADE)
    val tag = varchar("tag", 40)
    val teamId = varchar("team_id", 36)
    val scope = varchar("scope", 16)
    val ownerId = varchar("owner_id", 36).default("")
    override val primaryKey = PrimaryKey(memoryId, tag)
    init { index("memory_tag_lookup", false, teamId, scope, ownerId, tag, memoryId) }
}

/** A team's curation setting (off until an administrator turns it on) and what its last run did. A missing row means off. */
object MemorySettings : Table("memory_settings") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.CASCADE)
    val curationEnabled = bool("curation_enabled").default(false)
    val lastRunAt = long("last_run_at").nullable()
    val lastRunId = varchar("last_run_id", 36).nullable()
    val lastSuperseded = integer("last_superseded").default(0)
    val lastQueued = integer("last_queued").default(0)
    override val primaryKey = PrimaryKey(teamId)
}

/** When a scope (the team's entries, or one member's personal ones) was last curated: a run only reads scopes that changed since. */
object MemoryCurationState : Table("memory_curation_state") {
    val teamId = varchar("team_id", 36)
    val scope = varchar("scope", 16)
    val ownerId = varchar("owner_id", 36).default("")
    val curatedAt = long("curated_at")
    override val primaryKey = PrimaryKey(teamId, scope, ownerId)
}

/** A pair curation did not decide alone. `team_id`, `scope` and `owner_id` repeat the older entry's, so a reviewer's queue is one index range. */
object MemoryReviews : Table("memory_reviews") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val scope = varchar("scope", 16)
    val ownerId = varchar("owner_id", 36).default("")
    val olderId = reference("older_id", Memories.id, onDelete = ReferenceOption.CASCADE)
    val newerId = reference("newer_id", Memories.id, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", 16)
    val reason = varchar("reason", 200)
    /** `open`, `applied` or `dismissed`. */
    val status = varchar("status", 12)
    val createdAt = long("created_at")
    val resolvedAt = long("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("memory_review_pair", olderId, newerId)
        index("memory_review_queue", false, teamId, status, scope, ownerId, createdAt)
        index("memory_review_closed", false, status, resolvedAt)
    }
}
