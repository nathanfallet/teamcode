package me.nathanfallet.teamcode.cli.daemon

import me.nathanfallet.teamcode.api.models.ResumableSessions
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

/** The daemon's reading of Claude Code transcripts: what a folder offers to resume, and nothing more than a title leaves. */
class ClaudeTranscriptsTest {
    private val fs = FileSystem.SYSTEM
    private val ids = (1..9).map { "0000000$it-aaaa-4bbb-8ccc-00000000000$it" }

    private fun user(text: String, cwd: String, extra: String = "") =
        """{"type":"user","sessionId":"s","cwd":"$cwd","timestamp":"2026-10-25T10:00:00Z"$extra,"message":{"role":"user","content":${quote(text)}}}"""
    private fun quote(text: String) = buildString {
        append('"')
        for (char in text) when (char) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t"); else -> append(char) }
        append('"')
    }
    private fun assistant(cwd: String) = """{"type":"assistant","cwd":"$cwd","message":{"role":"assistant","content":[{"type":"text","text":"Sure."}]}}"""
    private fun toolResult(cwd: String) = """{"type":"user","cwd":"$cwd","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"file body"}]}}"""

    /** A Claude config directory and a project folder, with [lastWrite] taken from a map so the order never depends on the clock. */
    private fun scratch(block: (root: Path, folder: String, write: (String, Long, List<String>) -> Unit, transcripts: ClaudeTranscripts) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-transcripts " + secureRandomBytes(8).toByteString().hex())
        val folder = (root / "work" / "my_app.v2").toString()
        val writes = mutableMapOf<String, Long>()
        val project = root / "claude" / "projects" / ClaudeTranscripts.projectName(folder)
        fs.createDirectories(project)
        val transcripts = ClaudeTranscripts(root / "claude" / "projects", windows = false, lastWrite = { path, _ -> writes[path.name] ?: 0L })
        val write = { name: String, at: Long, lines: List<String> ->
            fs.write(project / name) { writeUtf8(lines.joinToString("\n", postfix = "\n")) }
            writes[name] = at
        }
        try { block(root, folder, { id, at, lines -> write("$id.jsonl", at, lines) }, transcripts) }
        finally { fs.deleteRecursively(root) }
    }

    @Test fun projectNamesFollowClaudeCode() {
        assertEquals("-Users-ada-git-opensource-teamcode", ClaudeTranscripts.projectName("/Users/ada/git/opensource/teamcode"))
        assertEquals("-home-ada-my-app-v2--x-", ClaudeTranscripts.projectName("/home/ada/my_app.v2/ x\u00e9"), "Dots, underscores, spaces and accents become -")
        assertEquals("C--Users-ada-app", ClaudeTranscripts.projectName("C:\\Users\\ada\\app"))
        assertEquals("/cfg/projects", ClaudeTranscripts.projectsDirectory("/cfg", null).toString(), "CLAUDE_CONFIG_DIR first")
        assertEquals("/home/ada/.claude/projects", ClaudeTranscripts.projectsDirectory(null, "/home/ada".toPath()).toString())
        assertNull(ClaudeTranscripts.projectsDirectory("", null))
    }

    @Test fun aFolderWithoutConversationsOffersNone() = scratch { root, folder, _, transcripts ->
        assertEquals(emptyList(), transcripts.list(folder, 20).sessions)
        assertEquals(emptyList(), transcripts.list((root / "elsewhere").toString(), 20).sessions, "No project directory at all")
        assertEquals(emptyList(), ClaudeTranscripts(null).list(folder, 20).sessions, "No home directory")
    }

    @Test fun theMostRecentComeFirstWithTitleDateAndTurns() = scratch { _, folder, write, transcripts ->
        write(ids[0], 1_000, listOf(user("Fix the login bug", folder), assistant(folder), toolResult(folder), user("And add a test", folder)))
        write(ids[1], 3_000, listOf(user("Second conversation", folder)))
        write(ids[2], 2_000, listOf(user("Third", folder), """{"type":"custom-title","customTitle":"Old name","sessionId":"s"}""",
            """{"type":"custom-title","customTitle":"Release  checklist","sessionId":"s"}"""))
        val page = transcripts.list(folder, 20)
        assertEquals(listOf(ids[1], ids[2], ids[0]), page.sessions.map { it.id })
        assertEquals(listOf("Second conversation", "Release checklist", "Fix the login bug"), page.sessions.map { it.title }, "The last name given wins over the first message")
        assertEquals(listOf(1, 1, 2), page.sessions.map { it.turns }, "Tool results are not messages")
        assertEquals(listOf(3_000L, 2_000L, 1_000L), page.sessions.map { it.lastActiveAt })
        assertNull(page.nextBefore)
    }

    @Test fun pagesAreBoundedAndContinueBeforeTheLastOne() = scratch { _, folder, write, transcripts ->
        for (i in 0 until 5) write(ids[i], 1_000L * (i + 1), listOf(user("Message $i", folder)))
        val first = transcripts.list(folder, 2)
        assertEquals(listOf(ids[4], ids[3]), first.sessions.map { it.id })
        assertEquals(4_000L, first.nextBefore)
        val second = transcripts.list(folder, 2, before = first.nextBefore)
        assertEquals(listOf(ids[2], ids[1]), second.sessions.map { it.id })
        val last = transcripts.list(folder, 2, before = second.nextBefore)
        assertEquals(listOf(ids[0]), last.sessions.map { it.id })
        assertNull(last.nextBefore)
        assertEquals(5, transcripts.list(folder, 500).sessions.size, "The limit is bounded, not refused")
        assertTrue(ResumableSessions.MAX_LIMIT < 500)
    }

