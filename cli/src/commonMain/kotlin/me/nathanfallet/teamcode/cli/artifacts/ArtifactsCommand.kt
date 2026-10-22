package me.nathanfallet.teamcode.cli.artifacts

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.platform.hostOs
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.ArtifactConflictException
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath

class Artifacts : SuspendingNoOpCliktCommand(name = "artifacts") {
    override fun help(context: Context) = "Publish, read and share artifacts: pages (HTML or Markdown, with their files) people read behind the TeamCode login."
    init { subcommands(ArtifactList(), ArtifactShow(), ArtifactPublish(), ArtifactPull(), ArtifactAccess(), ArtifactShare(), ArtifactDelete()) }
}

private val artifactId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** An artifact id, or its URL (`<server>/artifact/<id>`). */
internal fun artifactReference(value: String): String {
    val trimmed = value.trim()
    if (artifactId.matches(trimmed)) return trimmed
    return Regex("/artifact/(${artifactId.pattern})(?:[/?#]|$)").find(trimmed)?.groupValues?.get(1)
        ?: throw CliUserException("$value is not an artifact id or URL. teamcode artifacts list shows them.")
}

private fun size(bytes: Long) = when {
    bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}

private fun summary(artifact: ArtifactView) = "${artifact.id}  v${artifact.currentVersion}  ${artifact.access}  ${artifact.title}  (${artifact.ownerName}, ${artifact.relation})"

private class ArtifactList : SuspendingCliktCommand(name = "list") {
    private val scope by option(help = "mine, shared, team (everything you can read, the default) or all (administrators).").choice("mine", "shared", "team", "all").default("team")
    private val member by option("--member", help = "Only artifacts owned by this member id.")
    private val session by option("--session", help = "Only artifacts published from this cloud session.")
    private val run by option("--run", help = "Only artifacts published from this agent run.")
    private val limit by option("--limit", help = "At most this many (1–50).").int().default(50)
    private val json by option("--json", help = "Print the JSON answer.").flag()
    override fun help(context: Context) = "List the artifacts you can read, most recently changed first."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val page = client.artifacts(scope, member, session, run, limit)
            if (json) return echo(Serialization.json.encodeToString(ArtifactListView.serializer(), page))
            if (page.artifacts.isEmpty()) echo("No artifact.")
            page.artifacts.forEach { echo(summary(it)) }
            if (page.nextBefore != null) echo("More artifacts exist; narrow the list with --scope, --member, --session or --run.", err = true)
        }
    }
}

private class ArtifactShow : SuspendingCliktCommand(name = "show") {
    private val artifact by argument(help = "The artifact id or URL.")
    private val versions by option("--versions", help = "Also list the kept versions.").flag()
    private val json by option("--json", help = "Print the JSON answers.").flag()
    override fun help(context: Context) = "Show an artifact: its details, the files of its current version and who it is shared with."
    override suspend fun run() {
        val id = artifactReference(artifact)
        sdk(loadConfig()).use { client ->
            val view = client.artifact(id)
            if (view.currentVersion < 1) throw CliUserException("${view.title.ifEmpty { id }} has no published version yet: its publication did not finish.")
            val current = client.artifactVersion(id, view.currentVersion)
            val members = client.artifactMembers(id)
            val history = if (versions) client.artifactVersions(id) else null
            if (json) {
                echo(Serialization.json.encodeToString(ArtifactView.serializer(), view)); echo(Serialization.json.encodeToString(ArtifactVersionView.serializer(), current))
                echo(Serialization.json.encodeToString(ArtifactMembersView.serializer(), members))
                history?.forEach { echo(Serialization.json.encodeToString(ArtifactVersionView.serializer(), it)) }
                return
            }
            echo("${view.title}\n${view.url}")
            if (view.description.isNotEmpty()) echo(view.description)
            echo("Owner ${view.ownerName} (you are ${view.relation}); access ${view.access}" + (if (view.access == "public") (view.publicVersion?.let { ", public link pinned to v$it" } ?: ", public link follows the latest version") else ""))
            echo("Version ${view.currentVersion}, ${current.fileCount} file(s), ${size(current.totalBytes)}; stored ${size(view.storedBytes)}")
            current.files.forEach { echo("  ${it.path}  ${size(it.size)}  ${it.contentType}${if (it.role == "page") "  (page)" else ""}") }
            if (members.members.isEmpty()) echo("Not shared with any member.")
            members.members.forEach { echo("Shared with ${it.username} as ${it.role}${if (it.reason == "session_transfer") " (session transfer)" else ""}") }
            history?.forEach { echo("v${it.number}  ${it.fileCount} file(s)  ${size(it.totalBytes)}  from v${it.baseVersion}") }
        }
    }
}

