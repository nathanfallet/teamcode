package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

/** View tickets for apps that show an artifact in their own web view (`artifacts.tickets.v1`). */
internal fun ApiCatalog.artifactTicketOperations() {
    post("/api/v1/artifacts/{id}/tickets") {
        operationId = "issueArtifactTicket"; tag(ApiTags.ARTIFACTS)
        summary = "Get a view ticket for an app"
        description = "The member ticket the viewer page mints, for one version (the current one when `version` is omitted): `contentUrl` serves its entry page " +
            "and the files beside it, with no credential, until `expiresAt` (at most 8 hours) or until the member loses access or signs out. Interactive logins only. " +
            "An administrator's ticket for another member's personal artifact is audited (`artifact.admin_viewed`)."
        parameters { pathId("id", "The artifact id.") }
        body<IssueArtifactTicketRequest>("The version, or nothing for the current one.")
        responses {
            ok<ArtifactTicketView>("The ticket.")
            notFound("No such artifact or version, one the caller cannot read, or artifacts are not active (`not_found`).")
            error(HttpStatusCode.TooManyRequests, "More than 60 tickets in a minute for this login session (`artifact_ticket_rate_limited`, with `Retry-After`).")
        }
    }
}
