package me.nathanfallet.teamcode.companion.infrastructure.settings

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.companion.domain.models.StoredWorkflowDraft
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowDraftRepository

/** Drafts of workflow definitions in the device's settings, as JSON; a draft that cannot be read is dropped. */
class SettingsWorkflowDraftRepository(private val settings: Settings) : WorkflowDraftRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(accountId: String, key: String): StoredWorkflowDraft? {
        val text = settings.getStringOrNull(name(accountId, key)) ?: return null
        return try {
            json.decodeFromString(StoredWorkflowDraft.serializer(), text)
        } catch (_: Exception) {
            settings.remove(name(accountId, key))
            null
        }
    }

    override fun save(accountId: String, key: String, draft: StoredWorkflowDraft) {
        settings.putString(name(accountId, key), json.encodeToString(StoredWorkflowDraft.serializer(), draft))
    }

    override fun clear(accountId: String, key: String) = settings.remove(name(accountId, key))

    private fun name(accountId: String, key: String) = "workflows.draft.$accountId.$key"
}
