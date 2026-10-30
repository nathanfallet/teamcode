package me.nathanfallet.teamcode.companion.domain.usecases.automations

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.companion.domain.models.ActionKind
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationDefinition
import me.nathanfallet.teamcode.companion.domain.models.AutomationPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.AutomationRunPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.CreatedAutomation
import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.RunSource
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.models.parsePayload
import me.nathanfallet.teamcode.companion.domain.models.requireInput
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

const val AUTOMATION_NAME_MAX = 120
const val AUTOMATION_PROMPT_MAX = 32_000

/** Whether the Automations tab is offered: a signed-in account on a server that announces `automations.v1`. */
class AutomationsAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    suspend fun available(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        if (!account.signedIn) return false
        return try {
            Features.AUTOMATIONS in features.features(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Unknown for now (offline): hidden rather than offered and refused.
            false
        }
    }
}

/** What the use cases share: the capability check, the account guard and the repository. */
class AutomationAccess(val repository: AutomationRepository, val features: FeaturesRepository, val accounts: AccountRepository, val guard: AccountGuard) {
    suspend fun <T> call(accountId: String, call: suspend () -> T): T = guard(accountId) {
        if (Features.AUTOMATIONS !in features.features(accountId)) throw CompanionException.Unsupported(Features.AUTOMATIONS)
        call()
    }

    fun memberId(accountId: String): String = accounts.accounts.value.firstOrNull { it.id == accountId }?.memberId ?: throw CompanionException.Unauthorized()
    fun isAdministrator(accountId: String): Boolean = accounts.accounts.value.firstOrNull { it.id == accountId }?.isAdministrator == true
}

/** A page of automations; the team's only for an administrator. */
class LoadAutomationsUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, scope: AutomationScope, before: String? = null): AutomationPage {
        if (scope == AutomationScope.TEAM && !access.isAdministrator(accountId)) throw CompanionException.Unsupported("team")
        return access.call(accountId) { access.repository.automations(accountId, scope, before) }
    }
}

class GetAutomationUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, id: String): Automation = access.call(accountId) { access.repository.automation(accountId, id) }
}

/**
 * Checks a definition before anything is sent (the server checks again and words its refusals by code): a name, a
 * prompt, a cron expression with its zone for a schedule, a JSON object as the sample payload, a cloud action on a profile.
 */
fun checkDefinition(definition: AutomationDefinition) {
    val name = definition.name.trim()
    requireInput(name.isNotEmpty() && name.length <= AUTOMATION_NAME_MAX, InvalidInput.Problem.AUTOMATION_NAME, AUTOMATION_NAME_MAX, "Give the automation a name of 1 to $AUTOMATION_NAME_MAX characters.")
    requireInput(definition.prompt.isNotBlank() && definition.prompt.length <= AUTOMATION_PROMPT_MAX, InvalidInput.Problem.AUTOMATION_PROMPT, AUTOMATION_PROMPT_MAX, "Write a prompt of 1 to $AUTOMATION_PROMPT_MAX characters.")
    requireInput(definition.action.kind == ActionKind.CLOUD && !definition.action.profileId.isNullOrBlank(), InvalidInput.Problem.CHOOSE_PROFILE, message = "Choose a profile.")
    requireInput(definition.action.maxRuntimeMinutes in 1..1440 && definition.maxRunsPerDay in 1..1000, InvalidInput.Problem.AUTOMATION_LIMITS, message = "A run lasts 1 to 1440 minutes and an automation runs 1 to 1000 times a day.")
    if (definition.trigger.kind == TriggerKind.CRON) {
        requireInput(definition.trigger.cron?.trim()?.split(Regex("\\s+"))?.size == 5, InvalidInput.Problem.AUTOMATION_CRON, message = "A schedule has five fields: minute, hour, day of month, month, day of week.")
        requireInput(!definition.trigger.timezone.isNullOrBlank(), InvalidInput.Problem.AUTOMATION_TIMEZONE, message = "Enter a time zone such as Europe/Paris.")
    }
    if (definition.trigger.kind == TriggerKind.WEBHOOK) {
        requireInput(definition.variables.all { it.name.isNotBlank() && it.path.trim().startsWith("$") }, InvalidInput.Problem.AUTOMATION_VARIABLE, message = "Each variable needs a name and a path starting with $.")
        definition.samplePayload?.takeIf { it.isNotBlank() }?.let { requireInput(parsePayload(it) != null, InvalidInput.Problem.AUTOMATION_PAYLOAD, message = "The sample payload must be a JSON object.") }
    }
}

