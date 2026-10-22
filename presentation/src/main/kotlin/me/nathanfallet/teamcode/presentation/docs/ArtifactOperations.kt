package me.nathanfallet.teamcode.presentation.docs

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
