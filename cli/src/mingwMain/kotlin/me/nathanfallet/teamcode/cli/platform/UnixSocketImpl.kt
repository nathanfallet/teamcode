package me.nathanfallet.teamcode.cli.platform

// Cloud pods run Linux: the runner, the supervisor and the credential helper never run on Windows.
actual fun listenUnix(path: String): UnixListener = throw UnsupportedOperationException("Unix sockets are not available on Windows.")
actual fun connectUnix(path: String): UnixConnection = throw UnsupportedOperationException("Unix sockets are not available on Windows.")
