package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.remote.RemoteSessionRecord
import me.nathanfallet.teamcode.cli.remote.RemoteSessions
import me.nathanfallet.teamcode.cli.remote.knownFolders
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class KnownFoldersTest {
    private val home = "/Users/me".toPath()

    @Test fun recentSessionsComeFirstThenOfferedFoldersLatestAdded() {
        val view = knownFolders(
            recent = listOf("/Users/me/git/app" to 200L, "/Users/me/git/api" to 100L),
            offered = listOf("/Users/me/git/old", "/Users/me/git/api", "/Users/me/git/new"),
            limit = 20, home = home, windows = false, exists = { true },
        )
        assertEquals(listOf("/Users/me/git/app", "/Users/me/git/api", "/Users/me/git/new", "/Users/me/git/old"), view.folders.map { it.path })
        assertEquals(listOf(200L, 100L, null, null), view.folders.map { it.lastUsedAt }, "Only a folder a session started in has a date")
    }

    @Test fun missingHomeAndSystemFoldersAreLeftOutAndTheListIsBounded() {
        val view = knownFolders(
            recent = listOf("/Users/me" to 9L, "/etc/ssh" to 8L, "/Users/me/gone" to 7L, "/Users/me/a" to 6L, "/Users/me/b" to 5L, "/Users/me/c" to 4L),
            offered = emptyList(), limit = 2, home = home, windows = false, exists = { it != "/Users/me/gone" },
        )
        assertEquals(listOf("/Users/me/a", "/Users/me/b"), view.folders.map { it.path })
    }

    @Test fun aPathWithControlCharactersIsNeverOffered() {
        val view = knownFolders(listOf("/Users/me/x\u001b[2Jy" to 1L), listOf("/Users/me/ok\ninjected"), 20, home, false) { true }
        assertEquals(emptyList(), view.folders)
    }

    @Test fun windowsSpellingsOfOneFolderCountOnce() {
        val view = knownFolders(listOf("C:\\git\\app" to 3L, "c:/git/app/" to 2L), listOf("C:\\git\\APP"), 20, "C:\\Users\\me".toPath(), true) { true }
        assertEquals(listOf("C:\\git\\app"), view.folders.map { it.path })
    }

    @Test fun recordsGiveEachDirectoryOnceNewestFirst() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-known-${kotlin.random.Random.nextLong().toULong()}" / "remote"
        try {
            val sessions = RemoteSessions(root)
            assertEquals(emptyList(), sessions.recentDirectories(), "No record yet")
            sessions.save(RemoteSessionRecord("8f3c0a2e-1111-4222-8333-944455556661", "/Users/me/one", "claude"))
            sessions.save(RemoteSessionRecord("8f3c0a2e-1111-4222-8333-944455556662", "/Users/me/one", "codex"))
            val found = sessions.recentDirectories()
            assertEquals(listOf("/Users/me/one"), found.map { it.first }, "A directory used twice is listed once")
        } finally { FileSystem.SYSTEM.deleteRecursively(root.parent!!, mustExist = false) }
    }
}
