package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket
import me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion

/** Artifacts of an account's server. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface ArtifactRepository {
    /** Newest first; [sessionId] keeps those one cloud session published; [before] is the previous page's cursor. */
    suspend fun artifacts(accountId: String, scope: ArtifactScope, sessionId: String?, before: String?): ArtifactPage
    suspend fun artifact(accountId: String, artifactId: String): Artifact
    /** Newest first. */
    suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion>
    /** A ticket for [version], the current one when null. */
    suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket
}
