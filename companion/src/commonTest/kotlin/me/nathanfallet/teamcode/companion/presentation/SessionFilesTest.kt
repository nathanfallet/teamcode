package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeFileRepository
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PendingFile
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.TranscriptRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.files.SessionFilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.AnswerPermissionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.picked
import me.nathanfallet.teamcode.companion.policy
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionNotice
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import kotlin.test.*

/** The composer's files: the capability, the upload and its progress, retries, the cap, and the message that carries them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilesTest {
    private val dispatcher = StandardTestDispatcher()
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = TranscriptPage(emptyList(), nextBefore = null, lastSeq = 0)
        override fun follow(accountId: String, sessionId: String, after: Long) = emptyFlow<me.nathanfallet.teamcode.companion.domain.models.LiveUpdate<me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent>>()
    }
    private val sessions = FakeSessionRepository().apply { byId["s1"] = cloudSession("s1") }
    private val files = FakeFileRepository()
    private val features = FakeFeatures().apply { features = features + Features.FILES }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(withFiles: Boolean = true): SessionViewModel {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        return SessionViewModel(
            SessionRoute("a1", "s1"), GetSessionUseCase(sessions, guard), LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard),
            SendMessageUseCase(sessions, features, guard, SequenceIds()), ControlSessionUseCase(sessions, guard, SequenceIds()), accounts,
            AnswerPermissionUseCase(sessions, features, guard, SequenceIds()), if (withFiles) SessionFilesUseCase(files, features, guard, SequenceIds()) else null,
        )
    }

    private fun TestScope.started(withFiles: Boolean = true) = viewModel(withFiles).also { it.start(); settle() }

    @Test fun withoutTheCapabilityThereIsNoPaperclipAndMessagesGoAsBefore() = runTest(dispatcher) {
        features.features = features.features - Features.FILES
        val model = started()
        assertFalse(model.uiState.value.files.supported)
        assertEquals(0, files.policyReads)
        // Picked files reaching the screen anyway (a drop) are ignored.
        model.attach(listOf(picked("a.png"))); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        model.onDraftChange("hello"); model.send(); settle()
        assertEquals("send s1 hello ${model.uiState.value.pending.single().clientMessageId}", sessions.calls.last())
        model.stop()
    }

    @Test fun aTeamWithoutStorageShowsThePaperclipAndExplainsIt() = runTest(dispatcher) {
        files.filePolicy = policy(available = false)
        val model = started()
        assertTrue(model.uiState.value.files.supported)
        assertFalse(model.uiState.value.files.canAttach)
        model.explainFiles()
        assertEquals(FileProblem.StorageNotConfigured, model.uiState.value.files.notice)
        model.dismissFileNotice()
        assertNull(model.uiState.value.files.notice)
        model.stop()
    }

    @Test fun aPolicyThatCannotBeReadIsReadAgainNextTime() = runTest(dispatcher) {
        files.policyFailure = CompanionException.Unreachable()
        val model = started()
        assertFalse(model.uiState.value.files.supported)
        model.stop()
        files.policyFailure = null
        model.start(); settle()
        assertTrue(model.uiState.value.files.supported)
        assertEquals(2, files.policyReads)
        model.stop()
    }

    @Test fun aFileUploadsWithItsProgressThenGoesWithTheMessage() = runTest(dispatcher) {
        val model = started()
        val gate = CompletableDeferred<Unit>()
        files.gate = gate; files.progressBefore = 40
        model.attach(listOf(picked("a.png", size = 100))); settle()
        val uploading = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.UPLOADING, uploading.state)
        assertEquals(40, uploading.sent); assertEquals(0.4f, uploading.progress)
        // Send waits for the upload.
        model.onDraftChange("What is in it?")
        assertFalse(model.uiState.value.canSend)
        model.send()
        assertEquals(SessionNotice.FilesNotReady, model.uiState.value.notice)
        assertTrue(model.uiState.value.pending.isEmpty())
        gate.complete(Unit); settle()
        val uploaded = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.UPLOADED, uploaded.state)
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        val message = model.uiState.value.pending.single()
        assertEquals(listOf(uploaded.uploaded), message.files)
        assertEquals("send s1 What is in it? ${message.clientMessageId} files=${uploaded.uploaded!!.id}", sessions.calls.last())
        // The composer starts over.
        assertTrue(model.uiState.value.files.files.isEmpty())
        assertEquals("", model.uiState.value.draft)
        model.stop()
    }

    @Test fun filesAloneMakeAMessage() = runTest(dispatcher) {
        val model = started()
        assertFalse(model.uiState.value.canSend)
        model.attach(listOf(picked("a.png"), picked("b.pdf"))); settle()
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        val message = model.uiState.value.pending.single()
        assertEquals("", message.text)
        assertEquals(listOf("a.png", "b.pdf"), message.files.map { it.name })
        assertEquals(PendingMessage.State.SENT, message.state)
        model.stop()
    }

    @Test fun aFailedMessageIsRetriedWithItsFiles() = runTest(dispatcher) {
        val model = started()
        model.attach(listOf(picked("a.png"))); settle()
        sessions.sendResult = { throw CompanionException.Unreachable() }
        model.send(); settle()
        val failed = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.FAILED, failed.state)
        sessions.sendResult = { me.nathanfallet.teamcode.companion.domain.models.InputReceipt("in-$it", "queued", false, null) }
        model.retry(failed.clientMessageId); settle()
        assertEquals(2, sessions.calls.count { it.endsWith("files=${failed.files.single().id}") })
        model.stop()
    }

    @Test fun aMessageRefusedForItsFilesKeepsTheCode() = runTest(dispatcher) {
        val model = started()
        model.attach(listOf(picked("a.png"))); settle()
        sessions.sendResult = { throw CompanionException.Server(409, "file_unavailable") }
        model.send(); settle()
        val refused = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.REFUSED, refused.state)
        assertEquals("file_unavailable", refused.reason)
        model.stop()
    }

    @Test fun aTransientFailureRetriesUnderTheSameKeyAndARefusalCannotBeRetried() = runTest(dispatcher) {
        val model = started()
        files.result = { _, _ -> throw CompanionException.FileRefused("file_storage_unavailable", 503) }
        model.attach(listOf(picked("a.png"))); settle()
        val failed = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.FAILED, failed.state)
        assertEquals(FileProblem.StorageUnavailable, failed.problem)
        assertFalse(model.uiState.value.canSend)
        files.result = { file, key -> me.nathanfallet.teamcode.companion.domain.models.UploadedFile("f-$key", file.name, file.size, "image/png") }
        model.retryFile(failed.localId); settle()
        assertEquals(PendingFile.State.UPLOADED, model.uiState.value.files.files.single().state)
        val (first, second) = files.uploads
        assertEquals(first.second, second.second)

        files.result = { _, _ -> throw CompanionException.FileRefused("file_type_not_allowed", 415) }
        model.attach(listOf(picked("b.png"))); settle()
        val refused = model.uiState.value.files.files.last()
        assertIs<FileProblem.TypeNotAllowed>(refused.problem)
        model.retryFile(refused.localId); settle()
        assertEquals(3, files.uploads.size)
        assertEquals(PendingFile.State.FAILED, model.uiState.value.files.files.last().state)
        // Removing it lets the message go with the other.
        model.removeFile(refused.localId)
        assertTrue(model.uiState.value.canSend)
        model.stop()
    }

    @Test fun theSixthFileIsRefusedBeforeAnyUpload() = runTest(dispatcher) {
        val model = started()
        model.attach((1..6).map { picked("f$it.png") }); settle()
        assertEquals(5, model.uiState.value.files.files.size)
        assertEquals(5, files.uploads.size)
        assertEquals(FileProblem.TooMany(5), model.uiState.value.files.notice)
        assertFalse(model.uiState.value.files.canAttach)
        model.explainFiles()
        assertEquals(FileProblem.TooMany(5), model.uiState.value.files.notice)
        model.stop()
    }

    @Test fun removingAFileStopsItsUpload() = runTest(dispatcher) {
        val model = started()
        files.gate = CompletableDeferred()
        model.attach(listOf(picked("a.png"))); settle()
        val file = model.uiState.value.files.files.single()
        model.removeFile(file.localId); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        files.gate!!.complete(Unit); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        model.stop()
    }

    @Test fun aPickerThatFailedSaysSo() = runTest(dispatcher) {
        val model = started()
        model.pickFailed()
        assertEquals(FileProblem.Unreadable, model.uiState.value.files.notice)
        model.stop()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
