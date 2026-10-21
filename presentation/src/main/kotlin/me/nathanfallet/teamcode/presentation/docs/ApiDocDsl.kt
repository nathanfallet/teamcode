package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.Parameters
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import me.nathanfallet.teamcode.api.models.ApiError
import me.nathanfallet.teamcode.domain.team.CredentialAction
import me.nathanfallet.teamcode.presentation.credentialAction

/**
 * The documentation of one operation, keyed like the routing tree formats it (`GET /api/v1/members/{id}`).
 *
 * @param describe Its summary, description, parameters, body and specific responses. Authentication and the errors
 * every operation shares are added by [describeWith] and [commonResponses].
 */
internal class ApiOperation(val method: HttpMethod, val path: String, val describe: Operation.Builder.() -> Unit) {
    val key get() = "${method.value} $path"
}

/** Collects [ApiOperation]s; one function per area of the API fills it. */
internal class ApiCatalog {
    val operations = mutableListOf<ApiOperation>()
    fun get(path: String, describe: Operation.Builder.() -> Unit) { operations += ApiOperation(HttpMethod.Get, path, describe) }
    fun post(path: String, describe: Operation.Builder.() -> Unit) { operations += ApiOperation(HttpMethod.Post, path, describe) }
    fun put(path: String, describe: Operation.Builder.() -> Unit) { operations += ApiOperation(HttpMethod.Put, path, describe) }
    fun patch(path: String, describe: Operation.Builder.() -> Unit) { operations += ApiOperation(HttpMethod.Patch, path, describe) }
    fun delete(path: String, describe: Operation.Builder.() -> Unit) { operations += ApiOperation(HttpMethod.Delete, path, describe) }
}

/** Every documented operation. A new route needs its entry here; `ApiDocsTest` fails otherwise. */
internal val apiOperations: List<ApiOperation> by lazy {
    ApiCatalog().apply {
        serverOperations()
        teamOperations()
        capacityOperations()
        skillOperations()
        variantOperations()
        cloudOperations()
        webhookOperations()
    }.operations
}

/** The tags of the document, in display order. */
internal object ApiTags {
    const val SERVER = "Server"
    const val AUTH = "Authentication"
    const val MEMBERS = "Members"
    const val DEVICES = "Devices and sessions"
    const val SERVICE_KEYS = "Service keys"
    const val AUDIT = "Audit"
    const val ACCOUNTS = "Subscription accounts"
    const val CAPACITY = "Capacity"
    const val BUDGET_OVERRIDES = "Budget overrides"
    const val USAGE = "Usage review"
    const val GROUPS = "Groups"
    const val RUNS = "Agent runs"
    const val GUIDANCE = "Guidance"
    const val FORECASTS = "Forecasts"
    const val POLICY = "Model policy and routes"
    const val INFERENCE = "Inference"
    const val SKILLS = "Skills"
    const val SKILL_DELIVERY = "Skill delivery"
    const val VARIANTS = "Variants"
    const val MCP = "MCP gateway"
    const val CLOUD_ADMIN = "Cloud administration"
    const val CLOUD_SESSIONS = "Cloud sessions"
    const val CLOUD_CONVERSATION = "Cloud conversation"
    const val CLOUD_TRANSFERS = "Cloud transfers"
    const val GITHUB = "GitHub"
    const val WEBHOOKS = "Webhooks"

    val descriptions = linkedMapOf(
        SERVER to "Server discovery and gateway status.",
        AUTH to "Interactive login, the current member, password changes and logout.",
        MEMBERS to "Team members and their roles (administrators).",
        DEVICES to "CLI installations and login sessions.",
        SERVICE_KEYS to "Scoped, revocable credentials for unattended jobs and CI.",
        AUDIT to "The administrator audit trail.",
        ACCOUNTS to "Provider subscription accounts (Claude, Codex) pooled by the team.",
        CAPACITY to "Daily allowances, concurrency, pool budgets and provider quota windows.",
        BUDGET_OVERRIDES to "Temporary, audited replacements of a member's daily allowance.",
        USAGE to "Requests whose usage is unknown, and their manual reconciliation.",
        GROUPS to "Groups with cumulative budgets and scoped managers.",
        RUNS to "The registry of launched agents (`teamcode run`) and their per-run policies.",
        GUIDANCE to "Pacing directives delivered to agents, and the team's pacing settings.",
        FORECASTS to "Observed quota history and forecasts per subscription account.",
        POLICY to "Allowed models and efforts, and model routes.",
        INFERENCE to "Model requests relayed to the team's subscriptions.",
        SKILLS to "The team's skill catalog: versions, pins and per-member assignments.",
        SKILL_DELIVERY to "Skills a member may install, their files and installation reports.",
        VARIANTS to "Environment and MCP variants and their assignment to members.",
        MCP to "The MCP gateway, its tool policies, usage and call journal.",
        CLOUD_ADMIN to "Cloud clusters, profiles, settings and the cloud access policy (administrators).",
        CLOUD_SESSIONS to "Agents running in the team's Kubernetes clusters.",
        CLOUD_CONVERSATION to "Transcripts, messages, interrupts and attachments of cloud sessions.",
        CLOUD_TRANSFERS to "Changes of owner of cloud sessions.",
        GITHUB to "The team's GitHub App and members' GitHub links.",
        WEBHOOKS to "Signed, retried HTTP deliveries of the team's event feed to your own services.",
    )
}

