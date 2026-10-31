package me.nathanfallet.teamcode.companion.domain

import me.nathanfallet.teamcode.companion.*
import me.nathanfallet.teamcode.companion.domain.models.*
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.EdgeKind
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.FlowMaps
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.ProblemRule
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowChecks
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowEdits
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowProblem
import kotlin.test.*

/** Each rule the server checks at save (docs/workflows.md, "Checks at save"), run on the draft before anything is sent. */
class WorkflowChecksTest {
    private val ok = validDefinition()

    private fun check(definition: WorkflowDefinition, saved: Map<String, Workflow>? = null, selfId: String? = null) = WorkflowChecks.check(definition, saved, selfId)
    private fun rules(definition: WorkflowDefinition, saved: Map<String, Workflow>? = null, selfId: String? = null) = check(definition, saved, selfId).map { it.rule }
    private fun WorkflowDefinition.node(id: String, change: (DefinitionNode) -> DefinitionNode) = copy(nodes = nodes.map { if (it.id == id) change(it) else it })
    private fun assertOnly(rule: ProblemRule, definition: WorkflowDefinition, node: String? = null, saved: Map<String, Workflow>? = null, selfId: String? = null) {
        val found = check(definition, saved, selfId)
        assertEquals(listOf(rule), found.map { it.rule }, "expected only $rule, got $found")
        if (node != null) assertEquals(node, found.single().nodeId)
    }

    @Test fun aValidDefinitionHasNoProblem() = assertEquals(emptyList(), check(ok))

    @Test fun aNewBlankWorkflowOnlyNeedsANameAndAnInstruction() {
        val rules = rules(WorkflowEdits.blank())
        assertEquals(setOf(ProblemRule.NAME, ProblemRule.TITLE, ProblemRule.AGENT_INSTRUCTION), rules.toSet())
    }

    @Test fun theNameIsRequiredAndBounded() {
        assertOnly(ProblemRule.NAME, ok.copy(name = " "))
        assertOnly(ProblemRule.NAME, ok.copy(name = "x".repeat(121)))
        assertOnly(ProblemRule.NAME, ok.copy(name = "bad\nname"))
        assertEquals(emptyList(), check(ok.copy(name = "x".repeat(120))))
        assertOnly(ProblemRule.DESCRIPTION, ok.copy(description = "d".repeat(2_001)))
    }

    @Test fun limitsAreBounded() {
        assertOnly(ProblemRule.LIMITS, ok.copy(limits = DefinitionLimits(maxNodes = 0)))
        assertOnly(ProblemRule.LIMITS, ok.copy(limits = DefinitionLimits(maxVisits = 501)))
        assertOnly(ProblemRule.LIMITS, ok.copy(limits = DefinitionLimits(maxDepth = 6)))
        assertEquals(emptyList(), check(ok.copy(limits = DefinitionLimits(maxNodes = 100, maxVisits = 500, maxDepth = 0))))
    }

    @Test fun thereIsAtLeastOneNodeAndNoMoreThanTheLimit() {
        assertTrue(ProblemRule.NO_NODES in rules(ok.copy(nodes = emptyList())))
        assertOnly(ProblemRule.TOO_MANY_NODES, ok.copy(limits = DefinitionLimits(maxNodes = 1)))
    }

    @Test fun inputsAreCheckedLikeTheServerDoes() {
        fun with(input: WorkflowInput) = ok.copy(inputs = listOf(input), nodes = ok.nodes.map { it.copy(instruction = "Do it.") })
        assertOnly(ProblemRule.INPUT_ID, with(WorkflowInput("1bad", "L", true, null, 10)))
        assertOnly(ProblemRule.INPUT_ID, with(WorkflowInput("", "L", true, null, 10)))
        assertOnly(ProblemRule.INPUT_LABEL, with(WorkflowInput("a", " ", true, null, 10)))
        assertOnly(ProblemRule.INPUT_MAX_LENGTH, with(WorkflowInput("a", "L", true, null, 0)))
        assertOnly(ProblemRule.INPUT_MAX_LENGTH, with(WorkflowInput("a", "L", true, null, 32_001)))
        assertOnly(ProblemRule.INPUT_DEFAULT_LONG, with(WorkflowInput("a", "L", false, "too long", 3)))
        assertTrue(ProblemRule.INPUT_DUPLICATE in rules(ok.copy(inputs = listOf(WorkflowInput("a", "A", true, null, 5), WorkflowInput("a", "B", true, null, 5)))))
        assertTrue(ProblemRule.TOO_MANY_INPUTS in rules(ok.copy(inputs = (1..17).map { WorkflowInput("i$it", "I", false, null, 5) })))
    }

