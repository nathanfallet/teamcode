package me.nathanfallet.teamcode.cli.todo

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.startAgentProcess

/** What a finished child process left: its exit code and the (bounded) text it printed, or [timedOut]. */
internal class Captured(val exit: Int, val output: String, val timedOut: Boolean = false)

/** Runs one command and captures its output; null when it could not be started. Stubbed in tests, real in [ProcessRunner]. */
internal fun interface CommandCapture {
    suspend fun run(command: String, arguments: List<String>, directory: String?, timeoutMillis: Long): Captured?
}

/** The real thing: a child process of this one, with stdin closed, whose stdout is read until it exits or the time is up. */
internal object ProcessRunner : CommandCapture {
    private const val MAX_OUTPUT = 64 * 1024
    override suspend fun run(command: String, arguments: List<String>, directory: String?, timeoutMillis: Long): Captured? {
        val process = try {
            startAgentProcess(command, arguments, mapOf("GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1", "NO_COLOR" to "1", "GIT_TERMINAL_PROMPT" to "0"), directory ?: ".")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        process.closeInput()
        val text = StringBuilder()
        val finished = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val line = process.readLine(MAX_OUTPUT) ?: break
                if (text.length < MAX_OUTPUT) text.append(line).append('\n')
            }
            true
        }
        val exit = process.stop(1_000)
        return Captured(exit, text.toString(), timedOut = finished == null)
    }
}

/** What a check observed, ready to report: [passed] null is "unknown". [links] are what it found and the item should be bound to. */
internal class CheckOutcome(val passed: Boolean?, val observed: String, val urls: List<String> = emptyList(), val links: List<LinkTodoItemRequest> = emptyList())

/**
 * Runs the typed checks of a todo item (docs/todos.md, section 2) from the agent's environment, which holds the GitHub token:
 * `gh` for pull requests, releases and CI, a shell for `command`. The condition is read from the server's stored item, never from the
 * agent, so the agent chooses neither the command nor the moment. A check that fails to answer is unknown, never done.
 *
 * Pull requests are found by the marker `todo_start` returned, whatever their number, and bound to the item once found; a release is
 * the earliest published one that contains the PR's merge commit.
 */
