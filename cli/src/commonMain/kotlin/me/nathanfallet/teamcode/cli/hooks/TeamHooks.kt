package me.nathanfallet.teamcode.cli.hooks

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.EffectiveHookView
import me.nathanfallet.teamcode.api.models.HookArtifactView
import me.nathanfallet.teamcode.api.models.HookDefinitionView
import me.nathanfallet.teamcode.cli.cloud.agent.SkillDigest
import me.nathanfallet.teamcode.cli.cloud.agent.SkillPaths
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/** The events of the hook catalog, and those Codex supports (it has no `Notification` hook). */
internal object HookEvents {
    val all = listOf("PreToolUse", "PostToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "SessionStart", "SessionEnd", "PreCompact", "Notification")
    val codex = all - "Notification"
    /** The events [definition] fires on for [agent]; empty when the agent supports none of them. */
    fun forAgent(agent: String, definition: HookDefinitionView): List<String> =
        definition.events.filter { it in (if (agent == "codex") codex else all) }.distinct().sorted()
}

/** The catalog's canonical hook digest: a definition line, then the skill catalog's file lines. */
internal object HookDigest {
    fun of(definition: HookDefinitionView, entries: List<SkillDigest.Entry>): String {
        val head = "hook\u0000${definition.events.sorted().joinToString(",")}\u0000${definition.matcher.orEmpty()}\u0000" +
            "${definition.command}\u0000${definition.timeoutSeconds}\n"
        val files = entries.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.mode}\u0000${it.size}\u0000${it.sha256}\n" }
        return "sha256:" + (head + files).encodeUtf8().sha256().hex()
    }

    /** Whether a definition is one this client can install: known events, a safe command path, bounded values. */
    fun validDefinition(definition: HookDefinitionView) = definition.events.isNotEmpty() && definition.events.all { it in HookEvents.all } &&
        definition.events.toSet().size == definition.events.size && SkillPaths.validPath(definition.command) &&
        definition.timeoutSeconds in 1..600 && definition.matcher.let { it == null || (it.length in 1..256 && it.none(Char::isISOControl)) }

    /** Validates a download against the manifest: definition, file hashes and sizes, and the digest. Null when sound. */
    fun problem(manifest: EffectiveHookView, artifact: HookArtifactView): String? {
        if (artifact.name != manifest.name || artifact.version != manifest.version || artifact.digest != manifest.digest || artifact.definition != manifest.definition) return "mismatch"
        if (!validDefinition(artifact.definition) || artifact.files.isEmpty() || artifact.files.size > MAX_FILES) return "definition"
        var total = 0L
        val entries = artifact.files.map { file ->
            val bytes = file.contentBase64.decodeBase64()?.toByteArray() ?: return "content"
            total += bytes.size
            if (total > MAX_BYTES || bytes.size != file.size || bytes.toByteString().sha256().hex() != file.sha256) return "content"
            if (!SkillPaths.validPath(file.path) || file.mode !in setOf("file", "executable")) return "path"
            SkillDigest.Entry(file.path, file.mode, file.size, file.sha256)
        }
        if (entries.map { it.path.lowercase() }.toSet().size != entries.size) return "path"
        if (entries.none { it.path == artifact.definition.command && it.mode == "executable" }) return "definition"
        return if (of(artifact.definition, entries) != manifest.digest) "digest" else null
    }

    const val MAX_FILES = 50
    const val MAX_BYTES = 1024L * 1024
}

/** A team hook ready to run: [command] is the absolute path of its verified, installed executable. */
internal data class InstalledHook(val name: String, val version: String, val digest: String, val definition: HookDefinitionView, val command: String)

/** The member's own decision for one hook version, kept on this computer only. */
@Serializable
internal data class HookApproval(val digest: String, val approved: Boolean)

/**
 * Local approvals, keyed by hook name. A decision applies to one digest: a new version, or any change to its files or
 * definition, needs a new approval. Stored in the private configuration directory.
 */
internal class HookApprovals(private val file: Path, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): Map<String, HookApproval> = try {
        if (!fileSystem.exists(file)) emptyMap() else json.decodeFromString<Map<String, HookApproval>>(fileSystem.read(file) { readUtf8() })
    } catch (_: Exception) { emptyMap() }
    /**
     * Creates the approvals file, and so its directory, through a private write when it does not exist yet. Called
     * before anything is installed under that directory: on Windows a private write refuses an existing directory the
     * user does not own, and a directory created by another API under an elevated token belongs to Administrators.
     */
    fun prepare() {
        if (!fileSystem.exists(file)) writePrivateFile(file.toString(), json.encodeToString(emptyMap<String, HookApproval>()))
    }
    fun record(name: String, approval: HookApproval?) {
        val next = load().toMutableMap()
        if (approval == null) next.remove(name) else next[name] = approval
        writePrivateFile(file.toString(), json.encodeToString(next))
    }
}

/**
 * Installs verified hook versions under `<root>/<name>/<digest hex>/`, one directory per version, never modified once
 * written: a running agent keeps the version it started with, and a reinstallation of the same digest reuses it.
 * The tree lives in the private configuration directory; executables are made owner-only.
 */
