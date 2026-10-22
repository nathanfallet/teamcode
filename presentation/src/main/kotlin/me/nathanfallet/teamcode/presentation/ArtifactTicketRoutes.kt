package me.nathanfallet.teamcode.presentation

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.ArtifactTicketView
import me.nathanfallet.teamcode.api.models.IssueArtifactTicketRequest
import me.nathanfallet.teamcode.domain.artifacts.ArtifactViewing
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * `POST /api/v1/artifacts/{id}/tickets` (`artifacts.tickets.v1`): a view ticket for an app that shows the artifact in
 * its own web view, with the viewer page's rights. Not listed for service keys, so interactive logins only.
 */
internal fun Route.artifactTicketRoutes(viewing: ArtifactViewing, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    post("/api/v1/artifacts/{id}/tickets") {
        val id = call.parameters["id"]!!
        // The body is optional whatever the framing (none, empty, chunked) or its content type: nothing means the current version.
        val raw = call.receiveChannel().readBounded(MAX_BODY_BYTES).decodeToString()
        val input = if (raw.isBlank()) IssueArtifactTicketRequest() else try {
            Serialization.json.decodeFromString<IssueArtifactTicketRequest>(raw)
        } catch (_: SerializationException) {
            throw TeamFailure("invalid_request", 400, "The body is {\"version\": number} or nothing.")
        } catch (_: IllegalArgumentException) {
            throw TeamFailure("invalid_request", 400, "The body is {\"version\": number} or nothing.")
        }
        val issued = viewing.ticket(call.principal, id, input.version)
        call.respond(ArtifactTicketView(id, issued.version, "$origin/artifact/$id/${issued.ticket}/", issued.expiresAt))
    }
}

private const val MAX_BODY_BYTES = 1_024
