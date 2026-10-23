package me.nathanfallet.teamcode.cli.artifacts

/**
 * Creates [path] (its parent must exist) readable by the current user only, or checks that an existing one is a real
 * directory of this user, never a link. False when it cannot be made private.
 */
internal expect fun ensurePrivateDirectory(path: String): Boolean

/** Flushes standard output, where an MCP stdio server writes one JSON-RPC message per line. */
internal expect fun flushStandardOutput()
