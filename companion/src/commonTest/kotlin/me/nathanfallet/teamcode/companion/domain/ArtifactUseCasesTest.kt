package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeArtifactRepository
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.links.LinkTarget
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import kotlin.test.*

class ArtifactUseCasesTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val accounts = InMemoryAccountRepository(listOf(account("a1"), account("a2", origin = "https://other.example:8443")))
    private val resolve = ResolveLinkUseCase(accounts)

    @Test fun anOpenInAppLinkAndAViewerUrlLeadToTheArtifactOfTheAccountOnThatServer() {
        val viewer = "https://team.example/artifact/$id?v=3"
        val encoded = viewer.replace(":", "%3A").replace("/", "%2F").replace("?", "%3F").replace("=", "%3D")
        assertEquals(LinkTarget.Artifact("a1", id, 3), resolve("teamcode://open?url=$encoded"))
        assertEquals(LinkTarget.Artifact("a1", id, null), resolve("https://team.example/artifact/$id"))
        // The same server spelled with its default port, and another account on another port.
        assertEquals(LinkTarget.Artifact("a1", id, null), resolve("https://TEAM.example:443/artifact/$id"))
        assertEquals(LinkTarget.Artifact("a2", id, null), resolve("https://other.example:8443/artifact/$id"))
        // As chat apps and browsers copy them: an upper-case scheme, a fragment, a tracker.
        assertEquals(LinkTarget.Artifact("a1", id, 2), resolve("HTTPS://team.example/artifact/$id?utm_source=chat&v=2#top"))
        // A raw character outside the basic plane in the encoded URL survives decoding.
        assertEquals(LinkTarget.Artifact("a1", id, null), resolve("teamcode://open?url=https%3A%2F%2Fteam.example%2Fartifact%2F$id%3Fnote%3D\uD83D\uDE80"))
    }

    @Test fun aServerWithoutAccountIsNamedAndAnythingElseIsNoLink() {
        assertEquals(LinkTarget.UnknownServer("https://new.example"), resolve("https://new.example/artifact/$id"))
        for (link in listOf(
            "https://team.example/artifact/not-an-id", "https://team.example/artifact/$id/ticket/index.html", "https://team.example/artifact/$id?v=0", "https://team.example/artifact/$id?v=1&v=2", "https://team.example/artifact/$id?v=x",
            "https://user@team.example/artifact/$id", "http://team.example/artifact/$id", "javascript:alert(1)", "teamcode://open?url=%ZZ", "teamcode://open?url=https%3A%2F%2Fteam.example%2Fartifact%2F$id%-1",
            "teamcode://open?other=1", "https://team.example/dashboard", "",
        )) assertNull(resolve(link), link)
    }

    @Test fun theActiveAccountWinsAmongAccountsOfTheSameServer() {
        val shared = InMemoryAccountRepository(listOf(account("a1"), account("a3", username = "bob")))
        shared.setActive("a3")
        assertEquals(LinkTarget.Artifact("a3", id, null), ResolveLinkUseCase(shared)("https://team.example/artifact/$id"))
    }

    @Test fun aSignedOutAccountAsksToSignInAgain() {
        val out = InMemoryAccountRepository(listOf(account("a1", signedIn = false)))
        assertEquals(LinkTarget.SignInAgain("https://team.example", "ada"), ResolveLinkUseCase(out)("https://team.example/artifact/$id"))
        val mixed = InMemoryAccountRepository(listOf(account("a1", signedIn = false), account("a3", username = "bob")))
        assertEquals(LinkTarget.Artifact("a3", id, null), ResolveLinkUseCase(mixed)("https://team.example/artifact/$id"))
    }

    @Test fun theViewerStaysUnderItsTicket() {
        val prefix = "https://team.example/artifact/$id/tk_1/"
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix("https://team.example", id, prefix))
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix("https://team.example", id, "${prefix}index.html"))
        // A content URL naming its entry page in a folder keeps the ticket's directory as the prefix.
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix("https://team.example", id, "${prefix}docs/index.html"))
        // The server's own spelling of its origin is accepted and kept.
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix("https://team.example", id, "https://Team.Example:443/artifact/$id/tk_1/"))
        // Pages the web view reports in the canonical spelling, or the server's, are both under the prefix.
        assertTrue(OpenArtifactUseCase.allows(prefix, "https://TEAM.example:443/artifact/$id/tk_1/page.html"))
        for (url in listOf("https://evil.example/artifact/$id/tk_1/", "https://team.example/artifact/$id/../x/", "https://team.example/artifact/other/tk/",
            "https://team.example/artifact/$id/tk%2F1/", "https://team.example.evil/artifact/$id/tk/", "https://u@team.example/artifact/$id/tk/", "https://team.example"))
            assertNull(OpenArtifactUseCase.contentPrefix("https://team.example", id, url), url)
        assertTrue(OpenArtifactUseCase.allows(prefix, "${prefix}img/a.png?x=1#top"))
        // A percent in a file name is a page; escaped twice, a dot or a slash is not.
        assertTrue(OpenArtifactUseCase.allows(prefix, "${prefix}chart%20100%25.png"))
        assertFalse(OpenArtifactUseCase.allows(prefix, "${prefix}%252f..%252fx"))
        for (url in listOf("${prefix}../other/", "${prefix}%2e%2e/x", "https://team.example/dashboard", "${prefix}a\\..\\b", "${prefix}..%5c..%5cx", "${prefix}%252e%252e/x", "https://team.example/artifact/$id/tk_2/"))
            assertFalse(OpenArtifactUseCase.allows(prefix, url), url)
    }

    @Test fun aTicketForAnotherServerOrVersionIsRefused() = runTest {
        val artifacts = FakeArtifactRepository()
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, credentials(), FixedClock()))
        val open = OpenArtifactUseCase(artifacts, FakeFeatures(setOf("artifacts.v1", "artifacts.tickets.v1")), accounts, guard)
        assertEquals("https://team.example/artifact/$id/t0/", open("a1", id, null).prefix)
        artifacts.tickets = { a, _ -> ArtifactTicket(a, 2, "https://evil.example/artifact/$a/t/", 1) }
        assertFailsWith<CompanionException.ForeignContent> { open("a1", id, null) }
        artifacts.tickets = { a, _ -> ArtifactTicket(a, 2, "https://team.example/artifact/$a/t/", 1) }
        assertFailsWith<CompanionException.Server> { open("a1", id, 3) }
        assertFailsWith<CompanionException.Unsupported> { OpenArtifactUseCase(artifacts, FakeFeatures(setOf("artifacts.v1")), accounts, guard)("a1", id, null) }
        assertFailsWith<CompanionException.NotFound> { open("a1", "../x", null) }
    }
}
