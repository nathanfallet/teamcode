package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.Capabilities
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ArtifactViewerRoutesTest {
    private val id = "00000000-0000-4000-8000-0000000000aa"
    private val page = "<html><title>Report</title><link rel=stylesheet href=site.css><script>fetch('data.json')</script></html>".encodeToByteArray()
    private val css = "body{color:red}".encodeToByteArray()
    private val notes = "# Notes <script>alert(1)</script>\n\nSee [the docs](https://example.com) and [bad](javascript:alert(1)).".encodeToByteArray()
    private fun sha(bytes: ByteArray) = ArtifactRules.sha256(bytes)
    private val origin = "http://localhost"

    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }

    /** One artifact at version 2, whose access, availability and grants the test changes. */
    private inner class Store : ArtifactStore by stub() {
        var active = true
        var access = ArtifactAccess.PERSONAL
        var relation: String? = "owner"
        var granted = true
        var explode = false
        var entry = "index.html"
        var title = "Report"
        val calls = mutableListOf<String>()
        fun artifact(relation: String) = Artifact(id, "default", "admin-id", "alice", title, "", "chart", access, true, null, 2, "active", 19, ArtifactOrigin(), 1, 2, 2, null, relation)
        fun version(number: Int) = ArtifactVersion(id, number, entry, "d".repeat(64), 3, 30, "admin-id", 1_700_000_000_000, number - 1, null, null, listOf(
            ArtifactFile("index.html", sha(page), page.size.toLong(), "text/html; charset=utf-8", if (entry == "index.html") "page" else "html"),
            ArtifactFile("notes.md", sha(notes), notes.size.toLong(), "text/markdown; charset=utf-8", if (entry == "notes.md") "page" else "asset"),
            ArtifactFile("site.css", sha(css), css.size.toLong(), "text/css; charset=utf-8", "asset")))
        private fun inactive() { if (!active) throw TeamFailure("artifacts_disabled", 409, "Off.") }
        private fun readable(actor: Principal): String {
            inactive()
            val visible = relation ?: if (access != ArtifactAccess.PERSONAL) "team" else null
            return visible ?: throw TeamFailure("not_found", 404, "Artifact not found.")
        }
        override suspend fun artifact(actor: Principal, artifactId: String) = artifact(readable(actor))
        override suspend fun version(actor: Principal, artifactId: String, number: Int): ArtifactVersion {
            readable(actor); if (number !in 1..2) throw TeamFailure("not_found", 404, "Version not found."); calls += "version:$number"; return version(number)
        }
        override suspend fun versions(actor: Principal, artifactId: String) = listOf(version(2), version(1)).also { readable(actor) }
        override suspend fun members(actor: Principal, artifactId: String) = ArtifactSharing("admin-id", listOf(ArtifactMember("bob-id", "bob", ArtifactMemberRole.VIEWER, "invited", "admin-id", 1, null)))
        override suspend fun setAccess(actor: Principal, artifactId: String, access: ArtifactAccess, followLatest: Boolean, pinnedVersion: Int?): Artifact {
            calls += "access:${access.wire}:$followLatest:$pinnedVersion"; this.access = access; return artifact("owner")
        }
        override suspend fun share(actor: Principal, artifactId: String, member: String, role: ArtifactMemberRole) = members(actor, artifactId).also { calls += "share:$member:${role.wire}" }
        override suspend fun delete(actor: Principal, artifactId: String) { calls += "delete" }
        var listed: List<Artifact>? = null
        var nextBefore: String? = null
        var query: ArtifactQuery? = null
        override suspend fun list(actor: Principal, query: ArtifactQuery) = ArtifactPage(listed ?: listOf(artifact("owner")), nextBefore).also {
            inactive(); calls += "list:${query.scope.wire}"; this.query = query
        }
        override suspend fun availability(teamId: String) = if (active) ArtifactAvailability(true, null) else ArtifactAvailability(false, "artifact_storage_unverified")
        override suspend fun publicArtifact(artifactId: String) = if (active && access == ArtifactAccess.PUBLIC && artifactId == id) PublicArtifact(artifact("public"), version(2)) else null
        override suspend fun viewable(artifactId: String, number: Int, memberId: String?, tokenId: String?): ViewableVersion? {
            if (explode) error("database unreachable")
            calls += "viewable:${memberId ?: "anonymous"}"
            if (!active || !granted || artifactId != id) return null
            if (memberId == null && access != ArtifactAccess.PUBLIC) return null
            return ViewableVersion("default", version(number))
        }
    }

    private class Admin : ArtifactAdminStore by stub() {
        override suspend fun connection(teamId: String) = ArtifactStorageConnection("https://s3.example.com", "us-east-1", "artifacts", "", true, "AKIAEXAMPLE", "synthetic-secret", ServerSideEncryption.NONE, 1)
    }
    private inner class Bucket : ArtifactObjectStore by stub() {
        val keys = mapOf(page, css, notes).associateBy { "default/artifacts/$id/blobs/${sha(it)}" }
        override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) = keys[key]?.let {
            val bytes = if (range == null) it else it.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
            StoredObject(bytes, it.size.toLong(), null, null)
        }
    }
    private fun mapOf(vararg values: ByteArray) = values.toList()

    private fun ApplicationTestBuilder.install(store: Store, runs: AgentRunService? = null, clock: () -> Long = System::currentTimeMillis) {
        val bucket = Bucket()
        val service = ArtifactService(store, Admin(), bucket)
        application { configureGateway(gateway, team = FakeTeam(), publicOrigin = origin, artifacts = service, runs = runs,
            artifactViewing = ArtifactViewing(service, store, Admin(), bucket, ArtifactTickets(ByteArray(32) { 7 }, clock), clock)) }
    }
    private suspend fun ApplicationTestBuilder.viewer(cookie: String?, path: String = "/artifact/$id") = client.get(path) { cookie?.let { header(HttpHeaders.Cookie, "teamcode_session=$it") } }
    private fun ticketOf(html: String) = Regex("src=\"/artifact/$id/([^/\"]+)/").find(html)!!.groupValues[1]
    private fun assertSandboxed(response: HttpResponse, label: String) {
        assertEquals(ARTIFACT_CONTENT_CSP, response.headers["Content-Security-Policy"], "$label: the sandbox is on the answer")
        assertTrue(response.headers["Content-Security-Policy"]!!.startsWith("sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox;"))
        assertEquals("no-referrer", response.headers["Referrer-Policy"], label); assertEquals("nosniff", response.headers["X-Content-Type-Options"], label)
        assertEquals("*", response.headers["Access-Control-Allow-Origin"], label)
        assertTrue(response.headers.getAll("Set-Cookie").isNullOrEmpty(), "$label: content never sets a cookie")
    }

    @Test fun contentIsSandboxedOnEveryStatusAndAuthorizedByItsTicketOnly() = testApplication {
        var now = 1_000_000_000_000L
        val store = Store(); install(store) { now }
        val shell = viewer("member")
        assertEquals(HttpStatusCode.OK, shell.status)
        val html = shell.bodyAsText()
        assertEquals(ARTIFACT_VIEWER_CSP, shell.headers["Content-Security-Policy"])
        assertTrue("frame-ancestors 'none'" in shell.headers["Content-Security-Policy"]!! && "frame-src 'self'" in shell.headers["Content-Security-Policy"]!!)
        assertTrue("""sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"""" in html && "/index.html\"" in html, "The frame loads the entry page under the ticket")
        val ticket = ticketOf(html)
        val base = "/artifact/$id/$ticket"

        // 200: the entry page with its skeleton; the session cookie is not needed, the ticket is.
        val entry = client.get("$base/index.html")
        assertEquals(HttpStatusCode.OK, entry.status); assertSandboxed(entry, "200")
        assertTrue(entry.bodyAsText().startsWith("<!doctype html><meta charset=\"utf-8\">")); assertEquals(ContentType.Text.Html, entry.contentType()?.withoutParameters())
        assertEquals("private, max-age=30, no-transform", entry.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, client.get("$base/").status, "The bare prefix is the entry page")
        // 206: a single range of another file.
        val part = client.get("$base/site.css") { header(HttpHeaders.Range, "bytes=0-3") }
        assertEquals(HttpStatusCode.PartialContent, part.status); assertSandboxed(part, "206")
        assertEquals("bytes 0-3/${css.size}", part.headers[HttpHeaders.ContentRange]); assertEquals("body", part.bodyAsText())
        // 304: the cached copy is still current.
        val etag = client.get("$base/site.css").headers[HttpHeaders.ETag]!!
        val cached = client.get("$base/site.css") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, cached.status); assertSandboxed(cached, "304")
        // 416: outside the file, or several ranges.
        for (range in listOf("bytes=900-999", "bytes=0-1,4-5")) {
            val refused = client.get("$base/site.css") { header(HttpHeaders.Range, range) }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, refused.status, range); assertSandboxed(refused, "416")
            assertEquals("bytes */${css.size}", refused.headers[HttpHeaders.ContentRange])
            assertEquals(CACHE_CONTROL, refused.headers[HttpHeaders.CacheControl], "A refusal is never cached as the file")
        }
        // 404: a missing file, a forged ticket, a ticket of another artifact, dot segments.
        val other = "00000000-0000-4000-8000-0000000000bb"
        for (path in listOf("$base/missing.js", "/artifact/$id/${ticket.dropLast(2)}xx/index.html", "/artifact/$other/$ticket/index.html", "/artifact/$id/not-a-ticket/index.html", "$base/a/../index.html")) {
            val missing = client.get(path) { header(HttpHeaders.Cookie, "teamcode_session=admin") }
            assertEquals(HttpStatusCode.NotFound, missing.status, path); assertSandboxed(missing, "404 $path")
            assertEquals(CACHE_CONTROL, missing.headers[HttpHeaders.CacheControl]); assertFalse("<nav" in missing.bodyAsText(), "The dashboard is never served under the prefix")
            assertNull(missing.headers[HttpHeaders.ETag])
        }
        // 405 from the framework, and 500 from an unexpected failure.
        val post = client.post("$base/index.html")
        assertSandboxed(post, "framework ${post.status}"); assertTrue(post.status.value in 400..499)
        // Past the recheck, the store fails: an unexpected error is a 500, still sandboxed.
        store.explode = true
        now += 31_000
        val failed = client.get("$base/site.css")
        assertEquals(HttpStatusCode.InternalServerError, failed.status); assertSandboxed(failed, "500"); assertEquals(CACHE_CONTROL, failed.headers[HttpHeaders.CacheControl])
        store.explode = false
        // Markdown that is not the entry page is text, never HTML.
        val text = client.get("$base/notes.md")
        assertEquals(ContentType.Text.Plain, text.contentType()?.withoutParameters()); assertSandboxed(text, "markdown asset")
    }

    @Test fun aRevokedGrantStopsTheContentWithinTheRecheck() = testApplication {
        var now = 1_000_000_000_000L
        val store = Store(); install(store) { now }
        val ticket = ticketOf(viewer("member").bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/artifact/$id/$ticket/site.css").status)
        // Signed out, or access removed: the cached grant still serves for at most 30 seconds, then never again.
        store.granted = false
        now += 10_000
        assertEquals(HttpStatusCode.OK, client.get("/artifact/$id/$ticket/site.css").status)
        now += 25_000
        assertEquals(HttpStatusCode.NotFound, client.get("/artifact/$id/$ticket/site.css").status)
        store.granted = true
        // A ticket expires with the dashboard session's lifetime.
        now += 8 * 3_600_000L
        assertEquals(HttpStatusCode.NotFound, client.get("/artifact/$id/$ticket/site.css").status)
        assertEquals(1, store.calls.count { it == "version:2" }, "The shell reads the version once; content requests never audit")
    }

    @Test fun theViewerSignsInForPrivateArtifactsAndOpensPublicOnesToAnyone() = testApplication {
        var now = 1_000_000_000_000L
        val store = Store(); install(store) { now }
        val noRedirect = client.config { followRedirects = false }
        val anonymous = noRedirect.get("/artifact/$id?v=2")
        assertEquals(HttpStatusCode.Found, anonymous.status)
        assertEquals("/dashboard/login?next=" + "/artifact/$id?v=2".encodeURLParameter(), anonymous.headers[HttpHeaders.Location])
        store.relation = null
        assertEquals(HttpStatusCode.NotFound, viewer("member").status, "Signed in without access: 404, as if it did not exist")

        store.relation = null; store.access = ArtifactAccess.PUBLIC
        val public = viewer(null)
        assertEquals(HttpStatusCode.OK, public.status)
        val body = public.bodyAsText()
        assertTrue("not verified" in body, "A visitor sees the unverified banner"); assertFalse("alice" in body, "and not the author")
        assertFalse("csrf" in body || "artifact-viewer.js" in body, "No form and no script for a visitor")
        assertEquals(HttpStatusCode.Found, noRedirect.get("/artifact/$id?v=1").status, "A visitor sees only the version the link serves; any other asks to sign in")
        val ticket = ticketOf(body)
        val content = client.get("/artifact/$id/$ticket/index.html")
        assertEquals(HttpStatusCode.OK, content.status); assertEquals("private, max-age=30, no-transform", content.headers[HttpHeaders.CacheControl])
        assertTrue("viewable:anonymous" in store.calls)
        // Back to personal: the link asks to sign in, and the anonymous ticket stops at its next check.
        store.access = ArtifactAccess.PERSONAL
        assertEquals(HttpStatusCode.Found, noRedirect.get("/artifact/$id").status)
        now += 31_000
        assertEquals(HttpStatusCode.NotFound, client.get("/artifact/$id/$ticket/index.html").status)

        // A member reading a public artifact as a member sees its author and no banner.
        store.access = ArtifactAccess.PUBLIC; store.relation = "team"
        val member = viewer("member").bodyAsText()
        assertTrue("alice" in member); assertFalse("not verified" in member)
    }

    @Test fun inactiveArtifactsAnswer404AndOnlyAdministratorsSeeTheMenu() = testApplication {
        val store = Store(); install(store)
        val noRedirect = client.config { followRedirects = false }
        val ticket = ticketOf(viewer("member").bodyAsText())
        assertTrue("href=\"$ARTIFACT_GALLERY_PATH\"" in viewer("member", "/dashboard").bodyAsText(), "Members see Artifacts while they are active")
        assertEquals(HttpStatusCode.OK, viewer("member", ARTIFACT_GALLERY_PATH).status)
        val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText()).features
        assertTrue("artifacts.v1" in features)

        store.active = false
        for (cookie in listOf("member", "admin", null)) {
            assertEquals(HttpStatusCode.NotFound, noRedirect.get("/artifact/$id") { cookie?.let { header(HttpHeaders.Cookie, "teamcode_session=$it") } }.status, "viewer for $cookie")
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/artifact/$id/$ticket/index.html").status, "A ticket issued before serves nothing once artifacts are off")
        assertFalse("href=\"$ARTIFACT_GALLERY_PATH\"" in viewer("member", "/dashboard").bodyAsText(), "Members do not see the menu while artifacts are off")
        assertTrue("href=\"$ARTIFACT_GALLERY_PATH\"" in viewer("admin", "/dashboard").bodyAsText(), "Administrators do")
        val admin = noRedirect.get(ARTIFACT_GALLERY_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(HttpStatusCode.Found, admin.status); assertEquals(ARTIFACT_SETTINGS_PATH, admin.headers[HttpHeaders.Location], "and are sent to Settings → Artifacts")
        assertEquals(HttpStatusCode.NotFound, viewer("member", ARTIFACT_GALLERY_PATH).status)
    }

    @Test fun theGalleryFiltersByOriginAndAccessAndLinksRunsToWhatTheyPublished() = testApplication {
        val run = "11111111-2222-4333-8444-555555555555"
        val session = "22222222-3333-4444-8555-666666666666"
        val runs = object : AgentRunService by stub() {
            override suspend fun get(actor: Principal, id: String) = RunDetail(AgentRun(id, "admin-id", "alice", "claude", "interactive", null, 1000, 2000, null, null, null,
                RunState.RUNNING, RunPolicy(), 0, 0, 0, 0, false), emptyList(), false)
        }
        val store = Store(); install(store, runs = runs)
        val base = store.artifact("owner")
        store.listed = listOf(
            base.copy(title = "<script>alert(1)</script>", origin = ArtifactOrigin("local_run", run)),
            base.copy(id = "00000000-0000-4000-8000-0000000000ab", title = "Cloud", origin = ArtifactOrigin("cloud_session", null, session, 2)),
            base.copy(id = "00000000-0000-4000-8000-0000000000ac", title = "Direct", origin = ArtifactOrigin("api")))
        store.nextBefore = "2:00000000-0000-4000-8000-0000000000ac"

        val html = viewer("member", "$ARTIFACT_GALLERY_PATH?scope=mine&origin=cloud_session&access=team&session=$session").bodyAsText()
        assertEquals(ArtifactQuery(ArtifactScope.MINE, sessionId = session, originKind = "cloud_session", access = ArtifactAccess.TEAM), store.query)
        assertFalse("<script>alert(1)</script>" in html); assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html, "Titles are escaped")
        assertTrue("""<option value="cloud_session" selected>""" in html && """<option value="team" selected>""" in html, "The form shows the filters in force")
        assertTrue("""<input type="hidden" name="session" value="$session">""" in html)
        assertTrue("""href="$ARTIFACT_GALLERY_PATH?scope=team&amp;origin=cloud_session&amp;access=team&amp;session=$session"""" in html, "Tabs keep the filters")
        assertTrue("""href="$ARTIFACT_GALLERY_PATH?scope=mine&amp;origin=cloud_session&amp;access=team&amp;session=$session&amp;before=2%3A00000000-0000-4000-8000-0000000000ac"""" in html, "So does the next page")
        assertTrue("""href="$ARTIFACT_GALLERY_PATH?scope=mine&amp;origin=cloud_session&amp;access=team">Show every origin""" in html)
        // Each card links its origin to what the same run or session published.
        assertTrue("""<a href="$ARTIFACT_GALLERY_PATH?scope=mine&amp;run=$run">local run 11111111</a>""" in html, html)
        assertTrue("""<a href="$ARTIFACT_GALLERY_PATH?scope=mine&amp;session=$session">cloud session 22222222</a>""" in html)
        assertTrue(" · API · " in html)

        assertEquals(HttpStatusCode.BadRequest, viewer("member", "$ARTIFACT_GALLERY_PATH?origin=elsewhere").status)
        assertEquals(HttpStatusCode.BadRequest, viewer("member", "$ARTIFACT_GALLERY_PATH?access=secret").status)
        store.listed = emptyList(); store.nextBefore = null
        assertTrue("Clear the filters" in viewer("member", "$ARTIFACT_GALLERY_PATH?origin=api").bodyAsText())
        assertTrue("No artifact here yet" in viewer("member", ARTIFACT_GALLERY_PATH).bodyAsText())
        // The API takes the same filters.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/artifacts?origin=local_run&access=public") { bearerAuth("member") }.status)
        assertEquals(ArtifactQuery(originKind = "local_run", access = ArtifactAccess.PUBLIC), store.query)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/artifacts?access=everyone") { bearerAuth("member") }.status)

        // A launch's page links to its artifacts while they are on, and only then.
        assertTrue("""href="$ARTIFACT_GALLERY_PATH?scope=team&amp;run=$run">Artifacts published from this launch""" in viewer("member", "/dashboard/sessions/$run").bodyAsText())
        assertTrue("""scope=all&amp;run=$run""" in viewer("admin", "/dashboard/sessions/$run").bodyAsText(), "Administrators see every member's")
        store.active = false
        assertFalse("Artifacts published from this launch" in viewer("member", "/dashboard/sessions/$run").bodyAsText())
    }

    @Test fun theDashboardIsNeverFramedAndFormsManageTheArtifact() = testApplication {
        val store = Store(); install(store)
        for (path in listOf("/dashboard", "/artifact/$id", ARTIFACT_GALLERY_PATH)) {
            val framed = client.get(path) { header(HttpHeaders.Cookie, "teamcode_session=admin"); header("Sec-Fetch-Dest", "iframe") }
            assertEquals(HttpStatusCode.Forbidden, framed.status, path)
        }
        assertTrue("frame-ancestors 'none'" in viewer("admin", "/dashboard").headers["Content-Security-Policy"]!!)

        val csrf = FakeTeam().authenticate("admin")!!.tokenId
        suspend fun form(path: String, body: String) = client.config { followRedirects = false }.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$body")
        }
        val page = viewer("admin").bodyAsText()
        assertTrue("Share" in page && "bob" in page && "Delete this artifact" in page && "Version 1 only" in page)
        assertEquals(HttpStatusCode.Found, form("$ARTIFACT_GALLERY_PATH/$id/access", "access=public&publicVersion=1").status)
        assertEquals(HttpStatusCode.Found, form("$ARTIFACT_GALLERY_PATH/$id/members", "member=carol&role=editor").status)
        assertEquals(HttpStatusCode.BadRequest, form("$ARTIFACT_GALLERY_PATH/$id/delete", "confirm=Wrong").status, "Deleting asks for the exact title")
        assertEquals(HttpStatusCode.Found, form("$ARTIFACT_GALLERY_PATH/$id/delete", "confirm=Report").status)
        assertEquals(listOf("access:public:false:1", "share:carol:editor", "delete"), store.calls.filter { it.startsWith("access") || it.startsWith("share") || it == "delete" })
        val foreign = client.post("$ARTIFACT_GALLERY_PATH/$id/delete") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://evil.example"); contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&confirm=Report")
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals("""{"version":2}""", viewer("member", "$ARTIFACT_GALLERY_PATH/$id/live").bodyAsText())
        assertTrue("artifact-viewer.js" in viewer("member").bodyAsText(), "The latest version is followed")
        assertFalse("artifact-viewer.js" in viewer("member", "/artifact/$id?v=1").bodyAsText(), "A chosen version is not")
        val script = viewer("member", "/dashboard/assets/artifact-viewer.js")
        assertEquals(HttpStatusCode.OK, script.status); assertTrue("/live" in script.bodyAsText())
    }

    @Test fun signingInContinuesToTheArtifactAndSetsTheCookieForItsPath() = testApplication {
        val store = Store(); install(store)
        val noRedirect = client.config { followRedirects = false }
        val next = "/artifact/$id?v=2"
        val login = noRedirect.post("/dashboard/login?next=${next.encodeURLParameter()}") {
            header(HttpHeaders.Origin, origin); contentType(ContentType.Application.FormUrlEncoded); setBody("username=admin&password=secret")
        }
        assertEquals(next, login.headers[HttpHeaders.Location])
        val cookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertEquals(setOf("/dashboard", "/artifact"), cookies.map { Regex("Path=([^;]+)").find(it)!!.groupValues[1] }.toSet())
        assertTrue(cookies.all { "HttpOnly" in it })
        // Strict for the dashboard; Lax for the viewer, so a link opened from another site finds the member signed in.
        assertTrue(cookies.single { "Path=/dashboard" in it }.contains("SameSite=Strict")); assertTrue(cookies.single { "Path=/artifact" in it }.contains("SameSite=Lax"))
        for (target in listOf("https://evil.example/", "//evil.example/artifact/$id", "/dashboard/members", "/artifact/$id/ticket/x")) {
            val elsewhere = noRedirect.post("/dashboard/login?next=${target.encodeURLParameter()}") {
                header(HttpHeaders.Origin, origin); contentType(ContentType.Application.FormUrlEncoded); setBody("username=admin&password=secret")
            }
            assertEquals("/dashboard", elsewhere.headers[HttpHeaders.Location], target)
        }
        // Already signed in on the dashboard: the artifact cookie is set from the same session, without signing in again.
        val again = noRedirect.get("/dashboard/login?next=${next.encodeURLParameter()}") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals(next, again.headers[HttpHeaders.Location]); assertTrue(again.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { "Path=/artifact" in it })
    }

    @Test fun markdownEntryPagesAreRenderedEscaped() = testApplication {
        val store = Store().apply { entry = "notes.md" }; install(store)
        val ticket = ticketOf(viewer("member").bodyAsText())
        val rendered = client.get("/artifact/$id/$ticket/notes.md")
        assertEquals(ContentType.Text.Html, rendered.contentType()?.withoutParameters())
        val html = rendered.bodyAsText()
        assertTrue("<h1>Notes &lt;script&gt;alert(1)&lt;/script&gt;</h1>" in html, html)
        assertTrue("""<a href="https://example.com" target="_blank" rel="noopener noreferrer">the docs</a>""" in html)
        assertFalse("javascript:" in html.substringAfter("<body>").let { Regex("href=\"[^\"]*\"").findAll(it).joinToString { m -> m.value } }, "No script link survives")
    }

    @Test fun markdownKeepsLinkTargetsAndSurvivesCraftedInput() {
        val html = Markdown.render("[init](https://example.com/__init__.py) and **bold** in _a_ `code` \u00005\u0000\n\n" + ">".repeat(100_000) + " deep")
        assertTrue("""href="https://example.com/__init__.py"""" in html, html)
        assertTrue("<strong>bold</strong>" in html && "<em>a</em>" in html && "<code>code</code>" in html)
        assertFalse('\u0000' in html)
    }

    @Test fun entryPagesAndHeadingsKeepWhatTheAuthorWrote() {
        val latin1 = "<!doctype html><meta charset=\"windows-1252\"><p>caf".encodeToByteArray() + byteArrayOf(0xE9.toByte())
        assertEquals(latin1.toList() to false, ArtifactPages.html(latin1).let { it.first.toList() to it.second }, "A page with its doctype is sent byte for byte")
        val bare = ArtifactPages.html("<p>x</p>".encodeToByteArray())
        assertTrue(bare.second && bare.first.decodeToString().startsWith("<!doctype html><meta charset=\"utf-8\">"))
        assertFalse("charset" in ArtifactPages.html(byteArrayOf(0xE9.toByte())).first.decodeToString(), "A page in another encoding is not declared UTF-8")
        val commented = "<!-- built by an agent -->\n<!DOCTYPE html><p>x</p>".encodeToByteArray()
        assertEquals(commented.toList(), ArtifactPages.html(commented).first.toList(), "A doctype after a comment is still the page's own")
        val linked = Markdown.render("[x](a`b`) and `c`")
        assertFalse('\u0000' in linked || "href" in linked, linked); assertTrue("<code>c</code>" in linked)
        assertTrue("<h1>Learning C#</h1>" in Markdown.render("# Learning C#"))
        assertTrue("<h2>Issue</h2>" in Markdown.render("## Issue ##"))
        val started = System.nanoTime()
        Markdown.render("[".repeat(2_000_000))
        assertTrue(System.nanoTime() - started < 5_000_000_000, "Unclosed brackets are rendered in linear time")
    }

    @Test fun anUntitledArtifactIsDeletedByTypingUntitled() = testApplication {
        val store = Store().apply { title = "" }; install(store)
        val csrf = FakeTeam().authenticate("admin")!!.tokenId
        val deleted = client.config { followRedirects = false }.post("$ARTIFACT_GALLERY_PATH/$id/delete") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&confirm=Untitled")
        }
        assertEquals(HttpStatusCode.Found, deleted.status); assertTrue("delete" in store.calls)
    }

    private suspend fun ApplicationTestBuilder.ticket(token: String?, body: String? = null) = client.post("/api/v1/artifacts/$id/tickets") {
        token?.let { bearerAuth(it) }
        body?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

    @Test fun anAppGetsAMemberTicketWithTheViewerRightsAndNothingMore() = testApplication {
        val store = Store(); install(store)
        val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText()).features
        assertTrue("artifacts.tickets.v1" in features)
        // The current version by default; the content URL serves the entry page and its files, no credential needed.
        val issued = ticket("member")
        assertEquals(HttpStatusCode.OK, issued.status)
        val view = Serialization.json.decodeFromString<me.nathanfallet.teamcode.api.models.ArtifactTicketView>(issued.bodyAsText())
        assertEquals(2, view.version)
        assertTrue(view.contentUrl.startsWith("$origin/artifact/$id/") && view.contentUrl.endsWith("/index.html"))
        val path = view.contentUrl.removePrefix(origin)
        assertEquals(HttpStatusCode.OK, client.get(path).status)
        // The entry page by name, so its relative links resolve beside it.
        assertEquals(HttpStatusCode.OK, client.get(path.substringBeforeLast('/') + "/site.css").status)
        assertEquals(0, view.expiresAt % ArtifactViewing.TICKET_STEP_MILLIS)
        // The same ticket as the viewer page: one version read, which is what audits an administrator's look.
        assertEquals(1, store.calls.count { it == "version:2" })
        // An older version on request; a version that does not exist, or a member without access, is 404.
        assertEquals(1, Serialization.json.decodeFromString<me.nathanfallet.teamcode.api.models.ArtifactTicketView>(ticket("member", "{\"version\":1}").bodyAsText()).version)
        assertEquals(HttpStatusCode.NotFound, ticket("member", "{\"version\":3}").status)
        // No body at all, an empty one without a content type, or a malformed one.
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/artifacts/$id/tickets") { bearerAuth("member"); setBody("") }.status)
        assertEquals(HttpStatusCode.BadRequest, ticket("member", "not json").status)
        assertEquals(HttpStatusCode.NotFound, ticket("member", "{\"version\":0}").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/artifacts/not-an-id/tickets") { bearerAuth("member") }.status)
        store.relation = null
        assertEquals(HttpStatusCode.NotFound, ticket("member").status, "Personal and not shared")
        store.relation = "owner"; store.active = false
        assertEquals(HttpStatusCode.NotFound, ticket("member").status, "Artifacts turned off")
        store.active = true
        // Signed out, or a service key (the route is interactive only).
        assertEquals(HttpStatusCode.Unauthorized, ticket(null).status)
        assertEquals(HttpStatusCode.Forbidden, ticket("service").status)
    }

    @Test fun ticketsAreLimitedPerLoginSessionAndMinute() = testApplication {
        var now = 1_000_000_000_000L
        val store = Store(); install(store) { now }
        repeat(ArtifactViewing.TICKETS_PER_MINUTE) { assertEquals(HttpStatusCode.OK, ticket("member").status, "ticket ${it + 1}") }
        val refused = ticket("member")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("60", refused.headers[HttpHeaders.RetryAfter])
        assertTrue("artifact_ticket_rate_limited" in refused.bodyAsText())
        now += 60_000
        assertEquals(HttpStatusCode.OK, ticket("member").status, "A new minute")
    }

    @Test fun phonesAreOfferedToOpenTheArtifactInTheApp() = testApplication {
        val store = Store(); install(store)
        fun agent(ua: String) = suspend { client.get("/artifact/$id?v=1") { header(HttpHeaders.Cookie, "teamcode_session=member"); header(HttpHeaders.UserAgent, ua) }.bodyAsText() }
        val phone = agent("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)")()
        assertTrue("href=\"teamcode://open?url=${"$origin/artifact/$id?v=1".encodeURLParameter()}\"" in phone, phone)
        assertFalse("teamcode://" in agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0)")())
        // A visitor of a public link has no account to open it with.
        store.access = ArtifactAccess.PUBLIC
        assertFalse("teamcode://" in client.get("/artifact/$id") { header(HttpHeaders.UserAgent, "Android 15") }.bodyAsText())
    }
}

/** An implementation that fails on any call: the delegating fakes above override only what the routes use. */
private inline fun <reified T : Any> stub(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    if (method.name == "toString") "stub" else error("Unexpected call to ${method.name}.")
} as T
