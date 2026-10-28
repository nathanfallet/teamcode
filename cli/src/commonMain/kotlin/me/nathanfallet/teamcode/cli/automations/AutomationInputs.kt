package me.nathanfallet.teamcode.cli.automations

import com.github.ajalt.clikt.core.UsageError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException

/** What `automations create` and `automations edit` were given; every field is optional, [applyChanges] decides what is required. */
internal class AutomationChanges(
    val name: String? = null,
    val cron: String? = null,
    val webhook: Boolean = false,
    val timezone: String? = null,
    val catchup: String? = null,
    val catchupGraceMinutes: Int? = null,
    val action: String? = null,
    val profileId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int? = null,
    val prompt: String? = null,
    /** Replaces the declared variables when not null (an empty list clears them). */
    val variables: List<AutomationVariableView>? = null,
    val samplePayload: JsonObject? = null,
    val overlap: String? = null,
    val maxRunsPerDay: Int? = null,
    /** Edit: go back to the profile's own model, effort, or no sample payload. */
    val clearModel: Boolean = false,
    val clearEffort: Boolean = false,
    val clearSamplePayload: Boolean = false,
)

/**
 * The definition to send: [base] (an automation being edited) with [changes] on top, or a new one when [base] is null.
 * Local checks only cover what the command line can get wrong (a missing field, options that do not go with the trigger);
 * the server checks the rest (cron, timezone, prompt variables, limits) and says why.
 */
internal fun applyChanges(base: AutomationInput?, changes: AutomationChanges): AutomationInput {
    if (changes.cron != null && changes.webhook) throw UsageError("Use --cron or --webhook, not both.")
    val kind = when {
        changes.cron != null -> "cron"
        changes.webhook -> "webhook"
        else -> base?.trigger?.kind ?: throw UsageError("Choose the trigger: --cron \"0 21 * * *\" --timezone Europe/Paris, or --webhook.")
    }
    val cronOptions = changes.timezone != null || changes.catchup != null || changes.catchupGraceMinutes != null
    val trigger = if (kind == "cron") {
        val previous = base?.trigger?.takeIf { it.kind == "cron" }
        val cron = changes.cron ?: previous?.cron ?: throw UsageError("Use --cron with the five-field expression.")
        val timezone = changes.timezone ?: previous?.timezone
            ?: throw UsageError("--timezone is required with --cron: an IANA name such as Europe/Paris (the server's clock zone is never used).")
        AutomationTriggerView("cron", cron, timezone, changes.catchup ?: previous?.catchup, changes.catchupGraceMinutes ?: previous?.catchupGraceMinutes)
    } else {
        if (cronOptions) throw UsageError("--timezone, --catchup and --catchup-grace-minutes go with --cron, not a webhook.")
        AutomationTriggerView("webhook")
    }
    val previousAction = base?.action
    val actionKind = changes.action ?: previousAction?.kind ?: "cloud"
    val profile = changes.profileId ?: previousAction?.profileId
    // `remote` is sent as is: the server refuses it (`not_available_yet`) and the CLI says what that means.
    if (actionKind == "cloud" && profile == null) throw UsageError("Use --profile ID|NAME (see teamcode cloud profiles).")
    // The contract's own defaults (runtime, overlap, daily budget) apply to what is not given: no literal is repeated here.
    val action = AutomationActionView(actionKind, if (actionKind == "cloud") profile else null).let { fresh ->
        fresh.copy(
            model = if (changes.clearModel) null else changes.model ?: previousAction?.model,
            effort = if (changes.clearEffort) null else changes.effort ?: previousAction?.effort,
            maxRuntimeMinutes = changes.maxRuntimeMinutes ?: previousAction?.maxRuntimeMinutes ?: fresh.maxRuntimeMinutes,
        )
    }
    val prompt = changes.prompt ?: base?.prompt ?: throw UsageError("Use --prompt TEXT, --prompt-file FILE or --prompt - (standard input).")
    if (prompt.isBlank()) throw CliUserException("The prompt is empty.")
    val variables = changes.variables ?: base?.variables.orEmpty()
    if (kind == "cron" && (changes.variables?.isNotEmpty() == true)) throw UsageError("Payload variables (--var, --variables-file) belong to a webhook automation.")
    if (kind == "cron" && changes.samplePayload != null) throw UsageError("--sample-payload belongs to a webhook automation.")
    val fresh = AutomationInput(changes.name ?: base?.name ?: throw UsageError("Use --name TEXT."), trigger, action, prompt)
    return fresh.copy(
        variables = if (kind == "webhook") variables else emptyList(),
        samplePayload = if (kind != "webhook" || changes.clearSamplePayload) null else changes.samplePayload ?: base?.samplePayload,
        overlap = changes.overlap ?: base?.overlap ?: fresh.overlap,
        maxRunsPerDay = changes.maxRunsPerDay ?: base?.maxRunsPerDay ?: fresh.maxRunsPerDay,
    )
}

