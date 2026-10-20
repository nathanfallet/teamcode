package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.cli.CliOperationException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

internal actual fun acquireInstallationLock(path: String, exclusive: Boolean): InstallationLock? {
    val file = Path.of(path)
    require(!Files.isSymbolicLink(file))
    val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        val lock = try { channel.tryLock(0, Long.MAX_VALUE, !exclusive) } catch (_: OverlappingFileLockException) { null }
        if (lock == null) { channel.close(); return null }
        return object : InstallationLock { override fun close() { if (channel.isOpen) { lock.release(); channel.close() } } }
    } catch (error: Exception) { channel.close(); throw error }
}
internal actual fun sealInstallationExecutable(path: String) {
    val file = Path.of(path)
    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file))
    FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
}
internal actual fun flushInstallationDirectory(path: String) {
    FileChannel.open(Path.of(path), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
}
internal actual fun validatePrivateInstallationPath(path: String, directory: Boolean) {
    val file = Path.of(path)
    if (Files.isSymbolicLink(file) || !(if (directory) Files.isDirectory(file) else Files.isRegularFile(file)))
        throw CliOperationException("release_private_path_invalid")
    val permissions = Files.getPosixFilePermissions(file)
    if (permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") } ||
        Files.getOwner(file).name != System.getProperty("user.name")) throw CliOperationException("release_private_path_invalid")
}
internal actual fun installationTarget(): String = error("Managed installation requires the native executable.")
internal actual suspend fun executeInstalledPayload(path: String, arguments: List<String>, root: String): Int =
    error("Managed installation requires the native executable.")
