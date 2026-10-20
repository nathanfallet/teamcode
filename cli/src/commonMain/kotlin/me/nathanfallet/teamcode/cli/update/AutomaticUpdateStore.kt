package me.nathanfallet.teamcode.cli.update

import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.cli.service.randomHex
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Explicit local opt-in; contains paths/identity, never team or provider credentials. */
@Serializable
internal data class AutomaticUpdateSettings(val schemaVersion: Int = 1, val id: String, val configuration: String,
    val executable: String, val registration: String, val os: String, val userId: String, val enabled: Boolean = false) {
    val label: String get() = "me.nathanfallet.teamcode.updates.$id"
    init {
        require(schemaVersion == 1 && Regex("[a-f0-9]{32}").matches(id))
        require(os in setOf("macos", "linux", "windows"))
        require(if (os == "windows") Regex("S-1-[0-9-]{1,180}").matches(userId) else userId.isNotEmpty() && userId.all { it.isDigit() } && userId != "0")
        listOf(configuration, executable, registration).forEach { require(it.toPath().isAbsolute && it.none { c -> c.code < 32 || c.code == 127 }) }
        require(registration.toPath().name == label + when (os) { "macos" -> ".plist"; "linux" -> ".timer"; else -> ".xml" })
    }
}

/** STARTED is an observation, not proof that an OS worker is still alive. */
@Serializable
internal data class AutomaticUpdateResult(val schemaVersion: Int = 1, val attemptedAtMillis: Long,
    val outcome: String, val version: String? = null, val diagnostic: String? = null) {
    init {
        require(schemaVersion == 1 && attemptedAtMillis >= 0)
        require(outcome in setOf("STARTED", "NO_UPDATE", "UPDATED", "DEFERRED", "FAILED"))
        require(version == null || me.nathanfallet.teamcode.api.models.ReleaseFormat.validVersion(version))
        require(diagnostic == null || Regex("[a-zA-Z0-9_]{1,120}").matches(diagnostic))
    }
}

/** Private policy and bounded results survive process/service restarts. */
internal class AutomaticUpdateStore(paths: InstallationPaths) {
    private val fs = FileSystem.SYSTEM
    val paths = InstallationPaths(fs.canonicalize(paths.root), paths.target)
    val policy = paths.root / "automatic-update.json"
    private val result = paths.root / "automatic-update-result.json"
    val workerLock = paths.root / "automatic-update-worker.lock"
    val managementLock = paths.root / "automatic-update-settings.lock"

    fun settings(): AutomaticUpdateSettings? = read<AutomaticUpdateSettings>(policy)?.also {
        if (it.executable != paths.launcher.toString()) throw CliOperationException("automatic_settings_invalid")
    }
    fun result(): AutomaticUpdateResult? = read(result)
    fun save(settings: AutomaticUpdateSettings) {
        require(settings.executable == paths.launcher.toString())
        validatePrivateInstallationPath(paths.root.toString(), directory = true)
        writePrivateFile(policy.toString(), Serialization.json.encodeToString(AutomaticUpdateSettings.serializer(), settings))
    }
    fun record(value: AutomaticUpdateResult) {
        validatePrivateInstallationPath(paths.root.toString(), directory = true)
        writePrivateFile(result.toString(), Serialization.json.encodeToString(AutomaticUpdateResult.serializer(), value))
    }
    fun remove() { fs.delete(policy, mustExist = false) }
    private inline fun <reified T> read(path: Path): T? {
        if (fs.metadataOrNull(path) == null) return null
        validatePrivateInstallationPath(path.toString(), directory = false)
        return Serialization.json.decodeFromString<T>(readReleaseFile(path, 8192).decodeToString(throwOnInvalidSequence = true))
    }
}

internal fun automaticUpdateSettings(paths: InstallationPaths, configuration: String, platform: ServicePlatform, home: String): AutomaticUpdateSettings {
    val canonical = InstallationPaths(FileSystem.SYSTEM.canonicalize(paths.root), paths.target)
    val id = randomHex(16)
    val label = "me.nathanfallet.teamcode.updates.$id"
    val registration = when (platform.os) {
        "macos" -> "$home/Library/LaunchAgents/$label.plist"
        "windows" -> (canonical.root / "$label.xml").toString()
        else -> "${configuration.toPath().parent!!.parent}/systemd/user/$label.timer"
    }
    return AutomaticUpdateSettings(id = id, configuration = configuration, executable = canonical.launcher.toString(),
        registration = registration, os = platform.os, userId = platform.userId)
}
