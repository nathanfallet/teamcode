@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_read_nofollow

internal actual fun readWithoutLinks(root: String, components: List<String>, maxBytes: Long): ByteArray = memScoped {
    if (components.isEmpty() || components.size > 64) throw SourceReadFailure(SourceReadFailure.Kind.OTHER, -1)
    val base = root.trimEnd('\\', '/')
    val prefixes = components.runningReduce { a, b -> "$a\\$b" }.map { "$base\\$it" }
    val failed = alloc<IntVar>()
    val buffer = ByteArray((maxBytes + 1).toInt())
    val result = buffer.usePinned { pinned ->
        teamcode_windows_read_nofollow(allocArrayOf(prefixes.map { it.wcstr.ptr }), components.size, pinned.addressOf(0).reinterpret(), maxBytes + 1, failed.ptr)
    }
    if (result < 0) throw SourceReadFailure(when (result) {
        -2L -> SourceReadFailure.Kind.LINK; -3L -> SourceReadFailure.Kind.MISSING; -4L -> SourceReadFailure.Kind.NOT_REGULAR
        -5L -> SourceReadFailure.Kind.DENIED; else -> SourceReadFailure.Kind.OTHER
    }, failed.value)
    if (result > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, components.size - 1)
    buffer.copyOf(result.toInt())
}
