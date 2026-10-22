package me.nathanfallet.teamcode.cli.artifacts

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

internal actual fun ensurePrivateDirectory(path: String): Boolean = try {
    val directory = Paths.get(path)
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) false
    else {
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) } catch (_: UnsupportedOperationException) { }
        true
    }
} catch (_: java.io.IOException) { false }

internal actual fun flushStandardOutput() = System.out.flush()
