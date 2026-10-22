package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*

internal fun Artifact.view(origin: String) = ArtifactView(id, "$origin/artifact/$id", title, description, icon, access.wire, ownerId, ownerName, currentVersion, storedBytes,
    relation, this.origin.kind, this.origin.runId, this.origin.sessionId, this.origin.epoch, publicFollowsLatest, publicVersion, createdAt, updatedAt, publishedAt, expiresAt)

internal fun ArtifactVersion.view() = ArtifactVersionView(artifactId, number, entry, digest, fileCount, totalBytes, createdBy, createdAt, baseVersion,
    files.map { ArtifactFileView(it.path, it.sha256, it.size, it.contentType, it.role) }, originSessionId, originEpoch)

private fun ArtifactSharing.view() = ArtifactMembersView(ownerId, members.map { ArtifactMemberView(it.memberId, it.username, it.role.wire, it.reason, it.addedBy, it.addedAt, it.originSessionId) })

/**
 * Publication and reading of artifacts for members, their agents and keys (`artifacts:read`, `artifacts:write`).
 * Making public and deleting stay interactive (the credential classifier sends every other route to that rule).
 */
internal fun Route.artifactRoutes(artifacts: ArtifactService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    route("/api/v1/artifacts") {
        get {
            val parameters = call.request.queryParameters
            val scope = parameters["scope"]?.let { ArtifactScope.parse(it) ?: throw TeamFailure("invalid_artifact_query", 400, "The scope is mine, shared, team or all.") } ?: ArtifactScope.TEAM
            val limit = parameters["limit"]?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_artifact_query", 400, "Use a numeric limit.") } ?: ArtifactRules.MAX_LIST
            val access = parameters["access"]?.let { ArtifactAccess.parse(it) ?: throw TeamFailure("invalid_artifact_query", 400, "The access is personal, team or public.") }
            val page = artifacts.list(call.principal, ArtifactQuery(scope, parameters["member"], parameters["session"], parameters["run"], limit, parameters["before"], parameters["origin"], access))
            call.respond(ArtifactListView(page.artifacts.map { it.view(origin) }, page.nextBefore))
        }
        get("/policy") {
            val policy = artifacts.policy(call.principal)
            call.respond(ArtifactPolicyView(policy.canPublish, policy.reason))
        }
        post("/uploads") {
            val input = call.jsonBody<CreateArtifactUploadRequest>(131_072)
            // `teamcode mcp artifacts` names the run it publishes from; the store checks the run is the caller's.
            val run = call.request.headers.getAll("x-teamcode-run-id")
            val origin = when {
                run.isNullOrEmpty() -> ArtifactOrigin()
                run.size == 1 && me.nathanfallet.teamcode.api.models.RunIdentifiers.isValid(run.single()) -> ArtifactOrigin("local_run", run.single())
                else -> throw TeamFailure("invalid_run", 400, "Name one canonical run UUID in x-teamcode-run-id.")
            }
            val upload = artifacts.startUpload(call.principal, input.artifactId, input.files.map { UploadFile(it.sha256, it.size) }, origin)
            call.respond(HttpStatusCode.Created, ArtifactUploadView(upload.uploadId, upload.artifactId, upload.missing, ArtifactRules.CHUNK_BYTES, upload.expiresAt))
        }
        put("/uploads/{upload}/blobs/{sha256}/{number}") {
            val number = call.parameters["number"]!!.toIntOrNull() ?: throw TeamFailure("invalid_artifact_chunk", 400, "The chunk number is a whole number.")
            val bytes = call.receiveChannel().readBounded(ArtifactRules.CHUNK_BYTES)
            val receipt = try { artifacts.putChunk(call.principal, call.parameters["upload"]!!, call.parameters["sha256"]!!, number, bytes) }
            catch (error: ArtifactSecretFailure) {
                return@put call.respond(HttpStatusCode.UnprocessableEntity, ArtifactSecretView(ErrorDetail("artifact_secret_detected",
                    "This file holds a secret (${error.kind}). Remove it from the file, then publish again."), error.sha256, error.kind))
            }
            call.respond(ArtifactChunkView(receipt.complete, receipt.received, receipt.total))
        }
        get("/{id}") { call.respond(artifacts.artifact(call.principal, call.parameters["id"]!!).view(origin)) }
        delete("/{id}") { artifacts.delete(call.principal, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
        put("/{id}/access") {
            val input = call.jsonBody<SetArtifactAccessRequest>(1_024)
            call.respond(artifacts.setAccess(call.principal, call.parameters["id"]!!, input.access, input.publicFollowsLatest, input.publicVersion).view(origin))
        }
        get("/{id}/versions") { call.respond(artifacts.versions(call.principal, call.parameters["id"]!!).map { it.view() }) }
        post("/{id}/versions") {
            val input = call.jsonBody<CommitArtifactVersionRequest>(524_288)
            val published = try {
                artifacts.commit(call.principal, call.parameters["id"]!!, input.uploadId, input.entry, input.files.mapValues { UploadFile(it.value.sha256, it.value.size) },
                    input.baseVersion, input.title, input.description, input.icon, input.access, input.capabilities)
            } catch (error: ArtifactConflictFailure) {
                return@post call.respond(HttpStatusCode.Conflict, ArtifactConflictView(ErrorDetail("artifact_conflict",
                    "The artifact changed since version ${input.baseVersion}: version ${error.current?.number ?: 0} is live. Merge it, then publish with it as the base version."), error.current?.view()))
            }
            call.respond(if (published.replayed) HttpStatusCode.OK else HttpStatusCode.Created,
                ArtifactPublishedView(published.artifact.view(origin), published.version.view(), published.replayed))
        }
        get("/{id}/versions/{number}") {
            val number = call.parameters["number"]!!.toIntOrNull() ?: throw TeamFailure("not_found", 404, "Version not found.")
            call.respond(artifacts.version(call.principal, call.parameters["id"]!!, number).view())
        }
        get("/{id}/versions/{number}/file") {
            val number = call.parameters["number"]!!.toIntOrNull() ?: throw TeamFailure("not_found", 404, "Version not found.")
            val path = call.request.queryParameters["path"] ?: throw TeamFailure("not_found", 404, "Name the file with ?path=.")
            val (file, bytes) = artifacts.file(call.principal, call.parameters["id"]!!, number, path)
            // Never rendered by a browser on the server's origin: the content is served as a download, whatever it is.
            call.response.header(HttpHeaders.ContentDisposition, "attachment")
            call.response.header("X-TeamCode-Sha256", file.sha256)
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        get("/{id}/members") { call.respond(artifacts.members(call.principal, call.parameters["id"]!!).view()) }
        post("/{id}/members") {
            val input = call.jsonBody<ShareArtifactRequest>(1_024)
            call.respond(artifacts.share(call.principal, call.parameters["id"]!!, input.member, input.role).view())
        }
        delete("/{id}/members/{member}") { call.respond(artifacts.unshare(call.principal, call.parameters["id"]!!, call.parameters["member"]!!).view()) }
    }
}
