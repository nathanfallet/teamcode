package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*

/** The skill catalog and the delivery of skills to members. */
internal fun ApiCatalog.skillOperations() {
    get("/api/v1/skills") {
        operationId = "listSkills"; tag(ApiTags.SKILLS)
        summary = "List the skill catalog"
        description = "Every skill with its pinned (approved) version. Administrators only."
        responses { ok<List<SkillView>>("The skills.") }
    }
    get("/api/v1/skills/{name}/versions") {
        operationId = "listSkillVersions"; tag(ApiTags.SKILLS)
        summary = "A skill and its versions"
        description = "Every published version with its digest, size, publisher and whether it is pinned or retired. Administrators only."
        parameters { skillName() }
        responses {
            ok<SkillDetailView>("The skill.")
            notFound("No such skill (`not_found`).")
        }
    }
    post("/api/v1/skills/{name}/versions") {
        operationId = "publishSkillVersion"; tag(ApiTags.SKILLS)
        summary = "Publish a skill version"
        description = "Uploads an immutable version (1 to 200 files, at most 5 MiB, `SKILL.md` at the root; the body is " +
            "bounded at 8 MiB). The first publication creates the skill. Publishing never pins: an administrator " +
            "approves the version with `PUT /api/v1/skills/{name}/pin`. Republishing the same content is safe (200, " +
            "`created: false`). A `skills:publish` key must be owned by an administrator, and cannot pin, assign or read the catalog."
        parameters { skillName() }
        body<PublishSkillVersionRequest>("The version label and its files, base64-encoded (`mode` is `file` or `executable`).")
        responses {
            ok<PublishSkillVersionResponse>("The version already existed with the same content (`created: false`).")
            ok<PublishSkillVersionResponse>("Published.", HttpStatusCode.Created)
            badRequest("An invalid name, version, path, mode or base64 content (`invalid_skill_artifact`).")
            conflict("The version exists with other content (`skill_version_conflict`) or is retired (`skill_version_retired`), " +
                "the skill is retired (`skill_retired`), or a catalog limit is reached (`skill_limit`, `skill_version_limit`).")
            error(HttpStatusCode.PayloadTooLarge, "The body exceeds 8 MiB (`payload_too_large`).")
        }
    }
    put("/api/v1/skills/{name}/pin") {
        operationId = "pinSkillVersion"; tag(ApiTags.SKILLS)
        summary = "Approve a skill version"
        description = "Pins the version every allowed member receives. Updating and rolling back are both a pin. Administrators only."
        parameters { skillName() }
        body<PinSkillRequest>("The version to pin.")
        responses {
            ok<SkillView>("The skill with its new pin.")
            notFound("No such skill or version (`not_found`).")
            conflict("The skill or the version is retired (`skill_retired`, `skill_version_retired`).")
        }
    }
    delete("/api/v1/skills/{name}/pin") {
        operationId = "unpinSkill"; tag(ApiTags.SKILLS)
        summary = "Stop delivering a skill"
        description = "Removes the pin: members receive no version until another is pinned. Administrators only."
        parameters { skillName() }
        responses {
            ok<SkillView>("The skill, unpinned.")
            notFound("No such skill (`not_found`).")
        }
    }
    delete("/api/v1/skills/{name}") {
        operationId = "retireSkill"; tag(ApiTags.SKILLS)
        summary = "Retire a skill"
        description = "Removes the skill from the catalog and from every member's delivery. Its versions are kept; " +
            "`POST /api/v1/skills/{name}/restore` brings it back. Administrators only."
        parameters { skillName() }
        responses {
            ok<SkillView>("The retired skill.")
            notFound("No such skill (`not_found`).")
        }
    }
    post("/api/v1/skills/{name}/restore") {
        operationId = "restoreSkill"; tag(ApiTags.SKILLS)
        summary = "Restore a retired skill"
        description = "Administrators only."
        parameters { skillName() }
        responses {
            ok<SkillView>("The restored skill.")
            notFound("No such skill (`not_found`).")
        }
    }
    delete("/api/v1/skills/{name}/versions/{version}") {
        operationId = "retireSkillVersion"; tag(ApiTags.SKILLS)
        summary = "Retire a skill version"
        description = "A retired version can no longer be pinned or republished. Administrators only."
        parameters {
            skillName()
            pathId("version", "The version label.")
        }
        responses {
            ok<SkillVersionView>("The retired version.")
            notFound("No such version (`not_found`).")
            conflict("The version is pinned: pin another one first (`skill_version_pinned`).")
        }
    }
    get("/api/v1/skills/{name}/assignments") {
        operationId = "listSkillAssignments"; tag(ApiTags.SKILLS)
        summary = "Who may receive a skill"
        description = "The explicit decisions per member: `allow` delivers, `deny` or no decision delivers nothing. Administrators only."
        parameters { skillName() }
        responses {
            ok<List<SkillAssignmentView>>("The decisions.")
            notFound("No such skill (`not_found`).")
        }
    }
    put("/api/v1/skills/{name}/assignments/{memberId}") {
        operationId = "setSkillAssignment"; tag(ApiTags.SKILLS)
        summary = "Allow or deny a skill to a member"
        description = "Administrators only."
        parameters {
            skillName()
            pathId("memberId", "The member's identifier.")
        }
        body<SetSkillAssignmentRequest>("`allow` or `deny`.")
        responses {
            ok<SkillAssignmentView>("The decision.")
            badRequest("The decision is not `allow` or `deny` (`invalid_skill_assignment`).")
            notFound("No such skill or member (`not_found`).")
        }
    }
    delete("/api/v1/skills/{name}/assignments/{memberId}") {
        operationId = "clearSkillAssignment"; tag(ApiTags.SKILLS)
        summary = "Remove a member's decision"
        description = "Without a decision, the member receives nothing. Administrators only."
        parameters {
            skillName()
            pathId("memberId", "The member's identifier.")
        }
        responses {
            empty("Removed.")
            notFound("No such skill or member (`not_found`).")
        }
    }
    get("/api/v1/skill-delivery") {
        operationId = "getSkillManifest"; tag(ApiTags.SKILL_DELIVERY)
        summary = "The skills the caller may install"
        description = "The pinned version of every skill allowed to the caller, with its digest: the CLI compares it " +
            "with what is installed."
        responses { ok<SkillManifestView>("The manifest.") }
    }
    get("/api/v1/members/{memberId}/skill-delivery") {
        operationId = "getMemberSkillManifest"; tag(ApiTags.SKILL_DELIVERY)
        summary = "The skills a member may install"
        description = "The same manifest as `GET /api/v1/skill-delivery`, for a member. Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            ok<SkillManifestView>("The manifest.")
            notFound("No such member (`not_found`).")
        }
    }
    get("/api/v1/skill-delivery/{name}/{version}") {
        operationId = "downloadSkill"; tag(ApiTags.SKILL_DELIVERY)
        summary = "Download a skill version"
        description = "The files of the pinned version, base64-encoded, with each file's SHA-256 and the version's " +
            "digest (recomputable from the files). Only the version currently delivered to the caller."
        parameters {
            skillName()
            pathId("version", "The version label from the manifest.")
        }
        responses {
            ok<SkillArtifactView>("The files.")
            notFound("The version is not delivered to the caller (`not_found`).")
        }
    }
    put("/api/v1/skill-delivery/installations") {
        operationId = "reportSkillInstallations"; tag(ApiTags.SKILL_DELIVERY)
        summary = "Report installations on this device"
        description = "The CLI reports, for the device it runs on, each skill's status: `installed`, `failed`, " +
            "`conflict` or `removed`. A report never grants or revokes access."
        body<ReportSkillInstallationsRequest>("One entry per skill (at most one each).")
        responses {
            empty("Recorded.")
            badRequest("A duplicated skill, an unknown version or status (`invalid_skill_installation`).")
            conflict("The caller is not signed in from a registered CLI device (`device_required`).")
        }
    }
    get("/api/v1/skill-delivery/installations") {
        operationId = "listSkillInstallations"; tag(ApiTags.SKILL_DELIVERY)
        summary = "The caller's reported installations"
        description = "What each of the caller's devices last reported."
        responses { ok<List<SkillInstallationView>>("The installations.") }
    }
    get("/api/v1/members/{memberId}/skill-installations") {
        operationId = "listMemberSkillInstallations"; tag(ApiTags.SKILL_DELIVERY)
        summary = "A member's reported installations"
        description = "Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            ok<List<SkillInstallationView>>("The installations.")
            notFound("No such member (`not_found`).")
        }
    }
}

