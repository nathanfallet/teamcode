package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationDefinition
import me.nathanfallet.teamcode.companion.domain.models.AutomationPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationPreview
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.AutomationRunPage
import me.nathanfallet.teamcode.companion.domain.models.AutomationScope
import me.nathanfallet.teamcode.companion.domain.models.CreatedAutomation
import me.nathanfallet.teamcode.companion.domain.models.RunSource

/** The automations of an account's server (`automations.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface AutomationRepository {
    /** Newest first; [before] is the previous page's cursor. */
    suspend fun automations(accountId: String, scope: AutomationScope, before: String?): AutomationPage
    suspend fun automation(accountId: String, id: String): Automation
    suspend fun create(accountId: String, definition: AutomationDefinition): CreatedAutomation
    /** [version] is the one being replaced: a definition saved elsewhere meanwhile is refused (`409 stale_version`). */
    suspend fun update(accountId: String, id: String, version: Int, definition: AutomationDefinition): Automation
    suspend fun delete(accountId: String, id: String)
    suspend fun enable(accountId: String, id: String): Automation
    suspend fun disable(accountId: String, id: String): Automation
    suspend fun rotateSecret(accountId: String, id: String): CreatedAutomation
    /** A real test: a real run on the owner's quota. [idempotencyKey]: a retry with the same key returns the same run. */
    suspend fun test(accountId: String, id: String, payload: JsonObject?, idempotencyKey: String): AutomationRun
    /** A dry run of the saved automation: renders the prompt, starts nothing. */
    suspend fun preview(accountId: String, id: String, payload: JsonObject?): AutomationPreview
    /** The same for an unsaved draft. */
    suspend fun previewDraft(accountId: String, definition: AutomationDefinition, payload: JsonObject?): AutomationPreview
    suspend fun runs(accountId: String, id: String, before: String?, sources: Set<RunSource>, statuses: Set<String>): AutomationRunPage
    suspend fun run(accountId: String, id: String, runId: String): AutomationRun
    suspend fun cancelRun(accountId: String, id: String, runId: String): AutomationRun
}
