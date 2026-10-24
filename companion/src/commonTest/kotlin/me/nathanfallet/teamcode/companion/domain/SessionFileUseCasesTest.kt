package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeFileRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FileProblem
import me.nathanfallet.teamcode.companion.domain.models.PendingFile
import me.nathanfallet.teamcode.companion.domain.models.UploadOutcome
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.files.PhotoSizing
import me.nathanfallet.teamcode.companion.domain.usecases.files.SessionFilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.picked
import me.nathanfallet.teamcode.companion.policy
import kotlin.test.*

/** The checks before an upload, the problems an upload ends with, the capability, and the size of reduced photos. */
class SessionFileUseCasesTest {
    private val repository = FakeFileRepository()
    private val features = FakeFeatures().apply { features = features + Features.FILES }
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
    private val files = SessionFilesUseCase(repository, features, guard, SequenceIds())

    private fun pending(count: Int, size: Long = 100) = (1..count).map { PendingFile("l$it", "f$it.png", size, PendingFile.State.UPLOADED) }

    @Test fun withoutTheCapabilityThereIsNoPolicyAndNoRequest() = runTest {
        features.features = features.features - Features.FILES
        assertNull(files.policy("a1"))
        assertEquals(0, repository.policyReads)
        features.features = features.features + Features.FILES
        assertEquals(policy(), files.policy("a1"))
    }

    @Test fun aMessageCarriesFiveFilesAtMostWhateverTheTeamSays() {
        assertEquals(FileProblem.TooMany(5), files.check(picked("a.png"), pending(5), policy(maxFiles = 50)))
        assertNull(files.check(picked("a.png"), pending(4), policy(maxFiles = 50)))
        assertEquals(FileProblem.TooMany(2), files.check(picked("a.png"), pending(2), policy(maxFiles = 2)))
    }

    @Test fun sizesAndTypesAreCheckedBeforeAnyUpload() {
        val team = policy(maxFileBytes = 1_000, maxMessageBytes = 1_500, types = listOf("image/png", "text/plain"))
        assertEquals(FileProblem.Empty, files.check(picked("a.png", size = 0), emptyList(), team))
        assertEquals(FileProblem.TooLarge(1_000), files.check(picked("a.png", size = 1_001), emptyList(), team))
        assertEquals(FileProblem.MessageTooLarge(1_500), files.check(picked("a.png", size = 600), pending(1, size = 1_000), team))
        assertEquals(FileProblem.TypeNotAllowed(team.types), files.check(picked("a.pdf"), emptyList(), team))
        // The picker's type when the name says nothing; any text type goes as plain text.
        assertEquals(FileProblem.TypeNotAllowed(team.types), files.check(picked("scan", type = "application/vnd.ms-excel"), emptyList(), team))
        assertNull(files.check(picked("build.log", type = "text/x-log"), emptyList(), team))
        assertEquals("text/plain", files.typeOf(picked("build.log", type = "text/x-log; charset=utf-8"), team))
        // Nothing says: the server recognizes the bytes.
        assertNull(files.typeOf(picked("blob", type = "application/octet-stream"), team))
        assertNull(files.check(picked("blob"), emptyList(), team))
        assertEquals(FileProblem.StorageNotConfigured, files.check(picked("a.png"), emptyList(), policy(available = false)))
    }

    @Test fun eachServerCodeBecomesItsProblem() = runTest {
        val team = policy(maxFileBytes = 1_000)
        suspend fun outcome(error: Exception): FileProblem {
            repository.result = { _, _ -> throw error }
            return (files.upload("a1", "s1", picked("a.png"), team, "k") { _, _ -> } as UploadOutcome.Failed).problem
        }
        assertEquals(FileProblem.TooLarge(1_000), outcome(CompanionException.FileRefused("file_too_large", 413)))
        assertEquals(FileProblem.TypeNotAllowed(team.types), outcome(CompanionException.FileRefused("file_type_not_allowed", 415)))
        assertEquals(FileProblem.Quota(3600), outcome(CompanionException.FileRefused("upload_quota", 429, 3600)))
        assertTrue(FileProblem.Quota(3600).retryable); assertFalse(FileProblem.Quota(null).retryable)
        assertEquals(FileProblem.StorageNotConfigured, outcome(CompanionException.FileRefused("file_storage_not_configured", 409)))
        assertEquals(FileProblem.StorageUnavailable, outcome(CompanionException.FileRefused("file_storage_unavailable", 503)))
        assertEquals(FileProblem.Integrity, outcome(CompanionException.FileRefused("file_integrity_failed", 0)))
        assertEquals(FileProblem.Unreadable, outcome(CompanionException.FileRefused("file_unreadable", 0)))
        assertEquals(FileProblem.Denied, outcome(CompanionException.FileRefused("cloud_permission_denied", 403)))
        assertEquals(FileProblem.Other("whatever", transient = false), outcome(CompanionException.FileRefused("whatever", 400)))
        assertEquals(FileProblem.SessionGone, outcome(CompanionException.NotFound()))
        assertEquals(FileProblem.Unreachable, outcome(CompanionException.Unreachable()))
        assertTrue(outcome(CompanionException.Server(502, null)).retryable)
        assertTrue(FileProblem.Unreachable.retryable); assertTrue(FileProblem.StorageUnavailable.retryable); assertFalse(FileProblem.TooLarge(1).retryable)
    }

    @Test fun anUploadSendsTheGuessedTypeUnderItsKey() = runTest {
        val outcome = files.upload("a1", "s1", picked("notes.md"), policy(), "key-1") { _, _ -> }
        assertIs<UploadOutcome.Uploaded>(outcome)
        assertEquals(listOf("notes.md" to "key-1"), repository.uploads)
        assertEquals(listOf<String?>("text/markdown"), repository.contentTypes)
    }

    @Test fun aMessageWithFilesMayHaveNoTextAndNeedsTheCapability() = runTest {
        val sessions = FakeSessionRepository()
        val send = SendMessageUseCase(sessions, features, guard, SequenceIds())
        val attached = listOf(UploadedFile("f1", "a.png", 10, "image/png"))
        val message = send.draft("  ", attached)
        assertEquals("", message.text)
        assertFailsWith<IllegalArgumentException> { send.draft("  ") }
        assertFailsWith<IllegalArgumentException> { send.draft("hi", List(6) { attached.single() }) }
        assertEquals(PendingMessage.State.SENT, send("a1", "s1", message).state)
        assertEquals("send s1  ${message.clientMessageId} files=f1", sessions.calls.single())
        // A server that lost the capability: refused here, never sent without its files.
        features.features = features.features - Features.FILES
        assertEquals(PendingMessage.State.REFUSED, send("a1", "s1", send.draft("look", attached)).state)
        assertEquals(1, sessions.calls.size)
    }

    @Test fun photosAreReducedTo2048PixelsOnTheirLongerSide() {
        assertEquals(2048 to 1536, PhotoSizing.target(4032, 3024))
        assertEquals(1536 to 2048, PhotoSizing.target(3024, 4032))
        assertEquals(800 to 600, PhotoSizing.target(800, 600))
        assertEquals(2048 to 1, PhotoSizing.target(10_000, 2))
        assertEquals(1, PhotoSizing.sampleSize(3000, 2000))
        assertEquals(2, PhotoSizing.sampleSize(4096, 3072))
        assertEquals(4, PhotoSizing.sampleSize(9000, 6000))
        assertEquals("IMG_0001.jpg", PhotoSizing.jpegName("IMG_0001.HEIC"))
        assertEquals("photo.jpg", PhotoSizing.jpegName(null))
    }
}
