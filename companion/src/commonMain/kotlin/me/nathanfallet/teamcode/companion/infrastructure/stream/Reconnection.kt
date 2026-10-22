package me.nathanfallet.teamcode.companion.infrastructure.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transform
import me.nathanfallet.teamcode.client.CloudStreamEnd
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.infrastructure.repositories.translated
import kotlin.random.Random

/**
 * When to reconnect a stream: [firstDelayMillis] after the first failure (the server's `retry:`), then doubling up to
 * [maxDelayMillis], each delay shortened by up to a fifth at random so clients cut together do not return together.
 * The count restarts once an item arrives. A `429` waits at least its `Retry-After`, bounded by [maxRetryAfterMillis].
 */
class ReconnectPolicy(
    private val firstDelayMillis: Long = 2_000,
    private val maxDelayMillis: Long = 30_000,
    private val maxRetryAfterMillis: Long = 300_000,
    private val random: Random = Random.Default,
) {
    fun delayMillis(failures: Int, retryAfterSeconds: Long? = null): Long {
        val base = (firstDelayMillis shl (failures - 1).coerceIn(0, 16)).coerceAtMost(maxDelayMillis)
        val jittered = base - (base * random.nextDouble(0.0, 0.2)).toLong()
        val floor = retryAfterSeconds?.let { (it * 1_000).coerceAtMost(maxRetryAfterMillis) } ?: 0
        return maxOf(jittered, floor)
    }
}

/**
 * Runs [connect] again and again until the stream ends: a network failure, a closed stream, a `408`, `429` or `5xx`
 * reconnect after the policy's delay ([LiveUpdate.Reconnecting] says when); `ENDED` emits [LiveUpdate.Ended] and
 * completes; `DENIED` and every other refusal end the flow with a [CompanionException] (a `401` is
 * [CompanionException.Unauthorized], an unreadable event [CompanionException.UnreadableEvent], which a reconnection
 * would replay). [connect] keeps its own cursor so that each attempt resumes after the last item it sent.
 */
internal fun <T> reconnecting(
    policy: ReconnectPolicy,
    connect: suspend (send: suspend (LiveUpdate<T>) -> Unit, itemSent: () -> Unit) -> CloudStreamEnd,
): Flow<LiveUpdate<T>> = channelFlow<Any> {
    var failures = 0
    while (true) {
        val end = try {
            connect({ send(it) }) { failures = 0 }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!error.isTransient()) { send(Stop(error.translated(resource = true))); return@channelFlow }
            failures++
            val wait = policy.delayMillis(failures, (error as? TeamCodeException)?.retryAfterSeconds)
            send(LiveUpdate.Reconnecting(wait))
            delay(wait)
            continue
        }
        when (end) {
            CloudStreamEnd.ENDED -> send(LiveUpdate.Ended)
            CloudStreamEnd.DENIED -> send(Stop(CompanionException.AccessRevoked()))
        }
        return@channelFlow
    }
    // The producer always completes normally: a failing channel flow is cancelled, and cancellation could discard an
    // update already read. The failure travels as the last element instead and is thrown on the collector's side.
    // Rendezvous: each update is handed over before the next is read, so a slow collector slows the reading, not memory.
}.buffer(Channel.RENDEZVOUS).transform { element ->
    if (element is Stop) throw element.error
    @Suppress("UNCHECKED_CAST")
    emit(element as LiveUpdate<T>)
}

private class Stop(val error: Exception)

private fun Exception.isTransient(): Boolean = when (this) {
    is CompanionException -> false
    is TeamCodeException -> when {
        code == TeamCodeClient.MALFORMED_EVENT -> false
        else -> statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500
    }
    // A bug or the typed client's own refusal of an argument: retrying cannot help. Engines throw subclasses for
    // network failures, so only the exact type.
    else -> this::class != IllegalArgumentException::class && translated() is CompanionException.Unreachable
}
