package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.http.HttpMethod
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.Server
import io.ktor.openapi.Tag
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.openapi.plus
import io.ktor.server.routing.path
import io.ktor.server.routing.routingRoot
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.teamcode.presentation.MCP_PATH
import me.nathanfallet.teamcode.presentation.RUNNER_PATH

/** The OpenAPI document of the TeamCode HTTP API, generated from the server's routes when first requested. */
const val API_OPENAPI_PATH = "/api/openapi.json"

/** Swagger UI for [API_OPENAPI_PATH]. */
const val API_DOCS_PATH = "/api/docs"

/** The scheme of an interactive login: `POST /api/v1/auth/login`, `teamcode login` or the dashboard. */
internal const val SESSION_SCHEME = "session"

/** The scheme of a scoped service key, created from the dashboard, `teamcode keys create` or the API. */
internal const val SERVICE_KEY_SCHEME = "serviceKey"

/**
 * The version of `swagger-ui-dist` the page loads from unpkg, pinned with the Subresource Integrity hash of each file:
 * the browser refuses a modified file. A new version changes these three constants
 * (`curl -s <url> | openssl dgst -sha384 -binary | openssl base64 -A`).
 */
private const val SWAGGER_UI_VERSION = "5.17.14"
private const val SWAGGER_UI_CSS_SRI = "sha384-wxLW6kwyHktdDGr6Pv1zgm/VGJh99lfUbzSn6HNHBENZlCN7W602k9VkGdxuFvPn"
private const val SWAGGER_UI_JS_SRI = "sha384-wmyclcVGX/WhUkdkATwhaK1X1JtiNrr2EoYJ+diV3vj4v6OC5yCeSu+yW13SYJep"

private val documentJson = Json { encodeDefaults = false }
private val DocumentKey = AttributeKey<String>("TeamCodeOpenApiDocument")
/** Building the document annotates the routing tree: one build at a time, never twice for an application. */
private val documentLock = Any()

/**
 * Serves the OpenAPI document and Swagger UI. Both are public (they contain no team data) and are generated from the
 * routing tree, never committed: a route registered without an entry in [apiOperations] still appears, without a
 * description, and `ApiDocsTest` fails.
 */
internal fun Route.apiDocsRoutes(publicOrigin: String, inferencePaths: Set<String>) {
    get(API_OPENAPI_PATH) {
        call.respondText(call.application.apiOpenApiDocument(publicOrigin, inferencePaths), ContentType.Application.Json)
    }
    get(API_DOCS_PATH) {
        call.response.header("Content-Security-Policy", SWAGGER_UI_CSP)
        call.respondText(swaggerUiPage(API_OPENAPI_PATH), ContentType.Text.Html)
    }
}

/**
 * The document as served, built once per application: the routing tree no longer changes once the server runs.
 *
 * @param publicOrigin The server's public URL, declared as the document's only server.
 * @param inferencePaths The registered inference endpoints (`/v1/messages`, `/v1/responses`).
 */
