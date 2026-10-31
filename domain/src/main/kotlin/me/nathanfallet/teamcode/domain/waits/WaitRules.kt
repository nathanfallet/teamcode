package me.nathanfallet.teamcode.domain.waits

import java.time.LocalDate
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Bounds and pure checks of waits (docs/waits.md). */
object WaitRules {
    const val MAX_TITLE = 200
    const val MAX_BODY = 32_000
    const val MAX_FIELDS = 32
    const val MAX_ACTIONS = 8
    const val MAX_REFS = 10
    const val MAX_OPTIONS = 50
    const val MAX_LABEL = 200
    const val MAX_HELP = 2_000
    const val DEFAULT_TEXT = 8_000
    const val MAX_TEXT = 32_000
    const val MAX_OTHER = 500
    const val MAX_ANSWER_BYTES = 128 * 1024
    const val MAX_OPEN_PER_SESSION = 20
    val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val refId = Regex("[A-Za-z0-9._:-]{1,64}")
    private val styles = setOf("primary", "danger", "neutral")

    fun invalid(message: String) = TeamFailure("invalid_wait", 400, message)

    /** The secrets of a wait reach the agent as `TEAMCODE_WAIT_<FIELD ID UPPER-CASED>`; a field id is `[A-Za-z][A-Za-z0-9_]{0,63}`, so the name is always a valid shell identifier. */
    const val SECRET_ENV_PREFIX = "TEAMCODE_WAIT_"
    const val MAX_SECRET_BYTES = 4096
    /** A stored secret is deleted this long after the answer at the latest. */
    const val SECRET_LIFETIME_MILLIS = 24L * 3600 * 1000
    /** Only an agent that re-reads its environment before each command can be given a secret that arrives mid-session and ends with it. */
    val SECRET_AGENTS = setOf("claude")
    fun secretVariable(fieldId: String) = SECRET_ENV_PREFIX + fieldId.uppercase()

    private fun plain(value: String, max: Int, what: String) {
        if (value.isBlank() || value.length > max || value.any { it.isISOControl() }) throw invalid("Use $what of 1 to $max characters without control characters.")
    }

    const val MIN_AFTER_SECONDS = 30L
    const val MAX_AFTER_SECONDS = 30L * 24 * 3600
    const val DEFAULT_TIMEOUT_SECONDS = 24L * 3600
    const val MAX_TIMEOUT_SECONDS = 7L * 24 * 3600
    const val MAX_RESUME_MESSAGE = 2_000
    const val MAX_EVENT_BYTES = 16 * 1024
    const val MAX_EVENT_RESULT = 64
    const val MAX_EVENT_NOTE = 2_000
    const val MAX_EVENT_URL = 2_000

    /** An instant written as ISO-8601 in UTC (`2026-11-02T09:00:00Z`) or as epoch milliseconds; null when it is neither. */
    fun parseInstant(text: String): Long? {
        val value = text.trim()
        if (value.isNotEmpty() && value.length <= 16 && value.all { it.isDigit() }) return value.toLongOrNull()
        return try { java.time.Instant.parse(value).toEpochMilli() } catch (_: Exception) { null }
    }

    /**
     * The instant ([now] included) a wait resolves at, or the timeout of an event wait; null for a human wait. [validate]
     * has checked the definition.
     */
    fun dueAt(definition: WaitDefinition, now: Long): Long? {
        val schedule = definition.schedule ?: return null
        return when (definition.resolver) {
            WaitResolver.TIME -> schedule.at ?: (now + schedule.afterSeconds!! * 1000)
            WaitResolver.EVENT -> now + (schedule.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS) * 1000
            WaitResolver.HUMAN -> null
        }
    }

    /** Everything but the session and the assignee, which need the store. [now] is the database clock, for `at`. */
    fun validate(definition: WaitDefinition, now: Long = System.currentTimeMillis()): WaitDefinition {
        plain(definition.title, MAX_TITLE, "a title")
        definition.body?.let { if (it.length > MAX_BODY) throw invalid("The description is at most $MAX_BODY characters.") }
        if (definition.resolver != WaitResolver.HUMAN) return validateSelfResolving(definition, now)
        if (definition.schedule != null) throw invalid("at, after, source, timeoutSeconds, onTimeout and resumeMessage belong to waits for a time or an event: set resolver to time or event.")
        if (definition.refs.size > MAX_REFS) throw invalid("At most $MAX_REFS references.")
        definition.refs.forEach(::validateRef)
        if (definition.fields.size > MAX_FIELDS) throw invalid("At most $MAX_FIELDS fields.")
        // A secret is asked by a member or an agent in a session; a workflow's human node or the server's own waits never carry one.
        val secretsAllowed = definition.kind == WaitKind.HUMAN_REQUEST
        definition.fields.forEach { validateField(it, secretsAllowed) }
        if (definition.fields.map { it.id }.toSet().size != definition.fields.size) throw invalid("Field ids must be unique.")
        definition.fields.filter { it.type == FieldType.SECRET }.groupBy { secretVariable(it.id) }.entries.firstOrNull { it.value.size > 1 }
            ?.let { throw invalid("Secret fields ${it.value.joinToString(" and ") { f -> f.id }} would both be ${it.key}: field ids differ by more than their case.") }
        val actions = definition.actions.ifEmpty { listOf(WaitAction(DEFAULT_ACTION, "Done")) }
        if (actions.size > MAX_ACTIONS) throw invalid("At most $MAX_ACTIONS actions.")
        val fieldIds = definition.fields.filter { it.type != FieldType.INFO }.map { it.id }.toSet()
        for (action in actions) {
            if (!identifier.matches(action.id)) throw invalid("An action id starts with a letter and has at most 64 letters, digits or underscores.")
            plain(action.label, MAX_LABEL, "an action label")
            action.style?.let { if (it !in styles) throw invalid("An action style is primary, danger or neutral.") }
            action.requires.firstOrNull { it !in fieldIds }?.let { throw invalid("Action ${action.id} requires $it, which is not a field that takes a value.") }
        }
        if (actions.map { it.id }.toSet().size != actions.size) throw invalid("Action ids must be unique.")
        return definition.copy(actions = actions)
    }

