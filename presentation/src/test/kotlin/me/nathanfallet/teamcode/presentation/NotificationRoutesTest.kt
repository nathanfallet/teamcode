package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** The notification settings routes decode, authorize and encode; the rules are the domain's (NotificationService). */
class NotificationRoutesTest {
    private class Store : NotificationStore {
        val stored = mutableMapOf<String, MutableList<NotificationSetting>>()
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun behind(limit: Int) = emptyList<NotificationCursor>()
        override suspend fun read(cursor: NotificationCursor, limit: Int) = NotificationSourcePage(emptyList(), cursor.head)
        override suspend fun disabled(memberIds: Set<String>) = emptySet<Triple<String, String, NotificationKind>>()
        override suspend fun enqueue(cursor: NotificationCursor, through: Long, deliveries: List<NotificationDelivery>) = true
        override suspend fun claimDue(limit: Int, leaseMillis: Long) = emptyList<NotificationDelivery>()
        override suspend fun record(id: String, state: NotificationState, attempts: Int, nextAttemptAt: Long, error: String?) = Unit
        override suspend fun maintain(limit: Int) = 0
        override suspend fun settings(teamId: String, memberId: String) = stored[memberId].orEmpty()
        override suspend fun set(teamId: String, memberId: String, setting: NotificationSetting) {
            val list = stored.getOrPut(memberId) { mutableListOf() }
            list.removeAll { it.channel == setting.channel && it.kind == setting.kind }; list += setting
        }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "key" -> interactive.authenticate("admin")!!.copy(tokenId = "key", serviceScopes = setOf(ServiceScope.CLOUD_READ))
                else -> interactive.authenticate(token)
            }
        }
    }

    @Test fun aMemberReadsAndChangesTheirOwnSwitches() = testApplication {
        application { configureGateway(gateway, team = team(), notifications = NotificationService(Store(), listOf("push"))) }
        suspend fun call(method: HttpMethod, token: String?, json: String? = null) = client.request("/api/v1/notifications/settings") {
            this.method = method; if (token != null) bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        assertTrue(Notifications.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, null).status)

        val initial = Serialization.json.decodeFromString<NotificationSettingsView>(call(HttpMethod.Get, "admin").bodyAsText())
        assertEquals(listOf("push"), initial.channels)
        assertEquals(Notifications.kinds.toSet(), initial.settings.map { it.kind }.toSet(), "the wire kinds match the domain's")
        assertTrue(initial.settings.all { it.enabled })

        val changed = call(HttpMethod.Put, "admin", """{"channel":"push","kind":"turn_completed","enabled":false}""")
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals(false, Serialization.json.decodeFromString<NotificationSettingsView>(changed.bodyAsText()).settings.single { it.kind == "turn_completed" }.enabled)

        val unknown = call(HttpMethod.Put, "admin", """{"channel":"slack","kind":"waiting","enabled":false}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("invalid_notification_setting", Serialization.json.decodeFromString<ApiError>(unknown.bodyAsText()).error.type)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "key").status, "a service key has no person to notify")
    }

    @Test fun withoutTheServiceTheRoutesAndCapabilityAreAbsent() = testApplication {
        application { configureGateway(gateway, team = team()) }
        assertFalse(Notifications.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/notifications/settings") { bearerAuth("admin") }.status)
    }
}
