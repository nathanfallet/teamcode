package me.nathanfallet.teamcode.cli.platform

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

private class JvmUnixConnection(private val channel: SocketChannel) : UnixConnection {
    private val buffer = ByteBuffer.allocate(65_536).apply { limit(0) }

    override suspend fun readLine(maxLine: Int): String? = runInterruptible(Dispatchers.IO) {
        try { read(maxLine) } catch (_: IOException) { null }
    }

    private fun read(maxLine: Int): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            if (!buffer.hasRemaining()) {
                buffer.clear()
                val count = channel.read(buffer)
                buffer.flip()
                if (count < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
            }
            val next = buffer.get()
            if (next == '\n'.code.toByte()) return line.toString(Charsets.UTF_8)
            if (line.size() < maxLine) line.write(next.toInt())
        }
    }

    override suspend fun writeLine(line: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = ByteBuffer.wrap((line + "\n").encodeToByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            true
        } catch (_: IOException) { false }
    }

    override fun close() { try { channel.close() } catch (_: IOException) {} }
}

private class JvmUnixListener(private val server: ServerSocketChannel, private val file: Path) : UnixListener {
    override suspend fun accept(): UnixConnection? = try {
        JvmUnixConnection(runInterruptible(Dispatchers.IO) { server.accept() })
    } catch (_: IOException) { null }
    override fun close() {
        try { server.close() } catch (_: IOException) {}
        try { Files.deleteIfExists(file) } catch (_: IOException) {}
    }
}

/**
 * The JVM cannot narrow the umask, so the socket is chmod-ed right after bind: there is a short window at the process's
 * default mode. Pods run the native binary (which binds under a narrowed umask); the JVM serves tests and development.
 */
actual fun listenUnix(path: String): UnixListener {
    val file = Path.of(path)
    Files.deleteIfExists(file)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(file))
    try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----")) }
    catch (_: UnsupportedOperationException) {} // No POSIX modes (Windows): nothing to narrow.
    catch (error: Exception) { server.close(); Files.deleteIfExists(file); throw error }
    return JvmUnixListener(server, file)
}

actual fun connectUnix(path: String): UnixConnection {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try { channel.connect(UnixDomainSocketAddress.of(path)) } catch (error: IOException) { channel.close(); throw error }
    return JvmUnixConnection(channel)
}
