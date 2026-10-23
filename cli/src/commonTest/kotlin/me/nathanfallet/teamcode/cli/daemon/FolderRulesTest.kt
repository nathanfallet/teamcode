package me.nathanfallet.teamcode.cli.daemon

import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.holdExclusiveLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

/** `teamcode folders`: what may become a folder, how the daemon checks an order again, and the single-instance lock. */
class FolderRulesTest {
    private fun refused(block: () -> Unit) = assertFailsWith<CliUserException> { block() }.message

    @Test fun theHomeDirectoryItsParentsTraversalAndSystemDirectoriesAreRefused() = scratch { root, home ->
        FileSystem.SYSTEM.createDirectories(home / "git" / "teamcode")
        assertEquals(home / "git" / "teamcode", folderPath((home / "git" / "teamcode").toString(), home, windows = false))
        assertEquals(home / "git", folderPath("${home / "git" / "teamcode"}/..", home, windows = false), "`..` is resolved")
        assertTrue("may not run in" in refused { folderPath("${home / "git"}/..", home, windows = false) }, "Back to the home directory through `..`")
        assertTrue("may not run in" in refused { folderPath(home.toString(), home, windows = false) })
        assertTrue("contains your home directory" in refused { folderPath(root.toString(), home, windows = false) }, "A parent of the home directory")
        assertTrue("may not run in" in refused { folderPath("/", home, windows = false) })
        if (FileSystem.SYSTEM.metadataOrNull("/usr/bin".toPath())?.isDirectory == true)
            assertTrue("system directory" in refused { folderPath("/usr/bin", home, windows = false) })
        assertTrue("does not exist" in refused { folderPath((home / "missing").toString(), home, windows = false) })
        FileSystem.SYSTEM.write(home / "file") { writeUtf8("x") }
        assertTrue("not a directory" in refused { folderPath((home / "file").toString(), home, windows = false) })
    }