/**
 * Who may call an operation, read from the classifier the server enforces ([credentialAction]): the document cannot
 * drift from the actual authorization.
 *
 * @param public No credential needed.
 * @param keyScopes The alternative scopes a service key may hold to call it (an empty list: any key); `null` when a
 * service key can never call it.
 */
internal class ApiAccess(val public: Boolean, val keyScopes: List<String>?) {
    companion object {
        private const val SAMPLE_ID = "00000000-0000-4000-8000-000000000000"
        private val samples = mapOf("server" to "github", "name" to "example-skill", "version" to "1.0.0")

        fun of(method: HttpMethod, path: String, inferencePaths: Set<String>): ApiAccess {
            if (method == HttpMethod.Post && path == "/api/v1/auth/login") return ApiAccess(public = true, keyScopes = null)
            val sample = Regex("\\{([^}]+)}").replace(path) { samples[it.groupValues[1]] ?: SAMPLE_ID }
            val scopes = when (credentialAction(method.value, sample, inferencePaths)) {
                CredentialAction.DISCOVERY, CredentialAction.IDENTITY, CredentialAction.LOGOUT -> emptyList()
                CredentialAction.INFERENCE -> listOf("inference")
                CredentialAction.USAGE_READ -> listOf("usage:read", "team:read")
                CredentialAction.TEAM_READ -> listOf("team:read")
                CredentialAction.GUIDANCE -> listOf("guidance")
                CredentialAction.SKILLS_READ -> listOf("skills:read")
                CredentialAction.SKILLS_PUBLISH -> listOf("skills:publish")
                CredentialAction.MCP -> listOf("mcp")
                CredentialAction.CLOUD_READ -> listOf("cloud:read", "cloud:write", "admin:cloud", "team:read")
                CredentialAction.CLOUD_WRITE -> listOf("cloud:write", "admin:cloud")
                CredentialAction.INTERACTIVE -> null
            }
            return ApiAccess(public = false, keyScopes = scopes)
        }
    }

    val text: String get() = when {
        public -> "No authentication."
        keyScopes == null -> "An interactive login only: service keys are refused."
        keyScopes.isEmpty() -> "An interactive login or any service key."
        else -> "An interactive login, or a service key with " + keyScopes.joinToString(" or ") { "`$it`" } + "."
    }
}

/** The operation's own description followed by its authentication, and the matching security requirements. */
internal fun ApiOperation.describeWith(access: ApiAccess): Operation.Builder.() -> Unit = {
    describe()
    description = listOfNotNull(description?.takeIf { it.isNotBlank() }, "**Authentication:** ${access.text}").joinToString("\n\n")
    if (!access.public) security {
        requirement(SESSION_SCHEME)
        access.keyScopes?.let { scopes -> if (scopes.isEmpty()) requirement(SERVICE_KEY_SCHEME) else scopes.forEach { requirement(SERVICE_KEY_SCHEME, listOf(it)) } }
    }
    access.keyScopes?.takeIf { it.isNotEmpty() }?.let { extension("x-teamcode-scopes", it) }
}

/**
 * The errors every operation can answer: 400 for an invalid body, 401 and 403 for a protected operation, 500, and a
 * `default`. Where the operation describes one of these statuses itself, its text comes first and the shared case is
 * kept after it. The inference endpoints and the MCP gateway answer in their own protocol's error format, not
 * [ApiError].
 */
