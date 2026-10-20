package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readUTF8Line
import java.net.Socket
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.presentation.ForecastLivePolicy
import org.junit.jupiter.api.Timeout
import kotlin.test.*

class ForecastLiveHttpTest {
    @Test @Timeout(120)
    fun anOpenHttpStreamSeesAnotherReplicasUpdatesAndRevocationAndStopsOnSocketReset() = runBlocking {
        // Three sequential five-second assertions must fit within the same stream lifetime.
        // Browser reconnect acceptance keeps the fixture's short default lifetime.
        withForecastLiveFixture(ForecastLivePolicy(100, 30_000)) { fixture ->
            HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 30_000 } }.use { client ->
                client.prepareGet("${fixture.originA}/dashboard/forecasts/stream?account=${fixture.accountId}") {
                    header(HttpHeaders.Cookie, "teamcode_session=${fixture.session.token}")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()
                    suspend fun event(): Pair<String, JsonObject> = withTimeout(5_000) {
                        var name = ""
                        while (true) {
                            val line = channel.readUTF8Line() ?: error("Live stream ended before its event")
                            if (line.startsWith("event: ")) name = line.removePrefix("event: ")
                            if (line.startsWith("data: ")) return@withTimeout name to Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                    val initial = event()
                    assertEquals("snapshot", initial.first)
                    assertTrue(initial.second.getValue("html").jsonPrimitive.content.contains("31.0% observed"))
                    fixture.update(.47)
                    withTimeout(5_000) {
                        while (true) {
                            val next = event()
                            assertEquals("snapshot", next.first)
                            if (next.second.getValue("html").jsonPrimitive.content.contains("47.0% observed")) break
                        }
                    }
                    fixture.teamB.logout(fixture.session.principal)
                    withTimeout(5_000) {
                        while (true) {
                            val next = event()
                            if (next.first == "unavailable") {
                                assertEquals("access", next.second.getValue("reason").jsonPrimitive.content)
                                break
                            }
                        }
                    }
                }
                val fresh = fixture.teamA.login("admin", "live forecast replacement", "socket reset fixture")
                val port = URI(fixture.originA).port
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    withContext(Dispatchers.IO) {
                        socket.getOutputStream().apply {
                            write("GET /dashboard/forecasts/stream?account=${fixture.accountId} HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: teamcode_session=${fresh.token}\r\n\r\n".toByteArray()); flush()
                        }
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: error("Socket stream ended before its snapshot")
                            if (line.startsWith("data: ")) break
                        }
                    }
                    socket.setSoLinger(true, 0)
                }
                delay(150) // Allow a read already in flight at peer reset to finish.
                val stopped = fixture.loads.get()
                delay(300)
                assertEquals(stopped, fixture.loads.get(), "A disconnected browser must not keep polling the database")
                assertEquals(HttpStatusCode.Unauthorized, client.get("${fixture.originB}/api/v1/auth/me") { bearerAuth(fixture.session.token) }.status)
            }
        }
    }
}