    @Test fun nodeIdsAreIdentifiersAndUnique() {
        assertTrue(ProblemRule.NODE_ID in rules(ok.node("work") { it.copy(id = "9 work") }.copy(start = "9 work")))
        val twice = ok.copy(nodes = ok.nodes + agentNode("work", null))
        assertTrue(check(twice).any { it.rule == ProblemRule.NODE_DUPLICATE && it.nodeId == "work" })
    }

    @Test fun theStartNodeExistsAndIsNotAPerson() {
        assertEquals(ProblemRule.START_MISSING, check(ok.copy(start = "ghost")).first().rule)
        assertTrue(check(ok.copy(start = "review")).any { it.rule == ProblemRule.START_HUMAN && it.nodeId == "review" })
    }

    @Test fun aNodeNeedsATitleAndBoundedTexts() {
        assertOnly(ProblemRule.TITLE, ok.node("work") { it.copy(title = "") }, "work")
        assertOnly(ProblemRule.INSTRUCTION_LONG, ok.node("work") { it.copy(instruction = "x".repeat(32_001)) }, "work")
        assertOnly(ProblemRule.MAX_VISITS, ok.node("work") { it.copy(maxVisits = 0) }, "work")
        assertOnly(ProblemRule.MAX_VISITS, ok.node("work") { it.copy(maxVisits = 21) }, "work")
    }

    @Test fun anUnknownTemplateIsRefusedWithItsName() {
        val bad = ok.node("work") { it.copy(instruction = "Fix {{inputs.nope}} and {{ nodes.review.output }}, {{foo}}, {{nodes.work.output}}") }
        val found = check(bad).filter { it.rule == ProblemRule.TEMPLATE_UNKNOWN }
        // A known input and the output of another node pass; an unknown input, a free name and the node's own output do not.
        assertEquals(listOf("inputs.nope", "foo", "nodes.work.output"), found.map { it.args.single() })
        assertTrue(found.all { it.nodeId == "work" })
        // The arguments and the deliverable label are templates too.
        assertTrue(ProblemRule.TEMPLATE_UNKNOWN in rules(ok.node("work") { it.copy(deliverable = DefinitionDeliverable("text", label = "{{inputs.x}}")) }))
    }

    @Test fun anAgentNeedsAnInstructionAndADeliverable() {
        assertOnly(ProblemRule.AGENT_INSTRUCTION, ok.node("work") { it.copy(instruction = " ") }, "work")
        assertOnly(ProblemRule.DELIVERABLE_MISSING, ok.node("work") { it.copy(deliverable = null) }, "work")
    }

    @Test fun theDeliverableRulesFollowItsType() {
        fun deliver(d: DefinitionDeliverable) = ok.node("work") { it.copy(deliverable = d) }
        assertEquals(emptyList(), check(deliver(DefinitionDeliverable("url", domain = "github.com"))))
        assertOnly(ProblemRule.DELIVERABLE_DOMAIN, deliver(DefinitionDeliverable("text", domain = "github.com")), "work")
        assertOnly(ProblemRule.DELIVERABLE_DOMAIN, deliver(DefinitionDeliverable("url", domain = "not a domain")), "work")
        assertOnly(ProblemRule.DELIVERABLE_SCHEMA_ONLY_JSON, deliver(DefinitionDeliverable("text", schema = "{}")), "work")
        assertOnly(ProblemRule.DELIVERABLE_SCHEMA_MISSING, deliver(DefinitionDeliverable("json")), "work")
        assertOnly(ProblemRule.DELIVERABLE_SCHEMA_INVALID, deliver(DefinitionDeliverable("json", schema = "{not json")), "work")
        assertOnly(ProblemRule.DELIVERABLE_SCHEMA_INVALID, deliver(DefinitionDeliverable("json", schema = "[1]")), "work")
        assertEquals(emptyList(), check(deliver(DefinitionDeliverable("json", schema = """{"type":"object","required":["summary"]}"""))))
    }

