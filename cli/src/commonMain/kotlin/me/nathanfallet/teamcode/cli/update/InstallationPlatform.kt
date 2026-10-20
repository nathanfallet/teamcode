package me.nathanfallet.teamcode.cli.update

/** An OS-owned file lock, automatically released on process exit; lock files are never unlinked. */
internal interface InstallationLock : AutoCloseable
internal expect fun acquireInstallationLock(path: String, exclusive: Boolean): InstallationLock?
/** Flush and restrict a verified regular executable owned by the current user. */
internal expect fun sealInstallationExecutable(path: String)
internal expect fun flushInstallationDirectory(path: String)
/** Local execution attestations are trusted only inside current-user private regular files/directories. */
internal expect fun validatePrivateInstallationPath(path: String, directory: Boolean)
internal expect fun installationTarget(): String
/** POSIX replaces the launcher process; Windows waits for its exclusively owned child. */
internal expect suspend fun executeInstalledPayload(path: String, arguments: List<String>, root: String): Int
/** Windows only: the current user's PATH (HKCU\\Environment); absent value is null. Other targets never call these. */
internal expect fun readWindowsUserPath(): UserPathValue?
/** Persist the value and notify running applications of the environment change. */
internal expect fun writeWindowsUserPath(value: UserPathValue)
