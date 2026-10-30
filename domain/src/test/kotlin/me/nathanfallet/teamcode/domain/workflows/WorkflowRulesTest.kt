package me.nathanfallet.teamcode.domain.workflows

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.FieldType
import me.nathanfallet.teamcode.domain.waits.WaitAction
import me.nathanfallet.teamcode.domain.waits.WaitField
import kotlin.test.*

class WorkflowRulesTest {
    private val analyze = WorkflowNode("analyze", NodeKind.AGENT, "Analyze", "Plan {{inputs.ticket}}", deliverable = Deliverable(DeliverableType.ARTIFACT), transitions = listOf(Transition("delivered", "review")))
    private val review = WorkflowNode(
        "review", NodeKind.HUMAN, "Review the plan", "See {{nodes.analyze.output}}",
        fields = listOf(WaitField("feedback", FieldType.TEXT, "Feedback")),
        actions = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback"))),
        transitions = listOf(Transition("approve", "implement"), Transition("request_changes", "analyze")),
    )
    private val implement = WorkflowNode("implement", NodeKind.AGENT, "Implement", "Do it", deliverable = Deliverable(DeliverableType.URL, domain = "github.com"), transitions = listOf(Transition("delivered", null)))
    private val ticket = WorkflowDefinition("Fix a ticket", null, "analyze", SessionMode.SAME, listOf(WorkflowInput("ticket", "Ticket")), listOf(analyze, review, implement))

    private fun code(definition: WorkflowDefinition) = assertFailsWith<TeamFailure> { WorkflowRules.validate(definition) }.also { assertEquals("invalid_workflow", it.code) }.message.orEmpty()

    @Test fun theTicketWorkflowIsValidWithItsBackwardLoop() { assertEquals(ticket, WorkflowRules.validate(ticket)) }

    @Test fun theGraphMustHoldTogether() {
        assertTrue("start node" in code(ticket.copy(start = "nowhere")))
        assertTrue("cannot ask a person" in code(ticket.copy(start = "review")), "a person is asked about a session, so an agent works first")
        assertTrue("does not exist" in code(ticket.copy(nodes = listOf(analyze.copy(transitions = listOf(Transition("delivered", "ghost"))), review, implement))))
        assertTrue("cannot be reached" in code(ticket.copy(nodes = ticket.nodes + implement.copy(id = "orphan"))))
        assertTrue("ends the workflow" in code(ticket.copy(nodes = listOf(analyze.copy(transitions = listOf(Transition("delivered", "analyze")))))))
        assertTrue("unique" in code(ticket.copy(nodes = ticket.nodes + analyze)))
        assertTrue("say where approve" in code(ticket.copy(nodes = listOf(analyze, review.copy(transitions = review.transitions.drop(1)), implement))))
        assertTrue("no outcome" in code(ticket.copy(nodes = listOf(analyze, review.copy(transitions = review.transitions + Transition("other", null)), implement))))
    }

    @Test fun nodesOfEachKindCarryTheirOwnPartsOnly() {
        assertTrue("deliverable" in code(ticket.copy(nodes = listOf(analyze.copy(deliverable = null), review, implement))))
        assertTrue("instruction" in code(ticket.copy(nodes = listOf(analyze.copy(instruction = " "), review, implement))))
        assertTrue("no fields" in code(ticket.copy(nodes = listOf(analyze.copy(fields = review.fields), review, implement))))
        assertTrue("only asks a person" in code(ticket.copy(nodes = listOf(analyze, review.copy(deliverable = Deliverable(DeliverableType.TEXT)), implement))))
        assertTrue("requires" in code(ticket.copy(nodes = listOf(analyze, review.copy(actions = listOf(WaitAction("approve", "Approve", requires = listOf("ghost")), review.actions[1])), implement))), "wait rules apply to human nodes")
        assertTrue("name the member" in code(ticket.copy(nodes = listOf(analyze, review.copy(assignee = NodeAssignee(false, null)), implement))))
        assertTrue("domain" in code(ticket.copy(nodes = listOf(analyze.copy(deliverable = Deliverable(DeliverableType.TEXT, domain = "github.com")), review, implement))))
        assertTrue("schema" in code(ticket.copy(nodes = listOf(analyze.copy(deliverable = Deliverable(DeliverableType.JSON)), review, implement))))
        WorkflowRules.validate(ticket.copy(nodes = listOf(analyze.copy(deliverable = Deliverable(DeliverableType.JSON, schema = JsonObject(emptyMap()))), review, implement)))
    }

