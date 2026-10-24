package me.nathanfallet.teamcode.presentation.docs

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.GatewayService
import me.nathanfallet.teamcode.domain.gateway.RequestCancellationService
import me.nathanfallet.teamcode.domain.github.GitHubLinkService
import me.nathanfallet.teamcode.domain.mcp.McpGateway
import me.nathanfallet.teamcode.domain.hooks.HookCatalog
import me.nathanfallet.teamcode.domain.skills.SkillCatalog
import me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase
import me.nathanfallet.teamcode.presentation.CloudConversation
import me.nathanfallet.teamcode.presentation.CloudRunnerChannel
import me.nathanfallet.teamcode.presentation.FakeTeam
import me.nathanfallet.teamcode.presentation.configureGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocsTest {
    /** An implementation that is never called: the document is built from the routing tree alone. */
    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (method.name == "toString") "stub" else error("The documentation must not call ${method.name}.")
        } as T

    /** The server with every optional area enabled, as a production deployment with all features mounts it. */
    private fun Application.everyFeature() {
        val sessionStore = stub<CloudSessionStore>()
        val conversationStore = stub<CloudConversationStore>()
        val access = CloudAccessService(conversationStore, sessionStore)
        val sessions = CloudSessionService(sessionStore, access = access)
        val cluster = stub<ClusterPort>()
        configureGateway(
            stub<GatewayService>(), team = FakeTeam(), publicOrigin = "https://teamcode.example.org",
            accounts = AccountManagement(stub(), stub()), admission = stub(), directives = stub(), modelPolicies = stub(),
            routes = stub(), cancellation = RequestCancellationService(stub()), devices = stub(), serviceKeys = stub(),
            audit = stub(), pacingSettings = stub(), forecasts = stub(), groups = stub(), runs = stub(), budgetOverrides = stub(),
            reconciliation = stub(), variants = stub(), environment = me.nathanfallet.teamcode.domain.variants.ResolveOwnEnvironmentUseCase(stub()), skills = SkillCatalog(stub()), hooks = HookCatalog(stub()),
            mcp = McpGateway(ResolveEffectiveVariantsUseCase(stub()), stub(), stub()), mcpAccess = stub(),
            cloudRuntime = CloudRuntimeService(stub(), "https://teamcode.example.org"), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(stub(), cluster),
            cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(sessionStore), AuthenticateRunnerUseCase(sessionStore, cluster),
                RunnerInferenceUseCase(sessionStore, stub()), RunnerGitHubUseCase(sessionStore, stub())),
            github = GitHubLinkService(stub(), stub(), stub()),
            cloudConversation = CloudConversationService(conversationStore, sessionStore, access).let { conversation ->
                CloudConversation(conversation, access, RunnerConversationUseCase(sessionStore, conversationStore, { _, _ -> false }),
                    me.nathanfallet.teamcode.domain.team.TeamEventService(stub<me.nathanfallet.teamcode.domain.team.TeamEventStore>()),
                    files = CloudFileService(stub(), sessionStore, conversation, stub(), stub()))
            },
            cloudTransfers = CloudTransferService(sessionStore, stub(), sessions, CloudRecipientPolicy(sessionStore)),
            webhooks = me.nathanfallet.teamcode.domain.webhooks.WebhookService(stub(), stub()),
            apiPrices = stub(), poolSizing = stub(),
            artifactAdmin = me.nathanfallet.teamcode.domain.artifacts.ArtifactAdministrationService(stub(), stub()),
            artifacts = me.nathanfallet.teamcode.domain.artifacts.ArtifactService(stub(), stub(), stub()),
            artifactViewing = me.nathanfallet.teamcode.domain.artifacts.ArtifactViewing(me.nathanfallet.teamcode.domain.artifacts.ArtifactService(stub(), stub(), stub()),
                stub(), stub(), stub(), me.nathanfallet.teamcode.domain.artifacts.ArtifactTickets(ByteArray(32))),
            deviceDaemons = me.nathanfallet.teamcode.presentation.DeviceDaemonChannel(DeviceDaemonUseCase(stub()), CloudSessionService(sessionStore, access = access, daemons = stub())),
        )
    }

    private val inferencePaths = setOf("/v1/messages", "/v1/responses")

    private fun withDocument(block: (document: JsonObject, apiRoutes: Set<String>) -> Unit) = testApplication {
        var routes = emptySet<String>()
        application {
            everyFeature()
            routes = routingRoot.getAllRoutes().mapNotNull { route ->
                val method = route.method() ?: return@mapNotNull null
                val path = route.path(OpenApiRoutePathFormat)
                "${method.value} $path".takeIf { isApiRoute(method, path, inferencePaths) }
            }.toSet()
        }
        val response = client.get(API_OPENAPI_PATH)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        block(Json.parseToJsonElement(response.bodyAsText()).jsonObject, routes)
    }

    private fun JsonObject.operations(): Map<String, JsonObject> = getValue("paths").jsonObject.flatMap { (path, item) ->
        item.jsonObject.filterKeys { it in methods }.map { (method, operation) -> "${method.uppercase()} $path" to operation.jsonObject }
    }.toMap()

    private val methods = HttpMethod.DefaultMethods.map { it.value.lowercase() }.toSet()

    @Test
    fun everyApiRouteIsDocumented() = withDocument { document, apiRoutes ->
        val operations = document.operations()
        // Each route of the API has its operation, with a summary and a description written for it.
        val undocumented = apiRoutes.filter { key ->
            val operation = operations[key] ?: return@filter true
            operation["summary"]?.jsonPrimitive?.content.isNullOrBlank() || operation["operationId"] == null
        }
        assertTrue(undocumented.isEmpty(), "Describe these routes in presentation/docs (ApiCatalog):\n" + undocumented.sorted().joinToString("\n"))
        // And the catalog describes no route the server does not serve.
        val stale = apiOperations.map { it.key }.filter { it !in apiRoutes }
        assertTrue(stale.isEmpty(), "These documented operations match no route:\n" + stale.sorted().joinToString("\n"))
        assertEquals(apiRoutes, operations.keys)
        val ids = operations.values.map { it.getValue("operationId").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size, "Duplicate operationId: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun internalAndDashboardRoutesAreNotPublished() = withDocument { document, _ ->
        val paths = document.getValue("paths").jsonObject.keys
        assertTrue(paths.none { it.startsWith("/dashboard") || it.startsWith("/api/v1/cloud/runner/") }, paths.toString())
        assertFalse("/health" in paths || "/ready" in paths || "/" in paths || API_DOCS_PATH in paths || API_OPENAPI_PATH in paths, paths.toString())
    }

    @Test
    fun securityFollowsTheServerAuthorization() = withDocument { document, _ ->
        val schemes = document.getValue("components").jsonObject.getValue("securitySchemes").jsonObject
        assertEquals(setOf(SESSION_SCHEME, SERVICE_KEY_SCHEME), schemes.keys)
        for (scheme in schemes.values) assertEquals("bearer", scheme.jsonObject.getValue("scheme").jsonPrimitive.content)
        val operations = document.operations()
        fun security(key: String) = operations.getValue(key)["security"]?.jsonArray?.map { requirement ->
            requirement.jsonObject.mapValues { (_, scopes) -> scopes.jsonArray.map { it.jsonPrimitive.content } }
        }
        assertEquals(emptyList(), security("POST /api/v1/auth/login"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList()), mapOf(SERVICE_KEY_SCHEME to listOf("skills:publish"))),
            security("POST /api/v1/skills/{name}/versions"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList()), mapOf(SERVICE_KEY_SCHEME to listOf("skills:publish"))),
            security("PUT /api/v1/skills/{name}/pin"), "Pinning shares the publication right")
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList<String>())), security("DELETE /api/v1/skills/{name}/pin"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList<String>())), security("GET /api/v1/members"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList()), mapOf(SERVICE_KEY_SCHEME to emptyList<String>())), security("GET /api/v1/auth/me"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList()), mapOf(SERVICE_KEY_SCHEME to listOf("cloud:write")), mapOf(SERVICE_KEY_SCHEME to listOf("admin:cloud"))),
            security("POST /api/v1/cloud/sessions"))
        assertEquals(listOf(mapOf(SESSION_SCHEME to emptyList()), mapOf(SERVICE_KEY_SCHEME to listOf("mcp"))), security("POST /mcp/{server}"))
        // Every protected operation documents its 401 and 403, all in the error envelope.
        for ((key, operation) in operations) {
            val responses = operation.getValue("responses").jsonObject
            assertTrue("default" in responses && "500" in responses, key)
            if (key != "POST /api/v1/auth/login") assertTrue("401" in responses && "403" in responses, key)
        }
    }

    @Test
    fun errorsUseTheEnvelope() = withDocument { document, _ ->
        val schemas = document.getValue("components").jsonObject.getValue("schemas").jsonObject
        val error = schemas.getValue("ApiError").jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("error"), error.keys)
        val detail = schemas.getValue("ErrorDetail").jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("type", "message"), detail.keys)
        // One component per type: no numbered nullable copy (`GitHubLinkView2`), every reference resolves.
        assertTrue(schemas.keys.none { it.last().isDigit() }, schemas.keys.toString())
        val references = Regex("\"#/components/schemas/([^\"]+)\"").findAll(document.toString()).map { it.groupValues[1] }.toSet()
        assertTrue(references.all { it in schemas.keys }, (references - schemas.keys).toString())
        // The inference endpoints answer in their provider's error format, never in the envelope (except the 401).
        val messages = document.operations().getValue("POST /v1/messages").getValue("responses").jsonObject
        assertFalse("ApiError" in messages.getValue("400").toString(), messages.toString())
        assertTrue("ApiError" in messages.getValue("401").toString(), messages.toString())
        val unauthorized = document.operations().getValue("GET /api/v1/members").getValue("responses").jsonObject.getValue("401").toString()
        assertTrue("#/components/schemas/ApiError" in unauthorized, unauthorized)
    }

    @Test
    fun onlyTheQueryParametersARouteReadsAreListed() = withDocument { document, _ ->
        val operations = document.operations()
        fun queries(key: String) = operations.getValue(key)["parameters"]?.jsonArray.orEmpty()
            .map { it.jsonObject }.filter { it.getValue("in").jsonPrimitive.content == "query" }.map { it.getValue("name").jsonPrimitive.content }
        // `/runs` reads them; its nested resources inherit them from `RunsResource` but ignore them.
        assertEquals(listOf("member", "all", "before", "label"), queries("GET /api/v1/runs"))
        for (key in listOf("POST /api/v1/runs", "POST /api/v1/runs/{id}/end", "GET /api/v1/runs/{id}", "POST /api/v1/members/{memberId}/budget-overrides"))
            assertEquals(emptyList(), queries(key), key)
        val state = operations.getValue("GET /api/v1/cloud/sessions").getValue("parameters").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "state" }
        assertEquals("array", state.getValue("schema").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun documentDeclaresThePublicOrigin() = withDocument { document, _ ->
        assertTrue(document.getValue("openapi").jsonPrimitive.content.startsWith("3."))
        // The API version, not the server build: anonymous readers learn nothing more than the routes.
        assertEquals("1", document.getValue("info").jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals(listOf("https://teamcode.example.org"), document.getValue("servers").jsonArray.map { it.jsonObject.getValue("url").jsonPrimitive.content })
    }

    @Test
    fun swaggerUiIsPublicAndPinned() = testApplication {
        application { everyFeature() }
        val response = client.get(API_DOCS_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val page = response.bodyAsText()
        assertTrue("url: \"$API_OPENAPI_PATH\"" in page, page)
        assertEquals(2, Regex("""integrity="sha384-[A-Za-z0-9+/=]+"""").findAll(page).count(), page)
        // Third-party code on the server's origin: only the pinned CDN and the page's own bootstrap, never framed.
        val policy = response.headers["Content-Security-Policy"].orEmpty()
        assertTrue("script-src https://unpkg.com 'sha256-" in policy && "frame-ancestors 'none'" in policy, policy)
        val bootstrap = Regex("<script>(.*)</script>").find(page)!!.groupValues[1]
        val hash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bootstrap.encodeToByteArray()))
        assertTrue("'sha256-$hash'" in policy, policy)
    }
}
