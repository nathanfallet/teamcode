package me.nathanfallet.teamcode.cli.platform

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal actual fun readHookInput(): ByteArray? {
    if (System.console() != null) return null
    // JVM is a development/test target. A daemon reader cannot keep this command alive on a held-open pipe.
    val read = FutureTask { System.`in`.readNBytes(65_537) }
    Thread(read, "teamcode-hook-input").apply { isDaemon = true; start() }
    return try { read.get(1, TimeUnit.SECONDS) } catch (_: Exception) { read.cancel(true); null }
}