internal class TodoChecks(private val runner: CommandCapture, private val sessionId: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What a PR looked like: [state] is OPEN, CLOSED or MERGED. */
    private class Pr(val number: Int, val url: String, val state: String, val sha: String?, val author: String?)

    private suspend fun gh(vararg arguments: String): Captured? = runner.run("gh", arguments.toList(), null, GH_TIMEOUT_MILLIS)?.takeIf { !it.timedOut && it.exit == 0 }

    private fun repoOf(url: String) = Regex("https://github\\.com/([^/]+/[^/]+)/pull/\\d+").find(url)?.groupValues?.get(1)

    private fun parsePr(element: JsonObject) = Pr(
        element.getValue("number").jsonPrimitive.int, element.getValue("url").jsonPrimitive.content, element.getValue("state").jsonPrimitive.content,
        element["mergeCommit"]?.takeIf { it !is JsonNull }?.jsonObject?.get("oid")?.jsonPrimitive?.content, element["author"]?.takeIf { it !is JsonNull }?.jsonObject?.get("login")?.jsonPrimitive?.content,
    )

    /** The PR of [item]: the one it is linked to, else the one that carries its marker in [repo]. Null: none; the exception is "unknown". */
    private suspend fun pr(list: TodoView, item: TodoItemView, repo: String): Pr? {
        val linked = item.links.firstOrNull { it.kind == "pr" }
        if (linked != null) {
            val answer = gh("pr", "view", linked.ref, "--json", "number,state,url,mergeCommit,author") ?: throw Unknown("gh could not read ${linked.ref}")
            return parsePr(json.parseToJsonElement(answer.output).jsonObject)
        }
        val marker = marker(list, item.id)
        // The search narrows the candidates; the exact marker in the body decides, so another list's PR is never taken.
        val answer = gh("pr", "list", "--repo", repo, "--state", "all", "--search", "${list.id.take(8)} ${item.id} in:body", "--json", "number,state,url,mergeCommit,author,body", "--limit", "20")
            ?: throw Unknown("gh could not search the pull requests of $repo")
        val found = json.parseToJsonElement(answer.output).jsonArray.map { it.jsonObject }.filter { it["body"]?.jsonPrimitive?.contentOrNull?.contains(marker) == true }
        val me = gh("api", "user", "--jq", ".login")?.output?.trim()
        val mine = found.map(::parsePr).filter { me == null || it.author == null || it.author == me }
        return mine.maxByOrNull { it.number }
    }

    private fun marker(list: TodoView, itemId: String) = "<!-- teamcode-todo: ${list.id.take(8)}/$itemId -->"

    private class Unknown(message: String) : Exception(message)

    private fun linkOf(item: TodoItemView, pr: Pr) = LinkTodoItemRequest(sessionId, "pr", pr.url, pr.number, pr.sha)

    /** Every check of [item], in order, as the position-by-position report the server wants. */
    suspend fun run(list: TodoView, item: TodoItemView, attested: String?, urls: List<String>): List<Pair<Int, CheckOutcome>> =
        item.doneWhen.checks.mapIndexed { index, check ->
            index to try { evaluate(list, item, check, attested, urls) } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Unknown) { CheckOutcome(null, "unknown: ${error.message}") }
            catch (error: Exception) { CheckOutcome(null, "unknown: the check could not be read (${error::class.simpleName})") }
        }

    private suspend fun evaluate(list: TodoView, item: TodoItemView, check: TodoCheckView, attested: String?, urls: List<String>): CheckOutcome = when (check.type) {
        "pr_merged" -> {
            val pr = pr(list, item, check.repo!!)
            when {
                pr == null -> CheckOutcome(false, "No pull request of ${check.repo} carries this item's marker: open it with the marker from todo_start in its body.")
                pr.state == "MERGED" -> CheckOutcome(true, "PR #${pr.number} MERGED", listOf(pr.url), listOf(linkOf(item, pr)))
                else -> CheckOutcome(false, "PR #${pr.number} is ${pr.state}", listOf(pr.url), listOf(linkOf(item, pr)))
            }
        }
        "pr_closed" -> {
            val pr = pr(list, item, check.repo!!)
            when {
                pr == null -> CheckOutcome(false, "No pull request of ${check.repo} carries this item's marker.")
                pr.state == "CLOSED" -> CheckOutcome(true, "PR #${pr.number} closed without merge", listOf(pr.url), listOf(linkOf(item, pr)))
                else -> CheckOutcome(false, "PR #${pr.number} is ${pr.state}", listOf(pr.url))
            }
        }
        "release_contains" -> releaseContains(list, item, check)
        "ci_green" -> ciGreen(list, item, check)
        "command" -> command(list, item, check)
        "attested" -> if (attested.isNullOrBlank()) CheckOutcome(false, "This check is attested: give proof.text (and URLs) to todo_complete.") else CheckOutcome(true, "attested: ${mask(attested).take(500)}", urls)
        // The server verifies these through the item's links; what is reported here is ignored.
        else -> CheckOutcome(null, "verified by the server")
    }

    /** The merge commit of the PR of item [of]: from its link, else found by its marker. */
    private suspend fun mergeSha(list: TodoView, of: String, repo: String): String {
        val target = list.items.firstOrNull { it.id == of } ?: throw Unknown("item $of does not exist")
        target.links.firstOrNull { it.kind == "pr" }?.sha?.let { return it }
        val prRepo = target.doneWhen.checks.firstOrNull { it.type == "pr_merged" }?.repo ?: repo
        val found = pr(list, target, prRepo)
        return found?.takeIf { it.state == "MERGED" }?.sha ?: throw Unknown("the pull request of item $of is not merged yet")
    }

    private suspend fun releaseContains(list: TodoView, item: TodoItemView, check: TodoCheckView): CheckOutcome {
        val repo = check.repo!!
        val sha = check.commit ?: mergeSha(list, check.of ?: throw Unknown("no item or commit named"), repo)
        val releases = json.parseToJsonElement((gh("release", "list", "--repo", repo, "--exclude-drafts", "--limit", "30", "--json", "tagName,isPrerelease") ?: throw Unknown("gh could not list the releases of $repo")).output)
            .jsonArray.map { it.jsonObject.getValue("tagName").jsonPrimitive.content }
        var earliest: String? = null
        for (tag in releases.take(MAX_COMPARES)) {
            val status = gh("api", "repos/$repo/compare/$sha...${tag.replace("/", "%2F")}", "--jq", ".status")?.output?.trim() ?: throw Unknown("gh could not compare $tag")
            if (status == "ahead" || status == "identical") earliest = tag else break
        }
        return if (earliest == null) CheckOutcome(false, "no published release of $repo contains ${sha.take(7)}")
        else CheckOutcome(true, "release $earliest contains ${sha.take(7)}", listOf("https://github.com/$repo/releases/tag/$earliest"), listOf(LinkTodoItemRequest(sessionId, "release", "https://github.com/$repo/releases/tag/$earliest", tag = earliest)))
    }

    private suspend fun ciGreen(list: TodoView, item: TodoItemView, check: TodoCheckView): CheckOutcome {
        val repo = check.repo!!
        val sha = check.ref ?: mergeSha(list, check.of ?: throw Unknown("no item or ref named"), repo)
        val answer = gh("run", "list", "--repo", repo, "--commit", sha, "--json", "status,conclusion,workflowName", "--limit", "50") ?: throw Unknown("gh could not list the runs of $repo")
        val runs = json.parseToJsonElement(answer.output).jsonArray.map { it.jsonObject }.filter { check.workflow == null || it["workflowName"]?.jsonPrimitive?.contentOrNull == check.workflow }
        if (runs.isEmpty()) return CheckOutcome(false, "no CI run on ${sha.take(7)} yet")
        val pending = runs.count { it["status"]?.jsonPrimitive?.contentOrNull != "completed" }
        val failed = runs.filter { it["status"]?.jsonPrimitive?.contentOrNull == "completed" && it["conclusion"]?.jsonPrimitive?.contentOrNull !in setOf("success", "skipped", "neutral") }
        return when {
            failed.isNotEmpty() -> CheckOutcome(false, "CI on ${sha.take(7)}: ${failed.size} run(s) failed")
            pending > 0 -> CheckOutcome(false, "CI on ${sha.take(7)}: $pending run(s) still running")
            else -> CheckOutcome(true, "CI on ${sha.take(7)}: ${runs.size} run(s) green")
        }
    }

    private suspend fun command(list: TodoView, item: TodoItemView, check: TodoCheckView): CheckOutcome {
        val run = substitute(list, check.run!!)
        val shell = if (environment("OS") == "Windows_NT") "cmd.exe" to "/c" else "sh" to "-c"
        val seconds = (check.timeoutSeconds ?: 60).coerceIn(1, 120)
        val captured = runner.run(shell.first, listOf(shell.second, run), check.cwd, seconds * 1000L) ?: return CheckOutcome(null, "unknown: the shell could not be started")
        val tail = mask(captured.output.trim().takeLast(300))
        return when {
            captured.timedOut -> CheckOutcome(null, "unknown: the command did not finish in $seconds s")
            captured.exit == (check.expectExit ?: 0) -> CheckOutcome(true, "command exited ${captured.exit}" + if (tail.isNotEmpty()) ": $tail" else "")
            else -> CheckOutcome(false, "command exited ${captured.exit}, expected ${check.expectExit ?: 0}" + if (tail.isNotEmpty()) ": $tail" else "")
        }
    }

    /** `${pr:<item>.number}`, `${pr:<item>.mergeSha}` and `${release:<item>.tag}`, from what the items are linked to; an unlinked one makes the check unknown. */
    private fun substitute(list: TodoView, command: String): String = Regex("""\$\{(pr|release):([^.}]+)\.(number|mergeSha|tag)}""").replace(command) { match ->
        val (kind, id, field) = match.destructured
        val link = list.items.firstOrNull { it.id == id }?.links?.firstOrNull { it.kind == kind }
            ?: throw Unknown("waiting for the ${if (kind == "pr") "pull request" else "release"} of item $id")
        (when (field) { "number" -> link.number?.toString(); "mergeSha" -> link.sha; else -> link.tag } ?: throw Unknown("item $id has no $field yet")).filter { it.isLetterOrDigit() || it in "._-/" }
    }

    companion object {
        private const val GH_TIMEOUT_MILLIS = 20_000L
        private const val MAX_COMPARES = 15

        private val tokenShapes = Regex("""(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|Bearer\s+\S+|tc[a-z]*_[A-Za-z0-9_-]{20,})""")

        /** Masks what looks like a credential before a command's output is kept as proof. */
        fun mask(text: String): String = tokenShapes.replace(text, "[REDACTED]")
    }
}
