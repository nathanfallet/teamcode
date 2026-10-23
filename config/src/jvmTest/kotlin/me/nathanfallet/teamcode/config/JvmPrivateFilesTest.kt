package me.nathanfallet.teamcode.config

import java.nio.file.*
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class JvmPrivateFilesTest {
    private val root = Files.createTempDirectory("teamcode-private-")
    private val directory = root.resolve("teamcode")
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    @AfterTest fun clean() { root.toFile().deleteRecursively() }

    @Test fun `a private file is readable only by its owner`() {
        val file = directory.resolve("config.json")
        JvmPrivateFiles.write(file.toString(), "secret")
        assertEquals("secret", file.readText())
        if (posix) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        } else for (path in listOf(directory, file)) {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            val entries = view.acl
            assertEquals(1, entries.size, "Only the owner may have an entry on $path")
            assertEquals(AclEntryType.ALLOW, entries.single().type())
            assertEquals(view.owner, entries.single().principal())
        }
    }

    @Test fun `a symbolic link is never followed`() {
        val target = root.resolve("elsewhere").also { it.writeText("untouched") }
        Files.createDirectories(directory)
        val link = directory.resolve("config.json")
        try { Files.createSymbolicLink(link, target) } catch (_: Exception) { return } // Windows without the privilege.
        assertFails { JvmPrivateFiles.write(link.toString(), "secret") }
        assertEquals("untouched", target.readText())
    }

    @Test fun `create never replaces an existing file`() {
        val file = directory.resolve("installation-id").toString()
        assertTrue(JvmPrivateFiles.create(file, "first"))
        assertFalse(JvmPrivateFiles.create(file, "second"))
        assertEquals("first", Path.of(file).readText())
    }

    @Test fun `concurrent writers leave one complete content and no temporary file`() {
        val file = directory.resolve("config.json")
        val contents = (0 until 8).map { writer -> "writer-$writer-" + "x".repeat(4_096) }
        val pool = Executors.newFixedThreadPool(contents.size)
        val start = CountDownLatch(1)
        try {
            val tasks = contents.map { content -> pool.submit { start.await(); repeat(25) { JvmPrivateFiles.write(file.toString(), content) } } }
            start.countDown()
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertTrue(file.readText() in contents)
        assertEquals(listOf("config.json"), directory.listDirectoryEntries().map { it.name })
    }

    @Test fun `concurrent creators agree on one installation identity`() {
        val file = directory.resolve("installation-id").toString()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val ids = (0 until 8).map { pool.submit<String> { start.await(); InstallationIdStore(file, JvmPrivateFiles).id() } }
            start.countDown()
            assertEquals(1, ids.map { it.get(60, TimeUnit.SECONDS) }.toSet().size)
        } finally { pool.shutdownNow() }
    }
}
