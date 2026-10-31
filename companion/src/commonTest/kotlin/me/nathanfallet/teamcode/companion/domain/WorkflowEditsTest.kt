package me.nathanfallet.teamcode.companion.domain

import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowChecks
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowEdits
import kotlin.test.*

/** How the editor changes a definition keeps the graph consistent. */
class WorkflowEditsTest {
    private val ok = validDefinition()

    @Test fun addingAStepAppendsAnEmptyOneWhoseOutcomesAreNotRoutedYet() {
        val more = WorkflowEdits.addNode(ok, StepKind.WORKFLOW)
        val added = more.nodes.last()
        assertEquals("workflow1", added.id); assertEquals(StepKind.WORKFLOW, added.kind); assertEquals(emptyList(), added.transitions)
        assertEquals(listOf("work", "review", "workflow1"), more.nodes.map { it.id })
        // The first step of an empty workflow starts it.
        assertEquals("agent1", WorkflowEdits.addNode(ok.copy(nodes = emptyList(), start = ""), StepKind.AGENT).start)
        // An agent step starts with a text deliverable; ids stay unique.
        assertEquals("text", WorkflowEdits.addNode(ok, StepKind.AGENT).nodes.last().deliverable?.type)
        assertEquals("agent2", WorkflowEdits.addNode(WorkflowEdits.addNode(ok, StepKind.AGENT), StepKind.AGENT).nodes.last().id)
    }

    @Test fun changingTheKindDropsWhatBelongsToTheOtherKindAndKeepsOnlyTheOutcomesThatExist() {
        val person = WorkflowEdits.setKind(ok, "review", StepKind.AGENT).node("review")!!
        assertEquals(StepKind.AGENT, person.kind)
        assertEquals(emptyList(), person.fields); assertEquals(emptyList(), person.actions); assertNull(person.assigneeMemberId)
        assertEquals("text", person.deliverable?.type)
        // `approve` and `request_changes` are gone; an agent step only delivers.
        assertEquals(emptyList(), person.transitions)
        val sub = WorkflowEdits.setKind(ok, "work", StepKind.WORKFLOW).node("work")!!
        assertNull(sub.deliverable); assertEquals(listOf(StepTransition("delivered", "review")), sub.transitions)
        // The same kind changes nothing.
        assertEquals(ok, WorkflowEdits.setKind(ok, "work", StepKind.AGENT))
    }

    @Test fun routingAnOutcomeSetsItsTargetAndEndsWithNull() {
        val routed = WorkflowEdits.route(ok, "work", "delivered", null)
        assertEquals(listOf(StepTransition("delivered", null)), routed.node("work")!!.transitions)
        val back = WorkflowEdits.route(routed, "review", "approve", "work")
        assertEquals(StepTransition("approve", "work"), back.node("review")!!.transitions.first { it.on == "approve" })
        // Outcomes that do not exist are not stored.
        assertEquals(ok.node("work")!!.transitions, WorkflowEdits.route(ok, "work", "ghost", "review").node("work")!!.transitions)
    }

    @Test fun removingAStepDropsTheTransitionsIntoItAndMovesTheStart() {
        val removed = WorkflowEdits.removeNode(ok, "work")
        assertEquals(listOf("review"), removed.nodes.map { it.id })
        assertEquals("review", removed.start)
        assertEquals(listOf(StepTransition("approve", null)), removed.node("review")!!.transitions)
        assertEquals("", WorkflowEdits.removeNode(removed, "review").start)
    }

    @Test fun movingAStepOnlyReordersTheList() {
        assertEquals(listOf("review", "work"), WorkflowEdits.moveNode(ok, "review", -1).nodes.map { it.id })
        assertSame(ok, WorkflowEdits.moveNode(ok, "work", -1))
        assertSame(ok, WorkflowEdits.moveNode(ok, "review", 5))
        assertEquals(ok.start, WorkflowEdits.moveNode(ok, "review", -1).start)
    }

    @Test fun renamingAStepFollowsTheStartTheTransitionsAndTheTemplates() {
        val renamed = WorkflowEdits.renameNode(ok, "work", "build")
        assertEquals("build", renamed.start)
        assertEquals("Look at {{nodes.build.output}}", renamed.node("review")!!.instruction)
        assertEquals("build", renamed.node("review")!!.transitions.first { it.on == "request_changes" }.to)
        assertEquals(emptyList(), WorkflowChecks.check(renamed))
        // A new id that is taken or not an id is typed but not followed, so the problem shows instead of merging two steps.
        val taken = WorkflowEdits.renameNode(ok, "work", "review")
        assertEquals("work", taken.start); assertEquals("Look at {{nodes.work.output}}", taken.nodes.first { it.title == "Review" }.instruction)
        assertTrue(WorkflowChecks.check(taken).isNotEmpty())
    }

    @Test fun renamingAnInputFollowsTheTemplates() {
        val renamed = WorkflowEdits.renameInput(ok, "ticket", "issue")
        assertEquals("issue", renamed.inputs.single().id)
        assertEquals("Fix {{inputs.issue}}.", renamed.node("work")!!.instruction)
        assertEquals(emptyList(), WorkflowChecks.check(renamed))
    }

    @Test fun renamingAFieldOrAnActionFollowsWhatNamesIt() {
        val review = ok.node("review")!!
        val field = WorkflowEdits.renameField(review, "feedback", "why")
        assertEquals(listOf("why"), field.actions.first { it.id == "request_changes" }.requires)
        assertEquals(emptyList(), WorkflowEdits.removeField(review, "feedback").actions.first { it.id == "request_changes" }.requires)
        val action = WorkflowEdits.renameAction(review, "approve", "ship")
        assertEquals("ship", action.transitions.first { it.to == null }.on)
    }

    @Test fun theTemplatesOfferedAreTheInputsAndTheStepsThatCanRunBefore() {
        assertEquals(listOf("{{inputs.ticket}}"), WorkflowEdits.templates(ok, "work").filter { it.startsWith("{{inputs") })
        // `review` follows `work`; `work` also follows `review` through the loop back, but never itself.
        assertEquals(listOf("{{inputs.ticket}}", "{{nodes.work.output}}"), WorkflowEdits.templates(ok, "review"))
        assertEquals(listOf("{{inputs.ticket}}", "{{nodes.review.output}}"), WorkflowEdits.templates(ok, "work"))
        val lonely = ok.copy(nodes = ok.nodes + agentNode("island", null))
        assertEquals(listOf("{{inputs.ticket}}"), WorkflowEdits.templates(lonely, "island"))
    }

    @Test fun newFieldsAndRetypingKeepOnlyWhatFitsTheType() {
        val review = ok.node("review")!!
        val choice = WorkflowEdits.newField(review, "choice")
        assertEquals("field1", choice.id); assertEquals(1, choice.options.size)
        val text = WorkflowEdits.retype(choice.copy(multiple = true, default = "x"), "text")
        assertEquals(emptyList(), text.options); assertFalse(text.multiple); assertNull(text.default)
        assertFalse(WorkflowEdits.retype(DefinitionField("a", "text", "A", required = true), "info").required)
    }

    @Test fun aCopyKeepsTheStepsAndSaysSoInItsName() {
        assertEquals("Fix a ticket (copy)", WorkflowEdits.copyOf(ok, "(copy)").name)
        assertEquals(ok.nodes, WorkflowEdits.copyOf(ok, "(copy)").nodes)
        assertEquals(120, WorkflowEdits.copyOf(ok.copy(name = "x".repeat(120)), "(copy)").name.length)
    }
}

private fun WorkflowDefinition.node(id: String) = nodes.firstOrNull { it.id == id }