private class ArtifactPublish : SuspendingCliktCommand(name = "publish") {
    private val page by argument(help = "The page to publish: an .html, .htm or .md file, published under its file name.")
    private val files by option("--file", help = "Another file: PATH (published at its path relative to the page's directory, so the page's relative links keep working) or PUBLISHED=PATH. Repeat as needed.").multiple()
    private val removed by option("--remove", help = "A published path of the base version to drop. Repeat as needed.").multiple()
    private val artifact by option("--artifact", help = "Publish a new version of this artifact (id or URL) instead of a new artifact.")
    private val baseVersion by option("--base-version", help = "The version your changes start from; refused if another version was published since. Default: the live version.").int()
    private val title by option("--title", help = "The title; default for a new artifact the page's <title> or first # heading, and for a new version the current title.")
    private val description by option("--description", help = "One sentence for the gallery.")
    private val icon by option("--icon", help = "One short lowercase word, such as chart or report.")
    private val access by option("--access", help = "personal (only you and invited members, the default for a new artifact) or team.").choice("personal", "team")
    private val json by option("--json", help = "Print the JSON answer.").flag()
    override fun help(context: Context) = "Publish a page and its files as a new artifact, or as a new version of one. Files are read from the current directory, " +
        "without following symbolic links; credentials and key files are refused."
    override suspend fun run() {
        // The configuration directory holds the stored login: never published, as for `teamcode mcp artifacts`.
        val sources = ArtifactSources(listOf("."), listOfNotNull(me.nathanfallet.teamcode.cli.config.configPath().toPath().parent?.toString()))
        val entry = sources.read(page, page.replace('\\', '/').substringAfterLast('/'))
        val others = files.map { spec ->
            val published = if ('=' in spec) spec.substringBefore('=').replace('\\', '/').removePrefix("./") else publishedBesidePage(page, spec)
            sources.read(spec.substringAfter('=', spec), published)
        }
        sdk(loadConfig()).use { client ->
            val result = try {
                ArtifactPublisher(client).publish(ArtifactPublication(entry, others, removed.toSet(), artifact?.let(::artifactReference), baseVersion, title, description, icon, access))
            } catch (conflict: ArtifactConflictException) {
                throw CliUserException("The artifact changed: version ${conflict.current?.number ?: 0} is live. Review it (teamcode artifacts pull), then publish again with --base-version ${conflict.current?.number ?: 0}.")
            }
            if (json) return echo(Serialization.json.encodeToString(ArtifactPublishedView.serializer(), result.published))
            val view = result.published.artifact
            echo("${if (result.published.replayed) "Already published" else "Published"} version ${result.published.version.number} of ${view.title}: ${view.url}")
            echo("${result.filesUploaded} file(s) sent, ${result.filesReused} reused; access ${view.access}.")
        }
    }
}

private class ArtifactPull : SuspendingCliktCommand(name = "pull") {
    private val artifact by argument(help = "The artifact id or URL.")
    private val version by option("--version", help = "The version to download; default the current one.").int()
    private val out by option("--out", help = "An empty or new directory to write the files into.").required()
    override fun help(context: Context) = "Download the files of an artifact version, each checked against its SHA-256."
    override suspend fun run() {
        val id = artifactReference(artifact)
        val fs = FileSystem.SYSTEM
        val target = out.toPath()
        if (fs.metadataOrNull(target)?.let { !it.isDirectory || fs.list(target).isNotEmpty() } == true) throw CliUserException("$out exists and is not an empty directory.")
        sdk(loadConfig()).use { client ->
            val number = version ?: client.artifact(id).currentVersion
            if (number < 1) throw CliUserException("This artifact has no published version yet: its publication did not finish.")
            val manifest = client.artifactVersion(id, number)
            manifest.files.forEach { ArtifactLimits.requirePublishedPath(it.path) }
            fs.createDirectories(target)
            for (file in manifest.files) {
                val bytes = client.artifactFile(id, number, file.path, file.size)
                if (bytes.size.toLong() != file.size || bytes.toByteString().sha256().hex() != file.sha256) throw CliUserException("${file.path} does not match its SHA-256. Pull again.")
                val destination = file.path.split('/').fold(target) { parent, segment -> parent / segment }
                destination.parent?.let { fs.createDirectories(it) }
                fs.write(destination, mustCreate = true) { write(bytes) }
            }
            echo("Wrote version $number (${manifest.files.size} file(s)) to $out; the page is ${manifest.entry}.")
        }
    }
}

