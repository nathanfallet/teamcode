package me.nathanfallet.teamcode.companion.presentation.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PendingFile
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.UploadOutcome
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.domain.usecases.files.SessionFilesUseCase

/** The files of the next message, as the composer shows them. */
data class FilesUiState(
    /** The team's policy once read; null before, or when the server does not take files (no paperclip then). */
    val policy: FilePolicy? = null,
    /** In the order they were picked; each one uploads as soon as it is picked. */
    val files: List<PendingFile> = emptyList(),
    /** A file refused before any upload (one too many, too large, a type the team does not take), or files that are off. */
    val notice: FileProblem? = null,
) {
    /** The paperclip is shown: the server takes files. When the team's storage is off, a tap explains it instead. */
    val supported: Boolean get() = policy != null
    val canAttach: Boolean get() = policy?.available == true && files.size < policy.maxFiles
    val uploading: Boolean get() = files.any { it.state == PendingFile.State.UPLOADING }
    /** Every file is uploaded: the message may go. */
    val ready: Boolean get() = files.all { it.state == PendingFile.State.UPLOADED }
}

/**
 * The pending files of one session's composer: reads the policy ([load]), checks then uploads each picked file
 * ([attach]), retries a failed upload with its idempotency key ([retry]), and hands the uploaded files to the next
 * message ([take]). Its state lives in the screen's state, read by [read] and changed by [write].
 */
class SessionFiles(
    private val scope: CoroutineScope,
    private val accountId: String,
    private val sessionId: String,
    private val useCase: SessionFilesUseCase,
    private val read: () -> FilesUiState,
    private val write: ((FilesUiState) -> FilesUiState) -> Unit,
) {
    /** What each pending file uploads from, with the idempotency key all its attempts share. */
    private val sources = mutableMapOf<String, Pair<PickedFile, String>>()
    private val uploads = mutableMapOf<String, Job>()
    private var loading: Job? = null

    /** Reads the policy once (again after a failed read): whether to show the paperclip, and the team's limits. */
    fun load() {
        if (read().policy != null || loading?.isActive == true) return
        loading = scope.launch {
            try {
                val policy = useCase.policy(accountId) ?: return@launch
                write { it.copy(policy = policy) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // No paperclip for now: the next time the screen shows, the policy is read again.
            }
        }
    }

    /** The paperclip, tapped while files are off for the team: says so instead of opening a picker. */
    fun explainUnavailable() {
        val policy = read().policy ?: return
        if (!policy.available) write { it.copy(notice = FileProblem.StorageNotConfigured) }
        else if (read().files.size >= policy.maxFiles) write { it.copy(notice = FileProblem.TooMany(policy.maxFiles)) }
    }

    /** Checks each of [picked] then uploads it; one refused before upload is said in [FilesUiState.notice], the others go on. */
    fun attach(picked: List<PickedFile>) {
        val policy = read().policy ?: return
        var notice: FileProblem? = null
        for (file in picked) {
            val problem = useCase.check(file, read().files, policy)
            if (problem != null) { notice = problem; continue }
            val id = useCase.newId()
            sources[id] = file to useCase.newId()
            write { it.copy(files = it.files + PendingFile(id, file.name, file.size, PendingFile.State.UPLOADING)) }
            upload(id, policy)
        }
        write { it.copy(notice = notice) }
    }

    /** Uploads a failed file again, under the same key: the server never stores it twice. */
    fun retry(localId: String) {
        val policy = read().policy ?: return
        val file = read().files.firstOrNull { it.localId == localId } ?: return
        if (file.state != PendingFile.State.FAILED || file.problem?.retryable != true || localId !in sources) return
        update(localId) { it.copy(state = PendingFile.State.UPLOADING, sent = 0, problem = null) }
        upload(localId, policy)
    }

    /** Drops a file from the next message, stopping its upload; one already stored is deleted by the server within 24 hours. */
    fun remove(localId: String) {
        uploads.remove(localId)?.cancel()
        sources.remove(localId)
        write { state -> state.copy(files = state.files.filterNot { it.localId == localId }, notice = null) }
    }

    fun dismissNotice() = write { it.copy(notice = null) }

    /** The uploaded files, in order, for the message being sent; null while one still uploads or failed. */
    fun uploaded(): List<UploadedFile>? = read().takeIf { it.ready }?.files?.map { it.uploaded!! }

    /** The message took the files: the composer starts over. */
    fun clear() {
        uploads.values.forEach { it.cancel() }
        uploads.clear(); sources.clear()
        write { it.copy(files = emptyList(), notice = null) }
    }

    private fun upload(localId: String, policy: FilePolicy) {
        val (file, key) = sources[localId] ?: return
        uploads[localId] = scope.launch {
            var shown = -1
            val outcome = useCase.upload(accountId, sessionId, file, policy, key) { sent, total ->
                // One state change per percent, not per 64 KiB chunk.
                val percent = if (total <= 0) 0 else (sent * 100 / total).toInt()
                if (percent != shown) { shown = percent; update(localId) { it.copy(sent = sent) } }
            }
            uploads.remove(localId)
            when (outcome) {
                is UploadOutcome.Uploaded -> update(localId) { it.copy(state = PendingFile.State.UPLOADED, sent = it.size, uploaded = outcome.file, name = outcome.file.name) }
                is UploadOutcome.Failed -> update(localId) { it.copy(state = PendingFile.State.FAILED, sent = 0, problem = outcome.problem) }
            }
        }
    }

    private fun update(localId: String, change: (PendingFile) -> PendingFile) =
        write { state -> state.copy(files = state.files.map { if (it.localId == localId) change(it) else it }) }
}
