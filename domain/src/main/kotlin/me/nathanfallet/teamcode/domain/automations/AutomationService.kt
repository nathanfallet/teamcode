package me.nathanfallet.teamcode.domain.automations

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.domain.cloud.CloudProfile
import me.nathanfallet.teamcode.domain.cloud.CloudSessionStore
import me.nathanfallet.teamcode.domain.cloud.CloudSettings
import me.nathanfallet.teamcode.domain.cloud.SecretSealer
import me.nathanfallet.teamcode.domain.team.*

/** What automations read from cloud sessions: profiles (to check the owner's assignment) and the team's caps. */
interface AutomationDirectory {
    suspend fun profile(teamId: String, profileId: String): CloudProfile?
    suspend fun settings(teamId: String): CloudSettings

    /** Reads them from the cloud session store. */
    class Cloud(private val store: CloudSessionStore) : AutomationDirectory {
        override suspend fun profile(teamId: String, profileId: String) = store.profile(teamId, profileId)
        override suspend fun settings(teamId: String) = store.settings(teamId)
    }
}

class CreatedAutomation(val automation: Automation, val secret: String?) {
    override fun toString() = "CreatedAutomation(automation=${automation.id}, secret=[REDACTED])"
}

/** A dry run or a draft preview; [renderedPrompt] is never stored or logged. */
data class AutomationPreview(
    val renderedPrompt: String?, val variables: List<ExtractedVariable>, val problems: List<VariableProblem>, val profileVersion: Int?,
    val wouldSkip: String?, val nextRuns: List<Long>,
)

/** A webhook request as received: the automation id and optional version from the path, the proof, and the raw body. */
class TriggerRequest(
    val automationId: String, val version: Int?, val token: String?, val timestamp: String?, val signature: String?,
    val deliveryKey: String?, val body: ByteArray,
) { override fun toString() = "TriggerRequest(automation=$automationId, version=$version)" }

/**
 * Management, tests and the webhook receiver. Business rules live here; routes only decode and encode
 * (docs/automations.md). The owner manages their automations; an administrator reads the team's, pauses, deletes and
 * previews them, never arms or runs another member's.
 */
