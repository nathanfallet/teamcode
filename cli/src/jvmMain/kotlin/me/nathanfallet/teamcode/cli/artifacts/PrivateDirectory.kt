package me.nathanfallet.teamcode.cli.artifacts

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/** As on POSIX: created `0700`, or an existing real directory of this user, made `0700`; never a link or another user's directory. */
internal actual fun ensurePrivateDirectory(path: String): Boolean = try {
    val directory = Paths.get(path)
    val posix = directory.fileSystem.supportedFileAttributeViews().contains("posix")
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (posix) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createDirectory(directory)
    }
    when {
        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) -> false
        posix && Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).name != System.getProperty("user.name") -> false
        else -> { if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); true }
    }
} catch (_: java.io.IOException) { false } catch (_: RuntimeException) { false }

internal actual fun flushStandardOutput() = System.out.flush()