    const val DEFAULT_ACTION = "done"

    /** A wait for a time or an event asks nobody: no fields, no actions, and exactly the settings of its resolver. */
    private fun validateSelfResolving(definition: WaitDefinition, now: Long): WaitDefinition {
        val schedule = definition.schedule ?: throw invalid(
            if (definition.resolver == WaitResolver.TIME) "A wait for a time needs at (an instant) or after (seconds)." else "A wait for an event needs source (webhook).")
        if (definition.fields.isNotEmpty() || definition.actions.isNotEmpty()) throw invalid("A wait for a time or an event asks nobody: it has no fields and no actions.")
        if (definition.refs.size > MAX_REFS) throw invalid("At most $MAX_REFS references.")
        definition.refs.forEach(::validateRef)
        schedule.resumeMessage?.let {
            if (it.isBlank() || it.length > MAX_RESUME_MESSAGE || it.any { c -> c.isISOControl() && c != '\n' && c != '\t' }) throw invalid("resumeMessage is 1 to $MAX_RESUME_MESSAGE characters.")
        }
        if (definition.resolver == WaitResolver.TIME) {
            if (schedule.source != null || schedule.timeoutSeconds != null || schedule.onTimeout != null) throw invalid("source, timeoutSeconds and onTimeout belong to waits for an event.")
            if ((schedule.at == null) == (schedule.afterSeconds == null)) throw invalid("Give either at or after, not both and not neither.")
            schedule.afterSeconds?.let { if (it !in MIN_AFTER_SECONDS..MAX_AFTER_SECONDS) throw invalid("after is $MIN_AFTER_SECONDS seconds to 30 days ($MAX_AFTER_SECONDS seconds).") }
            schedule.at?.let {
                if (it <= now) throw invalid("at must be in the future.")
                if (it > now + MAX_AFTER_SECONDS * 1000) throw invalid("at is at most 30 days from now.")
            }
        } else {
            if (schedule.at != null || schedule.afterSeconds != null) throw invalid("at and after belong to waits for a time.")
            if (schedule.source == null) throw invalid("A wait for an event needs source (webhook).")
            schedule.timeoutSeconds?.let { if (it !in MIN_AFTER_SECONDS..MAX_TIMEOUT_SECONDS) throw invalid("timeoutSeconds is $MIN_AFTER_SECONDS seconds to 7 days ($MAX_TIMEOUT_SECONDS seconds).") }
        }
        return definition.copy(actions = emptyList(), kind = WaitKind.TIMED)
    }

    /** What an outside caller posted to an event address, checked: a short `result`, an https `url`, a `note`. Throws `400 invalid_event`. */
    fun checkEvent(result: String, url: String?, note: String?) {
        fun bad(message: String) = TeamFailure("invalid_event", 400, message)
        if (result.isBlank() || result.length > MAX_EVENT_RESULT || result.any { it.isISOControl() }) throw bad("result is 1 to $MAX_EVENT_RESULT characters, for example success or failure.")
        url?.let { if (it.length > MAX_EVENT_URL || !it.startsWith("https://") || it.any { c -> c.isWhitespace() || c.isISOControl() }) throw bad("url is an https address without spaces.") }
        note?.let { if (it.length > MAX_EVENT_NOTE || it.any { c -> c.isISOControl() && c != '\n' && c != '\t' }) throw bad("note is at most $MAX_EVENT_NOTE characters.") }
    }

    private fun validateRef(ref: WaitRef) {
        when (ref.kind) {
            "artifact", "session" -> if (ref.id == null || !refId.matches(ref.id) || ref.url != null) throw invalid("A ${ref.kind} reference has an id and no url.")
            "url" -> {
                val url = ref.url
                if (url == null || ref.id != null || url.length > 2_000 || !url.startsWith("https://") || url.any { it.isWhitespace() || it.isISOControl() })
                    throw invalid("A url reference is an https address without spaces.")
            }
            else -> throw invalid("A reference is an artifact, a session or a url.")
        }
        ref.title?.let { plain(it, MAX_LABEL, "a reference title") }
    }

