package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.FixedInstallation
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.capabilities
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
        // Mobile keyboards capitalize the first letter.
        assertEquals("https://team.example", normalizeOrigin("Https://Team.example"))
        assertEquals("https://team.example", normalizeOrigin("https://team.example:443"))
        assertEquals("https://team.example:8443", normalizeOrigin("https://team.example:08443"))
        // What a phone keyboard or a paste leaves around the address: spaces, line breaks, invisible characters, the
        // period typed after a double space.
        assertEquals("https://team.example", normalizeOrigin("\nhttps://team.example \t"))
        assertEquals("https://team.example", normalizeOrigin("\u200Bteam.example\uFEFF"))
        assertEquals("https://team.example", normalizeOrigin("team.example /"))
        assertEquals("https://team.example", normalizeOrigin("team.example. "))
        for (invalid in listOf("http://team.example", "http://[::1]:8080", "https://team.example/path", "https://user:pw@team.example", "https://team.example?x=1",
            "https://team.example#x", "ftp://team.example", "https://", "https://team..example", "https://team.example..", "https://team.example:70000", "", " \n "))
            assertFailsWith<CompanionException.InvalidOrigin>(invalid) { normalizeOrigin(invalid) }
    }

    @Test fun inspectingAServerRefusesAnotherApiVersionOnlyWhenItIsAnnounced() = runTest {
        // TeamCode keeps its version behind authentication: the server is accepted, its API checked at sign-in.
        assertEquals("https://team.example", InspectServerUseCase(auth)("team.example").origin)
        assertEquals("discover https://team.example", auth.calls.single())
        auth.info = server.copy(apiVersion = 2)
        assertEquals(2, assertFailsWith<CompanionException.UnsupportedApi> { InspectServerUseCase(auth)("team.example") }.apiVersion)
    }

    @Test fun signingInReadsTheCapabilitiesWithTheNewSessionAndRefusesAnotherApi() = runTest {
        val account = assertIs<SignInResult.SignedIn>(signIn(server, "ada", "pw", "Phone")).account
        assertEquals("capabilities https://team.example token-1", auth.calls.last())
        assertEquals("0.7.0", account.serverVersion)
        assertTrue(auth.abandoned.isEmpty())

        accounts.remove(account.id)
        auth.announced = capabilities.copy(apiVersion = 2)
        assertEquals(2, assertFailsWith<CompanionException.UnsupportedApi> { signIn(server, "ada", "pw", "Phone") }.apiVersion)
        // Nothing kept, and the session the login created is revoked.
        assertTrue(accounts.accounts.value.isEmpty())
        assertEquals(listOf("https://team.example token-1"), auth.abandoned)

        auth.announced = capabilities
        auth.capabilitiesFailure = CompanionException.Unreachable()
        assertFailsWith<CompanionException.Unreachable> { signIn(server, "ada", "pw", "Phone") }
        assertTrue(accounts.accounts.value.isEmpty())
        assertEquals(2, auth.abandoned.size)
    }

    @Test fun signingInStoresTheTokenAndTheAccountButNeverThePassword() = runTest {
        val result = assertIs<SignInResult.SignedIn>(signIn(server, " ada ", "secret password", "TeamCode Test · Bench"))
        assertEquals(listOf(
            "login https://team.example ada secret password TeamCode Test · Bench 11111111-1111-4111-8111-111111111111",
            "capabilities https://team.example token-1",
        ), auth.calls)
        assertEquals("token-1", store.read(result.account.id))
        assertEquals(listOf(result.account), accounts.accounts.value)
        assertEquals(result.account.id, accounts.activeAccountId.value)
        assertFalse(accounts.accounts.value.toString().contains("secret password"))
        assertFalse(accounts.accounts.value.toString().contains("token-1"))
    }

    @Test fun theSameMemberOnTheSameServerKeepsOneAccountAndAnotherUsernameAddsOne() = runTest {
        val first = assertIs<SignInResult.SignedIn>(signIn(server, "ada", "pw", "Phone")).account
        val firstToken = store.read(first.id)
        auth.sessions.clear(); auth.sessions += LoginSession("token-2", 3_000_000L, member())
        val again = assertIs<SignInResult.SignedIn>(signIn(server, "ada", "pw", "Renamed")).account
        assertEquals(first.id, again.id)
        assertEquals("Phone", again.deviceName) // The server keeps the first name of a device; so does the app.
        assertEquals("token-2", store.read(first.id))
        // The server keeps the replaced session: the app revokes it.
        assertEquals(listOf("https://team.example $firstToken"), auth.abandoned)
        auth.sessions.clear(); auth.sessions += LoginSession("token-3", 3_000_000L, member(id = "m2", username = "grace"))
        val other = assertIs<SignInResult.SignedIn>(signIn(server, "grace", "pw", "Phone")).account
        assertNotEquals(first.id, other.id)
        assertEquals(2, accounts.accounts.value.size)
        assertEquals(other.id, accounts.activeAccountId.value)
        assertEquals(1, auth.abandoned.size)
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
        // A temporary session reads nothing else: the capabilities come with the session that follows the change.
        assertEquals("capabilities https://team.example token-2", auth.calls.single { it.startsWith("capabilities") })
        assertEquals("token-2", store.read(signed.account.id))
    }

    @Test fun leavingThePasswordChangeRevokesTheTemporarySession() = runTest {
        auth.sessions.clear()
        auth.sessions += LoginSession("temporary", 2_000_000L, member(passwordChangeRequired = true))
        val pending = assertIs<SignInResult.PasswordChangeRequired>(signIn(server, "ada", "temporary-pw", "Phone"))
        ChangePasswordUseCase(auth, signIn).abandon(pending)
        assertEquals(listOf("https://team.example temporary"), auth.abandoned)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test fun aFailedSignInAfterTheChangeAsksToSignInWithTheNewPassword() = runTest {
        auth.sessions.clear()
        auth.sessions += LoginSession("temporary", 2_000_000L, member(passwordChangeRequired = true))
        val pending = assertIs<SignInResult.PasswordChangeRequired>(signIn(server, "ada", "temporary-pw", "Phone"))
        auth.loginFailure = CompanionException.Unreachable()
        // Retrying the change would reuse the revoked temporary session: the user signs in again instead.
        assertFailsWith<CompanionException.PasswordChanged> { ChangePasswordUseCase(auth, signIn)(pending, "temporary-pw", "a new long password", "a new long password") }
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test fun anUnsupportedApiFoundAfterThePasswordChangeIsSaidAsSuch() = runTest {
        auth.sessions.clear()
        auth.sessions += LoginSession("temporary", 2_000_000L, member(passwordChangeRequired = true))
        auth.sessions += LoginSession("token-2", 2_000_000L, member())
        val pending = assertIs<SignInResult.PasswordChangeRequired>(signIn(server, "ada", "temporary-pw", "Phone"))
        auth.announced = capabilities.copy(apiVersion = 2)
        assertFailsWith<CompanionException.UnsupportedApi> { ChangePasswordUseCase(auth, signIn)(pending, "temporary-pw", "a new long password", "a new long password") }
        assertTrue(accounts.accounts.value.isEmpty())
        assertEquals(listOf("https://team.example token-2"), auth.abandoned)
    }

    @Test fun signingOutRevokesThenForgetsEvenOffline() = runTest {
        accounts.save(account("a1")); accounts.save(account("a2", memberId = "m2", username = "grace"))
        store.write("a1", "t1"); store.write("a2", "t2")
        val signOut = SignOutUseCase(auth, accounts, store)
        assertEquals(SignOutUseCase.Result.REVOKED, signOut("a2"))
        assertEquals("logout a2", auth.calls.last())
        assertNull(store.read("a2")); assertEquals(listOf("a2"), auth.released)
        assertEquals("a1", accounts.activeAccountId.value)
        auth.logoutFailure = CompanionException.Unreachable()
        assertEquals(SignOutUseCase.Result.SERVER_UNREACHABLE, signOut("a1"))
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

    @Test fun aLate401DoesNotSignOutAnAccountSignedInAgainMeanwhile() = runTest {
        accounts.save(account("a1")); store.write("a1", "new")
        // While the refresh with the old token waits, the member signs in again (new session, new expiry), then the
        // old token's 401 arrives.
        auth.meResult = { accounts.update(account("a1", expiresAt = 3_000_000L)); unauthorized() }
        val refreshed = RefreshAccountUseCase(auth, accounts, store, FixedClock())("a1")!!
        assertTrue(refreshed.signedIn)
        assertEquals("new", store.read("a1"))
    }

    @Test fun aLockedSecureStoreKeepsTheAccountSignedIn() = runTest {
        accounts.save(account("a1"))
        val locked = object : me.nathanfallet.teamcode.companion.domain.services.CredentialStore {
            override val persistent = true
            override suspend fun read(accountId: String): String? = throw IllegalStateException("locked")
            override suspend fun write(accountId: String, token: String) = Unit
            override suspend fun delete(accountId: String) = Unit
        }
        assertFailsWith<CompanionException.CredentialStorage> { RefreshAccountUseCase(auth, accounts, locked, FixedClock())("a1") }
        assertTrue(accounts.accounts.value.single().signedIn)
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
        val rename = RenameDeviceUseCase(auth, accounts, store)
        assertFailsWith<IllegalArgumentException> { rename("a1", " ") }
        assertFailsWith<IllegalArgumentException> { rename("a1", "bad\nname") }
        assertEquals("Work phone", rename("a1", " Work phone ").deviceName)
        assertEquals("Work phone", accounts.accounts.value.single().deviceName)
    }

    @Test fun aRevokedDeviceFoundWhileRenamingSignsTheAccountOut() = runTest {
        accounts.save(account("a1"))
        auth.renameFailure = CompanionException.Unauthorized()
        assertFailsWith<CompanionException.Unauthorized> { RenameDeviceUseCase(auth, accounts, store)("a1", "Work phone") }
        assertFalse(accounts.accounts.value.single().signedIn)
        assertEquals(listOf("a1"), auth.released)
    }
}
