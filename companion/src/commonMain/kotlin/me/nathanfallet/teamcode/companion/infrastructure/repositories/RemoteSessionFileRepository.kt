package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.client.CloudFileReadException
import me.nathanfallet.teamcode.client.CloudFileSource
import me.nathanfallet.teamcode.client.CloudFileTypes
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FilePolicy
import me.nathanfallet.teamcode.companion.domain.models.PickedFile
import me.nathanfallet.teamcode.companion.domain.models.UploadedFile
import me.nathanfallet.teamcode.companion.domain.repositories.SessionFileRepository
import me.nathanfallet.teamcode.companion.domain.usecases.files.SessionFilesUseCase

/**
 * Files over the typed client: [TeamCodeClient.cloudFilePolicy] and [TeamCodeClient.uploadCloudFile], which streams the
 * file, retries transient failures under one idempotency key and checks the stored SHA-256. A refusal of the file is
 * kept with its code ([CompanionException.FileRefused]); a `401` and a missing session are translated as for any call.
 */
class RemoteSessionFileRepository(private val calls: AccountCalls) : SessionFileRepository {
    override suspend fun policy(accountId: String): FilePolicy = calls.withAccount(accountId) { client ->
        val policy = client.cloudFilePolicy()
        FilePolicy(policy.available, policy.reason, policy.maxFileBytes, policy.maxMessageBytes, policy.maxFilesPerMessage, policy.types)
    }

    override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, contentType: String?, idempotencyKey: String,
        onProgress: (sent: Long, total: Long) -> Unit): UploadedFile = calls.withAccountLong(accountId, UPLOAD_TIMEOUT_MILLIS) { client ->
        val uploaded = try {
            client.uploadCloudFile(sessionId, file.name, CloudFileSource(file.size, file.open), contentType, idempotencyKey, onProgress = onProgress)
        } catch (_: CloudFileReadException) {
            throw CompanionException.FileRefused(SessionFilesUseCase.FILE_UNREADABLE, 0)
        } catch (error: TeamCodeException) {
            throw refusal(error) ?: error
        }
        UploadedFile(uploaded.id, uploaded.name, uploaded.size, uploaded.contentType)
    }

    override fun typeFor(name: String): String? = CloudFileTypes.forName(name)

    /** The refusal of the file itself; null for what any call translates (signed out, the session gone, the network). */
    private fun refusal(error: TeamCodeException): CompanionException.FileRefused? = when {
        error.code == TeamCodeClient.FILE_INTEGRITY_FAILED -> CompanionException.FileRefused(error.code, 0)
        error.statusCode == 0 || error.statusCode == 401 || error.statusCode == 404 -> null
        else -> CompanionException.FileRefused(error.code, error.statusCode, error.retryAfterSeconds)
    }

    private companion object {
        /** Every attempt of one upload together; the client bounds each attempt to 15 minutes. */
        const val UPLOAD_TIMEOUT_MILLIS = 30 * 60_000L
    }
}