    @Test fun everyNodeMustBeReachableFromTheStart() {
        val lonely = ok.copy(nodes = ok.nodes + agentNode("island", null))
        assertTrue(check(lonely).any { it.rule == ProblemRule.NOT_REACHABLE && it.nodeId == "island" })
    }

    @Test fun someNodeMustEndTheWorkflow() {
        val loop = ok.node("review") { it.copy(transitions = listOf(StepTransition("approve", "work"), StepTransition("request_changes", "work"))) }
        assertEquals(listOf(ProblemRule.NO_ENDING), rules(loop))
    }

    @Test fun anOutcomeNeedsATransitionToAnExistingNode() {
        assertTrue(check(ok.node("work") { it.copy(transitions = emptyList()) }).any { it.rule == ProblemRule.TRANSITION_MISSING && it.nodeId == "work" && it.args == listOf("delivered") })
        assertTrue(check(ok.node("review") { it.copy(transitions = it.transitions.filter { t -> t.on != "approve" }) }).any { it.rule == ProblemRule.TRANSITION_MISSING && it.args == listOf("approve") })
        val toGhost = ok.node("work") { it.copy(transitions = listOf(StepTransition("delivered", "ghost"))) }
        assertTrue(check(toGhost).any { it.rule == ProblemRule.TRANSITION_TARGET && it.args == listOf("delivered", "ghost") })
        assertTrue(ProblemRule.TRANSITION_OUTCOME in rules(ok.node("work") { it.copy(transitions = it.transitions + StepTransition("surprise", null)) }))
        assertTrue(ProblemRule.TRANSITION_DUPLICATE in rules(ok.node("work") { it.copy(transitions = it.transitions + it.transitions) }))
    }

    @Test fun aPersonStepWithoutButtonsHasTheDefaultDoneOutcome() {
        val person = ok.node("review") { it.copy(actions = emptyList(), fields = emptyList(), transitions = listOf(StepTransition("done", null))) }
        assertEquals(emptyList(), check(person))
        val noRoute = ok.node("review") { it.copy(actions = emptyList(), fields = emptyList(), transitions = emptyList()) }
        assertTrue(check(noRoute).any { it.rule == ProblemRule.TRANSITION_MISSING && it.nodeId == "review" && it.args == listOf("done") })
    }

    @Test fun theWaitRulesApplyToAPersonStep() {
        fun review(change: (DefinitionNode) -> DefinitionNode) = ok.node("review", change)
        fun field(f: DefinitionField) = review { it.copy(fields = listOf(f), actions = listOf(DefinitionAction("approve", "Approve"), DefinitionAction("request_changes", "Request changes")), transitions = it.transitions) }
        assertOnly(ProblemRule.FIELD_ID, field(DefinitionField("1x", "text", "L")), "review")
        assertOnly(ProblemRule.FIELD_LABEL, field(DefinitionField("a", "text", " ")), "review")
        assertOnly(ProblemRule.FIELD_SECRET, field(DefinitionField("a", "secret", "L")), "review")
        assertOnly(ProblemRule.FIELD_MAX_LENGTH, field(DefinitionField("a", "number", "L", maxLength = 5)), "review")
        assertOnly(ProblemRule.FIELD_MAX_LENGTH, field(DefinitionField("a", "text", "L", maxLength = 32_001)), "review")
        assertOnly(ProblemRule.FIELD_OPTIONS, field(DefinitionField("a", "choice", "L")), "review")
        assertOnly(ProblemRule.FIELD_OPTIONS, field(DefinitionField("a", "text", "L", multiple = true)), "review")
        assertOnly(ProblemRule.FIELD_OPTION_VALUE, field(DefinitionField("a", "choice", "L", options = listOf(DefinitionOption("x", "")))), "review")
        assertOnly(ProblemRule.FIELD_OPTION_DUPLICATE, field(DefinitionField("a", "choice", "L", options = listOf(DefinitionOption("x", "X"), DefinitionOption("x", "Y")))), "review")
        assertOnly(ProblemRule.FIELD_INFO_VALUE, field(DefinitionField("a", "info", "L", required = true)), "review")
        assertOnly(ProblemRule.FIELD_DEFAULT, field(DefinitionField("a", "number", "L", default = "abc")), "review")
        assertOnly(ProblemRule.FIELD_DEFAULT, field(DefinitionField("a", "date", "L", default = "tomorrow")), "review")
        assertOnly(ProblemRule.FIELD_DEFAULT, field(DefinitionField("a", "confirm", "L", default = "maybe")), "review")
        assertEquals(emptyList(), check(field(DefinitionField("a", "choice", "L", default = "x", options = listOf(DefinitionOption("x", "X"))))))
        assertEquals(emptyList(), check(field(DefinitionField("a", "choice", "L", multiple = true, default = """["x","y"]""", options = listOf(DefinitionOption("x", "X"), DefinitionOption("y", "Y"))))))
        assertEquals(emptyList(), check(field(DefinitionField("a", "date", "L", default = "2026-10-31"))))
        assertTrue(ProblemRule.FIELD_DUPLICATE in rules(review { it.copy(fields = listOf(DefinitionField("a", "text", "A"), DefinitionField("a", "text", "B"))) }))
        assertTrue(ProblemRule.FIELDS_TOO_MANY in rules(review { it.copy(fields = (1..33).map { n -> DefinitionField("f$n", "text", "F") }) }))
    }