/** Variants, their assignments, and the MCP gateway. */
internal fun ApiCatalog.variantOperations() {
    get("/api/v1/variants") {
        operationId = "listVariants"; tag(ApiTags.VARIANTS)
        summary = "List the variants"
        description = "Environment (`ENV`) and MCP variants: a named value or MCP server for one target (an " +
            "environment variable or an MCP server name). Secrets are never returned, only `hasSharedSecret`. Administrators only."
        responses { ok<List<VariantView>>("The variants.") }
    }
    post("/api/v1/variants") {
        operationId = "createVariant"; tag(ApiTags.VARIANTS)
        summary = "Create a variant"
        description = "`binding` is `SHARED` (one secret for everyone) or `PER_MEMBER` (each member's own secret at " +
            "assignment). An MCP variant needs its `endpoint`, which cannot change later. Administrators only."
        body<CreateVariantRequest>("The variant.")
        responses {
            ok<VariantView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid kind, binding, target, endpoint or secret (`invalid_variant`).")
            conflict("A variant of this name exists for the target (`variant_exists`), or the limit is reached (`variant_limit`).")
        }
    }
    patch("/api/v1/variants/{id}") {
        operationId = "updateVariant"; tag(ApiTags.VARIANTS)
        summary = "Change a variant"
        description = "Replaces the name and configuration. A null `secret` keeps the stored one unless `clearSecret`. " +
            "`expectedVersion` must be the current version. Administrators only."
        parameters { pathId("id", "The variant's identifier.") }
        body<UpdateVariantRequest>("The new name, configuration and optional secret.")
        responses {
            ok<VariantView>("The updated variant.")
            badRequest("Invalid configuration or secret (`invalid_variant`).")
            notFound("No such variant (`not_found`).")
            conflict("The variant changed since it was read (`variant_conflict`).")
        }
    }
    delete("/api/v1/variants/{id}") {
        operationId = "deleteVariant"; tag(ApiTags.VARIANTS)
        summary = "Delete a variant"
        description = "Removes the variant and its assignments. Administrators only."
        parameters { pathId("id", "The variant's identifier.") }
        responses {
            empty("Deleted.")
            notFound("No such variant (`not_found`).")
        }
    }
    post("/api/v1/variants/{id}/bulk-assign") {
        operationId = "bulkAssignVariant"; tag(ApiTags.VARIANTS)
        summary = "Assign a shared variant to everyone"
        description = "Assigns the variant to every member of its target, except members with a direct assignment " +
            "(kept) or an exclusion. Shared variants only. Administrators only."
        parameters { pathId("id", "The variant's identifier.") }
        responses {
            ok<BulkAssignmentResponse>("How many members were assigned, kept or excluded.")
            badRequest("A per-member variant cannot be assigned in bulk (`invalid_variant`).")
            notFound("No such variant (`not_found`).")
        }
    }
    get("/api/v1/variant-assignments") {
        operationId = "listVariantAssignments"; tag(ApiTags.VARIANTS)
        summary = "Every member's variants"
        description = "For each member, the effective variant per target, where it comes from (`DIRECT`, `GROUP` or " +
            "`BULK`), and their exclusions. Administrators only."
        responses { ok<List<MemberVariantAssignmentsView>>("The assignments.") }
    }
    get("/api/v1/members/{memberId}/variants") {
        operationId = "getMemberVariants"; tag(ApiTags.VARIANTS)
        summary = "A member's variants"
        description = "Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        responses {
            ok<MemberVariantAssignmentsView>("The member's assignments.")
            notFound("No such member (`not_found`).")
        }
    }
    put("/api/v1/members/{memberId}/variants") {
        operationId = "assignVariant"; tag(ApiTags.VARIANTS)
        summary = "Assign a variant to a member"
        description = "Replaces the member's variant for the variant's target. A `PER_MEMBER` variant needs `memberSecret`. Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        body<AssignVariantRequest>("The variant and, for a per-member variant, the member's secret.")
        responses {
            ok<VariantAssignmentView>("The assignment.")
            badRequest("A missing or refused member secret (`invalid_variant`).")
            notFound("No such member or variant (`not_found`).")
            conflict("The member is excluded from this target (`variant_excluded`).")
        }
    }
    delete("/api/v1/members/{memberId}/variants/{kind}/{target}") {
        operationId = "unassignVariant"; tag(ApiTags.VARIANTS)
        summary = "Remove a member's direct variant"
        description = "Administrators only."
        parameters { variantTarget() }
        responses {
            empty("Removed.")
            notFound("No such member or assignment (`not_found`).")
        }
    }
    put("/api/v1/members/{memberId}/variant-exclusions") {
        operationId = "excludeVariantTarget"; tag(ApiTags.VARIANTS)
        summary = "Exclude a member from a target"
        description = "The member receives no variant for the target, not even a bulk or group one. Administrators only."
        parameters { pathId("memberId", "The member's identifier.") }
        body<VariantTargetView>("The kind (`ENV` or `MCP`) and the target.")
        responses {
            empty("Excluded.")
            notFound("No such member (`not_found`).")
        }
    }
    delete("/api/v1/members/{memberId}/variant-exclusions/{kind}/{target}") {
        operationId = "includeVariantTarget"; tag(ApiTags.VARIANTS)
        summary = "Remove a member's exclusion"
        description = "Administrators only."
        parameters { variantTarget() }
        responses {
            empty("Removed.")
            notFound("No such member or exclusion (`not_found`).")
        }
    }
    get("/api/v1/variants/{id}/tool-policy") {
        operationId = "getToolPolicy"; tag(ApiTags.MCP)
        summary = "An MCP variant's tool policy"
        description = "`ALL` exposes every upstream tool, `ALLOWLIST` only `tools`. Administrators only."
        parameters { pathId("id", "The MCP variant's identifier.") }
        responses {
            ok<McpToolPolicyView>("The policy.")
            notFound("No such variant (`not_found`).")
        }
    }
    put("/api/v1/variants/{id}/tool-policy") {
        operationId = "setToolPolicy"; tag(ApiTags.MCP)
        summary = "Restrict an MCP variant's tools"
        description = "Administrators only."
        parameters { pathId("id", "The MCP variant's identifier.") }
        body<McpToolPolicyView>("The mode and, for `ALLOWLIST`, the tool names.")
        responses {
            ok<McpToolPolicyView>("The saved policy.")
            badRequest("An invalid mode or tool list, or a variant that is not MCP (`invalid_tool_policy`).")
            notFound("No such variant (`not_found`).")
        }
    }
    get("/api/v1/mcp/servers") {
        operationId = "listMcpServers"; tag(ApiTags.MCP)
        summary = "The caller's MCP servers"
        description = "The MCP servers assigned to the caller and the gateway path of each (`/mcp/<server>`). No " +
            "upstream address or credential."
        responses { ok<List<McpServerView>>("The servers.") }
    }
    post("/mcp/{server}") {
        operationId = "callMcpServer"; tag(ApiTags.MCP)
        summary = "Send an MCP message"
        description = "One JSON-RPC 2.0 message per request (Streamable HTTP, stateless, no `Mcp-Session-Id`), " +
            "relayed to the assigned upstream server with the caller's own credential. Supports `initialize`, `ping`, " +
            "`tools/list` (only the allowed tools) and `tools/call`. Errors are JSON-RPC errors. A browser `Origin` " +
            "must be the server's own."
        parameters {
            pathId("server", "The MCP server name, from `GET /api/v1/mcp/servers`.")
            headerText("MCP-Protocol-Version", "The MCP protocol version, if the client sends one.")
        }
        body<JsonObject>("One JSON-RPC request or notification; batches are refused.")
        responses {
            ok<JsonObject>("The JSON-RPC response.")
            empty("A notification was accepted.", HttpStatusCode.Accepted)
            error(HttpStatusCode.BadRequest, "Invalid JSON or JSON-RPC message (JSON-RPC error `-32700` or `-32600`).")
            error(HttpStatusCode.NotFound, "The server is not assigned to the caller (JSON-RPC error `-32600`).")
            error(HttpStatusCode.UnsupportedMediaType, "The body is not `application/json`.")
            error(HttpStatusCode.TooManyRequests, "A per-member or per-server rate or concurrency limit (`mcp_member_rate`, `mcp_server_rate`, …).")
        }
    }
    get("/api/v1/mcp/usage") {
        operationId = "getMcpUsage"; tag(ApiTags.MCP)
        summary = "MCP gateway usage"
        description = "Calls, lists, errors, refusals, bytes and time per member and server over the last `days`. " +
            "Metadata only: never arguments or results. A member sees their own usage; administrators see the team's."
        parameters { queryNumber("days", "The window, 1 to 30 days (7 by default).") }
        responses {
            ok<List<McpUsageView>>("The usage.")
            badRequest("An invalid window (`invalid_window`).")
        }
    }
    get("/api/v1/mcp/calls") {
        operationId = "listMcpCalls"; tag(ApiTags.MCP)
        summary = "The MCP call journal"
        description = "Gateway operations, newest first: never arguments, results or credentials. Page with `before` " +
            "= `nextBeforeId`. Administrators only."
        parameters { queryNumber("before", "The `nextBeforeId` of the previous page.") }
        responses {
            ok<McpCallPageView>("A page of calls.")
            badRequest("`before` is not one positive call identifier (`invalid_cursor`).")
        }
    }
}

private fun io.ktor.openapi.Parameters.Builder.skillName() =
    pathId("name", "The skill's name: lowercase letters, digits and hyphens, at most 64 characters.")

private fun io.ktor.openapi.Parameters.Builder.variantTarget() {
    pathId("memberId", "The member's identifier.")
    pathId("kind", "`ENV` or `MCP`.")
    pathId("target", "The environment variable or MCP server name.")
}
