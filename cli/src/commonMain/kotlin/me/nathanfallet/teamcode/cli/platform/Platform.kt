package me.nathanfallet.teamcode.cli.platform


expect fun environment(name: String): String?
expect fun platformArguments(arguments: Array<String>): Array<String>
expect fun writePrivateFile(path: String, content: String)
/** Atomically publish a complete private file only if the destination does not exist. */
expect fun createPrivateFile(path: String, content: String): Boolean

expect fun secureRandomBytes(count: Int): ByteArray

/** Read at most 64 KiB from redirected stdin, requiring EOF within one second. Never read a terminal. */
internal expect fun readHookInput(): ByteArray?

expect suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean = false): Int

data class ServicePlatform(val os: String, val executable: String, val userId: String, val userName: String? = null, val userHome: String? = null)
expect fun servicePlatform(): ServicePlatform
expect fun installServiceSignalHandlers()
expect fun serviceStopRequested(): Boolean
