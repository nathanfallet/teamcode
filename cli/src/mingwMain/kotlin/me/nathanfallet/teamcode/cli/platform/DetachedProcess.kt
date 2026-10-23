@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.interop.windows.*
import platform.windows.GetLastError

private class WindowsDetachedProcess(private val handle: COpaquePointer) : DetachedProcess {
    private var exit: Int? = null
    override fun exitCode(): Int? {
        exit?.let { return it }
        memScoped {
            val code = alloc<UIntVar>()
            when (teamcode_windows_detached_poll(handle, code.ptr)) {
                1 -> exit = code.value.toInt()
                -1 -> exit = -1
            }
        }
        return exit
    }
}

actual fun startDetachedProcess(command: String, arguments: List<String>, log: String): DetachedProcess {
    val line = (listOf(command) + arguments).joinToString(" ", transform = ::quoteWindowsArgument)
    if (line.length >= 32_767) throw CliOperationException("detached_spawn_too_long")
    val handle = memScoped { teamcode_windows_spawn_detached(command.wcstr.ptr, line.wcstr.ptr, log.wcstr.ptr) }
        ?: throw CliOperationException("detached_spawn_${GetLastError()}")
    return WindowsDetachedProcess(handle)
}
