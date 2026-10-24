package me.nathanfallet.teamcode.cli.cloud

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.api.models.CloudFilePolicyView
import me.nathanfallet.teamcode.api.models.CloudFileView
import me.nathanfallet.teamcode.api.models.CloudFiles
import me.nathanfallet.teamcode.cli.artifacts.SourceReadFailure
import me.nathanfallet.teamcode.cli.artifacts.readWithoutLinks
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.terminal.printable
import me.nathanfallet.teamcode.client.CloudFileReadException
import me.nathanfallet.teamcode.client.CloudFileTypes
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** A line of `attach` about files, as [AttachFileCommands.parse] reads it. */
internal sealed interface FileCommand {
    /** Upload [path] and keep it for the next message; with [text], send that message with the pending files at once. */
    data class Attach(val path: String, val text: String?) : FileCommand
    /** List the pending files. */
    data object Show : FileCommand
    /** Drop every pending file. */
    data object Clear : FileCommand
    /** A file command typed wrong; [text] says how to type it. */
    data class Usage(val text: String) : FileCommand
}

/**
 * The file commands of `attach`, pure: `/attach PATH [message]` (alias `/file`), `/files`, `/unattach`. A path holding
 * spaces is quoted (`"my file.png"` or `'my file.png'`) or written with `\ ` for each space, as terminals paste a
 * dropped file; the rest of the line is the message, its surrounding quotes removed.
 */
internal object AttachFileCommands {
    const val USAGE = "Use /attach PATH [message]; quote a path or a message holding spaces (/attach \"my file.png\" \"what is in it?\")."
    private val QUOTES = setOf('"', '\'')

    /** The file command [line] holds, or null when it is not one. */
    fun parse(line: String): FileCommand? {
        val trimmed = line.trim()
        val command = trimmed.takeWhile { !it.isWhitespace() }
        val rest = trimmed.drop(command.length).trim()
        return when (command) {
            "/attach", "/file" -> attach(rest)
            "/files" -> if (rest.isEmpty()) FileCommand.Show else FileCommand.Usage("Use /files to list the files waiting for the next message.")
            "/unattach" -> if (rest.isEmpty()) FileCommand.Clear else FileCommand.Usage("Use /unattach to drop every file waiting for the next message.")
            else -> null
        }
    }

    private fun attach(rest: String): FileCommand {
        if (rest.isEmpty()) return FileCommand.Usage(USAGE)
        val (path, after) = word(rest) ?: return FileCommand.Usage(USAGE)
        if (path.isBlank()) return FileCommand.Usage(USAGE)
        val remainder = after.trim()
        val text = when {
            remainder.isEmpty() -> null
            remainder.length >= 2 && remainder.first() in QUOTES && remainder.last() == remainder.first() -> remainder.substring(1, remainder.length - 1)
            else -> remainder
        }
        return FileCommand.Attach(path, text)
    }

    /**
     * The first word of [text] and what follows it: quoted (single or double quotes, no escape inside), or up to the
     * first blank not written `\ `. Any other backslash is kept, for Windows paths. Null for a quote never closed.
     */
    private fun word(text: String): Pair<String, String>? {
        val first = text.first()
        if (first in QUOTES) {
            val end = text.indexOf(first, 1)
            if (end < 0) return null
            return text.substring(1, end) to text.substring(end + 1)
        }
        val word = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && text.getOrNull(i + 1) == ' ') { word.append(' '); i += 2; continue }
            if (c.isWhitespace()) break
            word.append(c); i++
        }
        return word.toString() to text.substring(i)
    }

    /**
     * The path [line] holds when it is nothing else, as a terminal pastes a dropped file (quoted, or with `\ ` for
     * spaces), with a directory part and an extension files may have; null otherwise. Whether the file exists is
     * checked by the caller. A bare `notes.md` is more likely a message and is not offered.
     */
    fun pastedPath(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || parse(trimmed) != null) return null
        val (path, after) = word(trimmed) ?: return null
        if (after.isNotBlank() || path.isBlank()) return null
        if ('/' !in path && '\\' !in path) return null
        if (path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase() !in CloudFileTypes.extensions) return null
        return path
    }
}