    @Test fun aSymbolicLinkIsResolvedAndCannotLeadOutOfTheRules() = scratch { root, home ->
        FileSystem.SYSTEM.createDirectories(root / "outside" / "work")
        try {
            FileSystem.SYSTEM.createSymlink(home / "work", root / "outside" / "work")
            FileSystem.SYSTEM.createSymlink(home / "me", home)
        } catch (_: Exception) { return@scratch } // No symbolic links for this user (Windows without the privilege).
        assertEquals(root / "outside" / "work", folderPath((home / "work").toString(), home, windows = false), "The folder is where the link points")
        assertTrue("may not run in" in refused { folderPath((home / "me").toString(), home, windows = false) }, "A link to the home directory")

        // A folder added as a directory, later replaced by a link pointing elsewhere, is refused at order time.
        FileSystem.SYSTEM.createDirectories(home / "project")
        val file = FoldersFile(listOf(LocalFolder("p1", (home / "project").toString())))
        assertEquals(OrderCheck.Accepted((home / "project").toString()), checkOrder("p1", "project", "claude", file, home, windows = false))
        FileSystem.SYSTEM.delete(home / "project")
        FileSystem.SYSTEM.createSymlink(home / "project", root / "outside" / "work")
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MOVED), checkOrder("p1", "project", "claude", file, home, windows = false))
    }

    @Test fun anOrderIsCheckedAgainstTheDaemonsOwnFolders() = scratch { _, home ->
        FileSystem.SYSTEM.createDirectories(home / "project")
        val file = FoldersFile(listOf(LocalFolder("p1", (home / "project").toString()), LocalFolder("gone", (home / "gone").toString()),
            LocalFolder("home", home.toString())))
        assertEquals(OrderCheck.Accepted((home / "project").toString()), checkOrder("p1", "project", "codex", file, home, windows = false))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_NOT_CONFIGURED), checkOrder("other", "project", "claude", file, home, windows = false))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MISSING), checkOrder("gone", "gone", "claude", file, home, windows = false))
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_REFUSED), checkOrder("home", home.name, "claude", file, home, windows = false), "A hand-edited file")
        assertEquals(OrderCheck.Refused(RefusalReasons.FOLDER_MISMATCH), checkOrder("p1", "other", "claude", file, home, windows = false))
        assertEquals(OrderCheck.Refused(RefusalReasons.AGENT_UNSUPPORTED), checkOrder("p1", "project", "gemini", file, home, windows = false))
        RefusalReasons.run { listOf(FOLDER_NOT_CONFIGURED, FOLDER_MISSING, FOLDER_MOVED, FOLDER_REFUSED, FOLDER_MISMATCH, AGENT_UNSUPPORTED, HOST_FAILED) }
            .forEach { assertTrue(Regex("[a-z_]{1,64}").matches(it), "The server's reason format: $it") }
    }

    @Test fun foldersKeepAStableIdAndPublishOnlyNamesAndLabels() = scratch { root, home ->
        // The private directory is created by the store's own writes (on Windows, owned by the user).
        val store = FolderStore(root / "config" / "folders.json", configStore((root / "config" / "config.json").toString()))
        FileSystem.SYSTEM.createDirectories(home / "git" / "teamcode")
        FileSystem.SYSTEM.createDirectories(root / "srv")
        val (first, added) = store.add(home / "git" / "teamcode", "/usr/bin:/opt/bin")
        assertTrue(added)
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(first.id), "The server's id format")
        val (again, addedAgain) = store.add(home / "git" / "teamcode", null)
        assertFalse(addedAgain)
        assertEquals(first, again, "The same folder keeps its id")
        val other = store.add(root / "srv", null).first
        assertEquals("/usr/bin:/opt/bin", store.read().searchPath)
        assertEquals(listOf(first.id to "teamcode", other.id to "srv"), published(store.read(), home).map { it.id to it.name })
        val separator = Path.DIRECTORY_SEPARATOR
        assertEquals("~${separator}git${separator}teamcode", published(store.read(), home).first().label, "The platform's own separator")
        assertEquals((root / "srv").toString(), published(store.read(), home).last().label)
        assertEquals(first, store.remove(first.id))
        assertEquals(other, store.remove((root / "srv").toString()), "By path too")
        assertNull(store.remove("unknown"))
        assertTrue(store.read().folders.isEmpty())
        assertTrue(FileSystem.SYSTEM.exists(root / "config" / "config.lock"), "Written under config.lock")
    }

    @Test fun onWindowsAFolderKeepsItsIdWhateverTheSeparatorSpelling() = scratch { root, _ ->
        val store = FolderStore(root / "config" / "folders.json", configStore((root / "config" / "config.json").toString()), windows = true)
        val (first, added) = store.add("C:\\Users\\me\\git\\app".toPath(), null)
        assertTrue(added)
        val (again, addedAgain) = store.add("C:/Users/me/git/app".toPath(), null)
        assertFalse(addedAgain, "The same folder spelled with forward slashes")
        assertEquals(first.id, again.id)
        assertEquals(first, store.remove("C:/Users/me/git/app/"), "Removed by either spelling")
        assertTrue(store.read().folders.isEmpty())
    }

    @Test fun onlyOneDaemonHoldsTheLock() = scratch { root, _ ->
        val path = daemonLockPath((root / "config.json").toString())
        val first = assertNotNull(holdExclusiveLock(path))
        assertNull(holdExclusiveLock(path), "A second daemon of the same configuration does not start")
        first.close()
        val third = assertNotNull(holdExclusiveLock(path), "Free again once the first one stops")
        third.close()
    }

    @Test fun aCloudRunnerIsNeverADevice() {
        val none = mapOf<String, String>()
        assertFalse(insideCloudRunner({ none[it] }, { false }))
        for (name in listOf("TEAMCODE_CLOUD_SESSION", "TEAMCODE_CLOUD_TOKEN_FILE", "TEAMCODE_AGENT_SOCKET", "TEAMCODE_CREDENTIAL_SOCKET"))
            assertTrue(insideCloudRunner({ if (it == name) "x" else null }, { false }), name)
        assertFalse(insideCloudRunner({ if (it == "TEAMCODE_AGENT_SOCKET") "" else null }, { false }))
        assertTrue(insideCloudRunner({ null }, { it == "/run/teamcode/agent.sock" }), "An agent whose environment was trimmed still sees the pod's sockets")
    }

    @Test fun theAgentsSearchPathAddsTheTerminalsAndTheUsualDirectories() {
        val home = "/home/me".toPath()
        val existing = setOf("/home/me/.local/bin", "/opt/homebrew/bin")
        assertEquals("/home/me/.nvm/bin:/usr/bin:/bin:/home/me/.local/bin:/opt/homebrew/bin",
            agentSearchPath("/usr/bin:/bin", "/home/me/.nvm/bin:relative:/usr/bin", home) { it in existing })
        assertNull(agentSearchPath("/usr/bin", null, null) { false }, "Nothing to add")
    }
}
