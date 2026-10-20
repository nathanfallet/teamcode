package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** One GitHub App per team. */
object GitHubApps : Table("github_apps") {
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "github_apps_team")
    val appId = long("app_id")
    val clientId = varchar("client_id", 64)
    val slug = varchar("slug", 100).nullable()
    val name = varchar("name", 120).nullable()
    /** AES-GCM envelope of the client secret and private key, bound to the team; never returned by any API. */
    val credential = text("credential")
    val version = integer("version")
    val updatedBy = varchar("updated_by", 36)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(teamId)
}

/** A member's GitHub App user-to-server tokens. One GitHub account per member and per team. */
object GitHubLinks : Table("github_links") {
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "github_links_member")
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "github_links_team")
    val githubUserId = long("github_user_id")
    val githubLogin = varchar("github_login", 39)
    /** The App the tokens were issued for; another configured App means the member links again. */
    val clientId = varchar("client_id", 64)
    /** AES-GCM envelope of the access and refresh tokens, bound to the team and member. */
    val credential = text("credential")
    val state = varchar("state", 24)
    val accessExpiresAt = long("access_expires_at").nullable()
    val refreshExpiresAt = long("refresh_expires_at").nullable()
    val tokenVersion = integer("token_version")
    val refreshPending = bool("refresh_pending").default(false)
    val refreshStartedAt = long("refresh_started_at").default(0)
    val linkedAt = long("linked_at")
    val refreshedAt = long("refreshed_at")
    override val primaryKey = PrimaryKey(memberId)
    init { uniqueIndex("github_link_user", teamId, githubUserId) }
}

/** Pending device or web links, single-use and short-lived. */
object GitHubLinkAttempts : Table("github_link_attempts") {
    val id = varchar("id", 64)
    val teamId = reference("team_id", Teams.id, onDelete = ReferenceOption.NO_ACTION, onUpdate = ReferenceOption.NO_ACTION, fkName = "github_link_attempts_team")
    val memberId = reference("member_id", Members.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "github_link_attempts_member")
        .index("github_attempt_member")
    val kind = varchar("kind", 8)
    /** AES-GCM envelope of the device code or PKCE verifier, bound to the attempt and member. */
    val secret = text("secret")
    val browserHash = char("browser_hash", 64).nullable()
    val pollInterval = integer("poll_interval")
    val nextPollAt = long("next_poll_at")
    val expiresAt = long("expires_at").index("github_attempt_expiry")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}
