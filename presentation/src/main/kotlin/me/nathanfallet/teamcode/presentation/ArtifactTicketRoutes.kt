package me.nathanfallet.teamcode.presentation

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.ArtifactTicketView
import me.nathanfallet.teamcode.api.models.IssueArtifactTicketRequest
import me.nathanfallet.teamcode.domain.artifacts.ArtifactViewing

/**
 * `POST /api/v1/artifacts/{id}/tickets` (`artifacts.tickets.v1`): a view ticket for an app that shows the artifact in
 * its own web view, with the viewer page's rights. Not listed for service keys, so interactive logins only.
 */
internal fun Route.artifactTicketRoutes(viewing: ArtifactViewing, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    post("/api/v1/artifacts/{id}/tickets") {
        val id = call.parameters["id"]!!
        val input = if (call.request.contentLength() == 0L) IssueArtifactTicketRequest() else call.receive<IssueArtifactTicketRequest>()
        val issued = viewing.ticket(call.principal, id, input.version)
        call.respond(ArtifactTicketView(id, issued.version, "$origin/artifact/$id/${issued.ticket}/", issued.expiresAt))
    }
}
