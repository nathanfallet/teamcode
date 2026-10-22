package me.nathanfallet.teamcode.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/** One dispatched server-sent event: its `event:` name (null when absent), its last `id:` and its joined `data:` lines. */
internal class ServerSentEvent(val event: String?, val id: String?, val data: String)

/**
 * Reads the SSE frames of [channel] and hands each dispatched event to [onEvent] until it returns `false`. Comment lines
 * (keep-alives) and `retry:` are skipped; a frame with neither a name nor data is not dispatched. Lines are bounded by
 * [maxLine] bytes and the whole data of one event by [maxData] characters. Throws [TeamCodeException] when the stream
 * closes or a bound is exceeded, so the caller reconnects from the last id it handled.
 */
internal suspend fun readServerSentEvents(
    channel: ByteReadChannel,
    maxLine: Int = MAX_SSE_LINE,
    maxData: Int = MAX_SSE_DATA,
    onEvent: suspend (ServerSentEvent) -> Boolean,
) {
    var event: String? = null
    var id: String? = null
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = channel.readUTF8Line(maxLine) ?: throw TeamCodeException(0, "The event stream closed.")
        val field = line.substringBefore(':')
        // "field: value" loses exactly one leading space after the colon; a line without a colon is a field with no value.
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when {
            line.isEmpty() -> {
                if (event != null || hasData) {
                    if (!onEvent(ServerSentEvent(event, id, data.toString()))) return
                }
                event = null; data.clear(); hasData = false
            }
            line.startsWith(":") -> Unit
            field == "event" -> event = value.trim()
            field == "id" -> id = value.trim()
            field == "data" -> {
                if (hasData) data.append('\n')
                data.append(value); hasData = true
                if (data.length > maxData) throw TeamCodeException(0, "An event of the stream exceeds the limit.")
            }
        }
    }
}

internal const val MAX_SSE_LINE = 256 * 1024
internal const val MAX_SSE_DATA = 1024 * 1024
