package me.nathanfallet.teamcode.companion.domain

import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.domain.usecases.links.ArtifactLink
import me.nathanfallet.teamcode.companion.domain.usecases.links.LinkTarget
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import kotlin.test.*

/** The artifact links a conversation opens in the app, and every link it leaves to the browser. */
class ArtifactLinkTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"

    @Test fun aViewerUrlIsParsedToItsOriginIdAndVersion() {
        assertEquals(ArtifactLink("https://team.example", id, null), ArtifactLink.parse("https://team.example/artifact/$id"))
        assertEquals(ArtifactLink("https://team.example", id, 3), ArtifactLink.parse("https://team.example/artifact/$id?v=3"))
        // A trailing slash, a fragment, a tracker.
        assertEquals(ArtifactLink("https://team.example", id, null), ArtifactLink.parse("https://team.example/artifact/$id/"))
        assertEquals(ArtifactLink("https://team.example", id, 2), ArtifactLink.parse("https://team.example/artifact/$id/?utm_source=chat&v=2#section"))
        // Upper case where it does not matter: the scheme, the host and the id (the server's ids are lower case).
        assertEquals(ArtifactLink("https://team.example", id, null), ArtifactLink.parse("HTTPS://Team.EXAMPLE/artifact/${id.uppercase()}"))
        // The default port is the same server; another port is another server.
        assertEquals(ArtifactLink("https://team.example", id, null), ArtifactLink.parse("https://team.example:443/artifact/$id"))
        assertEquals(ArtifactLink("https://team.example:8443", id, null), ArtifactLink.parse("https://team.example:8443/artifact/$id"))
        assertEquals(ArtifactLink("http://localhost:8080", id, null), ArtifactLink.parse("http://localhost:8080/artifact/$id"))
    }

    @Test fun anythingElseIsNoArtifactLink() {
        for (link in listOf(
            // Not an artifact id.
            "https://team.example/artifact/not-an-id", "https://team.example/artifact/${id.dropLast(1)}", "https://team.example/artifact/${id}0",
            "https://team.example/artifact/${id.replace('-', '_')}", "https://team.example/artifact/",
            // The path is case-sensitive on the server, and nothing may come before or after the id.
            "https://team.example/ARTIFACT/$id", "https://team.example/prefix/artifact/$id", "https://team.example//artifact/$id",
            "https://team.example/artifact/$id/tk_1/index.html", "https://team.example/artifact/$id/..", "https://team.example/artifact/$id/../../dashboard",
            "https://team.example/artifact/%30b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80", "https://team.example/artifact/$id%2F..%2Fx", "https://team.example/artifact/$id\\..\\x",
            "https://team.example/artifact/$id;x", "https://team.example/artifact/${id}x",
            // Another scheme, credentials, cleartext to a real host.
            "ftp://team.example/artifact/$id", "javascript:alert('https://team.example/artifact/$id')", "teamcode://open?url=https://team.example/artifact/$id",
            "https://user@team.example/artifact/$id", "https://team.example@evil.example/artifact/$id", "http://team.example/artifact/$id",
            // A version that is not one.
            "https://team.example/artifact/$id?v=0", "https://team.example/artifact/$id?v=-1", "https://team.example/artifact/$id?v=x", "https://team.example/artifact/$id?v=1&v=2",
            "https://team.example/artifact/$id?v=9999999999",
            // Spaces, control and invisible characters that would show a different URL from the one opened.
            " https://team.example/artifact/$id", "https://team.example/artifact/$id\n", "https://team.example‮/artifact/$id", "https://te​am.example/artifact/$id",
            "https://team.example:99999/artifact/$id", "https://team.example:/artifact/$id", "", "https://team.example",
        )) assertNull(ArtifactLink.parse(link), link)
    }

    @Test fun aConversationOpensInTheAppOnlyTheArtifactsOfItsOwnAccountsServer() {
        val accounts = InMemoryAccountRepository(listOf(account("a1"), account("a2", origin = "https://other.example:8443"), account("a3", origin = "https://out.example", signedIn = false)))
        val resolve = ResolveLinkUseCase(accounts)
        assertEquals(LinkTarget.Artifact("a1", id, 4), resolve.inConversation("https://TEAM.example:443/artifact/$id?v=4", "a1"))
        assertEquals("https://team.example/artifact/$id?v=4", resolve.viewerUrl(LinkTarget.Artifact("a1", id, 4)))
        assertEquals("https://team.example/artifact/$id", resolve.viewerUrl(LinkTarget.Artifact("a1", id, null)))
        // The member also has an account on that other server: still not opened in this conversation's overlay.
        assertNull(resolve.inConversation("https://other.example:8443/artifact/$id", "a1"))
        assertNull(resolve.inConversation("https://other.example/artifact/$id", "a2"))
        assertNull(resolve.inConversation("https://new.example/artifact/$id", "a1"))
        // Not an artifact link, a signed-out account, an account that is gone.
        assertNull(resolve.inConversation("https://team.example/dashboard", "a1"))
        assertNull(resolve.inConversation("https://out.example/artifact/$id", "a3"))
        assertNull(resolve.inConversation("https://team.example/artifact/$id", "gone"))
        // Never the typed-link conveniences: no `teamcode://` wrapper, no trimming.
        assertNull(resolve.inConversation("teamcode://open?url=https%3A%2F%2Fteam.example%2Fartifact%2F$id", "a1"))
        assertNull(resolve.inConversation(" https://team.example/artifact/$id", "a1"))
    }
}
