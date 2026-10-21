package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.*

/** Discovery, status, inference and the request cancellation. */
internal fun ApiCatalog.serverOperations() {
    get("/api/v1/capabilities") {
        operationId = "getCapabilities"; tag(ApiTags.SERVER)
        summary = "The server's version and enabled features"
        description = "What this server supports: its version, its mode and the feature flags clients check before using " +
            "an optional area (for example `cloud.sessions.v1` or `team.skills.v1`)."
        responses { ok<Capabilities>("The capabilities.") }
    }
    get("/api/v1/status") {
        operationId = "getStatus"; tag(ApiTags.SERVER)
        summary = "Gateway activity"
        description = "Counts of active, queued and completed inference requests on the replica that answers."
        responses { ok<GatewayStatus>("The status.") }
    }
    post("/v1/messages") {
        operationId = "createMessage"; tag(ApiTags.INFERENCE)
        summary = "Anthropic Messages request"
        description = "An Anthropic Messages API request (`stream` must be `true`), relayed to one of the team's " +
            "subscription accounts after the member's policy, allowance and concurrency checks. The response is the " +
            "provider's server-sent event stream. Agents launched with `teamcode run` call it through the local proxy. " +
            "Errors use the Anthropic error format, not the management envelope."
        body<JsonObject>("A Messages request: `model`, `messages`, `stream: true` and the usual Anthropic fields.")
        parameters { headerText("x-teamcode-client-request-id", "An identifier of the request, used by `POST /api/v1/requests/{id}/cancel`.") }
        responses {
            HttpStatusCode.OK { description = "The streamed response (`text/event-stream`)." }
            protocolError(HttpStatusCode.BadRequest, "The request is not a streaming JSON Messages request (`invalid_request`).")
            protocolError(HttpStatusCode.PayloadTooLarge, "The body exceeds 2 MiB (`payload_too_large`).")
            protocolError(HttpStatusCode.TooManyRequests, "No allowance, concurrency or account is available now (`Retry-After` says when to retry).")
        }
    }
    post("/v1/responses") {
        operationId = "createResponse"; tag(ApiTags.INFERENCE)
        summary = "OpenAI Responses request"
        description = "An OpenAI Responses API request (`stream` must be `true`), relayed to one of the team's Codex " +
            "subscription accounts after the same checks as `/v1/messages`. The response is the provider's event stream. " +
            "Errors use the OpenAI error format, not the management envelope."
        body<JsonObject>("A Responses request: `model`, `input` (string or array), `stream: true` and the usual fields.")
        parameters { headerText("x-teamcode-client-request-id", "An identifier of the request, used by `POST /api/v1/requests/{id}/cancel`.") }
        responses {
            HttpStatusCode.OK { description = "The streamed response (`text/event-stream`)." }
            protocolError(HttpStatusCode.BadRequest, "The request is not a streaming JSON Responses request (`invalid_request`).")
            protocolError(HttpStatusCode.PayloadTooLarge, "The body exceeds 2 MiB (`payload_too_large`).")
            protocolError(HttpStatusCode.TooManyRequests, "No allowance, concurrency or account is available now (`Retry-After` says when to retry).")
        }
    }
    post("/api/v1/requests/{id}/cancel") {
        operationId = "cancelRequest"; tag(ApiTags.INFERENCE)
        summary = "Cancel an inference request"
        description = "Stops one of the caller's own inference requests, on whichever replica serves it."
        parameters { pathId("id", "The `x-teamcode-client-request-id` the request was sent with (a UUID).") }
        responses { empty("Cancelled, or already finished.") }
    }
}