/** An [AutomationView] as the definition it was saved from, the base of an edit. */
internal fun AutomationView.toInput() = AutomationInput(name, trigger, action, prompt, variables, samplePayload, overlap, maxRunsPerDay)

/**
 * `--var 'NAME=$.json.path[;type=int][;values=a,b][;max=N][;optional][;sensitive]'`: a payload variable, written
 * `{{NAME}}` in the prompt. `values=` makes it an enum; the type is a string otherwise. The `;` of a quoted path
 * segment (`$['a;b']`) does not separate options.
 */
internal fun parseVariable(text: String): AutomationVariableView {
    val name = text.substringBefore('=', "")
    if (name.isEmpty() || '=' !in text) throw UsageError("Use --var 'NAME=\$.path[;type=int][;values=a,b][;max=N][;optional][;sensitive]'.")
    val parts = splitOutsideBrackets(text.substringAfter('='))
    val path = parts.first()
    if (!path.startsWith("$")) throw UsageError("The path of variable $name must start with \$ (for example \$.ticket.number).")
    var type: String? = null
    var values = emptyList<String>()
    var max = DEFAULT_VARIABLE.maxLength
    var required = true
    var sensitive = false
    for (option in parts.drop(1)) {
        val key = option.substringBefore('=')
        val value = option.substringAfter('=', "")
        when (key) {
            "type" -> type = value.takeIf { it in Automations.variableTypes } ?: throw UsageError("Variable $name: type is one of ${Automations.variableTypes.joinToString(", ")}.")
            "values" -> values = value.split(',').filter { it.isNotEmpty() }.also { if (it.isEmpty()) throw UsageError("Variable $name: values= needs a comma-separated list.") }
            "max" -> max = value.toIntOrNull()?.takeIf { it in 1..4096 } ?: throw UsageError("Variable $name: max is a number from 1 to 4096.")
            "optional" -> required = false
            "required" -> required = true
            "sensitive" -> sensitive = true
            else -> throw UsageError("Variable $name: unknown option \"$key\" (type, values, max, optional, sensitive).")
        }
    }
    if (values.isNotEmpty() && type != null && type != "enum") throw UsageError("Variable $name: values= goes with type=enum.")
    if (values.isEmpty() && type == "enum") throw UsageError("Variable $name: an enum needs values=a,b.")
    return AutomationVariableView(name, path, type ?: if (values.isNotEmpty()) "enum" else DEFAULT_VARIABLE.type, values, max, required, sensitive)
}

private val DEFAULT_VARIABLE = AutomationVariableView("name", "\$")

/**
 * The path, then the options. Only the path can hold a `;`: inside the `[...]` of a segment, where a quoted key
 * (`['a;b]']`) may also hold `]`. The options are split plainly, and an unclosed bracket or quote is an error.
 */
private fun splitOutsideBrackets(text: String): List<String> {
    var depth = 0
    var quote: Char? = null
    for ((index, char) in text.withIndex()) {
        when {
            quote != null -> if (char == quote) quote = null
            depth > 0 && (char == '\'' || char == '"') -> quote = char
            char == '[' -> depth++
            char == ']' && depth > 0 -> depth--
            char == ';' && depth == 0 -> return listOf(text.substring(0, index)) + text.substring(index + 1).split(';')
        }
    }
    if (depth > 0 || quote != null) throw UsageError("The variable path has an unclosed [ or quote: $text".substringBefore(';'))
    return listOf(text)
}

/** The most the server accepts for a payload; larger files are refused here instead of after the upload. */
internal const val MAX_PAYLOAD_BYTES = 64 * 1024

/** Parses a payload file or standard input. Never quotes the content in an error: it may hold secrets. */
internal fun parsePayload(text: String): JsonObject {
    if (text.encodeToByteArray().size > MAX_PAYLOAD_BYTES) throw CliUserException("The payload is larger than 64 KiB, the most a webhook accepts.")
    val element = try { Json.parseToJsonElement(text) } catch (_: Exception) { throw CliUserException("The payload is not valid JSON.") }
    return element as? JsonObject ?: throw CliUserException("The payload must be a JSON object ({ ... }).")
}

/** `--variables-file`: a JSON array of the API's variable objects. */
internal fun parseVariablesFile(text: String): List<AutomationVariableView> =
    try { me.nathanfallet.teamcode.api.Serialization.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AutomationVariableView.serializer()), text) }
    catch (_: Exception) { throw CliUserException("The variables file is not a JSON array of variables ({\"name\": ..., \"path\": \"\$.a.b\", \"type\": ...}).") }
