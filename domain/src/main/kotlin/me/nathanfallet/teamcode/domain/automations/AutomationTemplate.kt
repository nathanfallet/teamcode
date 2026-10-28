package me.nathanfallet.teamcode.domain.automations

import java.security.MessageDigest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

enum class VariableType(val wire: String) {
    STRING("string"), INT("int"), NUMBER("number"), BOOLEAN("boolean"), ENUM("enum");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/** A declared payload variable: [path] into the webhook body, a [type], and the bounds a value must respect. */
data class AutomationVariable(
    val name: String, val path: String, val type: VariableType = VariableType.STRING, val values: List<String> = emptyList(),
    val maxLength: Int = 256, val required: Boolean = true, val sensitive: Boolean = false,
)

/** A broken rule: the JSON [path] (or field) and the [rule], never the value. */
data class VariableProblem(val path: String, val rule: String)

/** A value as previews show it: [value] is null when [sensitive] or absent. */
data class ExtractedVariable(val name: String, val type: VariableType, val value: String?, val sensitive: Boolean, val present: Boolean)

/** [values] feeds the rendering (sensitive values included); [extracted] is what a caller may see. */
data class AutomationExtraction(val values: Map<String, String>, val extracted: List<ExtractedVariable>, val problems: List<VariableProblem>)

/**
 * Prompt templates: `{{name}}` placeholders replaced by plain text, nothing else interpreted. Payload variables are
 * declared and validated; run variables are computed by the server.
 */
object AutomationTemplate {
    const val MAX_PROMPT = 32_000
    const val MAX_VARIABLES = 32
    const val MAX_PATH_SEGMENTS = 16
    const val MAX_VALUE_LENGTH = 4_096
    const val MAX_ENUM_VALUES = 64
    val RUN_VARIABLES = setOf("run.id", "run.date", "run.time", "run.timezone", "run.source", "automation.id", "automation.name")
    private val placeholder = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")
    private val name = Regex("[A-Za-z0-9_-]{1,64}(\\.[A-Za-z0-9_-]{1,64}){0,7}")

    private sealed interface Segment { data class Key(val key: String) : Segment; data class Index(val index: Int) : Segment }

    fun placeholders(prompt: String): List<String> = placeholder.findAll(prompt).map { it.groupValues[1] }.toList()

    /** At save: the prompt's bounds, each variable's declaration, and every placeholder known. */
    fun validateDefinition(prompt: String, variables: List<AutomationVariable>, webhook: Boolean) {
        fun invalid(message: String): Nothing = throw TeamFailure("invalid_automation", 400, message)
        if (prompt.isBlank() || prompt.length > MAX_PROMPT) invalid("Write a prompt of 1 to $MAX_PROMPT characters.")
        if (variables.isNotEmpty() && !webhook) invalid("Only a webhook automation has payload variables; a cron one uses the run variables.")
        if (variables.size > MAX_VARIABLES) invalid("Declare at most $MAX_VARIABLES variables.")
        val names = mutableSetOf<String>()
        for (variable in variables) {
            if (!name.matches(variable.name) || variable.name.startsWith("run.") || variable.name.startsWith("automation.") || variable.name in RUN_VARIABLES)
                invalid("A variable name is letters, digits, '_' and '-', dot-separated, and does not start with run. or automation.")
            if (!names.add(variable.name)) invalid("The variable ${variable.name} is declared twice.")
            if (parsePath(variable.path) == null) invalid("The path of ${variable.name} is not a JSON path such as $.ticket.number.")
            if (variable.maxLength !in 1..MAX_VALUE_LENGTH) invalid("The maximum length of ${variable.name} must be between 1 and $MAX_VALUE_LENGTH.")
            if (variable.type == VariableType.ENUM && (variable.values.isEmpty() || variable.values.size > MAX_ENUM_VALUES || variable.values.any { it.isEmpty() || it.length > variable.maxLength }))
                invalid("The enum ${variable.name} needs 1 to $MAX_ENUM_VALUES values.")
            if (variable.type != VariableType.ENUM && variable.values.isNotEmpty()) invalid("Only an enum variable has values.")
        }
        val unknown = placeholders(prompt).filter { it !in names && it !in RUN_VARIABLES }.distinct()
        if (unknown.isNotEmpty()) invalid("The prompt uses unknown variables: ${unknown.take(5).joinToString(", ") { it.take(64) }}.")
    }

