package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

private const val ADMIN_TEXT = "Interactive administrators only; no service key reaches these routes."

/** The artifact bucket and settings (administrators). */
internal fun ApiCatalog.artifactOperations() {
    get("/api/v1/artifacts/storage") {
        operationId = "getArtifactStorage"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Read the artifact storage"
        description = "The S3-compatible bucket artifacts are stored in, without its secret access key. `verifiedAt` is the last passed connection test of this version; `inUse` is true once artifacts exist. $ADMIN_TEXT"
        responses { ok<ArtifactStorageView>("The storage, or `configured: false`.") }
    }
    put("/api/v1/artifacts/storage") {
        operationId = "saveArtifactStorage"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Save the artifact storage"
        description = "An `https` endpoint without path, a region, a bucket, an optional prefix, path-style addressing, the access key id and the secret access key " +
            "(omit it to keep the stored one; required when the endpoint or the bucket changes). The secret is encrypted and never returned. " +
            "Saving clears `verifiedAt`, so artifacts stop until the connection test passes again. $ADMIN_TEXT"
        body<SaveArtifactStorageRequest>("The storage and the version it replaces (0 for the first save).")
        responses {
            ok<ArtifactStorageView>("Saved.")
            badRequest("Invalid fields (`invalid_artifact_storage`), a link-local or metadata endpoint (`artifact_storage_host_not_allowed`), or a missing secret (`artifact_storage_secret_required`).")
            conflict("The storage changed meanwhile (`artifact_storage_conflict`), or artifacts exist and the endpoint, bucket or prefix would change (`artifact_storage_in_use`).")
        }
    }
    delete("/api/v1/artifacts/storage") {
        operationId = "deleteArtifactStorage"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Remove the artifact storage"
        description = "Removes the bucket configuration and its secret, and turns artifacts off. Refused while artifacts exist. $ADMIN_TEXT"
        responses {
            empty("Removed.")
            notFound("No storage is configured (`artifact_storage_missing`).")
            conflict("Artifacts exist (`artifact_storage_in_use`).")
        }
    }
    post("/api/v1/artifacts/storage/test") {
        operationId = "testArtifactStorage"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Test the artifact storage"
        description = "Writes, reads back (SHA-256 compared), lists and deletes one probe object under `<prefix>/<team>/`. Each step reports its HTTP status and S3 error code " +
            "(`AccessDenied`, `NoSuchBucket`, ...) or a short local code (`unreachable`, `timeout`, `dns`, `host_not_allowed`, `digest_mismatch`), never a response body. " +
            "A pass sets `verifiedAt`; a failure clears it. $ADMIN_TEXT"
        responses {
            ok<ArtifactStorageTestView>("The result of each step.")
            notFound("No storage is configured (`artifact_storage_missing`).")
        }
    }
    get("/api/v1/artifacts/settings") {
        operationId = "getArtifactSettings"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Read the artifact settings"
        description = "The switch, who may publish, who may make artifacts public, quotas and retention, and whether artifacts are active now (`active`, `inactiveReason`). $ADMIN_TEXT"
        responses { ok<ArtifactSettingsView>("The settings.") }
    }
    put("/api/v1/artifacts/settings") {
        operationId = "saveArtifactSettings"; tag(ApiTags.ARTIFACTS_ADMIN)
        summary = "Save the artifact settings"
        description = "Every field is required. Turning artifacts on needs a stored bucket whose current version passed the connection test. $ADMIN_TEXT"
        body<SaveArtifactSettingsRequest>("The settings and the version they replace (0 before the first save).")
        responses {
            ok<ArtifactSettingsView>("Saved.")
            badRequest("Out-of-range quotas or retention, or an unknown choice (`invalid_artifact_settings`).")
            conflict("The settings changed meanwhile (`artifact_settings_conflict`), or no storage is configured (`artifact_storage_missing`) or tested (`artifact_storage_unverified`).")
        }
    }
}

private const val INACTIVE = "While artifacts are not active, every call answers `409` with the reason as the code: `artifact_storage_missing`, `artifact_storage_unverified` or `artifacts_disabled`."
private const val UNKNOWN = "No such artifact, or one the caller cannot read (`not_found`): a personal artifact's existence is not revealed."

