package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Public release metadata. Authenticate exact manifest bytes before trusting these fields. */
@Serializable
data class ReleaseManifest(val schemaVersion: Int, val version: String, val tag: String, val sourceCommit: String, val assets: Map<String, ReleaseAsset>)
/** A canonical executable name, exact byte length and SHA-256 authenticated by its manifest. */
@Serializable
data class ReleaseAsset(val fileName: String, val sha256: String, val size: Long)

/** Shared release schema bounds; unknown future targets may be read but cannot be installed by older clients. */
object ReleaseFormat {
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_SIGNATURE_BYTES = 2048
    const val MAX_BINARY_BYTES = 256L * 1024 * 1024
    val targets = setOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64", "windows-x64")
    private const val NUMBER = "(?:0|[1-9][0-9]*)"
    private const val PART = "(?:$NUMBER|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    private val versionPattern = Regex("$NUMBER\\.$NUMBER\\.$NUMBER(?:-$PART(?:\\.$PART)*)?")
    /** Checks bounded SemVer without build metadata or ambiguous numeric identifiers. */
    fun validVersion(value: String) = value.length <= 80 && versionPattern.matches(value)
    /** Accepts bounded OS/CPU identifiers, including future schema-1 targets. */
    fun validTarget(value: String) = value.length <= 40 && Regex("[a-z][a-z0-9]*-[a-z0-9]+").matches(value)
    /** Derives the asset basename without accepting a path or a remote URL. */
    fun fileName(version: String, target: String): String {
        require(validVersion(version) && validTarget(target))
        return "teamcode-$version-$target" + if (target.startsWith("windows-")) ".exe" else ""
    }
}

/**
 * The desktop app's packages, published next to the CLI under the same version, and the signed manifest that lists them
 * (`desktop-manifest.json` and `desktop-manifest.sig`, same envelope and keys as the CLI's). Its `product` field is
 * `desktop`, so neither manifest can stand in for the other. Targets are the CLI's.
 */
object DesktopReleaseFormat {
    const val MANIFEST = "desktop-manifest.json"
    const val SIGNATURE = "desktop-manifest.sig"
    const val PRODUCT = "desktop"
    const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
    /** The install scripts of a release (`install.sh`, `install.ps1`) are a few kilobytes. */
    const val MAX_INSTALL_SCRIPT_BYTES = 256 * 1024

    /** The `.app` zipped with `ditto` on macOS, the per-user `.msi` on Windows, the app's folder as a `.tar.gz` on Linux. */
    fun fileName(version: String, target: String): String {
        require(ReleaseFormat.validVersion(version) && ReleaseFormat.validTarget(target))
        val extension = when {
            target.startsWith("macos-") -> "zip"
            target.startsWith("windows-") -> "msi"
            else -> "tar.gz"
        }
        return "teamcode-desktop-$version-$target.$extension"
    }
}