    /** Reads each variable from [payload]; a problem per broken rule, in declaration order. */
    fun extract(variables: List<AutomationVariable>, payload: JsonObject?): AutomationExtraction {
        val values = linkedMapOf<String, String>()
        val extracted = mutableListOf<ExtractedVariable>()
        val problems = mutableListOf<VariableProblem>()
        for (variable in variables) {
            val element = payload?.let { read(it, parsePath(variable.path) ?: emptyList()) }
            val problem = when {
                element == null || element is JsonNull -> if (variable.required) "required" else null
                element !is JsonPrimitive -> "not_scalar"
                else -> check(variable, element)
            }
            if (problem != null) { problems += VariableProblem(variable.path, problem); continue }
            val text = (element as? JsonPrimitive)?.content
            values[variable.name] = text.orEmpty()
            extracted += ExtractedVariable(variable.name, variable.type, text?.takeUnless { variable.sensitive }, variable.sensitive, text != null)
        }
        return AutomationExtraction(values, extracted, problems)
    }

    private fun check(variable: AutomationVariable, value: JsonPrimitive): String? {
        val text = value.content
        val rule = when (variable.type) {
            VariableType.STRING -> null
            VariableType.INT -> if (!value.isString && text.toLongOrNull() != null) null else "type:int"
            VariableType.NUMBER -> if (!value.isString && text.toDoubleOrNull()?.isFinite() == true) null else "type:number"
            VariableType.BOOLEAN -> if (!value.isString && value.booleanOrNull != null) null else "type:boolean"
            VariableType.ENUM -> if (value.isString && text in variable.values) null else "enum"
        }
        return rule ?: if (text.length > variable.maxLength) "max_length" else null
    }

    /** Replaces each placeholder once, from [values]; an unknown one renders empty (definitions are checked at save). */
    fun render(prompt: String, values: Map<String, String>): String = placeholder.replace(prompt) { values[it.groupValues[1]].orEmpty() }

    /** SHA-256 of the sorted name/value pairs: what a run records about its variables. */
    fun digest(values: Map<String, String>): String {
        val canonical = values.toSortedMap().entries.joinToString("\n") { (key, value) -> "${key.length}:$key=${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun read(root: JsonObject, path: List<Segment>): JsonElement? {
        var current: JsonElement = root
        for (segment in path) current = when (segment) {
            is Segment.Key -> (current as? JsonObject)?.get(segment.key) ?: return null
            is Segment.Index -> (current as? JsonArray)?.getOrNull(segment.index) ?: return null
        }
        return current
    }

    /** `$`, then `.key`, `['key']` or `[index]` segments; null when malformed. */
    private fun parsePath(path: String): List<Segment>? {
        if (!path.startsWith("$") || path.length > 512) return null
        val segments = mutableListOf<Segment>()
        var i = 1
        while (i < path.length) {
            when {
                path[i] == '.' -> {
                    val end = (i + 1 until path.length).firstOrNull { path[it] == '.' || path[it] == '[' } ?: path.length
                    val key = path.substring(i + 1, end)
                    if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
                    segments += Segment.Key(key); i = end
                }
                path.startsWith("['", i) -> {
                    val end = path.indexOf("']", i + 2).takeIf { it >= 0 } ?: return null
                    val key = path.substring(i + 2, end)
                    if (key.isEmpty() || '\'' in key || key.any { it.isISOControl() }) return null
                    segments += Segment.Key(key); i = end + 2
                }
                path[i] == '[' -> {
                    val end = path.indexOf(']', i).takeIf { it >= 0 } ?: return null
                    val index = path.substring(i + 1, end).takeIf { it.isNotEmpty() && it.length <= 6 && it.all(Char::isDigit) }?.toInt() ?: return null
                    segments += Segment.Index(index); i = end + 1
                }
                else -> return null
            }
        }
        return segments.takeIf { it.isNotEmpty() && it.size <= MAX_PATH_SEGMENTS }
    }
}