internal fun Application.apiOpenApiDocument(publicOrigin: String, inferencePaths: Set<String>): String = synchronized(documentLock) {
    attributes.computeIfAbsent(DocumentKey) {
        val encoded = documentJson.encodeToJsonElement(OpenApiDoc.serializer(), apiOpenApiDoc(publicOrigin, inferencePaths))
        encoded.jsonObject.withSingleSchemaPerType().withoutUndescribedQueryParameters().toString()
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Application.apiOpenApiDoc(publicOrigin: String, inferencePaths: Set<String>): OpenApiDoc {
    val catalog = apiOperations.associateBy { it.key }
    for (route in routingRoot.getAllRoutes()) {
        val method = route.method() ?: continue
        val path = route.path(OpenApiRoutePathFormat)
        val operation = catalog["${method.value} $path"]
        when {
            operation != null -> {
                val access = ApiAccess.of(method, path, inferencePaths)
                route.describe(operation.describeWith(access))
                route.describe(commonResponses(operation, access))
            }
            // Undescribed but kept in the document: ApiDocsTest lists it as missing.
            isApiRoute(method, path, inferencePaths) -> Unit
            else -> route.hide()
        }
    }
    val base = OpenApiDoc(
        info = OpenApiInfo(
            title = "TeamCode API",
            // The API version, not the build: the server's version is behind authentication (`/api/v1/capabilities`).
            version = "1",
            description = API_DESCRIPTION,
            contact = OpenApiInfo.Contact(name = "TeamCode", url = "https://github.com/nathanfallet/teamcode"),
            license = OpenApiInfo.License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0"),
        ),
        servers = listOf(Server(url = publicOrigin.trimEnd('/'))),
        tags = ApiTags.descriptions.map { (name, text) -> Tag(name = name, description = text) },
    )
    val schemes = mapOf(
        SESSION_SCHEME to ReferenceOr.Value(HttpSecurityScheme(scheme = "bearer", description = SESSION_DESCRIPTION)),
        SERVICE_KEY_SCHEME to ReferenceOr.Value(HttpSecurityScheme(scheme = "bearer", description = SERVICE_KEY_DESCRIPTION)),
    )
    val generated = base + schemes + routingRoot.descendants()
    // A public operation says so (`security: []`) rather than leaving its security undefined.
    return generated.copy(paths = generated.paths.mapValues { (_, item) ->
        if (item !is ReferenceOr.Value) item else ReferenceOr.Value(item.value.withExplicitSecurity())
    })
}

private fun PathItem.withExplicitSecurity(): PathItem {
    fun Operation?.explicit() = this?.let { it.copy(security = it.security ?: emptyList()) }
    return copy(get = get.explicit(), put = put.explicit(), post = post.explicit(), delete = delete.explicit(),
        options = options.explicit(), head = head.explicit(), patch = patch.explicit(), trace = trace.explicit())
}

/** The documented surface: the management API, the inference endpoints and the MCP gateway; never the runner channel. */
internal fun isApiRoute(method: HttpMethod, path: String, inferencePaths: Set<String>): Boolean = when {
    path.startsWith(RUNNER_PATH) -> false
    path.startsWith("/api/v1/") -> true
    path in inferencePaths -> method == HttpMethod.Post
    path == "$MCP_PATH/{server}" -> method == HttpMethod.Post
    else -> false
}

internal fun Route.method(): HttpMethod? =
    lineage().mapNotNull { (it as? RoutingNode)?.selector as? HttpMethodRouteSelector }.firstOrNull()?.method

/** The page's only inline script, allowed by its hash in [SWAGGER_UI_CSP]. */
private fun swaggerUiBootstrap(specPath: String) =
    "window.onload = () => { window.ui = SwaggerUIBundle({ url: \"$specPath\", dom_id: \"#swagger-ui\" }); };"

/**
 * The page runs third-party code on the server's origin, so it may only load the pinned files from unpkg, run its own
 * bootstrap, call this origin, and never be framed.
 */
private val SWAGGER_UI_CSP: String by lazy {
    val bootstrap = java.util.Base64.getEncoder().encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(swaggerUiBootstrap(API_OPENAPI_PATH).encodeToByteArray()),
    )
    "default-src 'none'; script-src https://unpkg.com 'sha256-$bootstrap'; style-src https://unpkg.com 'unsafe-inline'; " +
        "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
}

/** The Swagger UI page that reads [specPath]. */
internal fun swaggerUiPage(specPath: String): String =
    """
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>TeamCode API</title>
      <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@$SWAGGER_UI_VERSION/swagger-ui.css"
            integrity="$SWAGGER_UI_CSS_SRI" crossorigin="anonymous">
    </head>
    <body>
      <div id="swagger-ui"></div>
      <script src="https://unpkg.com/swagger-ui-dist@$SWAGGER_UI_VERSION/swagger-ui-bundle.js"
              integrity="$SWAGGER_UI_JS_SRI" crossorigin="anonymous"></script>
      <script>${swaggerUiBootstrap(specPath)}</script>
    </body>
    </html>
    """.trimIndent()

private const val API_DESCRIPTION = "The HTTP API of a TeamCode server: team administration, capacity, agent runs, " +
    "skills, variants, the MCP gateway and cloud sessions. The `teamcode` CLI and the dashboard use the same routes.\n\n" +
    "**Authentication.** Send `Authorization: Bearer <token>`. An interactive login (`POST /api/v1/auth/login`) can " +
    "call every operation its member's role allows. A service key can only call the operations that list one of its " +
    "scopes; every other operation answers 403 `credential_scope`. Click **Authorize** to try requests from this page.\n\n" +
    "**Errors.** Every `/api/v1` error answers `{\"error\": {\"type\": \"<stable code>\", \"message\": \"<text>\"}}`. " +
    "Clients should branch on `type`, never on `message`. A 429 carries `Retry-After`. The inference endpoints answer " +
    "in the Anthropic or OpenAI error format and the MCP gateway with JSON-RPC errors, except a missing credential " +
    "(401, in the envelope above).\n\n" +
    "**Conventions.** Times are Unix epoch milliseconds. Paginated lists return a cursor (`nextBeforeId`, `nextBefore`, " +
    "`nextAfter`): pass it back as `before` (or `after`) to read the next page; a null cursor means there is no more. " +
    "The operations available depend on the features this server enables (`GET /api/v1/capabilities`)."

private const val SESSION_DESCRIPTION = "An interactive login token, returned as `accessToken` by `POST /api/v1/auth/login` " +
    "(what `teamcode login` stores). It acts with every right of the member's role."

private const val SERVICE_KEY_DESCRIPTION = "A service key (Dashboard → Service keys, `teamcode keys create` or " +
    "`POST /api/v1/service-keys`). It acts as its owner, limited to its scopes: `inference`, `usage:read`, `guidance`, " +
    "`mcp`, `skills:read`, `skills:publish`, `skills:assign`, `hooks:read`, `hooks:publish`, `cloud:read`, `cloud:write`, `admin:cloud`, `team:read`, `artifacts:read` and `artifacts:write`."