/** [path] with a leading `~` or `~/` replaced by [home]; null when it needs a home directory and there is none. `~user` is kept. */
internal fun expandHome(path: String, home: String?): String? = when {
    path == "~" -> home
    path.startsWith("~/") || path.startsWith("~\\") -> home?.let { it.trimEnd('/', '\\') + path.substring(1) }
    else -> path
}

/** A size as `512 B`, `245 KB` or `1.2 MB` (powers of 1024). */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 + 512 * 1024) / (1024 * 1024) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The files uploaded for the next message, in order. */
internal class PendingFiles {
    private val files = mutableListOf<CloudFileView>()
    val ids: List<String> get() = files.map { it.id }
    val count: Int get() = files.size
    val bytes: Long get() = files.sumOf { it.size }
    fun isEmpty() = files.isEmpty()
    fun add(file: CloudFileView) { files += file }
    fun clear() { files.clear() }
    /** The line that lists them, or null when none waits. */
    fun line(): String? = if (files.isEmpty()) null else
        "Attached to your next message: " + files.joinToString(", ") { "${it.name.printable()} (${sizeLabel(it.size)})" } + ". /unattach drops ${if (files.size == 1) "it" else "them"}."
}

/**
 * The files of `attach` (`cloud.files.v1`): uploads a file as soon as `/attach` names it, after local checks that make
 * no request (the path, a directory, a link, the queue), then the server's capability and the team's policy (size,
 * type), and keeps it for the next message. A line that is only an existing file's path asks first and never uploads
 * by itself. Not thread-safe: `attach` calls it from one coroutine.
 */
