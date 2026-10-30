package me.nathanfallet.teamcode.domain.workflows

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class WorkflowServiceTest {
    private val ada = Member("ada", "ada", Role.MEMBER, false, false)
    private val bob = Member("bob", "bob", Role.MEMBER, false, false)
    private val gone = Member("gone", "gone", Role.MEMBER, true, false)
    private val admin = Member("admin", "admin", Role.ADMIN, false, false)
    private fun who(m: Member, scopes: Set<ServiceScope>? = null) = Principal(m, "token-${m.id}", "team", scopes)

    private class FakeStore(val members: Map<String, Member>) : WorkflowStore {
        var editors = WorkflowEditors()
        val saved = linkedMapOf<String, Workflow>()
        val audit = mutableListOf<String>()
        var next = 0
        override suspend fun revalidate(actor: Principal) {}
        override suspend fun member(teamId: String, memberId: String) = members[memberId]
        override suspend fun editors(teamId: String) = editors
        override suspend fun saveEditors(actor: Principal, editors: WorkflowEditors) { this.editors = editors; audit += "editors" }
        override suspend fun find(teamId: String, id: String) = saved[id]
        override suspend fun list(teamId: String, after: String?, limit: Int): WorkflowPage {
            val all = saved.values.sortedBy { it.id }.filter { after == null || it.id > after }
            return WorkflowPage(all.take(limit), all.getOrNull(limit - 1)?.id?.takeIf { all.size > limit })
        }
        override suspend fun save(actor: Principal, id: String?, definition: WorkflowDefinition, expectedVersion: Int?, limit: Int, check: (Map<String, WorkflowDefinition>) -> Unit): Workflow {
            check(saved.filterKeys { it != id }.mapValues { it.value.definition })
            if (saved.values.any { it.id != id && it.definition.name == definition.name }) throw TeamFailure("workflow_name_taken", 409, "x")
            val old = id?.let { saved.getValue(it) }
            if (old != null && expectedVersion != null && old.version != expectedVersion) throw TeamFailure("workflow_conflict", 409, "x")
            if (old == null && saved.size >= limit) throw TeamFailure("workflow_limit", 409, "x")
            val workflow = Workflow(id ?: "wf${++next}", "team", (old?.version ?: 0) + 1, definition, 1, 2, actor.member.id)
            saved[workflow.id] = workflow; audit += if (old == null) "created" else "updated"
            return workflow
        }
        override suspend fun delete(actor: Principal, id: String): Workflow {
            if (saved.values.any { it.id != id && it.definition.nodes.any { n -> n.workflowId == id } }) throw TeamFailure("workflow_in_use", 409, "x")
            return saved.remove(id) ?: throw TeamFailure("not_found", 404, "x")
        }
    }

    private val store = FakeStore(listOf(ada, bob, gone, admin).associateBy { it.id })
    private val service = WorkflowService(store)
    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code

    private fun definition(name: String = "Fix", call: String? = null) = WorkflowDefinition(
        name, null, "work", SessionMode.SAME, emptyList(),
        listOf(
            if (call == null) WorkflowNode("work", NodeKind.AGENT, "Work", "Do it", deliverable = Deliverable(DeliverableType.TEXT), transitions = listOf(Transition("delivered")))
            else WorkflowNode("work", NodeKind.WORKFLOW, "Call", workflowId = call, transitions = listOf(Transition("delivered"))),
        ),
    )

    @Test fun byDefaultOnlyAdministratorsEditAndEveryoneReads() = runBlocking {
        assertEquals("workflow_editors_only", code { service.create(who(ada), definition()) })
        val created = service.create(who(admin), definition())
        assertEquals(1, created.version); assertEquals(created.id, service.get(who(ada), created.id).id)
        assertEquals(listOf(created.id), service.list(who(ada), null, 10).workflows.map { it.id })
        assertFalse(service.settings(who(ada)).canEdit); assertTrue(service.settings(who(admin)).canEdit)
    }

    @Test fun theEditorsRuleOpensEditingToEveryoneOrToNamedMembers() = runBlocking {
        service.saveEditors(who(admin), WorkflowEditors(EditorMode.ALL))
        service.create(who(ada), definition("A"))
        service.saveEditors(who(admin), WorkflowEditors(EditorMode.MEMBERS, setOf("bob")))
        service.create(who(bob), definition("B")); assertEquals("workflow_editors_only", code { service.create(who(ada), definition("C")) })
        assertEquals("forbidden", code { service.saveEditors(who(bob), WorkflowEditors(EditorMode.ALL)) }, "only an administrator changes the rule")
        assertEquals("invalid_editors", code { service.saveEditors(who(admin), WorkflowEditors(EditorMode.MEMBERS, setOf("gone"))) })
        assertEquals("invalid_editors", code { service.saveEditors(who(admin), WorkflowEditors(EditorMode.ADMINS, setOf("bob"))) })
    }

    @Test fun aKeyReadsButNeverEdits() = runBlocking {
        val key = who(admin, setOf(ServiceScope.CLOUD_READ, ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD))
        assertEquals("credential_scope", code { service.create(key, definition()) })
        assertEquals(emptyList(), service.list(key, null, 10).workflows)
        assertFalse(service.settings(key).canEdit)
        assertEquals("credential_scope", code { service.list(who(admin, setOf(ServiceScope.ARTIFACTS_READ)), null, 10) })
    }

    @Test fun savingBumpsTheVersionAndRefusesAStaleOne() = runBlocking {
        val first = service.create(who(admin), definition())
        val second = service.update(who(admin), first.id, definition().copy(description = "v2"), expectedVersion = 1)
        assertEquals(2, second.version)
        assertEquals("workflow_conflict", code { service.update(who(admin), first.id, definition(), expectedVersion = 1) })
        assertEquals("not_found", code { service.update(who(admin), "nope", definition(), null) })
        assertEquals("invalid_workflow", code { service.create(who(admin), definition().copy(start = "ghost")) })
        assertEquals("workflow_name_taken", code { service.create(who(admin), definition()) })
    }

    @Test fun subWorkflowsMustExistNeverLoopAndCannotBeDeletedInUse() = runBlocking {
        val deploy = service.create(who(admin), definition("Deploy"))
        val caller = service.create(who(admin), definition("Caller", call = deploy.id))
        assertEquals("invalid_workflow", code { service.create(who(admin), definition("Orphan", call = "ghost")) })
        assertEquals("invalid_workflow", code { service.update(who(admin), deploy.id, definition("Deploy", call = caller.id), null) }, "Deploy -> Caller -> Deploy")
        assertEquals("workflow_in_use", code { service.delete(who(admin), deploy.id) })
        service.delete(who(admin), caller.id); service.delete(who(admin), deploy.id)
        assertEquals(emptyList(), service.list(who(admin), null, 10).workflows)
    }

    @Test fun pagesAreBounded() = runBlocking {
        repeat(3) { service.create(who(admin), definition("W$it")) }
        val page = service.list(who(ada), null, 2)
        assertEquals(2, page.workflows.size); assertNotNull(page.nextAfter)
        assertEquals(1, service.list(who(ada), page.nextAfter, 2).workflows.size)
        assertEquals("invalid_limit", code { service.list(who(ada), null, 101) })
    }
}
