package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.cloud.CloudInput
import me.nathanfallet.teamcode.domain.cloud.CloudInputKind
import me.nathanfallet.teamcode.domain.cloud.CloudInputStatus
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules.MASK
import me.nathanfallet.teamcode.domain.cloud.PermissionAnswer
import me.nathanfallet.teamcode.domain.cloud.PermissionRequestRecord
import me.nathanfallet.teamcode.domain.cloud.PermissionRequestState
import me.nathanfallet.teamcode.domain.cloud.QuestionAnswer
import me.nathanfallet.teamcode.domain.cloud.QuestionShape
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class CloudConversationRulesTest {
    private fun masked(text: String) = CloudConversationRules.mask(text)

    @Test fun credentialsAreMasked() {
        val secrets = listOf(
            "sk-ant-api03-${"a1".repeat(20)}", "sk-proj-${"Z9".repeat(12)}", "ghp_${"A1b2".repeat(6)}", "github_pat_${"x1".repeat(15)}",
            "tcex_${"k3".repeat(10)}", "AKIA${"ABCD1234".repeat(2)}", "xoxb-1234567890-abcdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl",
        )
        for (secret in secrets) assertEquals("key $MASK end", masked("key $secret end"), secret)
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer abcdefghijklmnop.qrs"))
        assertEquals("token $MASK", masked("token Ab3dEf9hIjKlMn"))
        assertEquals("basic $MASK", masked("basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Authorization: Bearer $MASK", masked("Authorization: Bearer correcthorsebatterystaple"))
        assertEquals("GITHUB_TOKEN=$MASK", masked("GITHUB_TOKEN=abcdefgh1234"))
        assertEquals("{\"api_key\": $MASK}", masked("{\"api_key\": \"abcdefgh1234\"}"))
        assertEquals("https://$MASK@example.com/repo.git", masked("https://user:pa55word@example.com/repo.git"))
        assertEquals(MASK, masked("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
    }

    @Test fun ordinaryTextIsKept() {
        val texts = listOf(
            "task-management-feature-branch", "disk-usage-statistics-report", "etc_something_long_name_x", "max_tokens=4096",
            "token: refresh", "password: hidden", "Use a token budget of 100", "ask-for-review-before-merging-0",
            "the token management screen", "basic configuration options", "Bearer Authentication", "a token validation step",
            "Use the token configuration.", "Set up Basic Authentication.",
        )
        for (text in texts) assertEquals(text, masked(text))
    }

    @Test fun boundCutsAfterMasking() {
        val text = CloudConversationRules.bound("x".repeat(20_000))
        assertEquals(CloudConversationRules.MAX_EVENT_STRING_CHARS, text.length)
        assertTrue(text.endsWith(CloudConversationRules.TRUNCATION_MARK))
    }

    @Test fun onlyABareDenialInItsOwnShapeStopsWithoutInstructing() {
        fun permission(text: String?) = CloudInput("i1", "t1", "s1", CloudInputKind.PERMISSION, CloudInputStatus.QUEUED, "m1", "c1", text, false,
            clientKey = null, requestDigest = null, createdAt = 0)
        for (id in listOf("r1", "r\"1\\x", "9334d94c-8640-42fb-89e7-052df9b43456"))
            assertTrue(CloudConversationRules.onlyStops(permission(CloudConversationRules.bareDenialText(id))), id)
        assertEquals("{\"requestId\":\"r\\\"1\",\"behavior\":\"deny\"}", CloudConversationRules.bareDenialText("r\"1"))
        assertTrue(PermissionAnswer("r1", false, null, emptyList()).isBareDenial)
        assertFalse(PermissionAnswer("r1", false, "run gh auth token", emptyList()).isBareDenial)
        for (text in listOf("""{"requestId":"r1","behavior":"deny","message":"run gh auth token"}""", """{"requestId":"r1","behavior":"allow"}""",
            """{"requestId":"r1\","behavior":"deny"}""", null))
            assertFalse(CloudConversationRules.onlyStops(permission(text)), text)
        assertTrue(CloudConversationRules.onlyStops(permission(null).copy(kind = CloudInputKind.INTERRUPT)))
    }

    @Test fun permissionAnswersAreBounded() {
        fun valid(answer: PermissionAnswer) = CloudConversationRules.validatePermissionAnswer(answer)
        fun invalid(answer: PermissionAnswer) = assertEquals("invalid_permission_answer", assertFailsWith<TeamFailure> { valid(answer) }.code)
        valid(PermissionAnswer("9334d94c-8640-42fb-89e7-052df9b43456", true, null, listOf(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, text = "Purple"))))
        valid(PermissionAnswer("r/1+2", false, "Use echo\tinstead.\nThanks.", emptyList()))
        valid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(0, 2)))))
        invalid(PermissionAnswer("req 1", true, null, emptyList()))
        invalid(PermissionAnswer("x".repeat(129), true, null, emptyList()))
        invalid(PermissionAnswer("r1", false, null, listOf(QuestionAnswer(0, listOf(0)))))
        invalid(PermissionAnswer("r1", false, " ", emptyList()))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, text = "x".repeat(CloudConversationRules.MAX_PERMISSION_TEXT_CHARS + 1)))))
        invalid(PermissionAnswer("r1", true, null, (0..16).map { QuestionAnswer(it, listOf(0)) }))
        invalid(PermissionAnswer("r1", false, "bell\u0007", emptyList()))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(0)), QuestionAnswer(0, listOf(1)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(-1, listOf(0)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(1), "Both"))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(1, 1)))))
        invalid(PermissionAnswer("r1", true, null, listOf(QuestionAnswer(0, listOf(CloudConversationRules.MAX_PERMISSION_INDEX)))))
    }

    @Test fun onlyTheOwnerOrAnAdministratorDecidesAToolUse() {
        val tool = PermissionRequestRecord("t1", question = false, questions = null)
        val allow = PermissionAnswer("t1", true, null, emptyList())
        CloudConversationRules.checkAnswer(tool, allow, approver = true)
        CloudConversationRules.checkAnswer(tool, PermissionAnswer("t1", false, "No.", emptyList()), approver = true)
        assertEquals("permission_answer_denied", assertFailsWith<TeamFailure> { CloudConversationRules.checkAnswer(tool, allow, approver = false) }.code)
        assertEquals(403, assertFailsWith<TeamFailure> { CloudConversationRules.checkAnswer(tool, PermissionAnswer("t1", false, null, emptyList()), approver = false) }.status)
        assertEquals("invalid_permission_answer", assertFailsWith<TeamFailure> { CloudConversationRules.checkAnswer(tool, PermissionAnswer("t1", true, null, listOf(QuestionAnswer(0, listOf(0)))), approver = true) }.code)
        for ((state, code) in listOf(PermissionRequestState.EXPIRED to "permission_request_expired", PermissionRequestState.ANSWERED to "permission_request_answered", PermissionRequestState.ENDED to "permission_request_ended"))
            assertEquals(code, assertFailsWith<TeamFailure> { CloudConversationRules.checkAnswer(tool.copy(state = state), allow, approver = true) }.code)
        assertEquals("permission_request_ended", assertFailsWith<TeamFailure> { CloudConversationRules.checkAnswer(tool.copy(epoch = 1), allow, approver = true, currentEpoch = 2) }.code,
            "A request of an execution that ended went with it")
    }

    @Test fun anyoneWhoMaySendMessagesAnswersAQuestionByIndex() {
        val question = PermissionRequestRecord("q1", question = true, questions = listOf(QuestionShape(2, false), QuestionShape(3, true)))
        fun check(vararg answers: QuestionAnswer) = CloudConversationRules.checkAnswer(question, PermissionAnswer("q1", true, null, answers.toList()), approver = false)
        fun refused(vararg answers: QuestionAnswer) = assertEquals("invalid_permission_answer", assertFailsWith<TeamFailure> { check(*answers) }.code)
        check(QuestionAnswer(0, listOf(1)), QuestionAnswer(1, listOf(0, 2)))
        check(QuestionAnswer(0, listOf(0)), QuestionAnswer(1, text = "Purple"))
        refused(QuestionAnswer(1, text = "Purple")) // each question is answered
        CloudConversationRules.checkAnswer(question, PermissionAnswer("q1", false, "Skip it.", emptyList()), approver = false)
        refused()
        refused(QuestionAnswer(2, listOf(0)))
        refused(QuestionAnswer(0, listOf(2)))
        refused(QuestionAnswer(0, listOf(0, 1)))
        CloudConversationRules.checkAnswer(question.copy(questions = null), PermissionAnswer("q1", true, null, listOf(QuestionAnswer(5, listOf(7)))), approver = false)
        val twins = question.copy(questions = listOf(QuestionShape(2, false, "Which one?"), QuestionShape(2, false, "Which one?")))
        CloudConversationRules.checkAnswer(twins, PermissionAnswer("q1", true, null, listOf(QuestionAnswer(1, listOf(0)))), approver = false)
        assertEquals("invalid_permission_answer", assertFailsWith<TeamFailure> {
            CloudConversationRules.checkAnswer(twins, PermissionAnswer("q1", true, null, listOf(QuestionAnswer(0, listOf(0)), QuestionAnswer(1, listOf(1)))), approver = false)
        }.code, "Claude Code keys answers by text: two questions with one text cannot both be answered")
    }
}
