@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.artifacts

import okio.FileSystem
import okio.Path.Companion.toPath

/** Under the user's profile, a new directory inherits the profile's ACL, which other users cannot read. */
internal actual fun ensurePrivateDirectory(path: String): Boolean = try {
    FileSystem.SYSTEM.createDirectories(path.toPath())
    FileSystem.SYSTEM.metadataOrNull(path.toPath())?.let { it.isDirectory && it.symlinkTarget == null } == true
} catch (_: okio.IOException) { false }

internal actual fun flushStandardOutput() { platform.posix.fflush(null) }