    @Test fun actionsRequireOnlyFieldsThatTakeAValue() {
        fun actions(vararg a: DefinitionAction, fields: List<DefinitionField> = listOf(DefinitionField("feedback", "text", "F"), DefinitionField("note", "info", "N"))) =
            ok.node("review") { it.copy(fields = fields, actions = a.toList(), transitions = a.map { x -> StepTransition(x.id, null) }) }
        assertOnly(ProblemRule.ACTION_REQUIRES, actions(DefinitionAction("go", "Go", listOf("note"))), "review")
        assertOnly(ProblemRule.ACTION_REQUIRES, actions(DefinitionAction("go", "Go", listOf("ghost"))), "review")
        assertOnly(ProblemRule.ACTION_ID, actions(DefinitionAction("1", "Go")), "review")
        assertOnly(ProblemRule.ACTION_LABEL, actions(DefinitionAction("go", "")), "review")
        assertTrue(ProblemRule.ACTION_DUPLICATE in rules(actions(DefinitionAction("go", "A"), DefinitionAction("go", "B"))))
        assertTrue(ProblemRule.ACTIONS_TOO_MANY in rules(ok.node("review") { it.copy(actions = (1..9).map { n -> DefinitionAction("a$n", "A") }) }))
        assertEquals(emptyList(), check(actions(DefinitionAction("go", "Go", listOf("feedback")))))
    }

    @Test fun aMemberAssigneeNamesTheMember() {
        assertOnly(ProblemRule.ASSIGNEE_MEMBER, ok.node("review") { it.copy(assigneeMemberId = "") }, "review")
        assertEquals(emptyList(), check(ok.node("review") { it.copy(assigneeMemberId = "m2") }))
    }

    private fun sub(id: String, inputs: List<WorkflowInput> = emptyList(), calls: List<String> = emptyList()) = workflow(
        id, inputs, calls.mapIndexed { i, c -> WorkflowStep("s$i", StepKind.WORKFLOW, "workflow", "S", null, null, null, null, c, emptyList(), emptyList(), 3) },
    ).copy(name = "Sub $id")
    private fun caller(id: String? = "sub", arguments: Map<String, String> = emptyMap(), maxDepth: Int = 3) = ok.copy(
        nodes = ok.nodes.map { if (it.id == "review") it.copy(transitions = listOf(StepTransition("approve", "call"), StepTransition("request_changes", "work"))) else it } +
            DefinitionNode("call", StepKind.WORKFLOW, "Call", workflowId = id, arguments = arguments, transitions = listOf(StepTransition("delivered", null))),
        limits = DefinitionLimits(maxDepth = maxDepth),
    )

