package me.nathanfallet.teamcode.config

import kotlin.uuid.Uuid
import okio.FileSystem
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
        val id = FileSystem.SYSTEM.read(file) { readUtf8() }
        require(isInstallationId(id)) { "Invalid TeamCode installation identity." }
        return id
    }
}

fun isInstallationId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
