package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Audit M3: logins carry the client's address, and the dashboard its trusted-browser cookie, to the throttle. */
class LoginOriginRoutesTest {
    private class NoGateway : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }
    private class RecordingTeam : TeamService {
        val origins = mutableListOf<LoginOrigin>()
        private val member = Member("m1", "alice", Role.MEMBER, false, false)
        override suspend fun login(username: String, password: String, device: String, installationId: String?) = error("unused")
        override suspend fun login(username: String, password: String, device: String, installationId: String?, platform: String?, osVersion: String?, origin: LoginOrigin): LoginSession {
            origins += origin
            return LoginSession("session-token-0123456789abcdef0123", Principal(member, "t1", "default"), Long.MAX_VALUE, if (origin.browser != null) "v1.m1.1.signature" else null)
        }
        override suspend fun authenticate(token: String): Principal? = null
        override suspend fun logout(principal: Principal) = error("unused")
        override suspend fun changePassword(principal: Principal, current: String, replacement: String) = error("unused")
        override suspend fun members(principal: Principal) = error("unused")
        override suspend fun createMember(principal: Principal, username: String, role: Role) = error("unused")
        override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean) = error("unused")
        override suspend fun resetPassword(principal: Principal, id: String) = error("unused")
    }

    @Test fun theApiLoginPassesThePeerAddressAndIgnoresAnUntrustedForwardedHeader() = testApplication {
        val team = RecordingTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "https://teamcode.test") }
        client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); header(HttpHeaders.XForwardedFor, "203.0.113.9"); setBody("""{"username":"alice","password":"secret"}""") }
        val origin = team.origins.single()
        assertNotNull(origin.sourceAddress); assertNotEquals("203.0.113.9", origin.sourceAddress); assertNull(origin.browser)
    }

    @Test fun theDashboardLoginSendsAndRenewsTheTrustedBrowserCookie() = testApplication {
        val team = RecordingTeam()
        application { configureGateway(NoGateway(), team = team, publicOrigin = "https://teamcode.test") }
        val noRedirect = client.config { followRedirects = false }
        val first = noRedirect.post("/dashboard/login") { header(HttpHeaders.Origin, "https://teamcode.test"); contentType(ContentType.Application.FormUrlEncoded); setBody("username=alice&password=secret") }
        assertEquals(HttpStatusCode.Found, first.status)
        assertEquals("", team.origins.last().browser, "A browser without the cookie is still a browser")
        val cookie = first.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$TRUSTED_BROWSER_COOKIE=") }
        for (attribute in listOf("Path=/dashboard/login", "HttpOnly", "Secure", "SameSite=Strict", "Max-Age=34560000")) assertTrue(attribute in cookie, cookie)
        noRedirect.post("/dashboard/login") {
            header(HttpHeaders.Origin, "https://teamcode.test"); header(HttpHeaders.Cookie, "$TRUSTED_BROWSER_COOKIE=v1.m1.1.signature")
            contentType(ContentType.Application.FormUrlEncoded); setBody("username=alice&password=secret")
        }
        assertEquals("v1.m1.1.signature", team.origins.last().browser)
    }

    @Test fun forwardedAddressesAreBelievedOnlyFromTrustedProxies() {
        val none = TrustedProxies.NONE
        assertEquals("10.0.0.5", none.clientAddress("10.0.0.5", listOf("203.0.113.9")), "Nothing is trusted by default")
        val proxies = TrustedProxies.parse("10.0.0.0/8,fd00::/8, 192.168.1.10")
        assertEquals("203.0.113.9", proxies.clientAddress("10.0.0.5", listOf("203.0.113.9")))
        assertEquals("203.0.113.9", proxies.clientAddress("10.0.0.5", listOf("6.6.6.6, 203.0.113.9", "10.1.2.3")), "A client cannot prepend its own address")
        assertEquals("203.0.113.9", proxies.clientAddress("203.0.113.9", listOf("6.6.6.6")), "An untrusted peer's header is ignored")
        assertEquals("2001:db8::1", proxies.clientAddress("fd00::1", listOf("2001:db8::1")))
        assertEquals("192.168.1.10", proxies.clientAddress("192.168.1.10", listOf("not-an-address")), "A hop that is no address stops the walk")
        assertEquals("192.168.1.11", proxies.clientAddress("192.168.1.11", listOf("203.0.113.9")), "A single address is a /32")
        assertEquals("10.0.0.5", proxies.clientAddress("10.0.0.5", emptyList()))
        assertFailsWith<IllegalArgumentException> { TrustedProxies.parse("proxy.internal") }
        assertFailsWith<IllegalArgumentException> { TrustedProxies.parse("10.0.0.0/33") }
        assertFailsWith<IllegalArgumentException> { TrustedProxies.parse("300.0.0.1") }
        assertEquals("10.0.0.5", TrustedProxies.parse(" ").clientAddress("10.0.0.5", listOf("203.0.113.9")))
    }
}
