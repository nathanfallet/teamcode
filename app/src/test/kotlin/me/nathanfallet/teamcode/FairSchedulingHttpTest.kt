package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.infrastructure.gateway.PoolGateway
import me.nathanfallet.teamcode.infrastructure.providers.ProviderTransport
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.configureGateway
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Real HTTP streams and shared admission state; the only upstream is an owned loopback fixture. */
class FairSchedulingHttpTest {
    private data class Dispatch(val id: String, val release: CompletableDeferred<Unit>, val atNanos: Long)
    private data class Work(val id: String, val token: String, val background: Boolean = false)

    @Test @Timeout(180)
    fun unlimitedMembersShareABusyAccountWhileALimitedMemberQueuesInOrder() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
                Database(container.jdbcUrl, container.username, container.password).use { b ->
                    a.migrate(); b.migrate()
                    val team = SqlTeamService(a)
                    val temporary = team.bootstrap()!!
                    val pending = team.login("admin", temporary, "load fixture")
                    team.changePassword(pending.principal, temporary, "load administrator replacement")
                    val heavy = team.login("admin", "load administrator replacement", "load fixture")
                    suspend fun member(username: String): LoginSession {
                        val created = team.createMember(heavy.principal, username, Role.MEMBER)
                        val initial = team.login(username, created.temporaryPassword, "load fixture")
                        val replacement = "$username replacement password"
                        team.changePassword(initial.principal, created.temporaryPassword, replacement)
                        return team.login(username, replacement, "load fixture")
                    }
                    val developer = member("developer")
                    val support = member("support")
                    val admission = SqlAdmissionStore(a)
                    admission.configurePool(heavy.principal, PoolBudget("", 1_100_000))
                    listOf(heavy to 5, developer to 5, support to 1).forEach { (session, weight) ->
                        // Only the heavy member has a concurrency limit; the others are unlimited, the default.
                        admission.configure(heavy.principal, MemberPolicy(session.principal.member.id, maxConcurrent = if (session == heavy) 2 else 0, weight = weight))
                    }
                    assertEquals(listOf(500_000L, 500_000L, 100_000L), listOf(heavy, developer, support).map { admission.capacity(it.principal).dailyAllowanceUnits })
                    // The batch read the dashboard uses must match reading each member alone.
                    val everyone = listOf(heavy, developer, support).map { it.principal.member.id }
                    assertEquals(everyone.map { admission.capacity(heavy.principal, it) }, admission.capacities(heavy.principal, everyone))
                    val cipher = CredentialCipher(ByteArray(32) { 39 })
                    val accounts = SqlAccountStore(a, cipher)
                    val enrolled = accounts.upsert(AccountMutationActor.System,
                        VerifiedProviderAccount(AccountIdentity("default", "anthropic", "load-fixture", "fixture-org"), "Load fixture"),
                        ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "load-enrollment") as EnrollmentResult.Created
                    accounts.handover(AccountMutationActor.System, "default", enrolled.account.id, accessOnly = true)
                    exercise(a, b, cipher, heavy, developer, support)
                }
            }
        }
    }

    private suspend fun exercise(a: Database, b: Database, cipher: CredentialCipher, heavy: LoginSession, developer: LoginSession, support: LoginSession) = coroutineScope {
        val initial = (1..2).map { Work("held-$it", heavy.token) }
        val background = (1..8).map { Work("background-$it", heavy.token, true) }
        val interactive = (1..3).map { Work("developer-$it", developer.token) } + (1..3).map { Work("support-$it", support.token) }
        val cancelled = Work("cancelled", heavy.token)
        val overflow = Work("overflow", heavy.token, true)
        val all = initial + background + interactive
        val pending = background
        val known = (all + cancelled + overflow).associateBy { it.id }
        val dispatched = Channel<Dispatch>(Channel.UNLIMITED)
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()
        val stream = FixtureGatewayService.EVENTS
        val upstream = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                route("/fixture") {
                    install(io.ktor.server.http.HttpRequestLifecycle) { cancelCallOnClose = true }
                    post {
                        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val id = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
                        check(id in known)
                        check(counts.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet() == 1)
                        check(call.request.headers[HttpHeaders.Authorization] == null)
                        check(call.request.headers["x-teamcode-workload"] == null)
                        val gate = CompletableDeferred<Unit>()
                        gates[id] = gate
                        val active = inFlight.incrementAndGet()
                        peakInFlight.updateAndGet { maxOf(it, active) }
                        try {
                            check(active <= 2 + interactive.size)
                            dispatched.send(Dispatch(id, gate, System.nanoTime()))
                            call.respondTextWriter(ContentType.Text.EventStream) {
                                write(stream.first()); flush()
                                gate.await()
                                stream.drop(1).forEach { write(it); flush() }
                            }
                        } finally { inFlight.decrementAndGet() }
                    }
                }
            }
        }
        upstream.start(wait = false)
        val upstreamOrigin = "http://127.0.0.1:${upstream.engine.resolvedConnectors().single().port}"
        fun http() = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 60_000; requestTimeoutMillis = 60_000 }
        }
        val providerHttp = http()
        val transport = object : ProviderTransport {
            override val provider = "anthropic"
            override val protocol = WireProtocol.MESSAGES
            override suspend fun refresh(previous: ProviderCredentials): ProviderCredentials = error("No real refresh expected")
            override suspend fun usage(account: AccountRecord, credentials: ProviderCredentials): List<AccountQuota> = error("No usage probe expected")
            override fun quotas(accountId: String, headers: Headers, now: Long): List<AccountQuota> = emptyList()
            override suspend fun send(account: AccountRecord, credentials: ProviderCredentials, body: JsonObject, headers: Map<String, String>, receive: suspend (HttpResponse) -> Unit) {
                check(credentials.accessToken == "synthetic-access")
                providerHttp.preparePost("$upstreamOrigin/fixture") { contentType(ContentType.Application.Json); setBody(body.toString()) }.execute(receive)
            }
        }
        val stores = listOf(SqlAdmissionStore(a), SqlAdmissionStore(b))
        val servers = listOf(a, b).mapIndexed { index, db ->
            val source = SqlCredentialSource(db, cipher) { _, _ -> error("No refresh expected") }
            val gateway = PoolGateway(stores[index], source, listOf(transport), SqlModelPolicyStore(db), SqlRoutingStore(db), queued = AwaitAdmissionUseCase(stores[index]))
            embeddedServer(Netty, host = "127.0.0.1", port = 0) { configureGateway(gateway, team = SqlTeamService(db), admission = stores[index]) }
        }
        val client = http()
        val jobs = mutableMapOf<String, Deferred<String>>()
        val starts = mutableMapOf<String, Long>()
        val waits = mutableListOf<Long>()
        try {
            servers.forEach { it.start(wait = false) }
            val ports = servers.map { it.engine.resolvedConnectors().single().port }
            suspend fun untilQueued(count: Int) = withTimeout(10_000) {
                while (stores.first().statistics().queued != count) delay(10)
            }
            suspend fun next(): Dispatch = withTimeout(10_000) { dispatched.receive() }.also {
                waits += (it.atNanos - starts.getValue(it.id)) / 1_000_000
            }
            fun start(work: Work) {
                starts[work.id] = System.nanoTime()
                val port = ports[jobs.size % ports.size]
                jobs[work.id] = async {
                    val response = client.post("http://127.0.0.1:$port/v1/messages") {
                        bearerAuth(work.token); contentType(ContentType.Application.Json)
                        header("x-teamcode-workload", if (work.background) "background" else "interactive")
                        setBody(body(work.id))
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    response.bodyAsText().also { assertEquals(stream.joinToString(""), it) }
                }
            }
            initial.forEach { start(it) }
            val held = listOf(next(), next())
            assertEquals(initial.map { it.id }.toSet(), held.map { it.id }.toSet())
            assertEquals(2, stores.last().statistics().active)
            background.dropLast(1).forEachIndexed { index, work -> start(work); untilQueued(index + 1) }
            Socket("127.0.0.1", ports.last()).use { socket ->
                val bytes = body(cancelled.id).toByteArray()
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().apply {
                        write("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${cancelled.token}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
                untilQueued(background.size)
                socket.setSoLinger(true, 0)
            }
            untilQueued(background.size - 1)
            assertNull(counts[cancelled.id])
            start(background.last()); untilQueued(background.size)
            val rejected = client.post("http://127.0.0.1:${ports.last()}/v1/messages") {
                bearerAuth(overflow.token); contentType(ContentType.Application.Json)
                header("x-teamcode-workload", "background"); setBody(body(overflow.id))
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status, "A member cannot exceed eight queued requests")
            assertNull(counts[overflow.id])
            assertTrue(jobs.values.none { it.isCompleted }, "Requests over a member limit wait without a client-facing quota rejection")

            // Unlimited members share the busy account at once: an account has no parallelism limit.
            interactive.forEach { start(it) }
            val immediate = (1..interactive.size).map { next() }
            assertEquals(interactive.map { it.id }.toSet(), immediate.map { it.id }.toSet())
            assertEquals(background.size, stores.first().statistics().queued, "Other members never wait behind a busy account")
            immediate.forEach { assertTrue(it.release.complete(Unit)); jobs.getValue(it.id).await() }
            assertTrue(background.none { jobs.getValue(it.id).isCompleted })

            // Keep one original stream held, so the heavy member's own limit exposes exactly one slot at a time.
            held.first().release.complete(Unit)
            jobs.getValue(held.first().id).await()
            val seen = mutableListOf<String>()
            repeat(pending.size) {
                val dispatch = next()
                seen += dispatch.id
                assertTrue(dispatch.release.complete(Unit))
                jobs.getValue(dispatch.id).await()
            }
            assertEquals(background.map { it.id }.toSet(), seen.toSet(), "Each freed slot of the member's limit admits one of its queued requests")
            held.last().release.complete(Unit)
            jobs.getValue(held.last().id).await()
            withTimeout(5_000) { while (stores.first().statistics().active != 0 || stores.first().statistics().completed != all.size.toLong()) delay(10) }
            assertEquals(0, stores.last().statistics().queued)
            assertEquals(2 + interactive.size, peakInFlight.get())
            assertEquals(all.map { it.id }.toSet(), counts.keys)
            assertTrue(counts.values.all { it.get() == 1 })
            assertEquals(listOf(60L, 18L, 18L), listOf(heavy, developer, support).map { stores.last().capacity(it.principal).chargedAndReservedUnits })
            val sorted = waits.sorted()
            println("HTTP queue fixture passed: replicas=2, members=3, completed=${all.size}, cancelledPending=1, rejectedOverflow=1, peakQueued=${pending.size}, peakUpstream=${peakInFlight.get()}, dispatchP50Ms=${sorted[sorted.size / 2]}, dispatchP95Ms=${sorted.last()}; no real provider calls.")
        } finally {
            withContext(NonCancellable) {
                gates.values.forEach { it.complete(Unit) }
                jobs.values.forEach { it.cancel() }
                client.close()
                withTimeoutOrNull(5_000) { jobs.values.forEach { it.join() } }
                servers.forEach { it.stop(0, 5_000) }
                providerHttp.close()
                upstream.stop(0, 5_000)
                dispatched.close()
            }
        }
    }

    private fun body(id: String) = """{"model":"claude-opus-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"$id"}]}"""
}
