package me.nathanfallet.teamcode.cli.cloud.agent

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.api.models.SkillArtifactView
import me.nathanfallet.teamcode.api.models.EffectiveSkillView
import me.nathanfallet.teamcode.api.models.EffectiveHookView
import me.nathanfallet.teamcode.api.models.HookArtifactView
import me.nathanfallet.teamcode.api.models.HookDefinitionView
import me.nathanfallet.teamcode.cli.hooks.HookDigest
import me.nathanfallet.teamcode.cli.hooks.HookEvents
import me.nathanfallet.teamcode.cli.launch.shellQuote
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** The canonical skill digest of the catalog: `sha256( <path> \0 <mode> \0 <size> \0 <sha256 hex> \n ...)`, files sorted by path. */
internal object SkillDigest {
    class Entry(val path: String, val mode: String, val size: Int, val sha256: String)

    fun of(entries: List<Entry>): String {
        val text = entries.sortedBy { it.path }.joinToString("") { "${it.path}\u0000${it.mode}\u0000${it.size}\u0000${it.sha256}\n" }
        return "sha256:" + text.encodeUtf8().sha256().hex()
    }

    /** Validates a downloaded artifact against what the manifest promised: file hashes, sizes and the digest. Null when it is sound. */
    fun problem(manifest: EffectiveSkillView, artifact: SkillArtifactView): String? {
        if (artifact.name != manifest.name || artifact.version != manifest.version || artifact.digest != manifest.digest) return "mismatch"
        val entries = artifact.files.map { file ->
            val bytes = file.contentBase64.decodeBase64()?.toByteArray() ?: return "encoding"
            if (bytes.size != file.size || bytes.toByteString().sha256().hex() != file.sha256) return "content"
            if (!SkillPaths.validPath(file.path) || file.mode !in setOf("file", "executable")) return "path"
            Entry(file.path, file.mode, file.size, file.sha256)
        }
        return if (entries.isEmpty() || of(entries) != manifest.digest) "digest" else null
    }
}

internal object SkillPaths {
    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]*")
    val name = TeamCodeClient.SKILL_NAME
    fun validName(value: String) = value.length in 1..64 && name.matches(value)
    fun validPath(value: String): Boolean {
        val parts = value.split('/')
        return value.length <= 255 && parts.size <= 8 && parts.all { segment.matches(it) && !it.endsWith(".") }
    }
    /** Where an agent looks for personal skills, under the agent's own home. */
    fun root(agent: String, home: String, codexHome: String?): Path = when (agent) {
        "claude" -> "$home/.claude/skills".toPath()
        "codex" -> ((codexHome?.takeIf { it.isNotBlank() } ?: "$home/.codex") + "/skills").toPath()
        else -> throw IllegalArgumentException("Unknown agent.")
    }
}

/** One line of the protocol between the runner and the installer in the agent's container. */
@Serializable
internal data class SkillMessage(
    val t: String,
    val name: String? = null,
    val version: String? = null,
    val digest: String? = null,
    val path: String? = null,
    val mode: String? = null,
    val data: String? = null,
    val status: String? = null,
    val skills: List<Item> = emptyList(),
) {
    @Serializable data class Item(val name: String, val version: String, val digest: String)
    fun encode() = codec.encodeToString(serializer(), this)
    companion object {
        const val MANIFEST = "manifest"; const val NEED = "need"; const val READY = "ready"; const val BEGIN = "begin"; const val FILE = "file"
        const val COMMIT = "commit"; const val END = "end"; const val RESULT = "result"; const val DONE = "done"
        const val INSTALLED = "installed"; const val UNCHANGED = "unchanged"; const val CONFLICT = "conflict"; const val FAILED = "failed"
        /** Raw bytes of one file chunk; a chunk is a base64 JSON line well under the supervisor's line bound. */
        const val CHUNK_BYTES = 192 * 1024
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String?): SkillMessage? = text?.let { try { codec.decodeFromString(serializer(), it) } catch (_: Exception) { null } }
    }
}

