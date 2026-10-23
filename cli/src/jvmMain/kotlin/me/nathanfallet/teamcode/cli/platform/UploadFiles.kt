package me.nathanfallet.teamcode.cli.platform

import java.io.OutputStream
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val PERMISSION_BITS = listOf(
    PosixFilePermission.OWNER_READ to 0x100, PosixFilePermission.OWNER_WRITE to 0x80, PosixFilePermission.OWNER_EXECUTE to 0x40,
    PosixFilePermission.GROUP_READ to 0x20, PosixFilePermission.GROUP_WRITE to 0x10, PosixFilePermission.GROUP_EXECUTE to 0x8,
    PosixFilePermission.OTHERS_READ to 0x4, PosixFilePermission.OTHERS_WRITE to 0x2, PosixFilePermission.OTHERS_EXECUTE to 0x1,
)
private fun permissions(mode: Int) = PERMISSION_BITS.filter { (_, bit) -> mode and bit != 0 }.map { it.first }.toSet()
private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

actual fun pathStatus(path: String): PathStatus? {
    val file = Paths.get(path)
    return try {
        if (posix) {
            val attributes = Files.readAttributes(file, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val mode = PERMISSION_BITS.filter { it.first in attributes.permissions() }.sumOf { it.second }
            PathStatus(attributes.isDirectory, attributes.isSymbolicLink, attributes.owner().name == System.getProperty("user.name"), mode)
        } else {
            val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            PathStatus(attributes.isDirectory, attributes.isSymbolicLink, true, 0)
        }
    } catch (_: NoSuchFileException) { null }
}

actual fun createModeDirectory(path: String, mode: Int): Boolean = try {
    if (posix) Files.createDirectory(Paths.get(path), PosixFilePermissions.asFileAttribute(permissions(mode))) else Files.createDirectory(Paths.get(path))
    true
} catch (_: FileAlreadyExistsException) { false }

actual fun setPathMode(path: String, mode: Int) {
    if (posix) Files.setPosixFilePermissions(Paths.get(path), permissions(mode))
}

actual fun createExclusiveFile(path: String, mode: Int): ExclusiveFile? {
    val file = Paths.get(path)
    try {
        if (posix) Files.createFile(file, PosixFilePermissions.asFileAttribute(permissions(mode))) else Files.createFile(file)
    } catch (_: FileAlreadyExistsException) { return null }
    if (posix) Files.setPosixFilePermissions(file, permissions(mode))
    val stream: OutputStream = Files.newOutputStream(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    return object : ExclusiveFile {
        override fun write(bytes: ByteArray, count: Int) = stream.write(bytes, 0, count)
        override fun close() = stream.close()
    }
}
