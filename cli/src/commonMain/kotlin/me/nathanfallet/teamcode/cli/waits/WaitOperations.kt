package me.nathanfallet.teamcode.cli.waits

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.client.TeamCodeClient

/** The most `waits list` fetches in one run; older entries are reached with `--before`. */
internal const val MAX_LISTED_WAITS = 500

/** Stops with a clear message when the server does not serve waits (it is older than this CLI). */
internal suspend fun requireWaits(client: TeamCodeClient) {
    if (Waits.CAPABILITY !in client.capabilities().features)
        throw CliUserException("This TeamCode server is too old for waits: it does not advertise ${Waits.CAPABILITY}. Ask an administrator to update it.")
}

private val WAIT_ID = Regex("[a-zA-Z0-9-]{1,36}")

/** The identifier arguments the client would reject with an exception: a usage message instead. */
internal fun requireWaitId(value: String, what: String = "wait id"): String {
    if (!WAIT_ID.matches(value)) throw UsageError("\"$value\" is not a valid $what: use the id that teamcode waits list prints.")
    return value
}

/** Pages of waits, newest first, until [limit] rows; the second value is the cursor of the next page when more exist. */
internal suspend fun listWaits(client: TeamCodeClient, assignee: String?, status: String, before: String?, limit: Int): Pair<List<WaitView>, String?> {
    val waits = mutableListOf<WaitView>()
    var cursor = before
    do {
        val page = client.waits(assignee, status, cursor, minOf(100, limit - waits.size))
        waits += page.waits
        cursor = page.nextBefore
    } while (cursor != null && waits.size < limit)
    return waits.take(limit) to cursor
}

private val FIELD_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

private fun one(field: WaitFieldView, text: String): JsonElement = when (field.type) {
    "text", "file" -> JsonPrimitive(text)
    "date" -> if (DATE.matches(text)) JsonPrimitive(text) else throw CliUserException("Field ${field.id} is a date: write it as YYYY-MM-DD, not \"$text\".")
    "number" -> text.trim().let { t ->
        t.toLongOrNull()?.let { JsonPrimitive(it) }
            ?: t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
            ?: throw CliUserException("Field ${field.id} is a number: \"$text\" is not one.")
    }
    "confirm" -> when (text.lowercase()) {
        "true", "yes", "y" -> JsonPrimitive(true)
        "false", "no", "n" -> JsonPrimitive(false)
        else -> throw CliUserException("Field ${field.id} is a confirmation: use true or false, not \"$text\".")
    }
    "choice" -> {
        if (!field.allowOther && field.options.none { it.value == text })
            throw CliUserException("Field ${field.id} takes one of: ${field.options.joinToString(", ") { it.value }}. \"$text\" is not one.")
        JsonPrimitive(text)
    }
    "secret" -> throw CliUserException("Field ${field.id} is a secret: waits do not take secrets yet.")
    "info" -> throw CliUserException("Field ${field.id} only shows information and takes no value.")
    else -> JsonPrimitive(text)
}

/**
 * The answer's values, typed by the declared fields of [wait]: [sets] are text from `--set FIELD=VALUE` (converted by
 * the field's type; a multiple choice repeats the option), [jsonSets] are JSON from `--set-json`, used as given.
 * Refuses an unknown field or an unconvertible value, so nothing reaches the server.
 */
internal fun answerValues(wait: WaitView, sets: List<Pair<String, String>>, jsonSets: List<Pair<String, String>>): Map<String, JsonElement> {
    val fields = wait.fields.associateBy { it.id }
    fun field(id: String) = fields[id] ?: throw CliUserException(
        "This wait has no field \"$id\"." + if (fields.isEmpty()) " It takes no values." else " Its fields: ${fields.keys.joinToString(", ")}.")
    val values = linkedMapOf<String, JsonElement>()
    sets.groupBy({ it.first }, { it.second }).forEach { (id, texts) ->
        val f = field(id)
        if (f.multiple && f.type == "choice") values[id] = JsonArray(texts.map { one(f, it) })
        else if (texts.size > 1) throw CliUserException("Field $id takes one value, not ${texts.size}: repeat --set only for a multiple choice.")
        else values[id] = one(f, texts.single())
    }
    jsonSets.forEach { (id, text) ->
        field(id)
        if (id in values) throw CliUserException("Field $id is given twice: use --set or --set-json for it, not both.")
        values[id] = try { Json.parseToJsonElement(text) } catch (_: Exception) { throw CliUserException("The value of $id is not valid JSON (--set-json): write strings in double quotes.") }
    }
    return values
}

/** The action to answer with: the one named, or the only one the wait has. */
internal fun chooseAction(wait: WaitView, requested: String?): String {
    val actions = wait.actions.map { it.id }.ifEmpty { listOf(Waits.DEFAULT_ACTION) }
    if (requested != null) {
        if (requested !in actions) throw CliUserException("This wait has no action \"$requested\". Its actions: ${actions.joinToString(", ")}.")
        return requested
    }
    return actions.singleOrNull() ?: throw CliUserException("This wait has several actions: choose one with --action (${actions.joinToString(", ")}).")
}

/** `--field ID:TYPE:LABEL[:required]`, with `--option FIELD=VALUE` adding the choices of a `choice` field. */
internal fun parseField(text: String, options: Map<String, List<String>>): WaitFieldView {
    val parts = text.split(":", limit = 3)
    if (parts.size < 3 || parts[2].isBlank()) throw UsageError("--field is ID:TYPE:LABEL[:required], such as notes:text:Notes:required.")
    val (id, type, rest) = parts
    if (!FIELD_ID.matches(id)) throw UsageError("\"$id\" is not a field id: a letter, then letters, digits or _ (64 at most).")
    if (type !in Waits.fieldTypes) throw UsageError("\"$type\" is not a field type: use ${Waits.fieldTypes.joinToString(", ")}.")
    if (type == "secret") throw UsageError("Secret fields are not available yet.")
    val required = rest.endsWith(":required")
    val label = if (required) rest.removeSuffix(":required") else rest
    val choices = options[id].orEmpty()
    if (type == "choice" && choices.isEmpty()) throw UsageError("The choice field $id needs its choices: --option $id=VALUE, repeated.")
    if (type != "choice" && choices.isNotEmpty()) throw UsageError("--option $id=… is for a choice field, and $id is $type.")
    return WaitFieldView(id, type, label, required = required, options = choices.map { WaitOptionView(it, it) })
}

/** `--action ID:LABEL`. */
internal fun parseAction(text: String): WaitActionView {
    val parts = text.split(":", limit = 2)
    if (parts.size != 2 || !FIELD_ID.matches(parts[0]) || parts[1].isBlank()) throw UsageError("--action is ID:LABEL, such as approve:Approve.")
    return WaitActionView(parts[0], parts[1])
}

/** `--ref artifact:ID`, `session:ID` or `url:https://…`. */
internal fun parseRef(text: String): WaitRefView {
    val kind = text.substringBefore(':', "")
    val value = text.substringAfter(':', "")
    if (kind !in Waits.refKinds || value.isBlank()) throw UsageError("--ref is artifact:ID, session:ID or url:https://… .")
    return if (kind == "url") WaitRefView(kind, url = value) else WaitRefView(kind, id = requireWaitId(value, "$kind id"))
}
