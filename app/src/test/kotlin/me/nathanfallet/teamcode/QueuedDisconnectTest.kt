package me.nathanfallet.teamcode

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.Socket
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.presentation.configureGateway
import kotlin.test.*

class QueuedDisconnectTest {
    @Test fun socketCloseBeforeResponseCancelsWaitingAdmission() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val actor = Principal(Member("test", "test", Role.MEMBER, false, false), "test", "test")
        val queue = object : AdmissionQueueStore {
            override suspend fun enqueue(actor: Principal, input: AdmissionInput): AdmissionTicket {
                entered.complete(Unit)
                return AdmissionTicket("test")
            }
            override suspend fun reserveQueued(actor: Principal, ticket: AdmissionTicket, input: AdmissionInput): Reservation = throw TeamFailure("queue_turn", 429, "Synthetic queue turn")
            override suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean) = Unit
            override suspend fun release(actor: Principal, ticket: AdmissionTicket) { released.complete(Unit) }
        }
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
                AwaitAdmissionUseCase(queue).reserve(actor, AdmissionInput(1, "claude-test", "anthropic"))
                fail("A cancelled pending request must never dispatch")
            }
        }
        val token = "synthetic-disconnect-capability-for-tests"
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configureGateway(gateway, token) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                val body = """{"model":"claude-test","stream":true,"messages":[]}""".toByteArray()
                socket.getOutputStream().apply {
                    write("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                    write(body); flush()
                }
                withTimeout(5_000) { entered.await() }
                socket.setSoLinger(true, 0) // A real peer reset, before receiving any response headers.
            }
            withTimeout(3_000) { released.await() }
        } finally { server.stop(0, 5_000) }
    }
}
