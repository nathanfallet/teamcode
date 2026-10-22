package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.FixedInstallation
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.member
import me.nathanfallet.teamcode.companion.server
import me.nathanfallet.teamcode.companion.unauthorized
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LoginSession
import me.nathanfallet.teamcode.companion.domain.models.SignInResult
import me.nathanfallet.teamcode.companion.domain.usecases.auth.ChangePasswordUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.InspectServerUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RenameDeviceUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignInUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.SignOutUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.normalizeOrigin
import kotlin.test.*

class AuthUseCasesTest {
    private val auth = FakeAuthRepository()
    private val accounts = InMemoryAccountRepository()
    private val store = credentials()
    private val signIn = SignInUseCase(auth, accounts, store, FixedInstallation, SequenceIds())

    @Test fun originsAreNormalizedAndOnlyHttpsOrLoopbackAccepted() {
        assertEquals("https://team.example", normalizeOrigin(" Team.Example/ "))
        assertEquals("https://team.example:8443", normalizeOrigin("https://team.example:8443"))
        assertEquals("http://localhost:8080", normalizeOrigin("http://localhost:8080"))
        assertEquals("http://[::1]:8080", normalizeOrigin("http://[::1]:8080"))
        for (invalid in listOf("http://team.example", "https://team.example/path", "https://user:pw@team.example", "https://team.example?x=1",
            "https://team.example#x", "ftp://team.example", "https://", "https://team..example", "https://team.example:70000", ""))
            assertFailsWith<CompanionException.InvalidOrigin>(invalid) { normalizeOrigin(invalid) }
    }

    @Test fun inspectingAServerRefusesAnotherApiVersion() = runTest {
        assertEquals("0.7.0", InspectServerUseCase(auth)("team.example").serverVersion)
        assertEquals("capabilities https://team.example", auth.calls.single())
        auth.info = server.copy(apiVersion = 2)
        assertEquals(2, assertFailsWith<CompanionException.UnsupportedApi> { InspectServerUseCase(auth)("team.example") }.apiVersion)
    }

    @Test fun signingInStoresTheTokenAndTheAccountButNeverThePassword() = runTest {
        val result = assertIs<SignInResult.SignedIn>(signIn(server, " ada ", "secret password", "TeamCode Test · Bench"))
        assertEquals("login https://team.example ada secret password TeamCode Test · Bench 11111111-1111-4111-8111-111111111111", auth.calls.single())
        assertEquals("token-1", store.read(result.account.id))
        assertEquals(listOf(result.account), accounts.accounts.value)
        assertEquals(result.account.id, accounts.activeAccountId.value)
        assertFalse(accounts.accounts.value.toString().contains("secret password"))
        assertFalse(accounts.accounts.value.toString().contains("token-1"))
    }

    @Test fun theSameMemberOnTheSameServerKeepsOneAccountAndAnotherUsernameAddsOne() = runTest {
        val first = assertIs<SignInResult.SignedIn>(signIn(server, "ada", "pw", "Phone")).account
        auth.sessions.clear(); auth.sessions += LoginSession("token-2", 3_000_000L, member())
        val again = assertIs<SignInResult.SignedIn>(signIn(server, "ada", "pw", "Renamed")).account
        assertEquals(first.id, again.id)
        assertEquals("Phone", again.deviceName) // The server keeps the first name of a device; so does the app.
        assertEquals("token-2", store.read(first.id))
        auth.sessions.clear(); auth.sessions += LoginSession("token-3", 3_000_000L, member(id = "m2", username = "grace"))
        val other = assertIs<SignInResult.SignedIn>(signIn(server, "grace", "pw", "Phone")).account
        assertNotEquals(first.id, other.id)
        assertEquals(2, accounts.accounts.value.size)
        assertEquals(other.id, accounts.activeAccountId.value)
    }

    @Test fun aTemporaryPasswordAsksForANewOneAndSignsInAgainWithIt() = runTest {
        auth.sessions.clear()
        auth.sessions += LoginSession("temporary", 2_000_000L, member(passwordChangeRequired = true))
        auth.sessions += LoginSession("token-2", 2_000_000L, member())
        val pending = assertIs<SignInResult.PasswordChangeRequired>(signIn(server, "ada", "temporary-pw", "Phone"))
        assertTrue(accounts.accounts.value.isEmpty())
        assertFalse(pending.toString().contains("temporary"))
        val change = ChangePasswordUseCase(auth, signIn)
        assertFailsWith<IllegalArgumentException> { change(pending, "temporary-pw", "a new long password", "another one") }
        assertFailsWith<IllegalArgumentException> { change(pending, "temporary-pw", "short", "short") }
        val signed = change(pending, "temporary-pw", "a new long password", "a new long password")
        assertEquals("password temporary temporary-pw a new long password", auth.calls[1])
        assertTrue(auth.calls[2].startsWith("login https://team.example ada a new long password "))
        assertEquals("token-2", store.read(signed.account.id))
    }

    @Test fun signingOutRevokesThenForgetsEvenOffline() = runTest {
        accounts.save(account("a1")); accounts.save(account("a2", memberId = "m2", username = "grace"))
        store.write("a1", "t1"); store.write("a2", "t2")
        val signOut = SignOutUseCase(auth, accounts, store)
        assertTrue(signOut("a2"))
        assertEquals("logout a2", auth.calls.last())
        assertNull(store.read("a2")); assertEquals(listOf("a2"), auth.released)
        assertEquals("a1", accounts.activeAccountId.value)
        auth.logoutFailure = CompanionException.Unreachable()
        assertFalse(signOut("a1"))
        assertNull(store.read("a1"))
        assertTrue(accounts.accounts.value.isEmpty())
        assertNull(accounts.activeAccountId.value)
    }

    @Test fun aRevokedOrExpiredTokenSignsTheAccountOutOnce() = runTest {
        val clock = FixedClock()
        val refresh = RefreshAccountUseCase(auth, accounts, store, clock)
        accounts.save(account("a1")); store.write("a1", "t1")
        assertTrue(refresh("a1")!!.signedIn)
        auth.meResult = { unauthorized() }
        assertFalse(refresh("a1")!!.signedIn)
        assertNull(store.read("a1"))
        assertEquals(listOf("a1"), auth.released)
        auth.calls.clear()
        refresh("a1")
        assertTrue(auth.calls.isEmpty()) // Signed out: no call until the member signs in again.

        accounts.save(account("a2", memberId = "m2", expiresAt = 500L)); store.write("a2", "t2")
        assertFalse(refresh("a2")!!.signedIn)
        assertTrue(auth.calls.isEmpty())
    }

    @Test fun anOfflineRefreshKeepsTheAccountSignedIn() = runTest {
        accounts.save(account("a1")); store.write("a1", "t1")
        auth.meResult = { throw CompanionException.Unreachable() }
        assertFailsWith<CompanionException.Unreachable> { RefreshAccountUseCase(auth, accounts, store, FixedClock())("a1") }
        assertTrue(accounts.accounts.value.single().signedIn)
        assertEquals("t1", store.read("a1"))
    }

    @Test fun renamingTheDeviceValidatesAndKeepsTheName() = runTest {
        accounts.save(account("a1"))
        val rename = RenameDeviceUseCase(auth, accounts)
        assertFailsWith<IllegalArgumentException> { rename("a1", " ") }
        assertFailsWith<IllegalArgumentException> { rename("a1", "bad\nname") }
        assertEquals("Work phone", rename("a1", " Work phone ").deviceName)
        assertEquals("Work phone", accounts.accounts.value.single().deviceName)
    }
}
