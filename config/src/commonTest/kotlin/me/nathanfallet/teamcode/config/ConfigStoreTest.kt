package me.nathanfallet.teamcode.config

import kotlin.test.*
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Plain atomic writes: these tests cover the store and the lock, the private writers have their own platform tests. */
private object PlainFiles : PrivateFiles {
    override fun write(path: String, content: String) {
        val temporary = "$path.${Uuid.random()}.tmp".toPath()
        FileSystem.SYSTEM.createDirectories(temporary.parent!!)
        FileSystem.SYSTEM.write(temporary) { writeUtf8(content) }
        FileSystem.SYSTEM.atomicMove(temporary, path.toPath())
    }
    override fun create(path: String, content: String): Boolean {
        if (FileSystem.SYSTEM.exists(path.toPath())) return false
        write(path, content)
        return true
    }
}

class ConfigStoreTest {
    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-config-${Uuid.random()}" / "teamcode"
    private val path = (directory / "config.json").toString()
    @AfterTest fun clean() { FileSystem.SYSTEM.deleteRecursively(directory.parent!!, mustExist = false) }

    @Test fun `the configuration keeps the format the CLI has always written`() {
        FileSystem.SYSTEM.createDirectories(directory)
        val store = ConfigStore(path, PlainFiles)
        assertNull(store.load())
        store.save(CliConfig("https://team.example", "token-1"))
        assertEquals("""{"serverUrl":"https://team.example","accessToken":"token-1"}""", FileSystem.SYSTEM.read(path.toPath()) { readUtf8() })
        FileSystem.SYSTEM.write(path.toPath()) { writeUtf8("""{"serverUrl":"https://team.example","accessToken":"token-2","future":true}""") }
        assertEquals("token-2", store.load()?.accessToken)
    }

    @Test fun `an unreadable configuration names its path but never its content`() {
        FileSystem.SYSTEM.createDirectories(directory)
        FileSystem.SYSTEM.write(path.toPath()) { writeUtf8("""{"serverUrl":"http://remote.example","accessToken":"secret-token"}""") }
        val error = assertFailsWith<UserFacingException> { ConfigStore(path, PlainFiles).load() }
        assertTrue(path in error.message)
        assertFalse("secret-token" in error.message)
    }

    @Test fun `replace returns the configuration it replaced and deleteIf keeps a newer one`() {
        val store = ConfigStore(path, PlainFiles)
        val first = CliConfig("https://team.example", "token-1")
        val second = CliConfig("https://team.example", "token-2")
        assertNull(store.replace(first))
        assertEquals("token-1", store.replace(second)?.accessToken)
        assertFalse(store.deleteIf(first), "Logout must not delete a configuration another sign-in just wrote")
        assertEquals("token-2", store.load()?.accessToken)
        assertTrue(store.deleteIf(second))
        assertNull(store.load())
        assertTrue(FileSystem.SYSTEM.exists(store.lockPath.toPath()), "The lock file is never deleted")
    }

    @Test fun `a second acquisition of the lock waits then refuses and the lock is free again afterwards`() {
        val store = ConfigStore(path, PlainFiles)
        val refusal = store.locked {
            assertFailsWith<UserFacingException> { withFileLock(store.lockPath, timeoutMillis = 100) { } }
        }
        assertTrue("config.lock" in refusal.message)
        assertEquals(1, store.locked { 1 })
    }

    @Test fun `the installation identity is created once and then reused`() {
        val id = InstallationIdStore(configSibling(path, "installation-id"), PlainFiles)
        val first = id.id()
        assertTrue(isInstallationId(first))
        assertEquals(first, InstallationIdStore(configSibling(path, "installation-id"), PlainFiles).id())
    }
}