private class ArtifactAccess : SuspendingCliktCommand(name = "access") {
    private val artifact by argument(help = "The artifact id or URL.")
    private val level by argument(help = "personal, team or public.").choice("personal", "team", "public")
    private val pinVersion by option("--pin-version", help = "For public: serve this version on the public link instead of the latest.").int()
    override fun help(context: Context) = "Change who can see an artifact: personal (you and invited members), team, or public (anyone with the link, if the team allows it). " +
        "The link stays the same. Needs your interactive sign-in."
    override suspend fun run() {
        if (pinVersion != null && level != "public") throw CliUserException("--pin-version only applies to public access.")
        sdk(loadConfig()).use { client ->
            val view = client.setArtifactAccess(artifactReference(artifact), SetArtifactAccessRequest(level, pinVersion == null, pinVersion))
            echo("${view.title} is now ${view.access}: ${view.url}")
        }
    }
}

private class ArtifactShare : SuspendingCliktCommand(name = "share") {
    private val artifact by argument(help = "The artifact id or URL.")
    private val member by argument(help = "The member: id, username or linked GitHub login.")
    private val role by option("--role", help = "viewer (read) or editor (also publish versions and invite).").choice("viewer", "editor").default("viewer")
    private val remove by option("--remove", help = "Remove the member instead.").flag()
    override fun help(context: Context) = "Invite a team member to an artifact, or remove one. The artifact's access level does not change."
    override suspend fun run() {
        val id = artifactReference(artifact)
        sdk(loadConfig()).use { client ->
            val members = if (remove) client.unshareArtifact(id, member) else client.shareArtifact(id, ShareArtifactRequest(member, role))
            echo(if (remove) "Removed $member." else "Shared with $member as $role.")
            echo("Invited members: " + members.members.joinToString(", ") { "${it.username} (${it.role})" }.ifEmpty { "none" })
        }
    }
}

private class ArtifactDelete : SuspendingCliktCommand(name = "delete") {
    private val artifact by argument(help = "The artifact id or URL.")
    private val yes by option("--yes", help = "Confirm: deletion is irreversible.").flag()
    override fun help(context: Context) = "Delete an artifact and every version of it. Irreversible; needs --yes and your interactive sign-in."
    override suspend fun run() {
        if (!yes) throw CliUserException("Deleting an artifact cannot be undone. Run it again with --yes to confirm.")
        val id = artifactReference(artifact)
        sdk(loadConfig()).use { client ->
            client.deleteArtifact(id)
            echo("Deleted $id. Its files are removed from the storage within minutes.")
        }
    }
}

/**
 * Where a `--file PATH` is published: relative to the page's directory, as the page itself is published under its file
 * name, so `publish dist/index.html --file dist/app.js` keeps `./app.js` working. A file outside that directory needs
 * an explicit `PUBLISHED=PATH`.
 */
internal fun publishedBesidePage(page: String, file: String, ignoreCase: Boolean = hostOs() != "linux"): String {
    val directory = lexical(page).dropLast(1)
    val path = lexical(file)
    // Compared as the file system does: without case on Windows and macOS, where `Dist` and `dist` are one directory.
    val beside = path.size > directory.size && path.take(directory.size).zip(directory).all { (a, b) -> a.equals(b, ignoreCase) }
    val published = path.drop(directory.size)
    if (!beside || ".." in published || published.first() == "/" || published.first().endsWith(':'))
        throw CliUserException("$file is outside the page's directory ${directory.joinToString("/").ifEmpty { "." }}. Say where it is published with PUBLISHED=$file.")
    return published.joinToString("/")
}

/** A path's segments with `.`, empty segments and resolvable `..` removed; an absolute path keeps its root (`/`, `C:`) as its first segment. */
private fun lexical(path: String): List<String> {
    val slashed = path.replace('\\', '/')
    val segments = mutableListOf<String>()
    if (slashed.startsWith('/')) segments += "/"
    for (segment in slashed.split('/')) when {
        segment.isEmpty() || segment == "." -> Unit
        segment == ".." && segments.isNotEmpty() && segments.last() != ".." && segments.last() != "/" && !segments.last().endsWith(':') -> segments.removeAt(segments.lastIndex)
        else -> segments += segment
    }
    return segments
}
