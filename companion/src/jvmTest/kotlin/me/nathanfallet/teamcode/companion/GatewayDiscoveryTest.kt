package me.nathanfallet.teamcode.companion

import io.ktor.client.engine.java.Java
import me.nathanfallet.teamcode.api.BuildInfo
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.companion.domain.models.SignInResult
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignInUseCase
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.CachedFeaturesRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAuthRepository
import me.nathanfallet.teamcode.domain.gateway.GatewayService
import me.nathanfallet.teamcode.domain.gateway.GatewaySnapshot
import me.nathanfallet.teamcode.domain.gateway.GenerationHead
import me.nathanfallet.teamcode.domain.gateway.GenerationRequest
import me.nathanfallet.teamcode.domain.team.LoginSession
import me.nathanfallet.teamcode.domain.team.Member
import me.nathanfallet.teamcode.domain.team.NewMember
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.Role
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.team.TeamService
import me.nathanfallet.teamcode.presentation.configureGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app's discovery and sign-in against the real server application, over a socket: the server keeps its
 * capabilities behind authentication, so a fake that answers them anonymously would hide a mismatch.
 */
class GatewayDiscoveryTest {
    private val member = Member("m1", "ada", Role.MEMBER, false, false)

    private val team = object : TeamService {
        override suspend fun login(username: String, password: String, device: String, installationId: String?): LoginSession {
            if (username != "ada" || password != "correct horse") throw TeamFailure("invalid_credentials", 401, "Wrong username or password.")
            return LoginSession("tc_session", Principal(member, "session-1", "default"), 4_000_000_000_000L)
        }
        override suspend fun authenticate(token: String) = if (token == "tc_session") Principal(member, "session-1", "default") else null
        override suspend fun logout(principal: Principal) = Unit
        override suspend fun changePassword(principal: Principal, current: String, replacement: String) = Unit
        override suspend fun members(principal: Principal): List<Member> = error("unused")
        override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember = error("unused")
        override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member = error("unused")
        override suspend fun resetPassword(principal: Principal, id: String): NewMember = error("unused")
    }

    private val gateway = object : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, Flow<ByteArray>) -> Unit) = error("No inference expected")
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }

    @Test fun theAppFindsTheServerSignsInThenReadsItsCapabilities() = runBlocking {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            configureGateway(gateway, team = team, publicOrigin = "http://127.0.0.1")
        }.start()
        try {
            val origin = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val accounts = InMemoryAccountRepository()
            val store = credentials()
            val clients = TeamCodeClientProvider(Java, store, FakePlatform)
            val auth = RemoteAuthRepository(clients, AccountCalls(clients, accounts), this)

            // Without a credential, the server refuses its capabilities: still a TeamCode server, the sign-in comes next.
            val found = InspectServerUseCase(auth)(origin)
            assertEquals(origin, found.origin)

            val account = assertIs<SignInResult.SignedIn>(SignInUseCase(auth, accounts, store, FixedInstallation, SequenceIds())(found, "ada", "correct horse", "Bench")).account
            assertEquals("tc_session", store.read(account.id))
            assertEquals(BuildInfo.VERSION, account.serverVersion)
            // The features come with the stored token.
            assertTrue("team.auth" in CachedFeaturesRepository(auth, accounts).features(account.id))
            clients.release(account.id)
        } finally {
            server.stop(0, 0)
        }
    }
}
