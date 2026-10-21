package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import kotlin.test.*

class CacheControlTest {
    @Test fun everyResponseForbidsStoringAndTransformingByProxies() = testApplication {
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
        }
        application { configureGateway(gateway, team = FakeTeam()) }
        val cookie = { request: HttpRequestBuilder -> request.header(HttpHeaders.Cookie, "teamcode_session=admin") }
        // Authenticated dashboard HTML, the sign-in page, a JSON API answer and a JSON error: Cloudflare skips its
        // content rewrites (e-mail obfuscation, minification) only when no-transform is present.
        val responses = listOf(
            client.get("/dashboard") { cookie(this) },
            client.get("/dashboard/members") { cookie(this) },
            client.get("/dashboard/login"),
            client.get("/api/v1/members") { bearerAuth("admin") },
            client.get("/api/v1/members"),
            client.get("/health"),
            client.get("/api/v1/auth/me") { bearerAuth("admin") },
        )
        for (response in responses) {
            assertEquals(listOf("no-store, no-transform"), response.headers.getAll(HttpHeaders.CacheControl), response.call.request.url.toString())
        }
        assertEquals(HttpStatusCode.OK, responses[3].status); assertEquals(HttpStatusCode.Unauthorized, responses[4].status)
        assertTrue(responses[0].contentType()?.match(ContentType.Text.Html) == true)
    }
}
