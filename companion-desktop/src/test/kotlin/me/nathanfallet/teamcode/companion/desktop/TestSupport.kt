package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import java.nio.file.Files
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.CloudSessionView

/** The app's preferences in memory: a test never writes this machine's own TeamCode preferences. */
val inMemorySettings = org.koin.dsl.module { single<com.russhwolf.settings.Settings> { com.russhwolf.settings.MapSettings() } }

/** A private temporary directory, deleted after [block]. */
fun <T> withTemporaryDirectory(block: (File) -> T): T {
    val directory = Files.createTempDirectory("teamcode-desktop").toFile()
    return try { block(directory) } finally { directory.deleteRecursively() }
}

val posix: Boolean get() = DesktopEnvironment.Os.current() != DesktopEnvironment.Os.WINDOWS

/**
 * A fake `teamcode` CLI: a shell script answering `version` with [version], recording its arguments and environment's
 * `XDG_CONFIG_HOME` in `calls`, and answering `remote start` and `remote resume` as [remote] says.
 */
fun fakeCli(directory: File, version: String = "1.0.0", remote: String = "echo '{\"id\":\"s-new\",\"name\":\"x\"}'; echo 'Remote session s-new on this device.' >&2"): File {
    val calls = File(directory, "calls")
    val script = File(directory, "teamcode")
    script.writeText(
        """
        |#!/bin/sh
        |echo "${'$'}* | ${'$'}{XDG_CONFIG_HOME-}" >> '${calls.path}'
        |case "${'$'}1" in
        |  version) echo "TeamCode $version" ;;
        |  remote) $remote ;;
        |  *) echo "unknown" >&2; exit 2 ;;
        |esac
        """.trimMargin() + "\n",
    )
    script.setExecutable(true)
    return script
}

fun sessionView(id: String, state: String, desiredState: String, deviceId: String?, online: Boolean, kind: String = "remote"): CloudSessionView =
    Serialization.json.decodeFromString(
        """{"id":"$id","name":"Session $id","ownerId":"m1","createdBy":{"initiatorId":"m1","credentialId":"c","effectiveMemberId":"m1"},
        "state":"$state","desiredState":"$desiredState","generation":1,"currentEpoch":1,"profile":{"id":"p1","version":1},"image":{"reference":"img"},
        "agent":{"kind":"claude"},"activity":{"lastActivityAt":42,"idleTimeoutMinutes":30},"resume":{"workspace":"w","conversation":"c"},
        "workspaceDeletion":{"state":"none"},"createdAt":1,"updatedAt":2,"kind":"$kind",
        "host":${if (deviceId == null) "null" else """{"deviceId":"$deviceId","deviceName":"Ada's MacBook","online":$online}"""}}""",
    )