/**
 * Installs the owner's pinned skills into one agent's personal skills directory, under the agent's own UID (the
 * runner's files are not reachable from the agent container, so this runs there). The runner sends the manifest;
 * the installer answers which skills it needs (missing, or a different digest); the runner streams those; the
 * installer verifies each against its digest, stages it next to the root and swaps it in.
 *
 * - A skill directory carries a `.teamcode-skill` marker. A directory of the same name without one is the member's
 *   own: it is left alone and reported as a conflict.
 * - A marked directory missing from the manifest (unassigned, unpinned, retired) is removed, so a transfer or a
 *   revoked decision leaves no copy of the former delivery behind.
 * - Paths are validated like the catalog's: relative, at most 8 segments, no dot segments or hidden names.
 */
internal class SkillInstaller(
    private val root: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val makeExecutable: suspend (List<Path>) -> Unit = { paths -> chmod(paths) },
    /** The file every delivered directory must contain; null for team hooks, whose runner checked the command file. */
    private val entryFile: String? = "SKILL.md",
    private val markerName: String = MARKER,
    /**
     * Reinstall every listed item instead of trusting a matching marker. Team hooks set it: their files sit in a
     * directory the agent can write, so each start puts back the verified version rather than reusing what is there.
     */
    private val alwaysReplace: Boolean = false,
) {
    private val staging = (root.parent ?: root).div(if (markerName == MARKER) ".teamcode-skills-staging" else ".teamcode-hooks-staging")
    private class Staged(val files: MutableMap<String, ByteArray> = LinkedHashMap(), val modes: MutableMap<String, String> = HashMap(), var current: Pair<String, MutableList<ByteArray>>? = null)
    private val staged = LinkedHashMap<String, Staged>()
    private var manifest: Map<String, SkillMessage.Item> = emptyMap()
    private var begun: String? = null
    private val results = mutableListOf<SkillMessage>()
    private var totalBytes = 0L

    /** Handles one message from the runner; returns the lines to answer. */
    suspend fun handle(message: SkillMessage): List<SkillMessage> = when (message.t) {
        SkillMessage.MANIFEST -> manifest(message.skills)
        SkillMessage.BEGIN -> { begin(message.name); emptyList() }
        SkillMessage.FILE -> { file(message); emptyList() }
        SkillMessage.COMMIT -> { commit(message); emptyList() }
        SkillMessage.END -> finish()
        else -> emptyList()
    }

    private fun marker(dir: Path) = dir.div(markerName)
    private fun markerDigest(dir: Path): String? = try {
        if (!fileSystem.exists(marker(dir))) null else fileSystem.read(marker(dir)) { readUtf8() }.lineSequence().firstOrNull()?.trim()
    } catch (_: Exception) { null }

    private fun manifest(skills: List<SkillMessage.Item>): List<SkillMessage> {
        fileSystem.deleteRecursively(staging, mustExist = false)
        manifest = skills.filter { SkillPaths.validName(it.name) }.associateBy { it.name }
        val needs = mutableListOf<SkillMessage>()
        for (skill in manifest.values) {
            val dest = root.div(skill.name)
            when {
                !fileSystem.exists(dest) -> needs += SkillMessage(SkillMessage.NEED, name = skill.name)
                !fileSystem.exists(marker(dest)) -> results += SkillMessage(SkillMessage.RESULT, name = skill.name, status = SkillMessage.CONFLICT)
                !alwaysReplace && markerDigest(dest) == skill.digest -> results += SkillMessage(SkillMessage.RESULT, name = skill.name, status = SkillMessage.UNCHANGED)
                else -> needs += SkillMessage(SkillMessage.NEED, name = skill.name)
            }
        }
        return needs + SkillMessage(SkillMessage.READY)
    }

    private fun begin(name: String?) {
        begun = name?.takeIf { it in manifest && it !in staged }
        if (begun != null) staged[begun!!] = Staged()
    }

    private fun file(message: SkillMessage) {
        val skill = staged[begun ?: return] ?: return
        val path = message.path?.takeIf(SkillPaths::validPath) ?: run { fail(); return }
        val chunk = message.data?.decodeBase64()?.toByteArray() ?: run { fail(); return }
        totalBytes += chunk.size
        if (totalBytes > MAX_TOTAL_BYTES || skill.files.size > MAX_FILES) { fail(); return }
        val current = skill.current
        val parts = if (current != null && current.first == path) current.second else mutableListOf<ByteArray>().also { skill.current = path to it; skill.modes[path] = message.mode ?: "file" }
        parts += chunk
        // A file ends with an empty `status` marker-free message: the runner sends `mode` on the first chunk and `status=last` on the final one.
        if (message.status == LAST) { skill.files[path] = parts.fold(ByteArray(0)) { a, b -> a + b }; skill.current = null }
    }

    private fun fail() { begun?.let { staged.remove(it); failed += it }; begun = null }
    private val failed = mutableSetOf<String>()

    private suspend fun commit(message: SkillMessage) {
        val name = begun ?: return
        begun = null
        val skill = staged[name] ?: return
        val expected = manifest[name] ?: return
        val entries = skill.files.map { (path, bytes) -> SkillDigest.Entry(path, skill.modes[path] ?: "file", bytes.size, bytes.toByteString().sha256().hex()) }
        if (entries.isEmpty() || SkillDigest.of(entries) != expected.digest || message.digest != expected.digest || (entryFile != null && entryFile !in skill.files)) { staged.remove(name); failed += name; return }
        val dir = staging.div(name)
        try {
            fileSystem.createDirectories(dir)
            for ((path, bytes) in skill.files) {
                val target = dir.div(path)
                target.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(target) { write(bytes) }
            }
            fileSystem.write(marker(dir)) { writeUtf8(expected.digest + "\n" + expected.version + "\n") }
            val executable = skill.modes.filterValues { it == "executable" }.keys.map { dir.div(it) }
            if (executable.isNotEmpty()) makeExecutable(executable)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { staged.remove(name); failed += name }
        skill.files.clear()
    }

    private fun finish(): List<SkillMessage> {
        // Remove what we delivered before and is no longer delivered.
        if (fileSystem.exists(root)) for (child in fileSystem.list(root)) {
            val unlisted = child.name !in manifest
            if (unlisted && !child.name.startsWith(".") && fileSystem.metadataOrNull(child)?.isDirectory == true && fileSystem.exists(marker(child))) {
                fileSystem.deleteRecursively(child, mustExist = false)
                results += SkillMessage(SkillMessage.RESULT, name = child.name, status = REMOVED)
            }
        }
        for (name in staged.keys.toList()) {
            val dir = staging.div(name)
            val dest = root.div(name)
            val status = try {
                if (!fileSystem.exists(dir)) SkillMessage.FAILED
                else if (fileSystem.exists(dest) && !fileSystem.exists(marker(dest))) SkillMessage.CONFLICT
                else {
                    fileSystem.createDirectories(root)
                    fileSystem.deleteRecursively(dest, mustExist = false)
                    fileSystem.atomicMove(dir, dest)
                    SkillMessage.INSTALLED
                }
            } catch (_: Exception) { SkillMessage.FAILED }
            results += SkillMessage(SkillMessage.RESULT, name = name, status = status)
        }
        for (name in failed) results += SkillMessage(SkillMessage.RESULT, name = name, status = SkillMessage.FAILED)
        fileSystem.deleteRecursively(staging, mustExist = false)
        return results.toList() + SkillMessage(SkillMessage.DONE)
    }

    companion object {
        const val MARKER = ".teamcode-skill"
        const val HOOK_MARKER = ".teamcode-hook"
        const val LAST = "last"
        const val REMOVED = "removed"
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_FILES = 200
        private suspend fun chmod(paths: List<Path>) {
            val child = startAgentProcess("chmod", listOf("755") + paths.map { it.toString() }, emptyMap(), "/")
            child.stop(10_000)
        }
    }
}

/** `teamcode cloud install-skills`: the installer's process in the agent container; speaks [SkillMessage] lines on standard input and output. */
internal class CloudInstallSkills(private val hooks: Boolean = false) : SuspendingCliktCommand(name = if (hooks) "install-hooks" else "install-skills") {
    override val hiddenFromHelp = true
    private val agent by option("--agent").choice("claude", "codex").required()
    override fun help(context: Context) = "Install the owner's delivered ${if (hooks) "team hooks" else "skills"} for the session runner (started by it, not by hand)."
    override suspend fun run() {
        val home = requireNotNull(environment("HOME")?.takeIf { it.isNotBlank() }) { "Set HOME." }
        val installer = if (hooks) SkillInstaller("$home/$CLOUD_HOOKS_DIR".toPath(), entryFile = null, markerName = SkillInstaller.HOOK_MARKER, alwaysReplace = true)
            else SkillInstaller(SkillPaths.root(agent, home, environment("CODEX_HOME")))
        while (true) {
            val line = readlnOrNull() ?: break
            val message = SkillMessage.decode(line) ?: continue
            for (reply in installer.handle(message)) println(reply.encode())
            if (message.t == SkillMessage.END) return
        }
        // The runner went away before the end: nothing is swapped in; staging is removed by the next run.
        SystemExit.exit(1)
    }
}

/**
 * The runner's half: fetches the owner's manifest and artifacts with the execution's credential, verifies them, and
 * streams what the installer asks for through the agent container. Bounded and fail-soft: a failure leaves the
 * previous installation in place and never keeps the agent from starting.
 */
internal class SkillSync(
    private val manifest: suspend () -> List<EffectiveSkillView>,
    private val artifact: suspend (EffectiveSkillView) -> SkillArtifactView,
    private val start: suspend (List<String>) -> AgentProcess,
    private val log: (String) -> Unit = {},
    /** The installer subcommand and the word used in the pod log; team hooks reuse this protocol. */
    private val installer: String = "install-skills",
    private val label: String = "Skills",
) {
    /** Installs what the manifest lists and returns each name's result (`installed`, `unchanged`, `conflict`, `failed`, `removed`). */
    suspend fun sync(agent: String): Map<String, String> {
        val statuses = LinkedHashMap<String, String>()
        val skills = try { manifest().sortedBy { it.name } } catch (error: CancellationException) { throw error } catch (_: Exception) { log("The delivered ${label.lowercase()} could not be listed; keeping the installed ones."); return statuses }
        var budget = MAX_BYTES
        val chosen = skills.filter { it.fileCount <= MAX_FILES && it.sizeBytes <= budget && SkillPaths.validName(it.name) }.take(MAX_SKILLS).filter { budget -= it.sizeBytes; budget >= 0 }
        if (chosen.size < skills.size) log("Some delivered ${label.lowercase()} were not installed (size or count limits).")
        val child = try { start(listOf("cloud", installer, "--agent", agent)) } catch (error: CancellationException) { throw error } catch (_: Exception) { log("The ${label.lowercase()} installer could not start."); return statuses }
        try {
            if (!child.writeLine(SkillMessage(SkillMessage.MANIFEST, skills = chosen.map { SkillMessage.Item(it.name, it.version, it.digest) }).encode())) return statuses
            val needed = mutableListOf<String>()
            while (true) {
                val reply = SkillMessage.decode(child.readLine(MAX_LINE)) ?: return statuses
                if (reply.t == SkillMessage.READY) break
                if (reply.t == SkillMessage.NEED) reply.name?.let(needed::add)
            }
            for (name in needed) {
                val entry = chosen.find { it.name == name } ?: continue
                val files = try { artifact(entry) } catch (error: CancellationException) { throw error } catch (_: Exception) { log(if (label == "Skills") "The skill $name could not be downloaded." else "An item could not be downloaded."); continue }
                val problem = SkillDigest.problem(entry, files)
                if (problem != null) { log(if (label == "Skills") "The skill $name was refused ($problem)." else "An item was refused ($problem)."); continue }
                child.writeLine(SkillMessage(SkillMessage.BEGIN, name = name).encode())
                for (file in files.files) {
                    val bytes = checkNotNull(file.contentBase64.decodeBase64()).toByteArray()
                    var offset = 0
                    do {
                        val end = minOf(bytes.size, offset + SkillMessage.CHUNK_BYTES)
                        val last = end >= bytes.size
                        child.writeLine(SkillMessage(SkillMessage.FILE, path = file.path, mode = file.mode, data = bytes.copyOfRange(offset, end).toByteString().base64(), status = if (last) SkillInstaller.LAST else null).encode())
                        offset = end
                    } while (!last)
                }
                child.writeLine(SkillMessage(SkillMessage.COMMIT, name = name, digest = entry.digest).encode())
            }
            child.writeLine(SkillMessage(SkillMessage.END).encode())
            val counts = HashMap<String, Int>()
            while (true) {
                val reply = SkillMessage.decode(child.readLine(MAX_LINE)) ?: break
                if (reply.t == SkillMessage.DONE) break
                if (reply.t == SkillMessage.RESULT) reply.status?.let { status ->
                    counts[status] = (counts[status] ?: 0) + 1
                    reply.name?.let { statuses[it] = status }
                }
            }
            // Counts only: names and contents are not logged.
            log("$label: " + (counts.entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none delivered" }) + ".")
        } finally { child.stop(2_000) }
        return statuses
    }

    companion object {
        const val MAX_SKILLS = 100
        const val MAX_FILES = 200
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_LINE = 1024 * 1024
    }
}

/** What the runner prepares for the agent before each start, on top of inference: the owner's team MCP servers, skills and hooks. */
internal interface AgentTools {
    /** Installs the owner's skills for [agent] and returns the names of the MCP servers to connect. Never throws except on cancellation. */
    suspend fun prepare(agent: String): List<String>

    /** Installs the owner's team hooks for [agent] and returns those installed. Never throws except on cancellation. */
    suspend fun hooks(agent: String): List<CloudHook> = emptyList()

    /** The artifact tools to start beside the agent when the owner may publish now, or null. Never throws except on cancellation. */
    suspend fun artifacts(): me.nathanfallet.teamcode.cli.launch.ArtifactsMcp? = null
}

/** Where cloud sessions install team hooks, relative to the agent's home. */
internal const val CLOUD_HOOKS_DIR = ".teamcode/hooks"

/** The artifact tools' scratch directory in the agent's container, private to the agent's UID. */
internal const val CLOUD_ARTIFACTS_SCRATCH = "/tmp/teamcode-artifacts"

/** A team hook installed in the agent's home, under [CLOUD_HOOKS_DIR]`/<name>/`. */
internal data class CloudHook(val name: String, val definition: HookDefinitionView)

/**
 * Claude Code settings for the installed team hooks, passed with `--settings`: each hook runs its executable from the
 * agent's home through the shell (`"$HOME"/'<dir>/<name>/<command>'`), merged by Claude Code with the agent's own hooks.
 */
internal fun cloudHookSettings(hooks: List<CloudHook>): String? {
    val events = hooks.flatMap { HookEvents.forAgent("claude", it.definition) }.distinct().sorted()
    if (events.isEmpty()) return null
    return buildJsonObject { putJsonObject("hooks") {
        for (event in events) putJsonArray(event) {
            for (hook in hooks) if (event in hook.definition.events) add(buildJsonObject {
                hook.definition.matcher?.let { put("matcher", it) }
                putJsonArray("hooks") { add(buildJsonObject {
                    put("type", "command")
                    put("command", "\"\$HOME\"/" + shellQuote("$CLOUD_HOOKS_DIR/${hook.name}/${hook.definition.command}"))
                    put("timeout", hook.definition.timeoutSeconds)
                }) }
            })
        }
    } }.toString()
}

/**
 * The runner's half for team hooks: downloads every delivered hook Claude Code can run, verifies its full digest
 * (definition and files), then installs the files through the skill installer protocol (`cloud install-hooks`),
 * identified by the digest of their file tree. Codex sessions get none: Codex runs only hooks a person trusted.
 */
internal class CloudHookSync(
    private val manifest: suspend () -> List<EffectiveHookView>,
    private val artifact: suspend (EffectiveHookView) -> HookArtifactView,
    private val start: suspend (List<String>) -> AgentProcess,
    private val log: (String) -> Unit = {},
) {
    suspend fun sync(agent: String): List<CloudHook> {
        if (agent != "claude") return emptyList()
        val delivered = try { manifest() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            log("Team hooks could not be listed; the agent starts without them."); return emptyList()
        }
        val runnable = delivered.filter { SkillPaths.validName(it.name) && HookDigest.validDefinition(it.definition) && HookEvents.forAgent(agent, it.definition).isNotEmpty() }
            .sortedBy { it.name }.take(MAX_HOOKS)
        val prepared = runnable.mapNotNull { hook ->
            val files = try { artifact(hook) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            if (files == null || HookDigest.problem(hook, files) != null) return@mapNotNull null
            val tree = SkillDigest.of(files.files.map { SkillDigest.Entry(it.path, it.mode, it.size, it.sha256) })
            Triple(hook, EffectiveSkillView(hook.name, hook.version, tree, files.files.sumOf { it.size.toLong() }, files.files.size),
                SkillArtifactView(hook.name, hook.version, tree, files.files))
        }
        if (prepared.size < runnable.size) log("Some team hooks were refused or could not be downloaded.")
        // An empty manifest still runs the installer, so hooks no longer delivered are removed.
        val statuses = SkillSync({ prepared.map { it.second } }, { entry -> prepared.first { it.first.name == entry.name }.third }, start, log,
            installer = "install-hooks", label = "Team hooks").sync(agent)
        return prepared.filter { statuses[it.first.name] in setOf(SkillMessage.INSTALLED, SkillMessage.UNCHANGED) }
            .map { CloudHook(it.first.name, it.first.definition) }
    }

    companion object { const val MAX_HOOKS = 50 }
}

/** The agent container's HOME below the workspace, as the session pod sets it (`KubernetesClusterPort.AGENT_HOME`). */
internal const val CLOUD_AGENT_HOME_NAME = ".agent-home"

internal class RunnerAgentTools(
    private val client: TeamCodeClient,
    private val start: suspend (List<String>) -> AgentProcess,
    private val log: (String) -> Unit = {},
    private val timeoutMillis: Long = 120_000,
    /** This binary in the agent's container and the session's working directory, for `teamcode mcp artifacts`. */
    private val binary: String? = null,
    private val workspace: String = "/workspace",
    /** The agent's home, with its sign-ins: never published, wherever it is. */
    private val home: String? = null,
) : AgentTools {
    /**
     * `teamcode mcp artifacts` in the agent's container, under the agent's UID, rooted at the workspace, with its
     * scratch directory in the container's own `/tmp` and the agent's home (`HOME`, by default `<workspace>/.home`) never published.
     * Only when the execution's owner may publish now; the agent reaches the runner's relay with the local token.
     */
    override suspend fun artifacts(): me.nathanfallet.teamcode.cli.launch.ArtifactsMcp? {
        val command = binary ?: return null
        val allowed = try { withTimeout(20_000) { client.artifactPolicy() }.canPublish }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { false }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
        if (!allowed) return null
        val root = workspace.trimEnd('/')
        // The runner's home and the agent's own (its container sets HOME to `<workspace>/.agent-home`), wherever HOME points.
        val homes = listOfNotNull("$root/.home", "$root/$CLOUD_AGENT_HOME_NAME", home?.trimEnd('/')?.takeIf { it.isNotEmpty() }).distinct()
        return me.nathanfallet.teamcode.cli.launch.ArtifactsMcp(command, root, CLOUD_ARTIFACTS_SCRATCH, homes)
    }

    override suspend fun prepare(agent: String): List<String> {
        val servers = try { withTimeout(20_000) { client.mcpServers() }.map { it.name }.filter { TeamCodeClient.MCP_SERVER_NAME.matches(it) }.distinct() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { log("Team MCP servers could not be listed; the agent starts without them."); emptyList() }
        try {
            withTimeout(timeoutMillis) {
                SkillSync({ client.skillManifest().skills }, { client.skillArtifact(it.name, it.version) }, start, log).sync(agent)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) { log("Installing the skills took too long; the agent starts with what is installed.") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { log("The skills could not be installed; the agent starts with what is installed.") }
        return servers
    }

    override suspend fun hooks(agent: String): List<CloudHook> = try {
        withTimeout(timeoutMillis) {
            CloudHookSync({ client.hookManifest().hooks }, { client.hookArtifact(it.name, it.version) }, start, log).sync(agent)
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) { log("Installing the team hooks took too long; the agent starts without them."); emptyList() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { log("The team hooks could not be installed; the agent starts without them."); emptyList() }
}
