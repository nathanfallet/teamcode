package me.nathanfallet.teamcode.cli.platform

import kotlinx.coroutines.*
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.*

/** The wake-up channel of a session host: Unix sockets on every native platform (AF_UNIX through Winsock on Windows). */
class UnixSocketTest {
    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tc-${Random.nextInt().toUInt().toString(16)}"
    @AfterTest fun cleanup() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }

    @Test fun linesGoBothWaysAndAClosedPeerEndsTheStream() = runBlocking {
        withTimeout(30_000) {
            FileSystem.SYSTEM.createDirectories(directory)
            val path = (directory / "a.sock").toString()
            val listener = listenUnix(path)
            try {
                val accepted = async(Dispatchers.Default) { listener.accept() }
                val client = connectUnix(path)
                val server = assertNotNull(accepted.await())
                assertTrue(client.writeLine("ping"))
                assertEquals("ping", server.readLine())
                assertTrue(server.writeLine("pong"))
                assertEquals("pong", client.readLine())
                assertTrue(client.writeLine("x".repeat(100) + "\nnext"))
                assertEquals("x".repeat(10), server.readLine(10), "A long line is cut, never buffered whole")
                assertEquals("next", server.readLine())
                client.close()
                assertNull(server.readLine(), "End of stream once the peer closed")
                server.close()
            } finally { listener.close() }
        }
    }

    @Test fun aStaleSocketFileIsReplacedAndNothingListeningRefusesTheConnection() = runBlocking {
        withTimeout(30_000) {
            FileSystem.SYSTEM.createDirectories(directory)
            val path = (directory / "b.sock").toString()
            assertFails { connectUnix(path) }
            listenUnix(path).close()
            val again = listenUnix(path)
            again.close()
            assertNull(again.accept(), "A closed listener accepts nothing")
        }
    }
}
