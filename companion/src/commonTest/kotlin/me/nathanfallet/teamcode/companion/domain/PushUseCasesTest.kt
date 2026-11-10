package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.PushRepository
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform
import me.nathanfallet.teamcode.companion.domain.usecases.links.LinkTarget
import me.nathanfallet.teamcode.companion.domain.usecases.links.NotificationLinks
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.push.ClearPushUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.push.PushNotice
import me.nathanfallet.teamcode.companion.domain.usecases.push.ReadPushUseCase
import me.nathanfallet.teamcode.companion.infrastructure.push.PushPayloadReader
import me.nathanfallet.teamcode.companion.domain.usecases.push.SyncPushUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The sync runs in backgroundScope, which advanceUntilIdle leaves alone: runCurrent runs it.
class PushUseCasesTest {
    private class FakePushPlatform : PushPlatform {
        override val token = MutableStateFlow<String?>(null)
        override suspend fun publicKey() = "spki"
        // The "encryption" of these tests: a payload is its JSON reversed.
        override suspend fun open(payload: String) = if (payload.startsWith("}")) PushPayloadReader.read(payload.reversed()) else error("not for this key")
    }

    private class FakePushRepository : PushRepository {
        val registered = mutableListOf<Triple<String, String, String>>()
        val cleared = mutableListOf<String>()
        var failing = false
        override suspend fun register(accountId: String, token: String, publicKey: String) {
            if (failing) error("offline")
            registered += Triple(accountId, token, publicKey)
        }
        override suspend fun clear(accountId: String) {
            if (failing) error("offline")
            cleared += accountId
        }
    }

    private val platform = FakePushPlatform()
    private val push = FakePushRepository()
    private val features = FakeFeatures(setOf(Features.PUSH))

    @Test fun everySignedInAccountIsRegisteredOnceTheTokenArrives() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1"), account("a2", signedIn = false)))
        backgroundScope.launch { SyncPushUseCase(accounts, features, push, platform)() }
        runCurrent()
        assertEquals(emptyList(), push.registered)

        platform.token.value = "t1"
        runCurrent()
        assertEquals(listOf(Triple("a1", "t1", "spki")), push.registered)

        // A new sign-in registers that account alone.
        accounts.save(account("a3"))
        runCurrent()
        assertEquals(listOf("a1", "a3"), push.registered.map { it.first })

        // A rotated token goes to every signed-in account again.
        platform.token.value = "t2"
        runCurrent()
        assertEquals(setOf("a1", "a3"), push.registered.filter { it.second == "t2" }.map { it.first }.toSet())
        assertEquals(4, push.registered.size)
    }

    @Test fun aServerWithoutPushIsNotCalledAndAFailureIsRetriedOnTheNextChange() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        features.features = emptySet()
        backgroundScope.launch { SyncPushUseCase(accounts, features, push, platform)() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(emptyList(), push.registered)

        features.features = setOf(Features.PUSH)
        push.failing = true
        accounts.save(account("a2"))
        runCurrent()
        assertEquals(emptyList(), push.registered)

        push.failing = false
        accounts.save(account("a3"))
        runCurrent()
        assertEquals(setOf("a1", "a2", "a3"), push.registered.map { it.first }.toSet())
    }

    @Test fun clearingIsBestEffortAndSkipsServersWithoutPush() = runTest {
        val clear = ClearPushUseCase(features, push)
        clear("a1")
        assertEquals(listOf("a1"), push.cleared)

        push.failing = true
        clear("a2") // No exception: the sign-out goes on.

        push.failing = false
        features.features = emptySet()
        clear("a3")
        assertEquals(listOf("a1"), push.cleared)
    }

    private fun payload(origin: String = "https://team.example", member: String = "m1", session: String? = "s1", wait: String? = null, title: String = "Ada is waiting") =
        buildString {
            append("""{"v":1,"id":"n1","origin":"$origin","memberId":"$member","kind":"waiting","title":"$title","body":"Review the plan",""")
            session?.let { append(""""sessionId":"$it",""") }
            wait?.let { append(""""waitId":"$it",""") }
            append(""""at":1,"future":true}""")
        }.reversed()

    @Test fun aPushIsShownForTheSignedInAccountOfItsServerAndMember() = runTest {
        val accounts = InMemoryAccountRepository(listOf(account("a1", memberId = "m0"), account("a2", memberId = "m1"), account("a3", origin = "https://other.example", memberId = "m1")))
        val read = ReadPushUseCase(accounts, platform)
        assertEquals(
            PushNotice("n1", "a2", "Ada is waiting", "Review the plan", "teamcode://notification?origin=https%3A%2F%2Fteam.example&member=m1&session=s1"),
            read(payload()),
        )
        // Not for this key, not a payload, an account signed out here or another member: nothing is shown.
        assertNull(read("garbage"))
        assertNull(read("}{".reversed()))
        assertNull(read(payload(member = "m9")))
        accounts.update(account("a2", memberId = "m1", signedIn = false))
        assertNull(read(payload()))
    }

    @Test fun aNotificationLinkOpensItsSessionOrTheWaitsOfItsAccount() {
        val accounts = InMemoryAccountRepository(listOf(account("a1", memberId = "m0"), account("a2", memberId = "m1"), account("a3", origin = "https://old.example", memberId = "m1", signedIn = false)))
        val resolve = ResolveLinkUseCase(accounts)
        assertEquals(LinkTarget.Session("a2", "s1"), resolve(NotificationLinks.link("https://team.example", "m1", "s1", null)))
        assertEquals(LinkTarget.Waits("a2"), resolve(NotificationLinks.link("https://team.example", "m1", null, "w1")))
        assertEquals(LinkTarget.Home("a1"), resolve(NotificationLinks.link("https://team.example", "m0", null, null)))
        assertEquals(LinkTarget.SignInAgain("https://old.example", "ada"), resolve(NotificationLinks.link("https://old.example", "m1", "s1", null)))
        // Another member, a malformed id, a repeated parameter: nothing opens.
        assertNull(resolve(NotificationLinks.link("https://team.example", "m9", "s1", null)))
        assertNull(resolve("teamcode://notification?origin=https%3A%2F%2Fteam.example&member=m1&session=..%2Fx"))
        assertNull(resolve("teamcode://notification?origin=https%3A%2F%2Fteam.example&member=m1&member=m0"))
    }
}