/** Creates (when [id] is null: disabled, with the webhook secret returned once) or changes an automation. */
class SaveAutomationUseCase(private val access: AutomationAccess) {
    suspend fun create(accountId: String, definition: AutomationDefinition): CreatedAutomation {
        checkDefinition(definition)
        return access.call(accountId) { access.repository.create(accountId, definition) }
    }

    suspend fun update(accountId: String, current: Automation, definition: AutomationDefinition): Automation {
        requireOwner(access, accountId, current)
        checkDefinition(definition)
        return access.call(accountId) { access.repository.update(accountId, current.id, current.version, definition) }
    }
}

private fun requireOwner(access: AutomationAccess, accountId: String, automation: Automation) =
    requireInput(automation.ownedBy(access.memberId(accountId)), InvalidInput.Problem.AUTOMATION_NOT_OWNER, message = "Only the owner changes, arms or tests an automation: it runs with the owner's quota and GitHub link.")

/** Arms (the owner only) or pauses (the owner, or an administrator for the team's) an automation. */
class SetAutomationEnabledUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, automation: AutomationOwned, enabled: Boolean): Automation {
        if (enabled) requireInput(automation.ownerId == access.memberId(accountId), InvalidInput.Problem.AUTOMATION_NOT_OWNER, message = "Only the owner arms an automation.")
        return access.call(accountId) { if (enabled) access.repository.enable(accountId, automation.id) else access.repository.disable(accountId, automation.id) }
    }
}

/** The owner and id of an automation, from a list row or a detail. */
data class AutomationOwned(val id: String, val ownerId: String)

class DeleteAutomationUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, id: String) = access.call(accountId) { access.repository.delete(accountId, id) }
}

/** A new webhook secret, shown once; the former one stays valid ten more minutes. Owner only. */
class RotateAutomationSecretUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, automation: Automation): CreatedAutomation {
        requireOwner(access, accountId, automation)
        return access.call(accountId) { access.repository.rotateSecret(accountId, automation.id) }
    }
}

/** The payload of a test: required for a webhook automation, refused for a cron one; a JSON object. */
private fun payloadOf(automation: Automation, text: String): JsonObject? {
    if (automation.trigger.kind != TriggerKind.WEBHOOK) return null
    return parsePayload(text) ?: throw InvalidInput(InvalidInput.Problem.AUTOMATION_PAYLOAD, 0, "The payload must be a JSON object.")
}

/** Renders the prompt of the saved automation with [payload] (JSON text): no run, no session, no quota. Administrators may. */
class PreviewAutomationUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, automation: Automation, payload: String): AutomationPreview {
        val body = payloadOf(automation, payload)
        return access.call(accountId) { access.repository.preview(accountId, automation.id, body) }
    }

    /** The editor's preview of an unsaved draft. */
    suspend fun draft(accountId: String, definition: AutomationDefinition, payload: String): AutomationPreview {
        checkDefinition(definition)
        val body = if (definition.trigger.kind == TriggerKind.WEBHOOK) parsePayload(payload) ?: throw InvalidInput(InvalidInput.Problem.AUTOMATION_PAYLOAD, 0, "The payload must be a JSON object.") else null
        return access.call(accountId) { access.repository.previewDraft(accountId, definition, body) }
    }
}

/**
 * A real test: a real session on the owner's quota, never on another member's automation (an administrator previews
 * those). [idempotencyKey] is kept by the caller for the form's lifetime, so a retry after a network failure returns the same run.
 */
class TestAutomationUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, automation: Automation, payload: String, idempotencyKey: String): AutomationRun {
        requireOwner(access, accountId, automation)
        val body = payloadOf(automation, payload)
        return access.call(accountId) { access.repository.test(accountId, automation.id, body, idempotencyKey) }
    }

    suspend fun refresh(accountId: String, run: AutomationRun): AutomationRun = access.call(accountId) { access.repository.run(accountId, run.automationId, run.id) }
}

/** A page of the history; [hideTests] leaves the test runs out. */
class LoadAutomationRunsUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, id: String, hideTests: Boolean, before: String? = null): AutomationRunPage {
        val sources = if (hideTests) setOf(RunSource.CRON, RunSource.WEBHOOK) else emptySet()
        return access.call(accountId) { access.repository.runs(accountId, id, before, sources, emptySet()) }
    }
}

/** Withdraws a waiting run, or pauses the session of a working one (the owner or an administrator). */
class CancelAutomationRunUseCase(private val access: AutomationAccess) {
    suspend operator fun invoke(accountId: String, run: AutomationRun): AutomationRun = access.call(accountId) { access.repository.cancelRun(accountId, run.automationId, run.id) }
}