/** Publication and reading of artifacts (members, their agents and keys). */
internal fun ApiCatalog.artifactMemberOperations() {
    get("/api/v1/artifacts") {
        operationId = "listArtifacts"; tag(ApiTags.ARTIFACTS)
        summary = "List artifacts"
        description = "Active artifacts, most recently changed first. `scope` is `mine` (owned), `shared` (invited), `team` (everything the caller can read: " +
            "owned, invited, team and public artifacts; the default) or `all` (administrators, or a key with `team:read`). $INACTIVE"
        parameters {
            queryText("scope", "`mine`, `shared`, `team` (default) or `all`.")
            queryText("member", "Only artifacts owned by this member id.")
            queryText("session", "Only artifacts published from this cloud session.")
            queryText("run", "Only artifacts published from this agent run.")
            queryNumber("limit", "1–50, default 50.")
            queryText("before", "The `nextBefore` of the previous page.")
        }
        responses { ok<ArtifactListView>("A page of artifacts.") }
    }
    post("/api/v1/artifacts/uploads") {
        operationId = "createArtifactUpload"; tag(ApiTags.ARTIFACTS)
        summary = "Start an upload"
        description = "Announces the distinct files (SHA-256 and size) of the version to publish; the answer lists the ones to send. Without `artifactId`, reserves a new " +
            "artifact (a draft deleted after a day if never committed). The upload is bound to the calling credential and expires after an hour. " +
            "Limits: 511 files and 256 MiB per version, 16 MiB per file, 255 files and 64 MiB to send. $INACTIVE"
        body<CreateArtifactUploadRequest>("The files and, to update an artifact, its id.")
        responses {
            ok<ArtifactUploadView>("The upload and the missing files.", HttpStatusCode.Created)
            notFound(UNKNOWN)
            conflict("Artifacts are not active (see above), the member owns the maximum number of artifacts (`artifact_quota_exceeded`), or a file is being cleaned up from the bucket (`artifact_busy`, retry after `Retry-After`).")
            error(HttpStatusCode.PayloadTooLarge, "A file, the version or what must be sent is too large (`artifact_file_too_large`, `artifact_version_too_large`, `artifact_publication_too_large`).")
            error(HttpStatusCode.TooManyRequests, "The member published the hourly maximum of versions (`artifact_rate_limited`, with `Retry-After`).")
        }
    }
    put("/api/v1/artifacts/uploads/{upload}/blobs/{sha256}/{number}") {
        operationId = "putArtifactChunk"; tag(ApiTags.ARTIFACTS)
        summary = "Send a chunk of a file"
        description = "The raw bytes (`application/octet-stream`) of chunk `number` (from 0) of an announced file, 1 MiB each except the last. After the last chunk " +
            "the file is checked (SHA-256 and size, 15 MiB for a binary file) and, for text, scanned for credentials, then stored in the artifact's folder. A chunk sent again is ignored."
        parameters {
            pathId("upload", "The upload id.")
            pathId("sha256", "The file's lowercase hexadecimal SHA-256.")
            pathId("number", "The chunk number, from 0.")
        }
        responses {
            ok<ArtifactChunkView>("The chunk was received; `complete` once the file is checked and stored.")
            notFound("No live upload of this credential has this id (`artifact_upload_not_found`).")
            error(HttpStatusCode.UnprocessableEntity, "The bytes do not match the announced hash (`artifact_blob_mismatch`), or a text file holds a credential " +
                "(`artifact_secret_detected`, with the file's `sha256` and the `kind` of credential, never its value).")
            error(HttpStatusCode.ServiceUnavailable, "The bucket did not accept the file (`artifact_storage_unavailable`): send the last chunk again.")
        }
    }
    get("/api/v1/artifacts/{id}") {
        operationId = "getArtifact"; tag(ApiTags.ARTIFACTS)
        summary = "Read an artifact"
        description = "Its details, current version and the caller's `relation` to it. `url` is the only link to share, whatever the access. $INACTIVE"
        parameters { pathId("id", "The artifact id.") }
        responses { ok<ArtifactView>("The artifact."); notFound(UNKNOWN) }
    }
    delete("/api/v1/artifacts/{id}") {
        operationId = "deleteArtifact"; tag(ApiTags.ARTIFACTS)
        summary = "Delete an artifact"
        description = "Irreversible: the artifact answers 404 at once and its folder is removed from the bucket, listed again and proven empty. The owner or an " +
            "administrator, signed in interactively: no service key and no agent can delete. Allowed while artifacts are off."
        parameters { pathId("id", "The artifact id.") }
        responses { empty("Deleted."); notFound(UNKNOWN) }
    }
    put("/api/v1/artifacts/{id}/access") {
        operationId = "setArtifactAccess"; tag(ApiTags.ARTIFACTS)
        summary = "Change who can see an artifact"
        description = "`personal`, `team` or `public`; a public link follows the latest version unless `publicVersion` pins one. The owner or an administrator, " +
            "signed in interactively. Making it public follows the team's `publicSharing`: the owner (default), administrators only, or nobody."
        parameters { pathId("id", "The artifact id.") }
        body<SetArtifactAccessRequest>("The access and, for public, the version policy.")
        responses {
            ok<ArtifactView>("The artifact.")
            notFound(UNKNOWN)
        }
    }
    get("/api/v1/artifacts/{id}/versions") {
        operationId = "listArtifactVersions"; tag(ApiTags.ARTIFACTS)
        summary = "List an artifact's versions"
        description = "The kept versions, newest first, without their files. $INACTIVE"
        parameters { pathId("id", "The artifact id.") }
        responses { ok<List<ArtifactVersionView>>("The versions."); notFound(UNKNOWN) }
    }
    post("/api/v1/artifacts/{id}/versions") {
        operationId = "commitArtifactVersion"; tag(ApiTags.ARTIFACTS)
        summary = "Publish a version"
        description = "Commits the files received in `uploadId` (and files the artifact already holds) as version `baseVersion + 1`. The owner and invited editors " +
            "publish; `access` may only be `personal` or `team`, set by the owner. The same commit replayed by the same credential within 10 minutes returns the same " +
            "version (`200`, `replayed: true`). Versions beyond the team's retention are pruned. $INACTIVE"
        parameters { pathId("id", "The artifact id returned by the upload.") }
        body<CommitArtifactVersionRequest>("The manifest, the entry page and the base version.")
        responses {
            ok<ArtifactPublishedView>("Published.", HttpStatusCode.Created)
            notFound(UNKNOWN + " Or the upload expired (`artifact_upload_not_found`).")
            conflict("The base version is not the live one (`artifact_conflict`, with the live version as `current`), a file was not received (`artifact_blob_missing`), " +
                "or a storage quota would be exceeded (`artifact_quota_exceeded`).")
            error(HttpStatusCode.TooManyRequests, "The member published the hourly maximum of versions (`artifact_rate_limited`, with `Retry-After`).")
        }
    }
    get("/api/v1/artifacts/{id}/versions/{number}") {
        operationId = "getArtifactVersion"; tag(ApiTags.ARTIFACTS)
        summary = "Read a version's manifest"
        description = "The version and its files. An administrator's read of another member's personal artifact is audited (`artifact.admin_viewed`). $INACTIVE"
        parameters { pathId("id", "The artifact id."); pathId("number", "The version number.") }
        responses { ok<ArtifactVersionView>("The version."); notFound(UNKNOWN) }
    }
    get("/api/v1/artifacts/{id}/versions/{number}/file") {
        operationId = "getArtifactFile"; tag(ApiTags.ARTIFACTS)
        summary = "Download a file of a version"
        description = "The raw bytes, always as an `application/octet-stream` attachment (pages are viewed at the artifact's URL, never here), with its SHA-256 in " +
            "`X-TeamCode-Sha256`. $INACTIVE"
        parameters { pathId("id", "The artifact id."); pathId("number", "The version number."); queryText("path", "The published path of the file.") }
        responses {
            empty("The file's bytes.", HttpStatusCode.OK)
            notFound(UNKNOWN)
            error(HttpStatusCode.ServiceUnavailable, "The bucket did not answer (`artifact_storage_unavailable`).")
        }
    }
    get("/api/v1/artifacts/{id}/members") {
        operationId = "listArtifactMembers"; tag(ApiTags.ARTIFACTS)
        summary = "List an artifact's invited members"
        description = "The owner and the members invited as `viewer` or `editor`, with why (`invited` or `session_transfer`). $INACTIVE"
        parameters { pathId("id", "The artifact id.") }
        responses { ok<ArtifactMembersView>("The members."); notFound(UNKNOWN) }
    }
    post("/api/v1/artifacts/{id}/members") {
        operationId = "shareArtifact"; tag(ApiTags.ARTIFACTS)
        summary = "Invite a member"
        description = "Invites an active member (by id, username or linked GitHub login) as `viewer` or `editor`, or changes their role. The owner and invited " +
            "editors, or their agents with `artifacts:write`. Inviting never changes the access level. $INACTIVE"
        parameters { pathId("id", "The artifact id.") }
        body<ShareArtifactRequest>("The member and the role.")
        responses { ok<ArtifactMembersView>("The members."); notFound(UNKNOWN + " Or no active member matches (`artifact_member_not_found`).") }
    }
    delete("/api/v1/artifacts/{id}/members/{member}") {
        operationId = "unshareArtifact"; tag(ApiTags.ARTIFACTS)
        summary = "Remove an invited member"
        description = "The owner and invited editors. $INACTIVE"
        parameters { pathId("id", "The artifact id."); pathId("member", "The member id, username or linked GitHub login.") }
        responses { ok<ArtifactMembersView>("The members."); notFound(UNKNOWN + " Or the member is not invited (`artifact_member_not_found`).") }
    }
}