internal class AttachFiles(
    private val client: TeamCodeClient,
    private val sessionId: String,
    private val home: String? = environment("HOME") ?: environment("USERPROFILE"),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    sealed interface Outcome {
        /** Not about files: handle the line as before. */
        data object NotFiles : Outcome
        /** Handled; nothing to send. */
        data object Done : Outcome
        /** Send [text] as a message with the pending files. */
        data class Send(val text: String) : Outcome
    }

    val pending = PendingFiles()
    /** Whether the server advertises `cloud.files.v1`, read once. */
    private var supported: Boolean? = null
    /** A pasted path waiting for its "attach? (y/N)" answer. */
    private var confirming: String? = null

    /** Whether the next line answers "attach? (y/N)"; it is then handed here even when blank. */
    val asking: Boolean get() = confirming != null

    /** Handles [line] when it is about files; [idle] is true when no question waits, so a pasted path may be offered. */
    suspend fun handle(line: String, idle: Boolean, say: (String) -> Unit): Outcome {
        confirming?.let { path ->
            confirming = null
            val answer = line.trim().lowercase()
            if (answer == "y" || answer == "yes") { attach(path, say); return Outcome.Done }
            say("Not attached.")
            // Anything but a no is a line of its own.
            return if (answer.isEmpty() || answer == "n" || answer == "no") Outcome.Done else handle(line, idle, say)
        }
        when (val command = AttachFileCommands.parse(line)) {
            null -> Unit
            is FileCommand.Usage -> { say(command.text); return Outcome.Done }
            FileCommand.Show -> { say(pending.line() ?: "No file waits for the next message."); return Outcome.Done }
            FileCommand.Clear -> {
                say(if (pending.isEmpty()) "No file waits for the next message." else "Dropped ${count(pending.count)}; the server deletes ${if (pending.count == 1) "it" else "them"} within 24 hours.")
                pending.clear()
                return Outcome.Done
            }
            is FileCommand.Attach -> return if (attach(command.path, say) && command.text != null) Outcome.Send(command.text) else Outcome.Done
        }
        if (idle) AttachFileCommands.pastedPath(line)?.let { path ->
            val local = expandHome(path, home)?.let(::absolute) ?: return@let
            val metadata = try { fileSystem.metadataOrNull(local) } catch (_: Exception) { null }
            if (metadata?.isRegularFile == true && metadata.symlinkTarget == null) {
                confirming = path
                say("Attach ${path.printable()} to your next message? (y/N)")
                return Outcome.Done
            }
        }
        return Outcome.NotFiles
    }

    /** The pending files reached the agent's queue with a message. */
    fun sent() = pending.clear()

    /** What to say when a message carrying the pending files was refused with [error]. */
    fun refused(error: TeamCodeException): String = when (error.code) {
        CloudFiles.FILE_UNAVAILABLE -> {
            pending.clear()
            "Message refused: an attached file is no longer available (sent already or expired after 24 hours). Attach it again."
        }
        CloudFiles.FILES_TOO_LARGE -> "Message refused: its files together exceed the team's limit per message. /unattach, then attach fewer."
        CloudFiles.UNSUPPORTED -> { pending.clear(); "Message refused: this server does not take files. Update it to send files." }
        "invalid_files" -> { pending.clear(); "Message refused: the attached files are not valid for this session. Attach them again." }
        else -> "Message refused (${error.code ?: "HTTP ${error.statusCode}"})."
    }

    /** Checks, uploads and queues [typed]; false when it was refused (the reason was said). */
    private suspend fun attach(typed: String, say: (String) -> Unit): Boolean {
        val shown = typed.printable()
        if (pending.count >= CloudFiles.MAX_FILES_PER_MESSAGE) return refuse(say, "A message carries ${CloudFiles.MAX_FILES_PER_MESSAGE} files at most: send the message first, or /unattach.")
        val expanded = expandHome(typed, home) ?: return refuse(say, "No home directory to expand ~ in $shown.")
        if ('\u0000' in expanded) return refuse(say, "$shown is not a valid path.")
        val path = absolute(expanded) ?: return refuse(say, "$shown is not a valid path.")
        val name = path.name
        val parent = path.parent ?: return refuse(say, "$shown is not a file.")
        val metadata = try { fileSystem.metadataOrNull(path) } catch (_: Exception) { null } ?: return refuse(say, "No file at $shown.")
        // Never followed: the file sent is the one typed, not another place a link points to.
        if (metadata.symlinkTarget != null) return refuse(say, "$shown is a symbolic link; attach the file it points to by its own path.")
        if (metadata.isDirectory) return refuse(say, "$shown is a directory; attach its files one by one.")
        if (!metadata.isRegularFile) return refuse(say, "$shown is not a regular file.")
        val guessed = CloudFileTypes.forName(name)

        // Now the server: does it take files, and what does the team allow?
        val policy = policy(say) ?: return false
        val size = metadata.size
        if (size == 0L) return refuse(say, "$shown is empty.")
        if (size != null && size > policy.maxFileBytes) return refuse(say, "$shown is ${sizeLabel(size)}; the team allows ${sizeLabel(policy.maxFileBytes)} per file.")
        if (size != null && pending.bytes + size > policy.maxMessageBytes)
            return refuse(say, "With $shown the message would carry ${sizeLabel(pending.bytes + size)}; the team allows ${sizeLabel(policy.maxMessageBytes)} per message. Send the pending files first.")
        if (pending.count >= policy.maxFilesPerMessage) return refuse(say, "A message carries ${policy.maxFilesPerMessage} files at most: send the message first, or /unattach.")
        if (guessed != null && guessed !in policy.types) return refuse(say, "$shown is not a type the team takes (${types(policy)}).")

        val bytes = try { readWithoutLinks(parent.toString(), listOf(name), policy.maxFileBytes) }
        catch (failure: SourceReadFailure) {
            return refuse(say, when (failure.kind) {
                SourceReadFailure.Kind.LINK -> "$shown is a symbolic link; attach the file it points to by its own path."
                SourceReadFailure.Kind.MISSING, SourceReadFailure.Kind.ROOT -> "No file at $shown."
                SourceReadFailure.Kind.NOT_REGULAR -> "$shown is not a regular file."
                SourceReadFailure.Kind.DENIED -> "$shown cannot be read with your permissions."
                SourceReadFailure.Kind.TOO_LARGE -> "$shown is larger than the ${sizeLabel(policy.maxFileBytes)} the team allows per file."
                SourceReadFailure.Kind.OTHER -> "$shown cannot be read."
            })
        }
        if (bytes.isEmpty()) return refuse(say, "$shown is empty.")
        if (pending.bytes + bytes.size > policy.maxMessageBytes)
            return refuse(say, "With $shown the message would carry ${sizeLabel(pending.bytes + bytes.size)}; the team allows ${sizeLabel(policy.maxMessageBytes)} per message. Send the pending files first.")
        val type = guessed ?: if (CloudFileTypes.isText(bytes) && "text/plain" in policy.types) "text/plain" else return refuse(say, "$shown is not a type the team takes (${types(policy)}).")

        var quarter = 0
        val file = try {
            client.uploadCloudFile(sessionId, name, bytes, type,
                onRetry = { say("Uploading $shown failed; retrying.") },
                onProgress = { sent, total ->
                    // Only a file that takes a while shows its progress, a quarter at a time.
                    if (sent == 0L) quarter = 0
                    else if (total >= 1024 * 1024 && sent < total && sent * 4 / total > quarter) { quarter = (sent * 4 / total).toInt(); say("Uploading $shown: ${quarter * 25}%") }
                })
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { return refuse(say, uploadRefusal(shown, error, policy)) }
        catch (_: CloudFileReadException) { return refuse(say, "$shown changed while it was sent; attach it again.") }
        catch (_: Exception) { return refuse(say, "Uploading $shown failed: the server could not be reached. Attach it again.") }
        pending.add(file)
        say("Uploaded $shown (${sizeLabel(file.size)})" + if (file.name != name) " as ${file.name.printable()}." else ".")
        pending.line()?.let(say)
        return true
    }

    /** The team's file policy, or null after saying why files cannot be sent now. */
    private suspend fun policy(say: (String) -> Unit): CloudFilePolicyView? = try {
        val supported = supported ?: (CloudFiles.FEATURE in client.capabilities().features).also { supported = it }
        if (!supported) { say("This server does not take files (it lacks ${CloudFiles.FEATURE}); it needs an update first."); null }
        else client.cloudFilePolicy().takeIf { it.available } ?: run {
            say("Files are off for this team: an administrator has to configure and test the team's artifact storage first.")
            null
        }
    } catch (error: CancellationException) { throw error }
    catch (error: TeamCodeException) { say("The server refused to say whether files can be sent (${error.code ?: "HTTP ${error.statusCode}"})."); null }
    catch (_: Exception) { say("The server could not be reached; attach the file again."); null }

    private fun uploadRefusal(shown: String, error: TeamCodeException, policy: CloudFilePolicyView): String = when (error.code) {
        CloudFiles.FILE_TOO_LARGE -> "$shown is larger than the ${sizeLabel(policy.maxFileBytes)} the team allows per file."
        CloudFiles.FILE_TYPE_NOT_ALLOWED -> "$shown is not a type the team takes (${types(policy)}); its content decides, not its name."
        CloudFiles.UPLOAD_QUOTA -> error.retryAfterSeconds?.let { "Your daily upload quota is used up; try again in ${wait(it)}." }
            ?: "This session holds too many files not sent yet: send a message with them, or wait until its host received them."
        CloudFiles.STORAGE_NOT_CONFIGURED -> "Files are off for this team: an administrator has to configure and test the team's artifact storage first."
        CloudFiles.STORAGE_UNAVAILABLE -> "The team's file storage did not answer; attach $shown again in a moment."
        "empty_file" -> "$shown is empty."
        "invalid_file_name" -> "The server does not accept the name of $shown; rename it."
        "idempotency_conflict" -> "The upload of $shown conflicted with an earlier one; attach it again."
        TeamCodeClient.FILE_INTEGRITY_FAILED -> "The server stored other bytes than $shown; it was not attached. Attach it again."
        "cloud_permission_denied", "forbidden" -> "You may not send files to this session."
        else -> "Uploading $shown was refused (${error.code ?: "HTTP ${error.statusCode}"})."
    }

    private fun absolute(path: String): Path? = try {
        val typed = path.toPath(normalize = false)
        if (typed.isAbsolute) typed else fileSystem.canonicalize(".".toPath()) / typed
    } catch (_: Exception) { null }

    private fun refuse(say: (String) -> Unit, text: String): Boolean { say(text); return false }
    private fun count(files: Int) = if (files == 1) "1 file" else "$files files"
    private fun wait(seconds: Long) = when {
        seconds >= 3600 -> "${(seconds + 1800) / 3600} h"
        seconds >= 60 -> "${(seconds + 30) / 60} min"
        else -> "$seconds s"
    }
    private fun types(policy: CloudFilePolicyView) = policy.types.joinToString(", ") { TYPE_LABELS[it] ?: it }

    private companion object {
        val TYPE_LABELS = mapOf("image/png" to "PNG", "image/jpeg" to "JPEG", "image/webp" to "WebP", "image/gif" to "GIF", "application/pdf" to "PDF",
            "text/plain" to "text", "text/markdown" to "Markdown", "text/csv" to "CSV", "application/json" to "JSON")
    }
}
