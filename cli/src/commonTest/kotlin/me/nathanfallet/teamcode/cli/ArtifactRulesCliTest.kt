package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.artifacts.*
import kotlin.test.*

class ArtifactRulesCliTest {
    @Test fun placesWhereCredentialsLiveAreRefusedByName() {
        for (path in listOf(".git/config", "repo/.git/HEAD", ".ssh/id_ed25519.pub", ".aws/credentials", ".teamcode/config.json", ".env", ".env.local",
            "deploy/server.pem", "keys/id_rsa", ".npmrc", "home/.netrc", ".claude/.credentials.json", "x/.codex/auth.json", "tls/site.key"))
            assertNotNull(ArtifactSources.refusal(path.split('/')), path)
        for (path in listOf("index.html", "docs/report.md", "env/config.json", "git/notes.txt", "auth.json", "img/keyboard.png"))
            assertNull(ArtifactSources.refusal(path.split('/')), path)
    }

    @Test fun publishedPathsFollowTheServerRules() {
        for (path in listOf("index.html", "css/site.css", "a/b/c/d/e/f/g/h.txt", "_data.json", "v1+2.js")) assertTrue(ArtifactLimits.validPublishedPath(path), path)
        for (path in listOf("", "/abs.html", "../up.html", ".hidden", "a//b", "a/b/c/d/e/f/g/h/i.txt", "con.css", "trail.", "sp ace.html", "back\\slash.css"))
            assertFalse(ArtifactLimits.validPublishedPath(path), path)
        assertTrue(ArtifactLimits.validEntry("report.HTML")); assertTrue(ArtifactLimits.validEntry("notes.md")); assertFalse(ArtifactLimits.validEntry("site.css"))
    }

    @Test fun theTitleComesFromThePageAndReferencesAcceptIdsAndUrls() {
        val html = ArtifactSource("index.html", "index.html", "<html><head><TITLE> Weekly &amp; \n monthly </TITLE></head></html>".encodeToByteArray())
        assertEquals("Weekly & monthly", artifactTitle(html))
        assertEquals("Notes", artifactTitle(ArtifactSource("notes.md", "notes.md", "intro\n# Notes\ntext".encodeToByteArray())))
        assertNull(artifactTitle(ArtifactSource("index.html", "index.html", "<p>none</p>".encodeToByteArray())))
        val title = "<title>late</title>"
        assertNull(artifactTitle(ArtifactSource("index.html", "index.html", (" ".repeat(9000) + title).encodeToByteArray())), "Only the first 8 KiB are read")
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(id, artifactReference(id))
        assertEquals(id, artifactReference("https://teamcode.example.org/artifact/$id"))
        assertEquals(id, artifactReference("https://teamcode.example.org/artifact/$id?v=2"))
        assertFailsWith<CliUserException> { artifactReference("https://teamcode.example.org/dashboard") }
    }
}
