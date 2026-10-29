package me.nathanfallet.teamcode.domain.workflows

import me.nathanfallet.teamcode.domain.team.*

data class WorkflowPage(val workflows: List<Workflow>, val nextAfter: String?)

/** A saved workflow with whether the caller may edit it, and the editing rule. */
data class WorkflowSettings(val editors: WorkflowEditors, val canEdit: Boolean)

interface WorkflowStore {
    suspend fun revalidate(actor: Principal)
    suspend fun member(teamId: String, memberId: String): Member?
    suspend fun editors(teamId: String): WorkflowEditors
    /** Stores the rule and audits `workflow.editors.changed`. */
    suspend fun saveEditors(actor: Principal, editors: WorkflowEditors)
    suspend fun find(teamId: String, id: String): Workflow?
    /** By id, ascending, after [after]: a keyset, one query. */
    suspend fun list(teamId: String, after: String?, limit: Int): WorkflowPage
    /**
     * Creates ([id] null) or replaces a workflow in one transaction, under the team's workflow lock, so that two
     * replicas saving at once cannot create a call loop: [check] gets every other workflow's definition and may throw.
     * A replacement fails `409 workflow_conflict` when the stored version is not [expectedVersion] (when given), and
     * both fail `409 workflow_name_taken` for a name another workflow has, `409 workflow_limit` past [limit].
     * Audited `workflow.created` / `workflow.updated`.
     */
    suspend fun save(actor: Principal, id: String?, definition: WorkflowDefinition, expectedVersion: Int?, limit: Int, check: (Map<String, WorkflowDefinition>) -> Unit): Workflow
    /** Under the same lock: `409 workflow_in_use` while another workflow calls it. Audited `workflow.deleted`. */
    suspend fun delete(actor: Principal, id: String): Workflow
}

/** Reading, saving and deleting workflow definitions (docs/workflows.md). Runs are a separate piece of work. */
class WorkflowService(private val store: WorkflowStore) {
    private fun missing() = TeamFailure("not_found", 404, "Workflow not found.")

    private suspend fun mayEdit(actor: Principal): Boolean {
        val editors = store.editors(actor.teamId)
        return when (editors.mode) {
            EditorMode.ALL -> true
            EditorMode.ADMINS -> actor.member.role.isAdministrator
            EditorMode.MEMBERS -> actor.member.role.isAdministrator || actor.member.id in editors.memberIds
        }
    }

    /** Editing is a person's act: an interactive login, never a key, whatever the rule says. */
    private suspend fun editor(actor: Principal) {
        actor.requireInteractive(); actor.requireReady(); store.revalidate(actor)
        if (!mayEdit(actor)) throw TeamFailure("workflow_editors_only", 403, "Only the members allowed to edit workflows can do this.")
    }

    suspend fun list(actor: Principal, after: String?, limit: Int): WorkflowPage {
        actor.requireCloudRead(); store.revalidate(actor)
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_PAGE.")
        return store.list(actor.teamId, after, limit)
    }

    suspend fun get(actor: Principal, id: String): Workflow {
        actor.requireCloudRead(); store.revalidate(actor)
        return store.find(actor.teamId, id) ?: throw missing()
    }

    suspend fun create(actor: Principal, definition: WorkflowDefinition): Workflow {
        editor(actor)
        val valid = WorkflowRules.validate(definition)
        return store.save(actor, null, valid, null, MAX_WORKFLOWS) { WorkflowRules.checkSubWorkflows(null, valid, it) }
    }

    suspend fun update(actor: Principal, id: String, definition: WorkflowDefinition, expectedVersion: Int?): Workflow {
        editor(actor)
        val valid = WorkflowRules.validate(definition)
        if (store.find(actor.teamId, id) == null) throw missing()
        return store.save(actor, id, valid, expectedVersion, MAX_WORKFLOWS) { WorkflowRules.checkSubWorkflows(id, valid, it) }
    }

    suspend fun delete(actor: Principal, id: String): Workflow { editor(actor); return store.delete(actor, id) }

    suspend fun settings(actor: Principal): WorkflowSettings {
        actor.requireCloudRead(); store.revalidate(actor)
        return WorkflowSettings(store.editors(actor.teamId), actor.canEditWorkflows())
    }

    private suspend fun Principal.canEditWorkflows() = serviceScopes == null && mayEdit(this)

    /** Administrators only, interactively. A member list names active members of the team. */
    suspend fun saveEditors(actor: Principal, editors: WorkflowEditors): WorkflowSettings {
        actor.requireInteractive(); actor.requireReady(); store.revalidate(actor)
        if (!actor.member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Administrator access is required.")
        if (editors.mode != EditorMode.MEMBERS && editors.memberIds.isNotEmpty()) throw TeamFailure("invalid_editors", 400, "Only the members mode has a list of members.")
        if (editors.memberIds.size > MAX_EDITORS) throw TeamFailure("invalid_editors", 400, "At most $MAX_EDITORS editors.")
        editors.memberIds.forEach { id ->
            val member = store.member(actor.teamId, id)
            if (member == null || member.disabled) throw TeamFailure("invalid_editors", 400, "Editors are active members of the team.")
        }
        store.saveEditors(actor, editors)
        return WorkflowSettings(editors, true)
    }

    companion object { const val MAX_PAGE = 100; const val MAX_WORKFLOWS = 200; const val MAX_EDITORS = 100 }
}