    @Test fun aSubWorkflowStepIsCheckedAgainstTheSavedWorkflows() {
        val savedSub = mapOf("sub" to sub("sub", listOf(WorkflowInput("topic", "Topic", true, null, 10), WorkflowInput("tone", "Tone", false, null, 10))))
        assertEquals(emptyList(), check(caller(arguments = mapOf("topic" to "{{inputs.ticket}}")), savedSub))
        assertTrue(check(caller(id = null), savedSub).any { it.rule == ProblemRule.CALLEE_MISSING && it.nodeId == "call" })
        assertTrue(check(caller(id = "gone"), savedSub).any { it.rule == ProblemRule.CALLEE_UNKNOWN && it.args == listOf("gone") })
        assertTrue(check(caller(), savedSub).any { it.rule == ProblemRule.ARGUMENT_MISSING && it.args == listOf("topic", "Sub sub") })
        assertTrue(check(caller(arguments = mapOf("topic" to "x", "wat" to "y")), savedSub).any { it.rule == ProblemRule.ARGUMENT_UNKNOWN && it.args.first() == "wat" })
        assertTrue(check(caller(arguments = mapOf("1bad" to "y", "topic" to "x")), savedSub).any { it.rule == ProblemRule.ARGUMENT_NAME })
        // Without the saved workflows (they could not be read) the server still checks them: nothing is claimed here.
        assertEquals(emptyList(), check(caller(arguments = mapOf("anything" to "x")), null))
    }

    @Test fun callLoopsAndDepthAreRefused() {
        assertTrue(check(caller(id = "me"), mapOf("me" to sub("me")), selfId = "me").any { it.rule == ProblemRule.CALLEE_SELF })
        val back = mapOf("sub" to sub("sub", calls = listOf("me")), "me" to sub("me"))
        assertTrue(check(caller(), back, selfId = "me").any { it.rule == ProblemRule.CALLEE_LOOP && it.args.single().contains("sub -> me") })
        val cycle = mapOf("sub" to sub("sub", calls = listOf("other")), "other" to sub("other", calls = listOf("sub")))
        assertTrue(check(caller(), cycle).any { it.rule == ProblemRule.CALLEE_LOOP })
        val chain = mapOf("sub" to sub("sub", calls = listOf("b")), "b" to sub("b", calls = listOf("c")), "c" to sub("c"))
        assertTrue(check(caller(maxDepth = 2), chain).any { it.rule == ProblemRule.CALLEE_DEPTH && it.args == listOf("3", "2") })
        assertEquals(emptyList(), check(caller(maxDepth = 3), chain))
    }

    @Test fun theFlowMapShowsEachStepOnceWithItsLoopsAndEnds() {
        val map = FlowMaps.of(ok)
        assertEquals(listOf("work", "review"), map.rows.map { it.nodeId })
        assertEquals(listOf(0, 1), map.rows.map { it.depth })
        assertEquals(listOf(FlowEdgeSummary("delivered", EdgeKind.NEXT, "review")), map.rows[0].edges.map { FlowEdgeSummary(it.outcome, it.kind, it.to) })
        assertEquals(listOf(FlowEdgeSummary("approve", EdgeKind.END, null), FlowEdgeSummary("request_changes", EdgeKind.LOOP, "work")), map.rows[1].edges.map { FlowEdgeSummary(it.outcome, it.kind, it.to) })
        assertEquals(emptyList(), map.unreachable)
    }

    private data class FlowEdgeSummary(val outcome: String, val kind: EdgeKind, val to: String?)

    @Test fun theFlowMapMarksJoinsMissingTargetsUnsetOutcomesAndLonelySteps() {
        val def = WorkflowDefinition("n", "", "a", "same", emptyList(), listOf(
            agentNode("a", "b").copy(transitions = listOf(StepTransition("delivered", "b"))),
            DefinitionNode("b", StepKind.HUMAN, "B", actions = listOf(DefinitionAction("x", "X"), DefinitionAction("y", "Y"), DefinitionAction("z", "Z")), transitions = listOf(StepTransition("x", "ghost"), StepTransition("y", "c"))),
            agentNode("c", "b"),
            agentNode("d", null),
        ))
        val map = FlowMaps.of(def)
        assertEquals(listOf("a", "b", "c"), map.rows.map { it.nodeId })
        assertEquals(listOf(EdgeKind.MISSING, EdgeKind.NEXT, EdgeKind.UNSET), map.rows[1].edges.map { it.kind })
        assertEquals(EdgeKind.LOOP, map.rows[2].edges.single().kind)
        assertEquals(listOf("d"), map.unreachable)
    }
}
