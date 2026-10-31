package me.nathanfallet.teamcode.domain.automations

import java.security.MessageDigest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.workflows.NodeKind
import me.nathanfallet.teamcode.domain.workflows.WorkflowDefinition

/** Bounds and pure checks of automations (docs/automations.md). */
object AutomationRules {
    const val MAX_NAME = 120
    const val DEFAULT_GRACE_MINUTES = 360
    const val MAX_GRACE_MINUTES = 1_440
    const val MAX_RUNTIME_MINUTES = 1_440
    const val MAX_RUNS_PER_DAY = 1_000
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    /** A slot found later than this is a catch-up, not an ordinary tick. */
    const val ON_TIME_MILLIS = 2 * 60_000L
    const val DAY_MILLIS = 24 * 3_600_000L
    const val WEBHOOK_PER_MINUTE = 30
    const val SIGNATURE_WINDOW_SECONDS = 300L
    const val PREVIOUS_SECRET_GRACE_MILLIS = 10 * 60_000L
    /** Without a delivery key, identical bodies within this window are one delivery. */
    const val BODY_DEDUPLICATION_MILLIS = 60_000L
    /** A queued run waits this long for capacity, then expires. */
    const val QUEUE_TIMEOUT_MILLIS = 30 * 60_000L
    const val RETENTION_MILLIS = 14 * DAY_MILLIS
    /** The sessions of an automation's latest runs it keeps; older ones are deleted when a run ends. */
    const val KEEP_SESSIONS = 10
    const val DEFAULT_MAX_PER_MEMBER = 20
    const val DEFAULT_RUNNING_PER_MEMBER = 2
    const val MAX_INPUTS = 50
    val deliveryKey = Regex("[A-Za-z0-9._:-]{1,128}")
    private val idPattern = Regex("[a-zA-Z0-9-]{1,36}")
    private val inputId = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val modelValue = Regex("[a-zA-Z0-9._:/-]{1,120}")

    fun invalid(message: String) = TeamFailure("invalid_automation", 400, message)

    /** Everything but the profile assignment, which needs the owner and the store. */
    fun validate(definition: AutomationDefinition, now: Long = System.currentTimeMillis()) {
        val name = definition.name
        if (name.isBlank() || name.length > MAX_NAME || name.any { it.isISOControl() }) throw invalid("Use a name of 1 to $MAX_NAME characters without control characters.")
        val trigger = definition.trigger
        when (trigger.kind) {
            TriggerKind.CRON -> {
                CronSchedule.parse(trigger.cron ?: throw invalid("A cron automation needs its cron expression."), trigger.timezone ?: throw invalid("A cron automation needs its timezone."), now)
                if (trigger.catchupGraceMinutes !in 0..MAX_GRACE_MINUTES) throw invalid("The catch-up grace is 0 to $MAX_GRACE_MINUTES minutes.")
                if (definition.samplePayload != null) throw invalid("Only a webhook automation has a sample payload.")
            }
            TriggerKind.WEBHOOK -> if (trigger.cron != null || trigger.timezone != null) throw invalid("A webhook automation has no cron expression or timezone.")
        }
        val action = definition.action
        if (action.kind == ActionKind.REMOTE) throw TeamFailure("not_available_yet", 422, "Remote sessions are not available to automations yet: use a cloud action.")
        if (definition.maxRunsPerDay !in 1..MAX_RUNS_PER_DAY) throw invalid("The runs per day are 1 to $MAX_RUNS_PER_DAY.")
        if (action.maxRuntimeMinutes !in 1..MAX_RUNTIME_MINUTES) throw invalid("The maximum runtime is 1 to $MAX_RUNTIME_MINUTES minutes.")
        if (action.kind == ActionKind.WORKFLOW) validateWorkflowAction(definition)
        else {
            if (action.workflowId != null || action.inputs.isNotEmpty()) throw invalid("Only a workflow action has a workflow and inputs.")
            if (action.profileId.isNullOrBlank() || !Regex("[a-zA-Z0-9-]{1,36}").matches(action.profileId)) throw invalid("A cloud action needs the profileId of a profile assigned to the owner.")
            for (value in listOfNotNull(action.model, action.effort)) if (!modelValue.matches(value)) throw invalid("Use a valid agent model and effort.")
            AutomationTemplate.validateDefinition(definition.prompt, definition.variables, trigger.kind == TriggerKind.WEBHOOK)
        }
        definition.samplePayload?.let { sample ->
            if (sample.toString().encodeToByteArray().size > MAX_PAYLOAD_BYTES) throw invalid("The sample payload is at most 64 KiB.")
            val problems = AutomationTemplate.extract(definition.variables, sample).problems
            if (problems.isNotEmpty()) throw invalid("The sample payload does not match the variables: ${describe(problems)}.")
        }
    }

