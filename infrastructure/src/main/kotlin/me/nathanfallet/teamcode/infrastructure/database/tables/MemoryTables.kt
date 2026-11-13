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
    val expiresAt = long("expires_at").nullable()
    val version = integer("version")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastReadAt = long("last_read_at").nullable()
    val readCount = integer("read_count").default(0)
    override val primaryKey = PrimaryKey(id)
    init { index("memory_visible", false, teamId, scope, ownerId, status, updatedAt) }
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
