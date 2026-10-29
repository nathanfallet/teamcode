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
    private fun notYet(message: String) = TeamFailure("not_available_yet", 422, message)

    private fun plain(value: String, max: Int, what: String) {
        if (value.isBlank() || value.length > max || value.any { it.isISOControl() }) throw invalid("Use $what of 1 to $max characters without control characters.")
    }

    /** Everything but the session and the assignee, which need the store. */
    fun validate(definition: WaitDefinition): WaitDefinition {
        plain(definition.title, MAX_TITLE, "a title")
        definition.body?.let { if (it.length > MAX_BODY) throw invalid("The description is at most $MAX_BODY characters.") }
        if (definition.resolver != WaitResolver.HUMAN) throw notYet("Waits for a time or an event are not available yet: ask a person.")
        if (definition.refs.size > MAX_REFS) throw invalid("At most $MAX_REFS references.")
        definition.refs.forEach(::validateRef)
        if (definition.fields.size > MAX_FIELDS) throw invalid("At most $MAX_FIELDS fields.")
        definition.fields.forEach(::validateField)
        if (definition.fields.map { it.id }.toSet().size != definition.fields.size) throw invalid("Field ids must be unique.")
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

    private fun validateField(field: WaitField) {
        if (!identifier.matches(field.id)) throw invalid("A field id starts with a letter and has at most 64 letters, digits or underscores.")
        plain(field.label, MAX_LABEL, "a field label")
        field.help?.let { if (it.length > MAX_HELP) throw invalid("Field help is at most $MAX_HELP characters.") }
        if (field.type == FieldType.SECRET) throw notYet("Secret fields are not available yet: secrets cannot reach the agent safely.")
        field.maxLength?.let { if (field.type != FieldType.TEXT || it !in 1..MAX_TEXT) throw invalid("Only a text field has a length limit, 1 to $MAX_TEXT.") }
        if (field.type == FieldType.CHOICE) {
            if (field.options.isEmpty() || field.options.size > MAX_OPTIONS) throw invalid("A choice field has 1 to $MAX_OPTIONS options.")
            field.options.forEach { plain(it.value, MAX_OTHER, "an option value"); plain(it.label, MAX_LABEL, "an option label") }
            if (field.options.map { it.value }.toSet().size != field.options.size) throw invalid("Option values must be unique.")
        } else if (field.options.isNotEmpty() || field.multiple || field.allowOther) throw invalid("Only a choice field has options.")
        if (field.type == FieldType.INFO && (field.required || field.default != null)) throw invalid("An info field takes no value.")
        field.default?.let { runCatching { checkValue(field, it) }.getOrElse { throw invalid("The default of ${field.id} does not fit the field.") } }
    }

    /** The values of [answer] checked against [wait]; throws `400 invalid_answer`. Secrets cannot be asked yet, so none is read. */
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

    private fun present(value: JsonElement?) = when {
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
            FieldType.SECRET -> throw IllegalArgumentException("secrets are not available yet")
            FieldType.INFO -> throw IllegalArgumentException("nothing to fill")
        }
    }
}
