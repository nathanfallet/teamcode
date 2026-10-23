@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.interop.process.*

private class PosixDetachedProcess(private val pid: Int) : DetachedProcess {
    private var exit: Int? = null
    override fun exitCode(): Int? {
        exit?.let { return it }
        memScoped {
            val code = alloc<IntVar>()
            when (teamcode_poll(pid, code.ptr)) {
                1 -> exit = code.value
                -1 -> exit = -1
            }
        }
        return exit
    }
}

actual fun startDetachedProcess(command: String, arguments: List<String>, log: String): DetachedProcess = memScoped {
    fun vector(strings: List<String>): CPointer<CPointerVar<ByteVar>> {
        require(strings.none { '\u0000' in it })
        return allocArray<CPointerVar<ByteVar>>(strings.size + 1).also { array ->
            strings.forEachIndexed { i, text -> array[i] = text.cstr.ptr }
            array[strings.size] = null
        }
    }
    val pid = alloc<IntVar>()
    val result = teamcode_spawn_detached(command, vector(listOf(command) + arguments), teamcode_environment(), log, pid.ptr)
    if (result != 0) throw CliOperationException("detached_spawn_$result")
    PosixDetachedProcess(pid.value)
}