class AutomationService(
    private val store: AutomationStore,
    private val directory: AutomationDirectory,
    private val sealer: SecretSealer,
    private val random: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) },
) {
    private fun missing() = TeamFailure("not_found", 404, "Automation not found.")
    private fun ownerOnly() = TeamFailure("automation_owner_only", 403, "Only the automation's owner can do this: it runs with their quota, tools and GitHub link.")

    private fun canSeeTeam(actor: Principal) = actor.canReadTeamCloud
    private fun visible(actor: Principal, automation: Automation?): Automation {
        if (automation == null || (automation.ownerId != actor.member.id && !canSeeTeam(actor))) throw missing()
        return automation
    }
    private suspend fun read(actor: Principal, id: String): Automation { actor.requireCloudRead(); store.revalidate(actor); return visible(actor, store.find(actor.teamId, id)) }
    private suspend fun owned(actor: Principal, id: String): Automation {
        actor.requireInteractive(); actor.requireReady()
        store.revalidate(actor)
        val automation = visible(actor, store.find(actor.teamId, id))
        if (automation.ownerId != actor.member.id) throw ownerOnly()
        return automation
    }

    suspend fun list(actor: Principal, owner: String?, before: String?, limit: Int): AutomationPage {
        actor.requireCloudRead()
        if (limit !in 1..100) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 100.")
        val target = owner ?: actor.member.id
        if (target != actor.member.id && !canSeeTeam(actor)) throw TeamFailure("forbidden", 403, "Administrator access is required to read other members' automations.")
        store.revalidate(actor)
        return store.list(actor.teamId, target.takeUnless { it == ALL }, before, limit)
    }

    suspend fun get(actor: Principal, id: String) = read(actor, id)

    suspend fun create(actor: Principal, definition: AutomationDefinition): CreatedAutomation {
        actor.requireInteractive(); actor.requireReady()
        val now = store.now()
        AutomationRules.validate(definition, now)
        requireProfile(actor.teamId, actor.member, definition)
        val settings = directory.settings(actor.teamId)
        val id = UUID.randomUUID().toString()
        val secret = if (definition.trigger.kind == TriggerKind.WEBHOOK) newSecret() else null
        val draft = Automation(id, actor.teamId, actor.member.id, actor.member.username, definition, enabled = false, disabledReason = DISABLED, disabledBy = null,
            version = 1, nextRunAt = null, consecutiveFailures = 0, lastRun = null, secretRotatedAt = now, previousSecretValidUntil = null, createdAt = now, updatedAt = now)
        val created = store.create(actor, draft, secret?.let { sealer.seal(it.encodeToByteArray(), context(id)) }, settings.maxAutomationsPerMember)
        return CreatedAutomation(created, secret)
    }

    suspend fun update(actor: Principal, id: String, version: Int, definition: AutomationDefinition): Automation {
        val current = owned(actor, id)
        if (definition.trigger.kind != current.definition.trigger.kind) throw AutomationRules.invalid("The trigger kind of an automation cannot change: create another one.")
        val now = store.now()
        AutomationRules.validate(definition, now)
        requireProfile(actor.teamId, actor.member, definition)
        return store.update(actor, id, version, definition, if (current.enabled) AutomationRules.nextRun(definition, now) else null)
    }

    /** Arms it: the owner only, interactively; the owner's GitHub link is lent to its sessions from now on. */
    suspend fun enable(actor: Principal, id: String): Automation {
        val current = owned(actor, id)
        val now = store.now()
        // The rules may have changed since it was saved (a timezone gone, a stricter limit): arming checks them again.
        AutomationRules.validate(current.definition, now)
        requireProfile(actor.teamId, actor.member, current.definition)
        return store.setEnabled(actor, id, true, AutomationRules.nextRun(current.definition, now))
    }

    /** Pauses it: the owner, or an interactive administrator for any automation of the team. */
    suspend fun disable(actor: Principal, id: String): Automation {
        manageable(actor, id)
        return store.setEnabled(actor, id, false, null)
    }

    suspend fun delete(actor: Principal, id: String) { manageable(actor, id); store.delete(actor, id) }

    private suspend fun manageable(actor: Principal, id: String): Automation {
        actor.requireInteractive(); actor.requireReady()
        store.revalidate(actor)
        val automation = visible(actor, store.find(actor.teamId, id))
        if (automation.ownerId != actor.member.id && !actor.isCloudAdministrator) throw ownerOnly()
        return automation
    }

    suspend fun rotateSecret(actor: Principal, id: String): CreatedAutomation {
        val current = owned(actor, id)
        if (current.definition.trigger.kind != TriggerKind.WEBHOOK) throw TeamFailure("not_webhook", 409, "Only a webhook automation has a secret.")
        val secret = newSecret()
        val rotated = store.rotateSecret(actor, id, sealer.seal(secret.encodeToByteArray(), context(id)), store.now() + AutomationRules.PREVIOUS_SECRET_GRACE_MILLIS)
        return CreatedAutomation(rotated, secret)
    }

    /** The editor's preview of an unsaved definition, as its owner would save it. */
    suspend fun preview(actor: Principal, definition: AutomationDefinition, payload: JsonObject?): AutomationPreview {
        actor.requireCloudRead()
        store.revalidate(actor)
        val now = store.now()
        AutomationRules.validate(definition, now)
        val profile = requireProfile(actor.teamId, actor.member, definition)
        val draft = Automation(PREVIEW_ID, actor.teamId, actor.member.id, actor.member.username, definition, false, DISABLED, null, 0, null, 0, null, now, null, now, now)
        return render(draft, payload, RunSource.TEST, now, profile.version, wouldSkip = null)
    }

    /**
     * A test of the saved definition. [dryRun] previews it (owner or cloud administrator); otherwise a real run as the
     * owner (the owner, interactively or with their `cloud:write` key), deduplicated by [idempotencyKey].
     */
    suspend fun test(actor: Principal, id: String, payload: JsonObject?, dryRun: Boolean, idempotencyKey: String?): TestOutcome {
        if (dryRun) actor.requireCloudRead() else actor.requireCloudWrite()
        store.revalidate(actor)
        val automation = visible(actor, store.find(actor.teamId, id))
        if (automation.ownerId != actor.member.id && (!dryRun || !actor.isCloudAdministrator)) throw ownerOnly()
        val webhook = automation.definition.trigger.kind == TriggerKind.WEBHOOK
        if (!webhook && payload != null) throw TeamFailure("payload_not_accepted", 422, "A cron automation takes no payload: its prompt uses the run variables only.")
        if (webhook && payload == null) throw TeamFailure("payload_required", 422, "Send the JSON payload the webhook would receive (the saved sample pre-fills it).")
        if (payload != null && payload.toString().encodeToByteArray().size > AutomationRules.MAX_PAYLOAD_BYTES) throw TeamFailure("payload_too_large", 413, "The payload is at most 64 KiB.")
        val now = store.now()
        if (dryRun) {
            val owner = store.member(actor.teamId, automation.ownerId) ?: throw missing()
            val profile = requireProfile(actor.teamId, owner, automation.definition)
            val preview = render(automation, payload, RunSource.TEST, now, profile.version, wouldSkip = if (!automation.enabled) DISABLED else store.wouldSkip(id))
            store.auditPreview(actor, id)
            return TestOutcome.Preview(preview)
        }
        val key = idempotencyKey ?: throw TeamFailure("idempotency_key_required", 400, "Send an Idempotency-Key header with a real test.")
        if (!AutomationRules.deliveryKey.matches(key)) throw TeamFailure("invalid_idempotency_key", 400, "Use an Idempotency-Key of 1 to 128 characters among A-Z, a-z, 0-9, '.', '_', ':' and '-'.")
        val run = prepareRun(automation, RunSource.TEST, "test:${actor.member.id}:$key", payload, now, scheduledFor = null, actor = actor,
            requestDigest = AutomationRules.sha256((payload?.toString() ?: "").encodeToByteArray()))
        val outcome = store.enqueue(actor, id, run.run)
        if (outcome.run.status == RunStatus.INVALID_PAYLOAD) throw invalidPayload(run.problems)
        return TestOutcome.Run(outcome)
    }

    /** The webhook: every refusal before the proof is the same `401`. */
    suspend fun trigger(request: TriggerRequest): RunOutcome {
        val denied = TeamFailure("invalid_signature", 401, "The request is not signed with this automation's secret.")
        if (request.body.size > AutomationRules.MAX_PAYLOAD_BYTES) throw TeamFailure("payload_too_large", 413, "The body is at most 64 KiB.")
        val (automation, sealed) = store.forTrigger(request.automationId) ?: throw denied
        val now = store.now()
        val secrets = listOfNotNull(sealed.current, sealed.previous?.takeIf { (sealed.previousValidUntil ?: 0) > now })
            .mapNotNull { sealer.open(it, context(automation.id))?.decodeToString() }
        val proven = if (request.token != null) AutomationRules.tokenMatches(secrets, request.token)
            else AutomationRules.signed(secrets, request.timestamp, request.signature, request.body, now / 1000)
        if (!proven || automation.definition.trigger.kind != TriggerKind.WEBHOOK) throw denied
        if (request.version != null && request.version != automation.version)
            throw TeamFailure("automation_version_changed", 409, "This URL is pinned to version ${request.version}; the automation is at version ${automation.version}. Use the URL of the current version, or the latest one.")
        if (!automation.enabled) throw TeamFailure("automation_disabled", 409, "This automation is paused.")
        val payload = try { kotlinx.serialization.json.Json.parseToJsonElement(request.body.decodeToString()).jsonObject } catch (_: IllegalArgumentException) { null }
        val key = request.deliveryKey?.also { if (!AutomationRules.deliveryKey.matches(it)) throw TeamFailure("invalid_idempotency_key", 400, "Use a delivery key of 1 to 128 characters among A-Z, a-z, 0-9, '.', '_', ':' and '-'.") }
            ?.let { "webhook:$it" } ?: "body:${AutomationRules.sha256(request.body)}:${now / AutomationRules.BODY_DEDUPLICATION_MILLIS}"
        val run = prepareRun(automation, RunSource.WEBHOOK, key, payload, now, scheduledFor = null, actor = null, notObject = payload == null)
        val outcome = store.enqueue(null, automation.id, run.run)
        if (outcome.run.status == RunStatus.INVALID_PAYLOAD) throw invalidPayload(run.problems.ifEmpty { listOf(VariableProblem("$", "not_object")) })
        if (outcome.run.status == RunStatus.SKIPPED_BUDGET && outcome is RunOutcome.Created)
            throw TeamFailure("automation_budget", 429, "This automation reached its runs per day.", retryAfterSeconds = 3_600)
        return outcome
    }

    suspend fun runs(actor: Principal, id: String, query: RunQuery): RunPage {
        if (query.limit !in 1..100) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 100.")
        read(actor, id)
        return store.runs(actor.teamId, id, query)
    }

    suspend fun run(actor: Principal, id: String, runId: String): AutomationRun {
        read(actor, id)
        return store.run(actor.teamId, id, runId) ?: throw TeamFailure("not_found", 404, "Run not found.")
    }

    /** The owner (interactively or with `cloud:write`) or a cloud administrator. */
    suspend fun cancel(actor: Principal, id: String, runId: String): AutomationRun {
        actor.requireCloudWrite()
        store.revalidate(actor)
        val automation = visible(actor, store.find(actor.teamId, id))
        if (automation.ownerId != actor.member.id && !actor.isCloudAdministrator) throw ownerOnly()
        return store.requestCancel(actor, id, runId)
    }

    private suspend fun requireProfile(teamId: String, owner: Member, definition: AutomationDefinition): CloudProfile {
        val profile = definition.action.profileId?.let { directory.profile(teamId, it) }
            ?: throw TeamFailure("profile_not_found", 404, "No profile has this id. List the profiles you can use with GET /api/v1/cloud/profiles.")
        if (!owner.role.isAdministrator && owner.id !in profile.assignedMemberIds) throw TeamFailure("profile_unassigned", 403, "This profile is not assigned to the automation's owner.")
        return profile
    }

    private fun render(automation: Automation, payload: JsonObject?, source: RunSource, now: Long, profileVersion: Int?, wouldSkip: String?): AutomationPreview {
        val extraction = AutomationTemplate.extract(automation.definition.variables, payload)
        val values = extraction.values + runVariables(automation, PREVIEW_RUN, source, now)
        val prompt = if (extraction.problems.isEmpty()) AutomationTemplate.render(automation.definition.prompt, values) else null
        val nextRuns = automation.definition.trigger.takeIf { it.kind == TriggerKind.CRON }?.let { CronSchedule.parse(it.cron!!, it.timezone!!, now).upcoming(now, 3) }.orEmpty()
        return AutomationPreview(prompt, extraction.extracted, extraction.problems, profileVersion, wouldSkip, nextRuns)
    }

    private fun newSecret() = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random())

    companion object {
        const val ALL = "*"
        const val DISABLED = "disabled"
        const val OWNER_UNAVAILABLE = "owner_unavailable"
        private const val PREVIEW_ID = "preview"
        private const val PREVIEW_RUN = "preview"
        fun context(automationId: String) = "automation:$automationId"

        fun invalidPayload(problems: List<VariableProblem>) =
            TeamFailure("invalid_payload", 422, "The payload does not match the automation's variables: ${AutomationRules.describe(problems)}.")

        /** The run variables, read at [at] in the automation's timezone (UTC for a webhook). */
        fun runVariables(automation: Automation, runId: String, source: RunSource, at: Long): Map<String, String> {
            val zone = automation.definition.trigger.timezone?.let { ZoneId.of(it) } ?: ZoneOffset.UTC
            val local = Instant.ofEpochMilli(at).atZone(zone)
            return mapOf("run.id" to runId, "run.date" to local.toLocalDate().toString(), "run.time" to "%02d:%02d".format(local.hour, local.minute),
                "run.timezone" to zone.id, "run.source" to source.wire, "automation.id" to automation.id, "automation.name" to automation.definition.name)
        }
    }
}

