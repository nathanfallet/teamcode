@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_read_nofollow

/** First read into a small buffer; a larger file is opened and checked again with a buffer of its own size. */
private const val FIRST_BUFFER = 64L * 1024

internal actual fun readWithoutLinks(root: String, components: List<String>, maxBytes: Long): ByteArray = memScoped {
    if (components.isEmpty() || components.size > 64) throw SourceReadFailure(SourceReadFailure.Kind.OTHER, -1)
    val base = root.trimEnd('\\', '/')
    val prefixes = components.runningReduce { a, b -> "$a\\$b" }.map { "$base\\$it" }
    val paths = allocArrayOf(prefixes.map { it.wcstr.ptr })
    val failed = alloc<IntVar>()
    val size = alloc<LongVar>()
    var capacity = minOf(maxBytes + 1, FIRST_BUFFER)
    repeat(3) {
        val buffer = ByteArray(capacity.toInt())
        val result = buffer.usePinned { pinned -> teamcode_windows_read_nofollow(paths, components.size, pinned.addressOf(0).reinterpret(), capacity, failed.ptr, size.ptr) }
        if (result == -7L) {
            if (size.value > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, components.size - 1)
            capacity = size.value + 1
            return@repeat
        }
        if (result < 0) throw SourceReadFailure(when (result) {
            -2L -> SourceReadFailure.Kind.LINK; -3L -> SourceReadFailure.Kind.MISSING; -4L -> SourceReadFailure.Kind.NOT_REGULAR
            -5L -> SourceReadFailure.Kind.DENIED; else -> SourceReadFailure.Kind.OTHER
        }, failed.value)
        if (result > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, components.size - 1)
        // More bytes than the size seen at opening: the file is growing; refused rather than truncated.
        if (result != size.value) throw SourceReadFailure(SourceReadFailure.Kind.OTHER, components.size - 1)
        return buffer.copyOf(result.toInt())
    }
    // The file kept growing past each buffer.
    throw SourceReadFailure(SourceReadFailure.Kind.OTHER, components.size - 1)
}
