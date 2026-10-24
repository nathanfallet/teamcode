package me.nathanfallet.teamcode.cli.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/**
 * `teamcode update --check --json`: one line that TeamCode Desktop reads to show its update banner. Only the
 * authenticated manifest's values are reported; [latestVersion] is null when no stable release is published.
 */
@Serializable
internal data class ReleaseCheckReport(
    val currentVersion: String,
    val latestVersion: String?,
    val updateAvailable: Boolean,
    val sourceCommit: String? = null,
    val keyId: String? = null,
)

internal fun releaseCheckJson(currentVersion: String, check: ReleaseCheck?): String = Json.encodeToString(
    ReleaseCheckReport.serializer(),
    ReleaseCheckReport(currentVersion, check?.release?.manifest?.version, check?.updateAvailable == true, check?.release?.manifest?.sourceCommit, check?.release?.keyId),
)
