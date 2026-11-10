package me.nathanfallet.teamcode.companion.domain.usecases.push

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform
import me.nathanfallet.teamcode.companion.domain.usecases.links.NotificationLinks

/** A push to show: its text, and the link a tap opens (NotificationLinks). */
data class PushNotice(val id: String, val accountId: String, val title: String, val body: String, val link: String)

/**
 * Opens a push received by the Android messaging service: decrypts the payload with the device key and finds the
 * signed-in account it is for. Null when it cannot be read (another key, a damaged payload) or when no signed-in account
 * matches its server and member: a push for an account signed out on this phone is not shown.
 */
class ReadPushUseCase(private val accounts: AccountRepository, private val platform: PushPlatform) {
    suspend operator fun invoke(payload: String): PushNotice? {
        val push = try {
            platform.open(payload)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { return null }
        val account = accounts.accounts.value.firstOrNull { it.signedIn && it.origin == push.origin && it.memberId == push.memberId } ?: return null
        val title = push.title.take(MAX_TITLE).ifBlank { return null }
        return PushNotice(push.id, account.id, title, push.body.take(MAX_BODY), NotificationLinks.link(push.origin, push.memberId, push.sessionId, push.waitId))
    }

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_BODY = 1_000
    }
}
