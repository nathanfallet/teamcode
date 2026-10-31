package me.nathanfallet.teamcode.cli.waits

import kotlin.time.Instant
import me.nathanfallet.teamcode.api.models.*

private fun time(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()

/** "5m", "3h", "2d": how long ago, for the list. */
internal fun age(createdAt: Long, now: Long): String {
    val seconds = ((now - createdAt) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}

internal const val SESSION_NOTE = "answer it in the session"

/** One row of `waits list`: id, status, kind, title, session name, age, source. A session's own request is answered in the session. */
internal fun WaitView.line(now: Long) = listOfNotNull(id, status, kind, title, sessionName ?: sessionId ?: "-", age(createdAt, now),
    if (source == "session") "session ($SESSION_NOTE)" else source, schedule()).joinToString("\t")

/** What a wait the server resolves says of itself: "resumes at …", or "webhook, times out at …". Null for a person's wait. */
internal fun WaitView.schedule(): String? = when {
    dueAt != null -> "resumes at ${time(dueAt!!)}"
    timeoutAt != null -> "waits for ${eventSource ?: "an event"}, times out at ${time(timeoutAt!!)} (${onTimeout ?: "wake"})"
    else -> null
}

/** What `waits ask` prints for a wait the server resolves, including the event address of a webhook (shown only now). */
internal fun WaitView.announcement(): String = buildList {
    add("Waiting: $id ($title). ${schedule() ?: ""}".trim())
    eventUrl?.let {
        add("Event address (shown only now; it holds the wait's secret):")
        add("  curl -X POST '$it' -H 'Content-Type: application/json' -d '{\"result\":\"success\"}'")
    }
}.joinToString("\n")

private fun WaitFieldView.describe(): String {
    val flags = listOfNotNull(if (required) "required" else null, if (multiple) "multiple" else null, if (allowOther) "other allowed" else null, if (oneTime) "one time" else null)
    val head = "  $id\t$type\t$label" + if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
    val extras = buildList {
        help?.let { add("    $it") }
        if (type == "secret") add("    secret: give it with teamcode waits answer --set-secret $id; the agent reads it as ${Waits.SECRET_ENV_PREFIX}${id.uppercase()}")
        if (options.isNotEmpty()) add("    options: " + options.joinToString(", ") { if (it.label == it.value) it.value else "${it.value} (${it.label})" })
        default?.let { add("    default: $it") }
    }
    return (listOf(head) + extras).joinToString("\n")
}

/** `waits show`: everything a person needs to answer. */
internal fun WaitView.details(): String = buildList {
    add("$title  [$id]")
    add("Status: $status" + (resolvedAt?.let { " at ${time(it)}" } ?: "") + "; kind: $kind; source: $source")
    add("Asked of: ${assigneeName ?: assigneeId}; session: ${sessionName ?: sessionId ?: "-"}; created ${time(createdAt)}")
    schedule()?.let { add("Resolves by itself ($resolver): $it") }
    resumeMessage?.let { add("Resume message: $it") }
    if (source == "session") add("This is a request of a cloud session: $SESSION_NOTE, not with teamcode waits answer.")
    body?.let { add(""); add(it) }
    if (refs.isNotEmpty()) { add(""); add("References:"); refs.forEach { add("  ${it.kind}\t${it.id ?: it.url}" + (it.title?.let { t -> "\t$t" } ?: "")) } }
    if (fields.isNotEmpty()) { add(""); add("Fields (id, type, label):"); fields.forEach { add(it.describe()) } }
    if (actions.isNotEmpty()) {
        add(""); add("Actions:")
        actions.forEach { add("  ${it.id}\t${it.label}" + if (it.requires.isEmpty()) "" else " (requires ${it.requires.joinToString(", ")})") }
    }
    answer?.let { a ->
        add(""); add("Answer: ${a.action} by ${a.authorId} at ${time(a.answeredAt)}")
        a.values.forEach { (k, v) -> add("  $k = $v") }
    }
}.joinToString("\n")