    @Test fun emptyUnreadableAndMessageLessFilesAreNotOffered() = scratch { _, folder, write, transcripts ->
        write(ids[0], 1_000, listOf(""))
        write(ids[1], 2_000, listOf("not json at all", "{\"type\":\"user\",", "\u0000\u0001"))
        write(ids[2], 3_000, listOf(assistant(folder), toolResult(folder), """{"type":"custom-title","customTitle":"Only a name"}"""))
        write(ids[3], 4_000, listOf(user("<task-notification>done</task-notification>", folder), user("Meta", folder, ",\"isMeta\":true"),
            user("Side", folder, ",\"isSidechain\":true"), user("<local-command-stdout>x</local-command-stdout>", folder)))
        write(ids[4], 5_000, listOf("garbage", user("Kept despite a bad line", folder), "{broken"))
        assertEquals(listOf(ids[4]), transcripts.list(folder, 20).sessions.map { it.id }, "A corrupted line is skipped, the file kept")
        assertFalse(transcripts.belongs(folder, ids[1]))
        assertTrue(transcripts.belongs(folder, ids[4]))
    }

    @Test fun anotherFolderWithTheSameProjectNameIsNotOffered() = scratch { root, folder, write, transcripts ->
        // `/work/my_app.v2` and `/work/my-app-v2` share Claude Code's project name; only cwd tells them apart.
        val twin = (root / "work" / "my-app-v2").toString()
        assertEquals(ClaudeTranscripts.projectName(folder), ClaudeTranscripts.projectName(twin))
        write(ids[0], 1_000, listOf(user("Mine", folder)))
        write(ids[1], 2_000, listOf(user("The twin's", twin)))
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).sessions.map { it.id })
        assertEquals(listOf(ids[1]), transcripts.list(twin, 20).sessions.map { it.id })
        assertFalse(transcripts.belongs(folder, ids[1]), "An id of another folder is refused")
        assertTrue(transcripts.belongs(twin, ids[1]))
    }

    @Test fun onlyUuidTranscriptsAreReadAndIdsAreChecked() = scratch { _, folder, write, transcripts ->
        write(ids[0], 1_000, listOf(user("Kept", folder)))
        write("agent-1234", 2_000, listOf(user("A subagent file of an older version", folder)))
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).sessions.map { it.id })
        assertFalse(transcripts.belongs(folder, "../${ids[0]}"))
        assertFalse(transcripts.belongs(folder, ids[0].uppercase()))
        assertFalse(transcripts.belongs(folder, ids[5]), "No such transcript")
    }

    @Test fun aSymbolicLinkIsNeverFollowed() = scratch { root, folder, write, transcripts ->
        write(ids[0], 1_000, listOf(user("Real", folder)))
        fs.write(root / "outside.jsonl") { writeUtf8(user("Outside", folder) + "\n") }
        try { fs.createSymlink(root / "claude" / "projects" / ClaudeTranscripts.projectName(folder) / "${ids[1]}.jsonl", root / "outside.jsonl") }
        catch (_: Exception) { return@scratch } // No symbolic links for this user (Windows without the privilege).
        assertEquals(listOf(ids[0]), transcripts.list(folder, 20).sessions.map { it.id })
        assertFalse(transcripts.belongs(folder, ids[1]))
    }

    @Test fun titlesAreOneShortLineWithoutControlOrFormatCharacters() {
        fun title(vararg lines: String) = summarizeTranscript(Buffer().writeUtf8(lines.joinToString("\n")))?.title
        val long = "word ".repeat(100)
        val cut = title(user(long, "/w"))!!
        assertEquals(ResumableSessions.TITLE_LENGTH, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("Line one line two", title(user("Line one\n\n\tline two  ", "/w")))
        assertEquals("évite le 🚀 \"quoted\" <b>", title(user("évite le \u202e🚀 \"quoted\" <b>\u200b", "/w")), "Bidi overrides and zero-width characters dropped")
        val emoji = "a" + "🚀".repeat(60)
        val trimmed = title(user(emoji, "/w"))!!
        assertFalse(trimmed.dropLast(1).last().isHighSurrogate(), "A surrogate pair is never cut in two")
        assertEquals("/review 42", title(user("<command-message>review</command-message>\n<command-name>/review</command-name>\n<command-args>42</command-args>", "/w")))
        assertEquals("Look at this", title("""{"type":"user","cwd":"/w","message":{"content":[{"type":"image","source":{}},{"type":"text","text":"Look at this"}]}}"""))
        assertEquals("From summary", title(user("first", "/w"), """{"type":"summary","summary":"From summary","leafUuid":"x"}"""))
    }

    @Test fun aLineOverTheBoundIsSkippedWithoutStoppingTheFile() {
        val huge = """{"type":"user","cwd":"/w","message":{"content":"${"x".repeat(3 * 1024 * 1024)}"}}"""
        val summary = summarizeTranscript(Buffer().writeUtf8(listOf(huge, user("After the large line", "/w")).joinToString("\n")))
        assertEquals("After the large line", summary?.title)
        assertEquals(1, summary?.turns)
    }
}
