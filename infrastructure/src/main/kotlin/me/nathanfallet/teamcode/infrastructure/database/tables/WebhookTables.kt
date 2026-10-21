package me.nathanfallet.teamcode.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.*

/** Team-wide webhook subscriptions. [secret] is sealed (`webhook:<id>` context); [types] are comma-separated wire names. */
object WebhookSubscriptions : Table("webhook_subscriptions") {
    val id = varchar("id", 36)
    val teamId = varchar("team_id", 36)
    val url = varchar("url", 2048)
    val types = varchar("types", 600)
    val description = varchar("description", 200)
    val enabled = bool("enabled")
    val disabledReason = varchar("disabled_reason", 64).nullable()
    val secret = text("secret")
    val createdBy = varchar("created_by", 36)
    val credentialId = varchar("credential_id", 36).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val version = integer("version")
    /** The last journal sequence turned into deliveries. */
    val fanoutSeq = long("fanout_seq")
    /** Consecutive deliveries that failed for good. */
    val failures = integer("failures")
    override val primaryKey = PrimaryKey(id)
    init { index("webhook_subscription_team", false, teamId, createdAt) }
}

/** One event for one subscription. [payload] is the exact body, fixed at fan-out so every retry sends the same bytes. */
object WebhookDeliveries : Table("webhook_deliveries") {
    val id = varchar("id", 36)
    val subscriptionId = reference("subscription_id", WebhookSubscriptions.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.NO_ACTION, fkName = "webhook_deliveries_subscription")
    val teamId = varchar("team_id", 36)
    /** Null for a test delivery. */
    val eventSeq = long("event_seq").nullable()
    val eventType = varchar("event_type", 40)
    val payload = text("payload")
    val state = varchar("state", 16)
    val attempts = integer("attempts")
    val nextAttemptAt = long("next_attempt_at")
    val leaseUntil = long("lease_until")
    val lastStatus = integer("last_status").nullable()
    val lastError = varchar("last_error", 40).nullable()
    val createdAt = long("created_at")
    val deliveredAt = long("delivered_at").nullable()
    val test = bool("test")
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex("webhook_delivery_event", subscriptionId, eventSeq)
        index("webhook_delivery_due", false, state, nextAttemptAt)
        index("webhook_delivery_log", false, subscriptionId, createdAt)
        index("webhook_delivery_retention", false, createdAt)
    }
}