    @Test fun templatesNameKnownInputsAndOtherNodes() {
        assertTrue("{{inputs.nope}}" in code(ticket.copy(nodes = listOf(analyze.copy(instruction = "{{inputs.nope}}"), review, implement))))
        assertTrue("{{nodes.analyze.output}}" in code(ticket.copy(nodes = listOf(analyze.copy(instruction = "{{nodes.analyze.output}}"), review, implement))), "a node cannot read its own output")
        assertTrue("{{nodes.ghost.output}}" in code(ticket.copy(nodes = listOf(analyze, review.copy(instruction = "{{nodes.ghost.output}}"), implement))))
        assertTrue("{{secrets.token}}" in code(ticket.copy(nodes = listOf(analyze.copy(instruction = "{{secrets.token}}"), review, implement))))
    }

    @Test fun boundsAreChecked() {
        assertTrue("name" in code(ticket.copy(name = " ")))
        assertTrue("maxVisits" in code(ticket.copy(nodes = listOf(analyze.copy(maxVisits = 0), review, implement))))
        assertTrue("1 to 2 nodes" in code(ticket.copy(limits = WorkflowLimits(maxNodes = 2))))
        assertTrue("unique" in code(ticket.copy(inputs = listOf(WorkflowInput("a", "A"), WorkflowInput("a", "B")))))
    }

    private fun caller(id: String) = WorkflowDefinition(
        "Caller", null, "call", SessionMode.SAME, emptyList(),
        listOf(WorkflowNode("call", NodeKind.WORKFLOW, "Deploy", workflowId = id, arguments = mapOf("ticket" to "{{inputs.ticket}}"), transitions = listOf(Transition("delivered", null)))),
    ).let { it.copy(inputs = listOf(WorkflowInput("ticket", "Ticket"))) }

    @Test fun subWorkflowsExistTakeTheirInputsAndNeverLoop() {
        WorkflowRules.validate(caller("deploy"))
        val deploy = ticket.copy(name = "Deploy")
        WorkflowRules.checkSubWorkflows("caller", caller("deploy"), mapOf("deploy" to deploy))
        assertTrue("does not exist" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows(null, caller("deploy"), emptyMap()) }.message.orEmpty())
        assertTrue("itself" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows("deploy", caller("deploy"), mapOf("deploy" to deploy)) }.message.orEmpty())
        assertTrue("loop" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows("caller", caller("deploy"), mapOf("deploy" to caller("caller"))) }.message.orEmpty())
        assertTrue("loop" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows(null, caller("a"), mapOf("a" to caller("b"), "b" to caller("a"))) }.message.orEmpty())
        val noArgs = caller("deploy").let { it.copy(nodes = it.nodes.map { n -> n.copy(arguments = emptyMap()) }) }
        assertTrue("needs the input ticket" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows(null, noArgs, mapOf("deploy" to deploy)) }.message.orEmpty())
        val chain = mapOf("a" to caller("b"), "b" to caller("c"), "c" to caller("d"), "d" to deploy)
        assertTrue("nested 4 deep" in assertFailsWith<TeamFailure> { WorkflowRules.checkSubWorkflows(null, caller("a"), chain) }.message.orEmpty())
        WorkflowRules.checkSubWorkflows(null, caller("a").copy(limits = WorkflowLimits(maxDepth = 4)), chain)
    }
}
