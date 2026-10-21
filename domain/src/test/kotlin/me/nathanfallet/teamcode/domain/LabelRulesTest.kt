package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class LabelRulesTest {
    @Test fun runsAndCloudSessionsShareOneLabelModel() {
        LabelRules.validate(mapOf("orchestrator.task" to "1234", "a" to "", "x/y-z" to "v".repeat(256)))
        for (bad in listOf(mapOf("Upper" to "x"), mapOf("" to "x"), mapOf("-a" to "x"), mapOf("a" to "v".repeat(257)), mapOf("a" to "line\nbreak"),
            mapOf("k".repeat(64) to "x"), (1..17).associate { "k$it" to "v" }))
            assertEquals("invalid_label", assertFailsWith<TeamFailure> { LabelRules.validate(bad) }.code, bad.toString())
        assertEquals("invalid_label", assertFailsWith<TeamFailure> {
            AgentRunService.validateStart("11111111-1111-1111-1111-111111111111", "claude", "interactive", mapOf("Upper" to "x"))
        }.code)
    }

    @Test fun filtersSplitOnTheFirstEqualsSign() {
        assertEquals(mapOf("a" to "b=c", "d" to ""), LabelRules.parseFilter(listOf("a=b=c", "d=")))
        assertEquals("invalid_label", assertFailsWith<TeamFailure> { LabelRules.parseFilter(listOf("nokey")) }.code)
        assertEquals("invalid_label", assertFailsWith<TeamFailure> { LabelRules.parseFilter((1..17).map { "k$it=v" }) }.code)
    }
}