    /**
     * A workflow action: no prompt (the inputs carry the text), no agent model or effort (the workflow's nodes choose), a profile
     * only to give agent nodes that name none, and every input value a template over the declared and the run variables.
     */
    private fun validateWorkflowAction(definition: AutomationDefinition) {
        val action = definition.action
        if (action.workflowId.isNullOrBlank() || !idPattern.matches(action.workflowId)) throw invalid("A workflow action needs the workflowId of a saved workflow.")
        if (definition.prompt.isNotEmpty()) throw invalid("A workflow action has no prompt: write the text of each input instead.")
        if (action.model != null || action.effort != null) throw invalid("A workflow action has no model or effort: the workflow's agent nodes choose.")
        if (action.profileId != null && !idPattern.matches(action.profileId)) throw invalid("The profileId is not a profile id.")
        if (action.inputs.size > MAX_INPUTS) throw invalid("A workflow action has at most $MAX_INPUTS inputs.")
        AutomationTemplate.validateVariables(definition.variables, definition.trigger.kind == TriggerKind.WEBHOOK)
        for ((id, text) in action.inputs) {
            if (!inputId.matches(id)) throw invalid("The input id $id is not valid.")
            if (text.length > AutomationTemplate.MAX_PROMPT) throw invalid("The input $id is at most ${AutomationTemplate.MAX_PROMPT} characters.")
            AutomationTemplate.checkPlaceholders("The input $id", text, definition.variables)
        }
    }

    /**
     * The inputs of a workflow action against the workflow it names: each declared by the workflow, and each required one (no
     * default) given. `invalid_automation`, naming the input.
     */
    fun checkWorkflowInputs(action: AutomationAction, workflow: WorkflowDefinition) {
        val declared = workflow.inputs.associateBy { it.id }
        action.inputs.keys.firstOrNull { it !in declared }?.let { throw invalid("The workflow ${workflow.name} has no input $it.") }
        workflow.inputs.firstOrNull { it.required && it.default.isNullOrEmpty() && action.inputs[it.id].isNullOrEmpty() }
            ?.let { throw invalid("The input ${it.id} of the workflow ${workflow.name} is required and has no default: give it a value.") }
        if (action.profileId == null && workflow.nodes.any { it.kind == NodeKind.AGENT && it.profileId == null })
            throw invalid("The workflow ${workflow.name} has agent nodes without a profile: choose the profile they run with.")
    }

    private val inputsSerializer = MapSerializer(String.serializer(), String.serializer())
    fun encodeInputs(inputs: Map<String, String>): String = kotlinx.serialization.json.Json.encodeToString(inputsSerializer, inputs)
    fun decodeInputs(text: String): Map<String, String> = kotlinx.serialization.json.Json.decodeFromString(inputsSerializer, text)

    /** The values a workflow action's inputs take for one run: each template rendered, an empty one left out so the workflow's default applies. */
    fun renderInputs(inputs: Map<String, String>, values: Map<String, String>): Map<String, String> =
        inputs.mapValues { (_, text) -> AutomationTemplate.render(text, values) }.filterValues { it.isNotEmpty() }

    /** `$.ticket.number: type:int, $.kind: enum` for an error message: paths and rules only. */
    fun describe(problems: List<VariableProblem>) = problems.take(10).joinToString(", ") { "${it.path}: ${it.rule}" }

    /** The next slot after [now] of an enabled cron automation; null for a webhook. */
    fun nextRun(definition: AutomationDefinition, now: Long): Long? {
        val trigger = definition.trigger
        if (trigger.kind != TriggerKind.CRON) return null
        return CronSchedule.parse(trigger.cron!!, trigger.timezone!!, now).next(now)
    }

    /** The next three slots of an enabled cron automation, from its stored next slot; empty otherwise. */
    fun upcoming(automation: Automation): List<Long> {
        val trigger = automation.definition.trigger
        val next = automation.nextRunAt?.takeIf { automation.enabled && trigger.kind == TriggerKind.CRON } ?: return emptyList()
        return try { listOf(next) + CronSchedule.parse(trigger.cron!!, trigger.timezone!!, next).upcoming(next, 2) } catch (_: TeamFailure) { listOf(next) }
    }

    /** `v1=` and the hex HMAC-SHA256 of `<timestamp>.<raw body>`, as outgoing webhooks sign. */
    fun signature(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        mac.update(timestamp.encodeToByteArray()); mac.update('.'.code.toByte())
        return "v1=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** A signature of one of [secrets] over a timestamp at most five minutes from [nowSeconds], compared in constant time. */
    fun signed(secrets: List<String>, timestamp: String?, header: String?, body: ByteArray, nowSeconds: Long): Boolean {
        if (timestamp == null || header == null || timestamp.length > 12 || !timestamp.all(Char::isDigit) || header.length > 80) return false
        if (kotlin.math.abs(nowSeconds - timestamp.toLong()) > SIGNATURE_WINDOW_SECONDS) return false
        return secrets.fold(false) { found, secret -> MessageDigest.isEqual(signature(secret, timestamp, body).encodeToByteArray(), header.encodeToByteArray()) || found }
    }

    /** The secret-URL mode: the path segment equals one of [secrets], compared in constant time. */
    fun tokenMatches(secrets: List<String>, token: String): Boolean =
        secrets.fold(false) { found, secret -> MessageDigest.isEqual(secret.encodeToByteArray(), token.encodeToByteArray()) || found }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
