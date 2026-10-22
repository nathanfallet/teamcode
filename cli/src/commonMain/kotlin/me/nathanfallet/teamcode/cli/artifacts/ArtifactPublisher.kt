package me.nathanfallet.teamcode.cli.artifacts

import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.client.ArtifactSecretException
import me.nathanfallet.teamcode.client.TeamCodeClient

/** What to publish: the page, other files, published paths to drop from the base version, and the details. */
class ArtifactPublication(
    val page: ArtifactSource,
    val files: List<ArtifactSource> = emptyList(),
    val removed: Set<String> = emptySet(),
    val artifactId: String? = null,
    /** The version these changes start from; null publishes over the live version. */
    val baseVersion: Int? = null,
    val title: String? = null, val description: String? = null, val icon: String? = null, val access: String? = null,
)

class ArtifactPublishResult(val published: ArtifactPublishedView, val filesUploaded: Int, val filesReused: Int)

/**
 * Publishes through the upload API: announce the version's distinct files, send only the missing ones in chunks,
 * then commit. Files of the base version that are neither replaced nor removed are kept without being sent again.
 * Shared by `teamcode artifacts publish` and `teamcode mcp artifacts`.
 */
class ArtifactPublisher(private val client: TeamCodeClient) {
    suspend fun publish(publication: ArtifactPublication): ArtifactPublishResult {
        val sources = listOf(publication.page) + publication.files
        sources.forEach { ArtifactLimits.requirePublishedPath(it.publishedPath) }
        if (!ArtifactLimits.validEntry(publication.page.publishedPath)) throw CliUserException("The page must be an .html, .htm or .md file.")
        val duplicate = sources.groupBy { it.publishedPath.lowercase() }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) throw CliUserException("${duplicate.first().publishedPath} is given twice.")
        val base = publication.artifactId?.let { id -> publication.baseVersion ?: client.artifact(id).currentVersion } ?: 0
        val kept = if (publication.artifactId != null && base > 0)
            client.artifactVersion(publication.artifactId, base).files.associate { it.path to ArtifactFileRef(it.sha256, it.size) } else emptyMap()
        publication.removed.firstOrNull { it !in kept }?.let { throw CliUserException("$it is not a file of version $base.") }
        val manifest = kept.filterKeys { path -> path !in publication.removed && sources.none { it.publishedPath.equals(path, ignoreCase = true) } } +
            sources.associate { it.publishedPath to ArtifactFileRef(it.sha256, it.size) }
        if (manifest.size > ArtifactLimits.MAX_VERSION_FILES || manifest.values.sumOf { it.size } > ArtifactLimits.MAX_VERSION_BYTES)
            throw CliUserException("A version is limited to ${ArtifactLimits.MAX_VERSION_FILES} files and 256 MiB.")
        val upload = client.startArtifactUpload(CreateArtifactUploadRequest(publication.artifactId, manifest.values.distinctBy { it.sha256 }))
        val bySha = sources.associateBy { it.sha256 }
        for (sha in upload.missing) {
            val source = bySha[sha] ?: throw CliUserException("The server asked for a file this publication does not hold. Publish again.")
            send(upload, source, sources)
        }
        val published = client.commitArtifactVersion(upload.artifactId, CommitArtifactVersionRequest(upload.uploadId, publication.page.publishedPath, manifest, base,
            // A new version keeps the artifact's title unless one is given; only a first version (a new artifact, or a
            // draft whose first publication failed) takes the page's.
            publication.title ?: if (base == 0) artifactTitle(publication.page) else null, publication.description, publication.icon, publication.access))
        return ArtifactPublishResult(published, upload.missing.size, manifest.values.map { it.sha256 }.distinct().size - upload.missing.size)
    }

    private suspend fun send(upload: ArtifactUploadView, source: ArtifactSource, sources: List<ArtifactSource>) {
        val chunk = upload.chunkBytes.coerceIn(1, 1024 * 1024)
        val count = if (source.bytes.isEmpty()) 1 else (source.bytes.size + chunk - 1) / chunk
        for (number in 0 until count) {
            val part = source.bytes.copyOfRange(number * chunk, minOf(source.bytes.size, (number + 1) * chunk))
            try { client.putArtifactChunk(upload.uploadId, source.sha256, number, part) }
            catch (error: ArtifactSecretException) {
                val paths = sources.filter { it.sha256 == error.sha256 }.joinToString(", ") { it.localPath }
                throw CliUserException("Refused $paths: it contains a credential (${error.kind}). Remove it from the file, then publish again.")
            }
        }
    }
}
