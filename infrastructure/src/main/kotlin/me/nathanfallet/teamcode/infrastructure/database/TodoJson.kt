package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.todos.*
import me.nathanfallet.teamcode.domain.waits.WaitRef

/** The JSON columns of a stored todo. A value this server cannot read (a newer check type) is a failure, never a silent "done". */
internal object TodoJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun refs(refs: List<WaitRef>) = buildJsonArray { refs.forEach { r -> add(buildJsonObject { put("kind", r.kind); r.id?.let { put("id", it) }; r.url?.let { put("url", it) }; r.title?.let { put("title", it) } }) } }.toString()
    fun refs(text: String) = json.parseToJsonElement(text).jsonArray.map { val o = it.jsonObject; WaitRef(o.getValue("kind").jsonPrimitive.content, o["id"]?.jsonPrimitive?.content, o["url"]?.jsonPrimitive?.content, o["title"]?.jsonPrimitive?.content) }

    fun strings(values: List<String>) = buildJsonArray { values.forEach { add(it) } }.toString()
    fun strings(text: String) = json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }

    private fun check(c: TodoCheck) = buildJsonObject {
        put("type", c.type)
        when (c) {
            is TodoCheck.PrMerged -> put("repo", c.repo)
            is TodoCheck.PrClosed -> { put("repo", c.repo); put("reason", c.reason) }
            is TodoCheck.ReleaseContains -> { put("repo", c.repo); c.of?.let { put("of", it) }; c.commit?.let { put("commit", it) } }
            is TodoCheck.CiGreen -> { put("repo", c.repo); c.of?.let { put("of", it) }; c.ref?.let { put("ref", it) }; c.workflow?.let { put("workflow", it) } }
            is TodoCheck.Command -> { put("run", c.run); c.cwd?.let { put("cwd", it) }; put("expectExit", c.expectExit); put("timeoutSeconds", c.timeoutSeconds) }
            is TodoCheck.ArtifactPublished -> { c.id?.let { put("id", it) }; c.minVersion?.let { put("minVersion", it) } }
            is TodoCheck.WaitAnswered -> c.action?.let { put("action", it) }
            is TodoCheck.Attested -> put("what", c.what)
        }
    }

    private fun check(o: JsonObject): TodoCheck {
        fun s(key: String) = o.getValue(key).jsonPrimitive.content
        fun opt(key: String) = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        return when (val type = s("type")) {
            "pr_merged" -> TodoCheck.PrMerged(s("repo"))
            "pr_closed" -> TodoCheck.PrClosed(s("repo"), s("reason"))
            "release_contains" -> TodoCheck.ReleaseContains(s("repo"), opt("of"), opt("commit"))
            "ci_green" -> TodoCheck.CiGreen(s("repo"), opt("of"), opt("ref"), opt("workflow"))
            "command" -> TodoCheck.Command(s("run"), opt("cwd"), s("expectExit").toInt(), s("timeoutSeconds").toInt())
            "artifact_published" -> TodoCheck.ArtifactPublished(opt("id"), opt("minVersion")?.toInt())
            "wait_answered" -> TodoCheck.WaitAnswered(opt("action"))
            "attested" -> TodoCheck.Attested(s("what"))
            else -> throw IllegalStateException("Unknown todo check type $type.")
        }
    }

    fun doneWhen(d: TodoDoneWhen) = buildJsonObject { put("text", d.text); put("checks", buildJsonArray { d.checks.forEach { add(check(it)) } }) }.toString()
    fun doneWhen(text: String): TodoDoneWhen {
        val o = json.parseToJsonElement(text).jsonObject
        return TodoDoneWhen(o.getValue("text").jsonPrimitive.content, o.getValue("checks").jsonArray.map { check(it.jsonObject) })
    }

    fun proof(p: TodoProof) = buildJsonObject { put("at", p.at); p.passed?.let { put("passed", it) }; put("text", p.text); put("urls", buildJsonArray { p.urls.forEach { add(it) } }) }.toString()
    fun proof(text: String): TodoProof {
        val o = json.parseToJsonElement(text).jsonObject
        return TodoProof(o.getValue("at").jsonPrimitive.long, o["passed"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean, o.getValue("text").jsonPrimitive.content, o.getValue("urls").jsonArray.map { it.jsonPrimitive.content })
    }

    fun notes(notes: List<TodoNote>) = buildJsonArray { notes.forEach { n -> add(buildJsonObject { put("at", n.at); put("text", n.text) }) } }.toString()
    fun notes(text: String) = json.parseToJsonElement(text).jsonArray.map { TodoNote(it.jsonObject.getValue("at").jsonPrimitive.long, it.jsonObject.getValue("text").jsonPrimitive.content) }

    fun links(links: List<TodoLink>) = buildJsonArray {
        links.forEach { l -> add(buildJsonObject { put("kind", l.kind); put("ref", l.ref); l.number?.let { put("number", it) }; l.sha?.let { put("sha", it) }; l.tag?.let { put("tag", it) }; put("at", l.at) }) }
    }.toString()
    fun links(text: String?): List<TodoLink> = if (text == null) emptyList() else json.parseToJsonElement(text).jsonArray.map {
        val o = it.jsonObject
        TodoLink(o.getValue("kind").jsonPrimitive.content, o.getValue("ref").jsonPrimitive.content, o["number"]?.jsonPrimitive?.int, o["sha"]?.jsonPrimitive?.content, o["tag"]?.jsonPrimitive?.content, o.getValue("at").jsonPrimitive.long)
    }

    fun events(events: List<TodoHookEvent>) = buildJsonArray { events.forEach { e -> add(buildJsonObject { put("at", e.at); put("kind", e.kind); put("text", e.text) }) } }.toString()
    fun events(text: String?): List<TodoHookEvent> = if (text == null) emptyList() else json.parseToJsonElement(text).jsonArray.map {
        val o = it.jsonObject; TodoHookEvent(o.getValue("at").jsonPrimitive.long, o.getValue("kind").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content)
    }
}
