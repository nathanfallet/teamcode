@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.cli.interop.process.*

actual fun platformArguments(arguments: Array<String>): Array<String> = arguments

actual suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean): Int {
    val child = memScoped {
        val values = mutableMapOf<String, String>()
        val inherited = teamcode_environment()!!
        var index = 0
        while (true) {
            val entry = inherited[index++]?.toKString() ?: break
            val separator = entry.indexOf('=')
            if (separator > 0) values[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        overrides.forEach { (name, value) -> if (value == null) values.remove(name) else values[name] = value }
        fun vector(strings: List<String>): CPointer<CPointerVar<ByteVar>> {
            require(strings.none { '\u0000' in it })
            return allocArray<CPointerVar<ByteVar>>(strings.size + 1).also { array ->
                strings.forEachIndexed { i, text -> array[i] = text.cstr.ptr }
                array[strings.size] = null
            }
        }
        val pid = alloc<IntVar>()
        val result = teamcode_spawn(command, vector(listOf(command) + arguments), vector(values.map { "${it.key}=${it.value}" }), pid.ptr, if (quiet) 1 else 0)
        if (result != 0) throw ProcessStartException(ProcessStartException.Reason.NotFound, command)
        pid.value
    }
    var reaped = false
    fun poll(): Int? = memScoped {
        val code = alloc<IntVar>()
        when (teamcode_poll(child, code.ptr)) {
            0 -> null
            1 -> { reaped = true; code.value }
            else -> { reaped = true; error("Cannot wait for the agent process.") }
        }
    }
    try {
        while (true) { poll()?.let { return it }; delay(50) }
    } finally {
        if (!reaped) withContext(NonCancellable) {
            teamcode_terminate(child, 0)
            repeat(100) { if (!reaped) { poll(); if (!reaped) delay(50) } }
            if (!reaped) {
                teamcode_terminate(child, 1)
                repeat(100) { if (!reaped) { poll(); if (!reaped) delay(10) } }
            }
        }
    }
}
