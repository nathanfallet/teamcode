package me.nathanfallet.teamcode.cli.platform

/** Longest line a socket reader keeps; the rest of a longer line is dropped up to its newline. */
const val MAX_SOCKET_LINE = 16 * 1024 * 1024

/** One end of a Unix stream socket, read and written as lines. Reads and writes may run concurrently; callers serialize writes. */
interface UnixConnection {
    /** The next line, or null at end of stream. */
    suspend fun readLine(maxLine: Int = MAX_SOCKET_LINE): String?
    /** Writes one line (a newline is appended); false once the peer is gone. */
    suspend fun writeLine(line: String): Boolean
    fun close()
}

interface UnixListener {
    /** The next connection, or null once the listener is closed. */
    suspend fun accept(): UnixConnection?
    fun close()
}

/** Listens on [path], replacing a stale socket file. The socket is readable and writable by its owner and group only. */
expect fun listenUnix(path: String): UnixListener

/** Connects to the socket at [path]; throws when nothing listens there. */
expect fun connectUnix(path: String): UnixConnection