/** A test's result: a preview, or the run it recorded (or replayed). */
sealed interface TestOutcome {
    data class Preview(val preview: AutomationPreview) : TestOutcome
    data class Run(val outcome: RunOutcome) : TestOutcome
}

/** A run ready to record, with the problems its payload had (the run is then `invalid_payload`). */
class PreparedRun(val run: NewRun, val problems: List<VariableProblem>)

/** Extracts, validates and renders a trigger of [automation]; a payload that is not an object ([notObject]) is invalid. */
fun prepareRun(automation: Automation, source: RunSource, deliveryKey: String, payload: JsonObject?, at: Long, scheduledFor: Long?, actor: Principal?,
    requestDigest: String? = null, notObject: Boolean = false): PreparedRun {
    val id = UUID.randomUUID().toString()
    val extraction = if (notObject) AutomationExtraction(emptyMap(), emptyList(), listOf(VariableProblem("$", "not_object")))
        else AutomationTemplate.extract(automation.definition.variables, payload)
    val invalid = extraction.problems.isNotEmpty()
    val prompt = if (invalid) null else AutomationTemplate.render(automation.definition.prompt, extraction.values + AutomationService.runVariables(automation, id, source, scheduledFor ?: at))
    return PreparedRun(NewRun(id, automation.id, source, deliveryKey, if (invalid) RunStatus.INVALID_PAYLOAD else RunStatus.QUEUED, if (invalid) "invalid_payload" else null,
        prompt, scheduledFor, extraction.extracted, AutomationTemplate.digest(extraction.values), actor?.member?.id, actor?.tokenId, requestDigest), extraction.problems)
}