internal fun commonResponses(operation: ApiOperation, access: ApiAccess): Operation.Builder.() -> Unit {
    val own = Operation.build(configure = operation.describe)
    val declared = own.responses?.responses.orEmpty().mapValues { (_, response) -> (response as? ReferenceOr.Value)?.value?.description.orEmpty() }
    val protocol = !operation.path.startsWith("/api/")
    fun text(status: HttpStatusCode, shared: String) = declared[status.value]?.takeIf { it.isNotBlank() }?.let { "$it Also: $shared" }
        ?: shared.replaceFirstChar { it.uppercase() }
    return {
        responses {
            // The authentication filter answers 401 in the envelope everywhere; StatusPages turns the other
            // failures of an inference endpoint (not of the MCP gateway) into the provider's error format. An
            // unexpected exception has no handler: its 500 has no body to rely on.
            val inference = operation.path.startsWith("/v1/")
            fun shared(status: HttpStatusCode, text: String) =
                if (status != HttpStatusCode.Unauthorized && (inference || status == HttpStatusCode.InternalServerError))
                    protocolError(status, text(status, text))
                else error(status, text(status, text))
            if (own.requestBody != null && !protocol)
                shared(HttpStatusCode.BadRequest, "the body is not the expected JSON object (`invalid_body`), or one of its values is refused (`type` names the problem).")
            if (!access.public) {
                shared(HttpStatusCode.Unauthorized, "no credential, or an unknown, expired or revoked one (`authentication_required`).")
                shared(HttpStatusCode.Forbidden, forbiddenText(access))
            }
            if (500 !in declared) shared(HttpStatusCode.InternalServerError, "an unexpected server error, without a body to rely on. Retry later.")
            if (protocol) default { description = "Any other error, in the protocol's error format." }
            else default {
                description = "Any other error, in the same envelope."
                schema = jsonSchema<ApiError>()
            }
        }
    }
}

private fun forbiddenText(access: ApiAccess) = when (access.keyScopes) {
    null -> "a service key was used (`credential_scope`), the member's role does not allow the operation (`forbidden`), " +
        "or the member must first replace a temporary password (`password_change_required`)."
    emptyList<String>() -> "the member is disabled (`forbidden`) or must first replace a temporary password (`password_change_required`)."
    else -> "the service key lacks the scope (`credential_scope`), the member's role does not allow the operation " +
        "(`forbidden`), or the member must first replace a temporary password (`password_change_required`)."
}

/** A JSON request body. */
internal inline fun <reified T : Any> Operation.Builder.body(text: String, required: Boolean = true) {
    requestBody {
        description = text
        this.required = required
        schema = jsonSchema<T>()
    }
}

/** A success response with a JSON body. */
internal inline fun <reified T : Any> Responses.Builder.ok(text: String, status: HttpStatusCode = HttpStatusCode.OK) {
    status {
        description = text
        schema = jsonSchema<T>()
    }
}

/** A success response without a body. */
internal fun Responses.Builder.empty(text: String, status: HttpStatusCode = HttpStatusCode.NoContent) {
    status { description = text }
}

/** An error response in the `{"error": {"type", "message"}}` envelope. */
internal fun Responses.Builder.error(status: HttpStatusCode, text: String) {
    status {
        description = text
        schema = jsonSchema<ApiError>()
    }
}

/**
 * An error of the inference endpoints or the MCP gateway, whose body follows the protocol (Anthropic, OpenAI or
 * JSON-RPC errors), not [ApiError].
 */
internal fun Responses.Builder.protocolError(status: HttpStatusCode, text: String) {
    status { description = text }
}

internal fun Responses.Builder.badRequest(text: String) = error(HttpStatusCode.BadRequest, text)
internal fun Responses.Builder.notFound(text: String) = error(HttpStatusCode.NotFound, text)
internal fun Responses.Builder.conflict(text: String) = error(HttpStatusCode.Conflict, text)

/** A required path parameter. */
internal fun Parameters.Builder.pathId(name: String, text: String) {
    path(name) {
        description = text
        required = true
        schema = jsonSchema<String>()
    }
}

/** An optional string query parameter. */
internal fun Parameters.Builder.queryText(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<String>()
    }
}

/** An optional integer query parameter. */
internal fun Parameters.Builder.queryNumber(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<Long>()
    }
}

/** An optional boolean query parameter. */
internal fun Parameters.Builder.queryFlag(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<Boolean>()
    }
}

/** A repeatable string query parameter (`?state=a&state=b`). */
internal fun Parameters.Builder.queryList(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<List<String>>()
        explode = true
    }
}

/** An optional request header. */
internal fun Parameters.Builder.headerText(name: String, text: String, required: Boolean = false) {
    header(name) {
        description = text
        this.required = required
        schema = jsonSchema<String>()
    }
}
