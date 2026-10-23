package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

/** The team hook catalog and the delivery of hooks to members. */
internal fun ApiCatalog.hookOperations() {
    get("/api/v1/hooks") {
        operationId = "listHooks"; tag(ApiTags.HOOKS)
        summary = "List the team hooks"
        description = "Every hook with its pinned (approved) version. Administrators only."
        responses { ok<List<HookView>>("The hooks.") }
    }
    get("/api/v1/hooks/{name}/versions") {
        operationId = "listHookVersions"; tag(ApiTags.HOOKS)
        summary = "A hook and its versions"
        description = "Every published version with its definition, digest, size and publisher, and whether it is pinned. Administrators only."
        parameters { hookName() }
        responses {
            ok<HookDetailView>("The hook.")
            notFound("No such hook (`not_found`).")
        }
    }
    post("/api/v1/hooks/{name}/versions") {
        operationId = "publishHookVersion"; tag(ApiTags.HOOKS)
        summary = "Publish a hook version"
        description = "Uploads an immutable version: its definition (events, optional matcher, the path of the " +
            "executable file to run, timeout) and 1 to 50 files, at most 1 MiB (the body is bounded at 2 MiB). The " +
            "first publication creates the hook. Publication never delivers: an interactive administrator pins each " +
            "version. With `allMembers: true`, the hook will be delivered to every member without an explicit decision " +
            "once pinned, members who join later included; a key may ask for it only while the hook is not pinned, so " +
            "the administrator's pin approves the audience with the code (servers advertising " +
            "`team.hooks.all-members.v1` support it); `false` leaves the audience as it is. Republishing the same " +
            "content is safe (200, `created: false`). A `hooks:publish` key must be owned by an administrator; it " +
            "cannot pin, assign or read the catalog."
        parameters { hookName() }
        body<PublishHookVersionRequest>("The version label, its definition and its files, base64-encoded (`mode` is `file` or `executable`).")
        responses {
            ok<PublishHookVersionResponse>("The version already existed with the same content (`created: false`).")
            ok<PublishHookVersionResponse>("Published.", HttpStatusCode.Created)
            badRequest("An invalid name, version, definition, path, mode or base64 content (`invalid_hook_definition`, `invalid_hook_artifact`).")
            error(HttpStatusCode.Forbidden, "A key asking for `allMembers` on a pinned hook (`hook_audience_requires_administrator`).")
            conflict("The version exists with other content (`hook_version_conflict`), or a catalog limit is reached (`hook_limit`, `hook_version_limit`).")
            error(HttpStatusCode.PayloadTooLarge, "The content exceeds 1 MiB (`hook_artifact_too_large`) or the body 2 MiB (`payload_too_large`).")
        }
    }
    get("/api/v1/hooks/{name}/versions/{version}") {
        operationId = "readHookVersion"; tag(ApiTags.HOOKS)
        summary = "Read a hook version"
        description = "Any published version's definition and files, verified against its digest, so the code can be " +
            "read before it is pinned. Administrators only."
        parameters {
            hookName()
            pathId("version", "The version label.")
        }
        responses {
            ok<HookArtifactView>("The definition and files.")
            notFound("No such hook or version (`not_found`).")
        }
    }
    put("/api/v1/hooks/{name}/pin") {
        operationId = "pinHookVersion"; tag(ApiTags.HOOKS)
        summary = "Approve a hook version"
        description = "Pins the version every allowed member receives. Updating and rolling back are both a pin. " +
            "Interactive administrators only: a hook runs code on members' computers, so no service key can pin one."
        parameters { hookName() }
        body<PinHookRequest>("The version to pin.")
        responses {
            ok<HookView>("The hook with its new pin.")
            notFound("No such hook or version (`not_found`).")
        }
    }
    put("/api/v1/hooks/{name}/audience") {
        operationId = "setHookAudience"; tag(ApiTags.HOOKS)
        summary = "Deliver a hook to all members"
        description = "With `allMembers: true`, every member without an explicit decision receives the pinned version, " +
            "members who join later included; an explicit `deny` still excludes a member. With `false`, only members " +
            "set to `allow` receive it. Each member still approves each version on their computer. Interactive " +
            "administrators only."
        parameters { hookName() }
        body<SetHookAudienceRequest>("The audience.")
        responses {
            ok<HookView>("The hook with its audience.")
            notFound("No such hook (`not_found`).")
        }
    }
    delete("/api/v1/hooks/{name}/pin") {
        operationId = "unpinHook"; tag(ApiTags.HOOKS)
        summary = "Stop delivering a hook"
        description = "Removes the pin: members receive no version until another is pinned. Administrators only."
        parameters { hookName() }
        responses {
            ok<HookView>("The hook, unpinned.")
            notFound("No such hook (`not_found`).")
        }
    }
    get("/api/v1/hooks/{name}/assignments") {
        operationId = "listHookAssignments"; tag(ApiTags.HOOKS)
        summary = "Members' decisions for a hook"
        description = "The explicit `allow` or `deny` decisions. Only `allow` delivers. Administrators only."
        parameters { hookName() }
        responses {
            ok<List<HookAssignmentView>>("The decisions.")
            notFound("No such hook (`not_found`).")
        }
    }
    put("/api/v1/hooks/{name}/assignments/{memberId}") {
        operationId = "setHookAssignment"; tag(ApiTags.HOOKS)
        summary = "Set a member's decision"
        description = "`allow` delivers the pinned version to the member; `deny` delivers nothing. Administrators only."
        parameters {
            hookName()
            pathId("memberId", "The member's identifier.")
        }
        body<SetHookAssignmentRequest>("`allow` or `deny`.")
        responses {
            ok<HookAssignmentView>("The decision.")
            badRequest("The decision is not `allow` or `deny` (`invalid_hook_assignment`).")
            notFound("No such hook or member (`not_found`).")
        }
    }
    delete("/api/v1/hooks/{name}/assignments/{memberId}") {
        operationId = "clearHookAssignment"; tag(ApiTags.HOOKS)
        summary = "Remove a member's decision"
        description = "Without a decision, the member receives nothing. Administrators only."
        parameters {
            hookName()
            pathId("memberId", "The member's identifier.")
        }
        responses {
            empty("Removed.")
            notFound("No such hook or member (`not_found`).")
        }
    }
    get("/api/v1/hook-delivery") {
        operationId = "getHookManifest"; tag(ApiTags.HOOK_DELIVERY)
        summary = "The team hooks the caller may install"
        description = "The pinned version of every hook allowed to the caller, with its definition and digest."
        responses { ok<HookManifestView>("The manifest.") }
    }
    get("/api/v1/members/{memberId}/hook-delivery") {
        operationId = "getMemberHookManifest"; tag(ApiTags.HOOK_DELIVERY)
        summary = "The team hooks a member may install"
        description = "The same manifest as `GET /api/v1/hook-delivery`, for a member. Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            ok<HookManifestView>("The manifest.")
            notFound("No such member (`not_found`).")
        }
    }
    get("/api/v1/hook-delivery/{name}/{version}") {
        operationId = "downloadHook"; tag(ApiTags.HOOK_DELIVERY)
        summary = "Download a hook version"
        description = "The definition and files of the pinned version, base64-encoded, with each file's SHA-256 and " +
            "the version's digest (recomputable from the definition and the files). Only the version currently " +
            "delivered to the caller."
        parameters {
            hookName()
            pathId("version", "The version label from the manifest.")
        }
        responses {
            ok<HookArtifactView>("The definition and files.")
            notFound("The version is not delivered to the caller (`not_found`).")
        }
    }
}

private fun io.ktor.openapi.Parameters.Builder.hookName() =
    pathId("name", "The hook's name: lowercase letters, digits and hyphens, at most 64 characters.")
