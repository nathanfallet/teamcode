package me.nathanfallet.teamcode.config

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Holds the lock in a separate JVM, the way the desktop app and a CLI command run side by side. */
object LockHolder {
    @JvmStatic fun main(arguments: Array<String>) {
        withFileLock(arguments[0]) {
            println("locked")
            System.out.flush()
            Thread.sleep(arguments[1].toLong())
        }
    }
}

class JvmConfigLockTest {
    private val root = Files.createTempDirectory("teamcode-lock-")
    private val lock = root.resolve("teamcode").resolve("config.lock").toString()
    @AfterTest fun clean() { root.toFile().deleteRecursively() }

    @Test fun `threads of one process take the lock one at a time`() {
        val inside = AtomicInteger()
        val overlap = AtomicInteger()
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 6).map {
                pool.submit {
                    start.await()
                    repeat(10) {
                        withFileLock(lock) {
                            if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
                            Thread.sleep(2)
                            inside.decrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(0, overlap.get())
    }

    @Test fun `another process holding the lock makes this one wait then refuse`() {
        val javaBinary = ProcessHandle.current().info().command().orElseThrow()
        val holder = ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"), LockHolder::class.java.name, lock, "3000")
            .redirectErrorStream(true).start()
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            val refusal = assertFailsWith<UserFacingException> { withFileLock(lock, timeoutMillis = 300) { } }
            assertTrue("config.lock" in refusal.message)
            // The holder exits on its own; the kernel releases its lock and nothing deletes the file.
            assertEquals("free", withFileLock(lock, timeoutMillis = 10_000) { "free" })
            assertTrue(Files.exists(java.nio.file.Path.of(lock)))
        } finally {
            holder.waitFor(10, TimeUnit.SECONDS)
            if (holder.isAlive) holder.destroy()
        }
    }
}
