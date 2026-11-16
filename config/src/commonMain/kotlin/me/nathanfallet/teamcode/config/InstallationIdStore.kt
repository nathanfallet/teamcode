package me.nathanfallet.teamcode.config

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * The non-secret logical installation ID (`installation-id` next to `config.json`), shared by the CLI and the desktop app
 * of one machine so the server sees one device. Created without replacement, so two first runs agree on one value; it
 * deliberately survives logout and server changes.
 */
class InstallationIdStore(private val path: String, private val files: PrivateFiles) {
    fun id(): String {
        read()?.let { return it }
        files.create(path, Uuid.random().toString())
        return checkNotNull(read()) { "The installation identity could not be created." }
    }
    private fun read(): String? {
        val file = path.toPath()
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return null
        require(metadata.symlinkTarget == null && metadata.isRegularFile && (metadata.size ?: 0) <= 64) {
            "The installation identity must be a small regular file, not a symbolic link."
        }
        val id = readRetrying(file)
        require(isInstallationId(id)) { "Invalid TeamCode installation identity." }
        return id
    }

    /**
     * Windows can refuse to open a file for a moment while another first run publishes it (a sharing violation surfaces as
     * "file not found" although the metadata just showed it). The file exists and is complete once published, so a short,
     * bounded retry reads it; a real failure still surfaces after the last attempt.
     */
    private fun readRetrying(file: okio.Path): String {
        var attempt = 0
        while (true) {
            try { return FileSystem.SYSTEM.read(file) { readUtf8() } }
            catch (error: IOException) {
                if (++attempt >= READ_ATTEMPTS) throw error
                val until = TimeSource.Monotonic.markNow() + RETRY_DELAY_MILLIS.milliseconds
                while (until.hasNotPassedNow()) { /* common code has no sleep; the wait is short and only on this rare path */ }
            }
        }
    }

    private companion object {
        const val READ_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 25
    }
}

fun isInstallationId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
