package me.nathanfallet.teamcode.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlin.coroutines.cancellation.CancellationException

/** One dispatched server-sent event: its `event:` name (null when absent or empty) and its joined `data:` lines. */
internal class ServerSentEvent(val event: String?, val data: String)

/**
 * Reads the SSE frames of [channel] and hands each dispatched event to [onEvent] until it returns `false`. Comment lines
 * (keep-alives), `id:` and `retry:` are skipped (callers resume from the sequence in the decoded data); a frame with
 * neither a name nor data is not dispatched. Lines are bounded by [maxLine] characters and the whole data of one event by
 * [maxData] characters. Throws [TeamCodeException] when the stream closes, fails or breaks a bound, so the caller
 * reconnects from the last sequence it handled.
 */
internal suspend fun readServerSentEvents(
    channel: ByteReadChannel,
    maxLine: Int = MAX_SSE_LINE,
    maxData: Int = MAX_SSE_DATA,
    onEvent: suspend (ServerSentEvent) -> Boolean,
) {
    var event: String? = null
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = try {
            channel.readUTF8Line(maxLine)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // An over-long line (Ktor's TooLongLineException) or a broken transport: reconnect like a closed stream.
            throw TeamCodeException(0, "The event stream failed.", null, error)
        } ?: throw TeamCodeException(0, "The event stream closed.")
        val field = line.substringBefore(':')
        // "field: value" loses exactly one leading space after the colon; a line without a colon is a field with no value.
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when {
            line.isEmpty() -> {
                if (event != null || hasData) {
                    if (!onEvent(ServerSentEvent(event, data.toString()))) return
                }
                event = null; data.clear(); hasData = false
            }
            line.startsWith(":") -> Unit
            field == "event" -> event = value.trim().ifEmpty { null }
            field == "data" -> {
                if (hasData) data.append('\n')
                data.append(value); hasData = true
                if (data.length > maxData) throw TeamCodeException(0, "An event of the stream exceeds the limit.", TeamCodeClient.MALFORMED_EVENT)
            }
        }
    }
}

internal const val MAX_SSE_LINE = 256 * 1024
internal const val MAX_SSE_DATA = 1024 * 1024
