package me.nathanfallet.teamcode.companion.domain.models

/** What a member can be notified about (docs/notifications.md, "What notifies whom"), in the order the screen lists them. */
enum class NotificationKind(val wire: String) {
    WAITING("waiting"),
    TURN_COMPLETED("turn_completed"),
    TURN_FAILED("turn_failed"),
    SESSION_FAILED("session_failed");

    companion object {
        fun parse(wire: String): NotificationKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The member's push switches on one server: one per kind, on until turned off. They are the member's, kept by the
 * server, so they hold on every phone of the member. [canTest]: the server lets this member send a test push.
 */
data class NotificationPreferences(val switches: Map<NotificationKind, Boolean>, val canTest: Boolean)

/** How a test push went. */
sealed interface PushTestOutcome {
    /** [delivered] of [phones] installations accepted it. */
    data class Sent(val delivered: Int, val phones: Int) : PushTestOutcome
    /** The team has no push provider: an administrator chooses one in the dashboard. */
    data object Off : PushTestOutcome
    /** No phone of the member is registered: the app on a phone, signed in with notifications allowed, registers it. */
    data object NoPhone : PushTestOutcome
}
