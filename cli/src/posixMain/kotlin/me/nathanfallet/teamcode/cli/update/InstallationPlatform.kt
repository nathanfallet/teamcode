@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package me.nathanfallet.teamcode.cli.update

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.interop.process.*
import platform.posix.execv
import platform.posix.setenv
import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform

internal actual fun acquireInstallationLock(path: String, exclusive: Boolean): InstallationLock? = memScoped {
    val descriptor = alloc<IntVar>()
    when (teamcode_install_lock(path, if (exclusive) 1 else 0, descriptor.ptr)) {
        0 -> null
        1 -> object : InstallationLock {
            private var value = descriptor.value
            override fun close() { if (value >= 0) { teamcode_install_unlock(value); value = -1 } }
        }
        else -> throw CliOperationException("release_lock_failed")
    }
}
internal actual fun sealInstallationExecutable(path: String) {
    if (teamcode_install_seal(path) != 0) throw CliOperationException("release_executable_permissions")
}
internal actual fun flushInstallationDirectory(path: String) {
    if (teamcode_install_sync_directory(path) != 0) throw CliOperationException("release_sync_failed")
}
internal actual fun validatePrivateInstallationPath(path: String, directory: Boolean) {
    if (teamcode_install_private_path(path, if (directory) 1 else 0) != 1) throw CliOperationException("release_private_path_invalid")
}
internal actual fun installationTarget(): String =
    (if (Platform.osFamily == OsFamily.MACOSX) "macos" else "linux") + "-" +
        when (Platform.cpuArchitecture) { CpuArchitecture.ARM64 -> "arm64"; CpuArchitecture.X64 -> "x64"; else -> error("Unsupported release target.") }

internal actual suspend fun executeInstalledPayload(path: String, arguments: List<String>, root: String): Int = memScoped {
    require((listOf(path, root) + arguments).none { '\u0000' in it })
    if (setenv("TEAMCODE_INSTALL_ROOT", root, 1) != 0) throw CliOperationException("release_launcher_failed")
    val values = listOf(path) + arguments
    val vector = allocArray<CPointerVar<ByteVar>>(values.size + 1)
    values.forEachIndexed { index, value -> vector[index] = value.cstr.ptr }
    vector[values.size] = null
    execv(path, vector)
    throw CliOperationException("release_launcher_failed")
}
internal actual fun readWindowsUserPath(): UserPathValue? = error("The Windows user PATH exists only on Windows.")
internal actual fun writeWindowsUserPath(value: UserPathValue): Unit = error("The Windows user PATH exists only on Windows.")
