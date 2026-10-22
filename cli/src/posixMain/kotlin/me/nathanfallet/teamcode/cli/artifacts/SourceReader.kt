@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.process.teamcode_open_nofollow
import platform.posix.close
import platform.posix.read

internal actual fun readWithoutLinks(root: String, components: List<String>, maxBytes: Long): ByteArray = memScoped {
    val failed = alloc<IntVar>()
    val size = alloc<LongVar>()
    val names = allocArrayOf(components.map { it.cstr.ptr })
    val fd = teamcode_open_nofollow(root, names, components.size, failed.ptr, size.ptr)
    if (fd < 0) throw SourceReadFailure(when (fd) {
        -1 -> SourceReadFailure.Kind.ROOT; -2 -> SourceReadFailure.Kind.LINK; -3 -> SourceReadFailure.Kind.MISSING
        -4 -> SourceReadFailure.Kind.NOT_REGULAR; -5 -> SourceReadFailure.Kind.DENIED; else -> SourceReadFailure.Kind.OTHER
    }, failed.value)
    try {
        if (size.value > maxBytes) throw SourceReadFailure(SourceReadFailure.Kind.TOO_LARGE, components.size - 1)
        // Bounded by the size seen after opening plus one byte: a file that grows meanwhile is refused, not truncated.
        val buffer = ByteArray((size.value + 1).toInt())
        var total = 0
        buffer.usePinned { pinned ->
            while (total < buffer.size) {
                val count = read(fd, pinned.addressOf(total), (buffer.size - total).convert()).toInt()
                if (count < 0) throw SourceReadFailure(SourceReadFailure.Kind.OTHER, components.size - 1)
                if (count == 0) break
                total += count
            }
        }
        if (total.toLong() != size.value) throw SourceReadFailure(SourceReadFailure.Kind.OTHER, components.size - 1)
        buffer.copyOf(total)
    } finally { close(fd) }
}
