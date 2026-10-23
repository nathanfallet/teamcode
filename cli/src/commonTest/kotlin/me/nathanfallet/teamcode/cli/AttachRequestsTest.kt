package me.nathanfallet.teamcode.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.AttachAction
import me.nathanfallet.teamcode.cli.cloud.AttachRequests
import kotlin.test.*

/** `teamcode cloud attach`: lines typed while the agent waits on a question or a permission request. */
class AttachRequestsTest {
    private var seq = 0L
    private fun event(type: String, data: String) = CloudEventView(++seq, type, 0, 1, Json.parseToJsonElement(data) as JsonObject)

    // The transcript of the reported session: a yes/no question, then "1" typed in attach.
    private val yesNo = """{"requestId":"52ec8144","toolName":"AskUserQuestion","question":true,"toolUseId":"toolu_1","input":{"questions":[
        {"question":"Is it you?","header":"Identity","options":[{"label":"Yes","description":"d"},{"label":"No","description":"d"}],"multiSelect":false}]}}"""
    private val twoQuestions = """{"requestId":"r2","toolName":"AskUserQuestion","question":true,"input":{"questions":[
        {"question":"Pick a color","options":[{"label":"Red"},{"label":"Blue"}],"multiSelect":false},
        {"question":"Pick toppings","options":[{"label":"Cheese"},{"label":"Ham"},{"label":"Olives"}],"multiSelect":true}]}}"""

    private fun AttachAction.answer() = assertIs<AttachAction.Answer>(this).request

    @Test fun anIndexTypedWhileAQuestionWaitsAnswersItInsteadOfQueueingAMessage() {
        val requests = AttachRequests()
        requests.observe(event(CloudEventTypes.TURN, """{"phase":"started"}"""))
        val prompt = requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, yesNo))
        assertNotNull(prompt)
        assertTrue("\"Is it you?\"" in prompt && "1: \"No\"" in prompt, prompt)
        val answer = requests.handle("1").answer()
        assertEquals(AnswerCloudPermissionRequest("52ec8144", "allow", answers = listOf(CloudQuestionAnswer(0, listOf(1)))), answer)
    }

    @Test fun questionsAreAnsweredOneByOneWithSeveralOptionsOrFreeText() {
        val requests = AttachRequests()
        requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, twoQuestions))
        assertEquals("This question has options 0 to 1.", assertIs<AttachAction.Notice>(requests.handle("5")).text)
        assertEquals("Choose one option for this question.", assertIs<AttachAction.Notice>(requests.handle("0,1")).text)
        // Anything but indexes is a free answer.
        val next = assertIs<AttachAction.Notice>(requests.handle("Green, please")).text
        assertTrue(next.startsWith("Question 2 of 2: \"Pick toppings\"") && "indexes" in next, next)
        val answer = assertIs<AttachAction.Answer>(requests.handle("2, 0"))
        assertEquals(listOf(CloudQuestionAnswer(0, text = "Green, please"), CloudQuestionAnswer(1, listOf(0, 2))), answer.request.answers)
        assertEquals("allow", answer.request.behavior)
        assertTrue("\"Cheese\", \"Olives\"" in answer.summary, answer.summary)
    }

    @Test fun messagesAndDenialsStayAvailableWhileAQuestionWaits() {
        val requests = AttachRequests()
        requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, yesNo))
        assertEquals(AttachAction.Message("1 more thing"), requests.handle("/message 1 more thing"))
        assertEquals(AnswerCloudPermissionRequest("52ec8144", "deny", "not now"), requests.handle("/deny not now").answer())
        assertIs<AttachAction.Notice>(requests.handle("/allow"))
    }

    @Test fun linesAreMessagesOnceTheQuestionIsSettled() {
        for (end in listOf(
            CloudEventTypes.INPUT to """{"inputId":"i1","kind":"permission","status":"consumed","requestId":"52ec8144","behavior":"allow"}""",
            CloudEventTypes.TURN to """{"phase":"interrupted"}""",
            CloudEventTypes.PERMISSION_EXPIRED to """{"requestId":"52ec8144"}""",
        )) {
            val requests = AttachRequests()
            requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, yesNo))
            requests.observe(event(end.first, end.second))
            assertNull(requests.prompt())
            assertEquals(AttachAction.Message("1"), requests.handle("1"))
        }
    }

    @Test fun anAnswerOnItsWayFreesTheLinesAndARejectionThatKeepsTheRequestOpenAsksAgain() {
        val requests = AttachRequests()
        requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, yesNo))
        requests.handle("0").answer()
        requests.sent("52ec8144", "i1")
        assertEquals(AttachAction.Message("0"), requests.handle("0"))
        val again = requests.observe(event(CloudEventTypes.INPUT, """{"inputId":"i1","kind":"permission","status":"rejected","reason":"agent_unavailable"}"""))
        assertTrue(again!!.startsWith("Question 1 of 1"), again)
        assertEquals(listOf(CloudQuestionAnswer(0, listOf(0))), requests.handle("0").answer().answers)
        // Refused for good (the request ended): no longer waiting.
        requests.sent("52ec8144", "i2")
        requests.observe(event(CloudEventTypes.INPUT, """{"inputId":"i2","kind":"permission","status":"rejected","reason":"unknown_permission_request"}"""))
        assertNull(requests.prompt())
    }

    @Test fun aToolUseIsAllowedOrDeniedAndOtherLinesStayMessages() {
        val requests = AttachRequests()
        val prompt = requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, """{"requestId":"p1","toolName":"Bash","question":false,"input":{"command":"ls"}}"""))
        assertTrue("/allow" in prompt!!, prompt)
        assertEquals(AttachAction.Message("1"), requests.handle("1"))
        assertEquals(AnswerCloudPermissionRequest("p1", "allow"), requests.handle("/allow").answer())
    }

    @Test fun aQuestionLeftOutOfTheEventCanOnlyBeDeclined() {
        val requests = AttachRequests()
        val prompt = requests.observe(event(CloudEventTypes.PERMISSION_REQUEST, """{"requestId":"q1","toolName":"AskUserQuestion","question":true,"inputTruncated":true,"inputPreview":"{"}"""))
        assertTrue("/deny" in prompt!!, prompt)
        assertEquals(AttachAction.Message("0"), requests.handle("0"))
        assertEquals("deny", requests.handle("/deny").answer().behavior)
    }

    @Test fun nothingWaitingLeavesEveryLineAMessage() {
        val requests = AttachRequests()
        assertNull(requests.prompt())
        assertEquals(AttachAction.Message("0"), requests.handle("0"))
        assertIs<AttachAction.Notice>(requests.handle("/deny"))
    }
}