/** Authentication, members, devices, service keys and the audit trail. */
internal fun ApiCatalog.teamOperations() {
    post("/api/v1/auth/login") {
        operationId = "login"; tag(ApiTags.AUTH)
        summary = "Sign in"
        description = "Exchanges a username and password for a session token. Send it as `Authorization: Bearer <accessToken>`. " +
            "A member with a temporary password must call `POST /api/v1/auth/password` before anything else."
        body<LoginRequest>("The credentials, and an optional device name and installation identifier for the Devices page.")
        responses {
            ok<LoginResponse>("Signed in.")
            error(HttpStatusCode.Unauthorized, "Unknown username, wrong password or a temporarily locked member (`invalid_credentials`).")
            error(HttpStatusCode.TooManyRequests, "Too many login attempts on this server (`login_rate_limit`): retry in a minute.")
        }
    }
    get("/api/v1/auth/me") {
        operationId = "getCurrentMember"; tag(ApiTags.AUTH)
        summary = "The signed-in member"
        description = "The member the credential acts for. For a service key, its owner."
        responses { ok<MemberView>("The member.") }
    }
    post("/api/v1/auth/logout") {
        operationId = "logout"; tag(ApiTags.AUTH)
        summary = "Sign out"
        description = "Revokes the credential used for this call. A service key revokes itself."
        responses { ok<SuccessResponse>("Revoked.") }
    }
    post("/api/v1/auth/password") {
        operationId = "changePassword"; tag(ApiTags.AUTH)
        summary = "Change the password"
        description = "Replaces the caller's password. Every existing session of the member is revoked: sign in again."
        body<PasswordRequest>("The current and the new password.")
        responses {
            ok<SuccessResponse>("Changed.")
            badRequest("The new password has fewer than 14 characters or equals the current one (`invalid_password`).")
            error(HttpStatusCode.Unauthorized, "The current password is wrong (`invalid_credentials`).")
        }
    }
    get("/api/v1/members") {
        operationId = "listMembers"; tag(ApiTags.MEMBERS)
        summary = "List the members"
        description = "Every member of the team, enabled or not. Administrators only."
        responses { ok<List<MemberView>>("The members.") }
    }
    post("/api/v1/members") {
        operationId = "createMember"; tag(ApiTags.MEMBERS)
        summary = "Create a member"
        description = "Creates a member with a one-time temporary password, returned once: share it privately. " +
            "Administrators only; ownership cannot be granted."
        body<CreateMemberRequest>("The username and the role (`MEMBER` or `ADMIN`).")
        responses {
            ok<CreatedMemberResponse>("Created.", HttpStatusCode.Created)
            badRequest("Invalid username (3 to 64 letters, digits, dots, underscores or hyphens: `invalid_username`) or role (`invalid_role`).")
            conflict("The username exists (`username_exists`), or the team has 1,000 members (`member_limit`).")
        }
    }
    patch("/api/v1/members/{id}") {
        operationId = "updateMember"; tag(ApiTags.MEMBERS)
        summary = "Change a member's role or state"
        description = "Sets the role and the disabled flag. Both revoke the member's sessions. The last ready owner " +
            "cannot be disabled or demoted. Administrators only."
        parameters { pathId("id", "The member's identifier.") }
        body<UpdateMemberRequest>("The new role and disabled flag.")
        responses {
            ok<MemberView>("The updated member.")
            notFound("No such member (`member_not_found`).")
            conflict("The last active administrator cannot be disabled or demoted (`last_admin`).")
        }
    }
    post("/api/v1/members/{id}/reset-password") {
        operationId = "resetMemberPassword"; tag(ApiTags.MEMBERS)
        summary = "Reset a member's password"
        description = "Gives the member a new temporary password, returned once, and revokes their sessions. " +
            "Administrators only; the last ready owner cannot be reset."
        parameters { pathId("id", "The member's identifier.") }
        responses {
            ok<CreatedMemberResponse>("The member and the temporary password.")
            notFound("No such member (`member_not_found`).")
        }
    }

    for ((prefix, whose) in listOf("" to "the caller's", "/members/{memberId}" to "a member's")) {
        val member = prefix.isNotEmpty()
        get("/api/v1$prefix/devices") {
            operationId = if (member) "listMemberDevices" else "listDevices"; tag(ApiTags.DEVICES)
            summary = "List $whose devices"
            description = "The CLI installations that signed in, with their active session count." +
                if (member) " Administrators only." else ""
            if (member) parameters { pathId("memberId", "The member's identifier.") }
            responses { ok<List<DeviceView>>("The devices.") }
        }
        get("/api/v1$prefix/auth/sessions") {
            operationId = if (member) "listMemberSessions" else "listSessions"; tag(ApiTags.DEVICES)
            summary = "List $whose login sessions"
            description = "The unexpired login sessions, with the device they belong to." + if (member) " Administrators only." else ""
            if (member) parameters { pathId("memberId", "The member's identifier.") }
            responses { ok<List<AuthSessionView>>("The sessions.") }
        }
        get("/api/v1$prefix/service-keys") {
            operationId = if (member) "listMemberServiceKeys" else "listServiceKeys"; tag(ApiTags.SERVICE_KEYS)
            summary = "List $whose service keys"
            description = "Key metadata only: a key's secret is shown once, at creation." + if (member) " Administrators only." else ""
            if (member) parameters { pathId("memberId", "The member's identifier.") }
            responses { ok<List<ServiceKeyView>>("The keys.") }
        }
        post("/api/v1$prefix/service-keys") {
            operationId = if (member) "createMemberServiceKey" else "createServiceKey"; tag(ApiTags.SERVICE_KEYS)
            summary = "Create a service key" + if (member) " for a member" else ""
            description = "Creates a key acting as its owner with only the chosen scopes, and returns its secret once " +
                "(`accessToken`). `validDays` is 1 to 90 (30 when omitted); an explicit `null` creates a key without " +
                "expiry, for administrators' keys only. `skills:publish` and `admin:cloud` need a key owned by an administrator." +
                if (member) " Administrators only; the member must be enabled and have replaced a temporary password." else ""
            if (member) parameters { pathId("memberId", "The member's identifier.") }
            body<CreateServiceKeyRequest>("The key's name, scopes and lifetime.")
            responses {
                ok<CreatedServiceKeyResponse>("Created: store `accessToken` now, it is never shown again.", HttpStatusCode.Created)
                badRequest("Invalid name, scopes or lifetime (`invalid_service_key`).")
                conflict("The member has too many keys: revoke an unused one (`service_key_limit`).")
            }
        }
    }
    patch("/api/v1/devices/{id}") {
        operationId = "renameDevice"; tag(ApiTags.DEVICES)
        summary = "Rename a device"
        description = "Changes the label shown for one of the caller's devices (administrators: any member's)."
        parameters { pathId("id", "The device's identifier.") }
        body<RenameDeviceRequest>("The new name.")
        responses {
            ok<DeviceView>("The renamed device.")
            notFound("No such device (`not_found`).")
        }
    }
    delete("/api/v1/devices/{id}") {
        operationId = "revokeDevice"; tag(ApiTags.DEVICES)
        summary = "Revoke a device"
        description = "Signs out every session of the device, on every replica."
        parameters { pathId("id", "The device's identifier.") }
        responses {
            empty("Revoked.")
            notFound("No such device (`not_found`).")
        }
    }
    delete("/api/v1/auth/sessions/{id}") {
        operationId = "revokeSession"; tag(ApiTags.DEVICES)
        summary = "Revoke a login session"
        description = "Signs out one login session of the caller (administrators: any member's)."
        parameters { pathId("id", "The session's identifier.") }
        responses {
            empty("Revoked.")
            notFound("No such session (`not_found`).")
        }
    }
    delete("/api/v1/service-keys/{id}") {
        operationId = "revokeServiceKey"; tag(ApiTags.SERVICE_KEYS)
        summary = "Revoke a service key"
        description = "Revokes one of the caller's keys (administrators: any member's). Calls with it fail at once."
        parameters { pathId("id", "The key's identifier.") }
        responses {
            empty("Revoked.")
            notFound("No such key (`not_found`).")
        }
    }
    get("/api/v1/audit") {
        operationId = "listAuditEvents"; tag(ApiTags.AUDIT)
        summary = "Read the audit trail"
        description = "Administrative actions, newest first, one page at a time: pass `nextBeforeId` as `before` for the " +
            "next page. Administrators only."
        parameters { queryNumber("before", "The `nextBeforeId` of the previous page.") }
        responses {
            ok<AuditPageView>("A page of events.")
            badRequest("`before` is not one positive event identifier (`invalid_cursor`).")
        }
    }
}