internal class LocalHookInstaller(
    private val root: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val makeExecutable: suspend (List<Path>) -> Unit,
) {
    fun directory(name: String, digest: String): Path = root / name / digest.removePrefix("sha256:")

    /**
     * The installed executable of this version, when a complete and intact installation exists. The marker lists every
     * file's path, mode, size and hash; that list must give back the approved digest with [definition], and each file
     * on disk must match its line. A file changed after installation (by the agent itself, for example) is not reused:
     * the version is downloaded and verified again.
     */
    fun installed(name: String, digest: String, definition: HookDefinitionView): Path? {
        val dir = directory(name, digest)
        val marker = dir / MARKER
        return try {
            val lines = fileSystem.read(marker) { readUtf8() }.lines().filter { it.isNotEmpty() }
            if (lines.firstOrNull() != digest) return null
            val entries = lines.drop(2).map { line ->
                val (path, mode, size, sha) = line.split('\u0000').takeIf { it.size == 4 } ?: return null
                SkillDigest.Entry(path, mode, size.toIntOrNull() ?: return null, sha)
            }
            if (entries.isEmpty() || HookDigest.of(definition, entries) != digest || entries.none { it.path == definition.command }) return null
            for (entry in entries) {
                if (!SkillPaths.validPath(entry.path)) return null
                val bytes = fileSystem.read(dir / entry.path) { readByteString() }
                if (bytes.size != entry.size || bytes.sha256().hex() != entry.sha256) return null
            }
            dir / definition.command
        } catch (_: Exception) { null }
    }

    /** Writes an artifact that [HookDigest.problem] accepted, and returns its executable. */
    suspend fun install(artifact: HookArtifactView): Path {
        installed(artifact.name, artifact.digest, artifact.definition)?.let { return it }
        val dir = directory(artifact.name, artifact.digest)
        // A staging directory of this process only: two launches installing the same version never share one.
        val staging = root / ".staging" / (artifact.name + "-" + artifact.digest.removePrefix("sha256:").take(16) + "-" + Random.nextLong().toULong().toString(16))
        try {
            // The first private write creates the staging directory owner-only before any hook file lands in it.
            val lines = artifact.files.joinToString("") { "${it.path}\u0000${it.mode}\u0000${it.size}\u0000${it.sha256}\n" }
            writePrivateFile((staging / MARKER).toString(), artifact.digest + "\n" + artifact.version + "\n" + lines)
            for (file in artifact.files) {
                val target = staging / file.path
                target.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(target) { write(checkNotNull(file.contentBase64.decodeBase64())) }
            }
            val executables = artifact.files.filter { it.mode == "executable" }.map { staging / it.path }
            if (executables.isNotEmpty()) makeExecutable(executables)
            dir.parent?.let { fileSystem.createDirectories(it) }
            if (installed(artifact.name, artifact.digest, artifact.definition) != null) {
                // Another launch installed it meanwhile: keep that directory, which an agent may already be running from.
                fileSystem.deleteRecursively(staging, mustExist = false)
            } else {
                // Only an incomplete or altered directory is replaced.
                fileSystem.deleteRecursively(dir, mustExist = false)
                fileSystem.atomicMove(staging, dir)
            }
        } catch (error: Throwable) {
            fileSystem.deleteRecursively(staging, mustExist = false)
            throw error
        }
        return checkNotNull(installed(artifact.name, artifact.digest, artifact.definition)) { "The hook was not installed." }
    }

    companion object { const val MARKER = ".teamcode-hook" }
}

/** What `teamcode run` found for this launch. Names are shown to the member, who owns the decision; nothing is sent back. */
internal class TeamHookPlan(val installed: List<InstalledHook>, val awaiting: List<EffectiveHookView>, val refused: Int, val failed: List<String>)

/**
 * Resolves the member's team hooks for a launch: the delivered versions the member approved on this computer are
 * verified and installed (downloaded only when missing); the others are listed as awaiting approval or refused.
 * Fail-soft: an unreadable manifest or a failed download leaves that hook out and never blocks the launch.
 */
internal class TeamHookSync(
    private val manifest: suspend () -> List<EffectiveHookView>,
    private val artifact: suspend (EffectiveHookView) -> HookArtifactView,
    private val approvals: HookApprovals,
    private val installer: LocalHookInstaller,
) {
    suspend fun plan(): TeamHookPlan {
        val delivered = manifest().filter { SkillPaths.validName(it.name) && HookDigest.validDefinition(it.definition) }.sortedBy { it.name }
        val decisions = approvals.load()
        val installed = mutableListOf<InstalledHook>()
        val awaiting = mutableListOf<EffectiveHookView>()
        val failed = mutableListOf<String>()
        var refused = 0
        for (hook in delivered) {
            val decision = decisions[hook.name]?.takeIf { it.digest == hook.digest }
            when {
                decision == null -> awaiting += hook
                !decision.approved -> refused++
                else -> try {
                    installed += InstalledHook(hook.name, hook.version, hook.digest, hook.definition, ensure(hook).toString())
                } catch (error: CancellationException) { throw error } catch (_: Exception) { failed += hook.name }
            }
        }
        return TeamHookPlan(installed, awaiting, refused, failed)
    }

    /** The installed executable of [hook], downloading and verifying it when it is not on this computer yet. */
    suspend fun ensure(hook: EffectiveHookView): Path {
        installer.installed(hook.name, hook.digest, hook.definition)?.let { return it }
        val files = artifact(hook)
        HookDigest.problem(hook, files)?.let { throw IllegalStateException("The hook ${hook.name} was refused ($it).") }
        // `hooks approve` installs before it records the approval: the hooks directory must already be private then.
        approvals.prepare()
        return installer.install(files)
    }
}
