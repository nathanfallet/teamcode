package me.nathanfallet.teamcode.config

import java.nio.file.*
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * An elevated Windows process (the CI runner, an administrator shell) creates files owned by Administrators. Another
     * first run must never find the directory before it belongs to the user, or it refuses it as someone else's.
     */
    @Test fun `concurrent first runs never see the directory before it is private`() {
        repeat(20) { round ->
            val directory = root.resolve("round-$round").resolve("teamcode")
            val stop = AtomicBoolean(false)
            val seen = AtomicReference<String?>(null)
            val observer = Thread {
                while (!stop.get() && seen.get() == null) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                    val problem = try { privacyProblem(directory) } catch (_: NoSuchFileException) { null }
                    if (problem != null) seen.compareAndSet(null, problem)
                }
            }.apply { start() }
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            try {
                val tasks = (0 until 8).map { pool.submit { start.await(); JvmPrivateFiles.ensurePrivateDirectory(directory) } }
                start.countDown()
                tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow(); stop.set(true); observer.join() }
            assertNull(seen.get(), "Round $round")
            assertNull(privacyProblem(directory))
            assertEquals(listOf("teamcode"), directory.parent.listDirectoryEntries().map { it.name }, "No staging directory is left behind")
        }
    }

    private fun privacyProblem(path: Path): String? {
        if (posix) return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
            .takeIf { it != "rwx------" }?.let { "$path is $it" }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        val entries = view.acl
        return when {
            entries.size != 1 -> "$path has ${entries.size} entries"
            entries.single().principal() != view.owner -> "$path is owned by ${view.owner}, not by the user of its entry ${entries.single().principal()}"
            else -> null
        }
    }
}
