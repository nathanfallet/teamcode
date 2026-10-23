package me.nathanfallet.teamcode.cli.daemon

import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.config.CliPrivateFiles
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.configSibling
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * A session the daemon hosts ([HOSTING], since it accepted it) or paused when it stopped ([PAUSED]): the next daemon
 * resumes the paused ones, and a session left [HOSTING] by a daemon that did not stop cleanly (a crash, a power cut)
 * once the server paused it for its lost host.
 */
@Serializable
internal data class DaemonSession(val sessionId: String, val epoch: Int, val state: String) {
    companion object {
        const val HOSTING = "hosting"
        const val PAUSED = "paused"
    }
}

@Serializable
private data class DaemonSessionsFile(val sessions: List<DaemonSession> = emptyList())

/** `daemon-sessions.json` beside `config.json`, written privately and under `config.lock`. An unreadable file is empty. */
internal class DaemonSessions(
    private val file: Path = configSibling(configPath(), "daemon-sessions.json").toPath(),
    private val lock: ConfigStore = configStore(),
) {
    fun read(): List<DaemonSession> = try {
        val metadata = FileSystem.SYSTEM.metadataOrNull(file)
        if (metadata == null || metadata.symlinkTarget != null || !metadata.isRegularFile || (metadata.size ?: 0) > 262_144) emptyList()
        else Serialization.json.decodeFromString<DaemonSessionsFile>(FileSystem.SYSTEM.read(file) { readUtf8() }).sessions
    } catch (_: Exception) { emptyList() }

    fun put(session: DaemonSession) = update { sessions -> sessions.filter { it.sessionId != session.sessionId } + session }
    fun remove(sessionId: String) = update { sessions -> sessions.filter { it.sessionId != sessionId } }

    private fun update(change: (List<DaemonSession>) -> List<DaemonSession>) = lock.locked {
        val current = read()
        val next = change(current)
        if (next != current) CliPrivateFiles.write(file.toString(), Serialization.json.encodeToString(DaemonSessionsFile(next)))
    }
}
