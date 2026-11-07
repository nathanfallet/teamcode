package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.cloud.CloudEventType
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudSessionSubagents
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Follows one runner batch to keep `cloud_session_subagents` exact: an agent appears with its `tool.call`, counts its
 * tool uses, ends with the result of its call, and, when the runner reports background tasks, stays `running` while a
 * live task stands for it. A batch costs a bounded number of statements (one insert, one update per agent that took
 * steps, at most three for results, a handful for the last task set), whatever it carries. The caller holds the
 * session row lock.
 */
internal class SubagentTracker(private val sessionId: String, private val now: Long) {
    private class Started(val type: String?, val description: String, val parentId: String?)
    private class Task(val id: String, val toolUseId: String, val type: String?, val description: String, val startedAt: Long?)

    private val started = LinkedHashMap<String, Started>()
    private val steps = HashMap<String, Int>()
    private val resultsOk = LinkedHashSet<String>()
    private val resultsFailed = LinkedHashSet<String>()
    private var tasks: List<Task>? = null
    private var tasksComplete = false

    /** The feed of [data]: its `parentToolUseId` when it is a plausible tool call id, null for the main agent. */
    fun see(type: CloudEventType, data: JsonObject?): String? {
        if (data == null) return null
        val feed = data.text("parentToolUseId")?.takeIf { CloudConversationRules.FEED.matches(it) }
        when (type) {
            CloudEventType.TOOL_CALL -> {
                if (feed != null) steps.merge(feed, 1, Int::plus)
                val id = data.text("id")
                if (id != null && data.text("name") in AGENT_TOOLS && CloudConversationRules.FEED.matches(id)) {
                    val input = data["input"] as? JsonObject
                    started.putIfAbsent(id, Started(input.text("subagent_type")?.take(64), input.text("description").orEmpty().take(200), feed))
                }
            }
            CloudEventType.TOOL_RESULT -> data.text("id")?.takeIf { CloudConversationRules.FEED.matches(it) }?.let {
                if ((data["isError"] as? JsonPrimitive)?.contentOrNull == "true") resultsFailed += it else resultsOk += it
            }
            CloudEventType.TASKS -> {
                val list = data["tasks"] as? JsonArray ?: return feed
                val parsed = list.mapNotNull { it as? JsonObject }.mapNotNull { task ->
                    val toolUseId = task.text("toolUseId")?.takeIf { CloudConversationRules.FEED.matches(it) } ?: return@mapNotNull null
                    // Only subagents are agents: background commands and monitors carry no `subagentType`.
                    val subagent = task.text("subagentType") ?: return@mapNotNull null
                    Task(task.text("id")?.take(64) ?: return@mapNotNull null, toolUseId, subagent.take(64), task.text("description").orEmpty().take(200), (task["startedAt"] as? JsonPrimitive)?.longOrNull)
                }
                tasks = parsed
                // A set cut at its limit does not say which tasks ended, so only a whole set closes agents.
                tasksComplete = ((data["count"] as? JsonPrimitive)?.intOrNull ?: list.size) <= list.size
            }
            else -> {}
        }
        return feed
    }

    fun flush() {
        if (started.isNotEmpty()) CloudSessionSubagents.batchInsert(started.entries, ignore = true, shouldReturnGeneratedValues = false) { (id, agent) ->
            this[CloudSessionSubagents.sessionId] = sessionId; this[CloudSessionSubagents.id] = id; this[CloudSessionSubagents.type] = agent.type
            this[CloudSessionSubagents.description] = agent.description; this[CloudSessionSubagents.state] = RUNNING; this[CloudSessionSubagents.startedAt] = now
            this[CloudSessionSubagents.steps] = 0; this[CloudSessionSubagents.parentId] = agent.parentId; this[CloudSessionSubagents.resultError] = false
        }
        for ((id, count) in steps) CloudSessionSubagents.update({ (CloudSessionSubagents.sessionId eq sessionId) and (CloudSessionSubagents.id eq id) }) {
            it.update(CloudSessionSubagents.steps, CloudSessionSubagents.steps + count)
        }
        // A call that returned ends its agent, unless a live task still stands for it (a background agent's call returns at once).
        for ((ids, final) in listOf(resultsOk to COMPLETED, resultsFailed to FAILED)) if (ids.isNotEmpty()) {
            CloudSessionSubagents.update({
                (CloudSessionSubagents.sessionId eq sessionId) and (CloudSessionSubagents.id inList ids) and CloudSessionSubagents.taskId.isNull() and (CloudSessionSubagents.state eq RUNNING)
            }) { it[state] = final; it[endedAt] = now; it[resultError] = final == FAILED }
        }
        if (resultsFailed.isNotEmpty()) CloudSessionSubagents.update({
            (CloudSessionSubagents.sessionId eq sessionId) and (CloudSessionSubagents.id inList resultsFailed) and CloudSessionSubagents.taskId.isNotNull()
        }) { it[resultError] = true }
        tasks?.let { flushTasks(it) }
    }

    private fun flushTasks(live: List<Task>) {
        val byAgent = live.associateBy { it.toolUseId }
        val existing = if (byAgent.isEmpty()) emptyMap() else CloudSessionSubagents.select(CloudSessionSubagents.id, CloudSessionSubagents.taskId, CloudSessionSubagents.state)
            .where { (CloudSessionSubagents.sessionId eq sessionId) and (CloudSessionSubagents.id inList byAgent.keys) }
            .associate { it[CloudSessionSubagents.id] to (it[CloudSessionSubagents.taskId] to it[CloudSessionSubagents.state]) }
        // An agent whose call this server never saw (it started before, or the call was purged) still shows, with its real start.
        val missing = live.filter { it.toolUseId !in existing }
        if (missing.isNotEmpty()) CloudSessionSubagents.batchInsert(missing, ignore = true, shouldReturnGeneratedValues = false) { task ->
            this[CloudSessionSubagents.sessionId] = sessionId; this[CloudSessionSubagents.id] = task.toolUseId; this[CloudSessionSubagents.type] = task.type
            this[CloudSessionSubagents.description] = task.description; this[CloudSessionSubagents.state] = RUNNING; this[CloudSessionSubagents.startedAt] = task.startedAt ?: now
            this[CloudSessionSubagents.steps] = 0; this[CloudSessionSubagents.taskId] = task.id; this[CloudSessionSubagents.resultError] = false
        }
        for (task in live) {
            val (taskId, state) = existing[task.toolUseId] ?: continue
            if (taskId == task.id && state == RUNNING) continue
            CloudSessionSubagents.update({ (CloudSessionSubagents.sessionId eq sessionId) and (CloudSessionSubagents.id eq task.toolUseId) }) {
                it[CloudSessionSubagents.taskId] = task.id; it[CloudSessionSubagents.state] = RUNNING; it[endedAt] = null
            }
        }
        if (!tasksComplete) return
        // Agents that had a live task and no longer do are over.
        for (failed in listOf(false, true)) CloudSessionSubagents.update({
            (CloudSessionSubagents.sessionId eq sessionId) and CloudSessionSubagents.taskId.isNotNull() and (CloudSessionSubagents.state eq RUNNING) and
                (CloudSessionSubagents.resultError eq failed) and (if (byAgent.isEmpty()) Op.TRUE else (CloudSessionSubagents.id notInList byAgent.keys))
        }) { it[state] = if (failed) FAILED else COMPLETED; it[endedAt] = now }
    }

    private fun JsonObject?.text(name: String) = (this?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        /** `Task` is the earlier name of the `Agent` tool. */
        private val AGENT_TOOLS = setOf("Agent", "Task")
    }
}
