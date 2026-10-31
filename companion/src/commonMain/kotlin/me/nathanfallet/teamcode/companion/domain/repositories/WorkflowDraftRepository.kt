package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.StoredWorkflowDraft

/** The unsaved edits of a workflow, per account and per workflow ([NEW] for one not saved yet). They never leave the device. */
interface WorkflowDraftRepository {
    fun load(accountId: String, key: String): StoredWorkflowDraft?
    fun save(accountId: String, key: String, draft: StoredWorkflowDraft)
    fun clear(accountId: String, key: String)

    companion object { const val NEW = "new" }
}