    private fun validateField(field: WaitField, secretsAllowed: Boolean) {
        if (!identifier.matches(field.id)) throw invalid("A field id starts with a letter and has at most 64 letters, digits or underscores.")
        plain(field.label, MAX_LABEL, "a field label")
        field.help?.let { if (it.length > MAX_HELP) throw invalid("Field help is at most $MAX_HELP characters.") }
        if (field.type == FieldType.SECRET) {
            if (!secretsAllowed) throw invalid("Only a request made in a session takes a secret field, not a workflow step.")
            if (field.default != null) throw invalid("A secret field has no default: ${field.id}.")
        } else if (field.oneTime) throw invalid("Only a secret field is oneTime.")
        field.maxLength?.let { if (field.type != FieldType.TEXT || it !in 1..MAX_TEXT) throw invalid("Only a text field has a length limit, 1 to $MAX_TEXT.") }
        if (field.type == FieldType.CHOICE) {
            if (field.options.isEmpty() || field.options.size > MAX_OPTIONS) throw invalid("A choice field has 1 to $MAX_OPTIONS options.")
            field.options.forEach { plain(it.value, MAX_OTHER, "an option value"); plain(it.label, MAX_LABEL, "an option label") }
            if (field.options.map { it.value }.toSet().size != field.options.size) throw invalid("Option values must be unique.")
        } else if (field.options.isNotEmpty() || field.multiple || field.allowOther) throw invalid("Only a choice field has options.")
        if (field.type == FieldType.INFO && (field.required || field.default != null)) throw invalid("An info field takes no value.")
        field.default?.let { runCatching { checkValue(field, it) }.getOrElse { throw invalid("The default of ${field.id} does not fit the field.") } }
    }

    /** The values of [answer] checked against [wait]; throws `400 invalid_answer`. A secret's value is checked here like any other and then kept apart by [WaitSecrets]. */
    fun checkAnswer(fields: List<WaitField>, actions: List<WaitAction>, actionId: String, values: Map<String, JsonElement>) {
        val action = actions.singleOrNull { it.id == actionId } ?: throw bad("Choose one of the actions: ${actions.joinToString(", ") { it.id }}.")
        if (values.toString().length > MAX_ANSWER_BYTES) throw bad("The answer is too large.")
        val byId = fields.associateBy { it.id }
        values.keys.firstOrNull { byId[it] == null || byId.getValue(it).type == FieldType.INFO }?.let { throw bad("$it is not a field to fill.") }
        for ((id, value) in values) {
            try { checkValue(byId.getValue(id), value) } catch (e: IllegalArgumentException) { throw bad("$id: ${e.message}") }
        }
        for (field in fields) {
            if (field.type == FieldType.INFO) continue
            if ((field.required || field.id in action.requires) && !present(values[field.id]))
                throw bad("${field.label} is needed to ${action.label.lowercase()}.")
        }
    }

    private fun bad(message: String) = TeamFailure("invalid_answer", 400, message)

    internal fun present(value: JsonElement?) = when {
        value == null || value is JsonNull -> false
        value is JsonPrimitive && value.isString -> value.content.isNotBlank()
        value is JsonArray -> value.isNotEmpty()
        else -> true
    }

    /** Throws [IllegalArgumentException] with a short reason when [value] does not fit [field]. */
    private fun checkValue(field: WaitField, value: JsonElement) {
        if (value is JsonNull) return
        when (field.type) {
            FieldType.TEXT -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("text expected")
                require(text.length <= (field.maxLength ?: DEFAULT_TEXT)) { "at most ${field.maxLength ?: DEFAULT_TEXT} characters" }
            }
            FieldType.CHOICE -> {
                val chosen = if (field.multiple) (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("a list of texts expected") }
                    ?: throw IllegalArgumentException("a list expected")
                else listOf((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("one option expected"))
                val allowed = field.options.map { it.value }.toSet()
                for (item in chosen) require(item in allowed || (field.allowOther && item.isNotBlank() && item.length <= MAX_OTHER && item.none { it.isISOControl() })) { "not one of the options" }
                require(chosen.toSet().size == chosen.size) { "options repeated" }
            }
            FieldType.CONFIRM -> require((value as? JsonPrimitive)?.booleanOrNull != null && !(value as JsonPrimitive).isString) { "true or false expected" }
            FieldType.NUMBER -> require((value as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull?.isFinite() == true } == true) { "a number expected" }
            FieldType.DATE -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("a date expected")
                try { LocalDate.parse(text) } catch (_: Exception) { throw IllegalArgumentException("a date as YYYY-MM-DD expected") }
            }
            FieldType.FILE -> {
                val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("a file name expected")
                require(name.isNotBlank() && name.length <= 255 && '/' !in name && '\\' !in name && name.none { it.isISOControl() }) { "a file name expected" }
            }
            FieldType.SECRET -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("a secret as text expected")
                require(text.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_BYTES) { "at most $MAX_SECRET_BYTES bytes" }
                require('\u0000' !in text) { "no NUL character" }
            }
            FieldType.INFO -> throw IllegalArgumentException("nothing to fill")
        }
    }
}
