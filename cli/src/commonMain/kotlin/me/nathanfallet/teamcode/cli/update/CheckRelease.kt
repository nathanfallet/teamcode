package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.api.models.ReleaseVersion
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.client.ReleaseClient
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata

internal data class ReleaseCheck(val currentVersion: String, val release: VerifiedRelease, val metadata: DownloadedReleaseMetadata) {
    val updateAvailable = ReleaseVersion.parse(release.manifest.version) > ReleaseVersion.parse(currentVersion)
}

/** A release API response is only a locator; even an explicit version must match signed metadata. */
internal class CheckRelease(
    private val transport: ReleaseClient,
    private val verifier: VerifyReleaseManifest = VerifyReleaseManifest(),
    private val currentVersion: String,
) {
    suspend operator fun invoke(version: String? = null): ReleaseCheck? {
        ReleaseVersion.parse(currentVersion)
        val candidate = version ?: transport.latestVersion() ?: return null
        val metadata = transport.metadata(candidate)
        val verified = verifier.verify(metadata.manifest, metadata.signature)
        if (verified.manifest.version != candidate) throw CliOperationException("release_version_mismatch")
        return ReleaseCheck(currentVersion, verified, metadata)
    }
}
