package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ArtifactSettingsRoutesTest {
    private class Store : ArtifactAdminStore {
        var stored: ArtifactStorage? = null
        var secret: String? = null
        var settings = ArtifactSettings()
        override suspend fun storage(actor: Principal) = stored
        override suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage {
            if ((stored?.version ?: 0) != expectedVersion) throw TeamFailure("artifact_storage_conflict", 409, "Changed.")
            secret = input.secretAccessKey ?: secret ?: throw TeamFailure("artifact_storage_secret_required", 400, "Secret.")
            return ArtifactStorage(actor.teamId, input.endpoint, input.region, input.bucket, input.prefix, input.pathStyle, input.accessKeyId, input.serverSideEncryption, null, expectedVersion + 1, 5)
                .also { stored = it }
        }
        override suspend fun inUse(actor: Principal) = false
        var usage = emptyList<ArtifactMemberUsage>()
        override suspend fun overview(actor: Principal) = ArtifactAdminOverview(stored, settings, false, usage)
        override suspend fun deleteStorage(actor: Principal) { stored ?: throw TeamFailure("artifact_storage_missing", 404, "None."); stored = null; settings = settings.copy(enabled = false) }
        override suspend fun recordTest(actor: Principal, version: Int, passed: Boolean): Boolean { stored = stored?.copy(verifiedAt = if (passed) 9 else null); return stored != null }
        override suspend fun settings(actor: Principal) = settings
        override suspend fun saveSettings(actor: Principal, settings: ArtifactSettings): ArtifactSettings {
            if (settings.version != this.settings.version) throw TeamFailure("artifact_settings_conflict", 409, "Changed.")
            if (settings.enabled && stored == null) throw TeamFailure("artifact_storage_missing", 409, "No storage.")
            if (settings.enabled && stored?.verifiedAt == null) throw TeamFailure("artifact_storage_unverified", 409, "Not tested.")
            return settings.copy(version = settings.version + 1).also { this.settings = it }
        }
        override suspend fun connection(teamId: String) = stored?.let { ArtifactStorageConnection(it.endpoint, it.region, it.bucket, it.prefix, it.pathStyle, it.accessKeyId, secret!!, it.serverSideEncryption, it.version) }
    }
    private class Objects : ArtifactObjectStore {
        val keys = mutableMapOf<String, ByteArray>()
        var denied = false
        override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { if (denied) throw ObjectStoreException(403, "AccessDenied"); keys[key] = bytes }
        override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) = keys[key]?.let { StoredObject(it, it.size.toLong(), null, null) }
        override suspend fun head(connection: ArtifactStorageConnection, key: String) = keys[key]?.let { ObjectHead(it.size.toLong(), null) }
        override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int) = ObjectListing(keys.keys.filter { it.startsWith(prefix) }.map { ListedObject(it, 1) }, null)
        override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> { keys.forEach(this.keys::remove); return emptyList() }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "admin-cloud" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.ADMIN_CLOUD))
                "monitor" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.TEAM_READ))
                else -> interactive.authenticate(token)
            }
        }
    }
    private val storageBody = """{"version":0,"endpoint":"https://s3.gra.io.cloud.ovh.net","region":"gra","bucket":"artifacts","prefix":"teamcode","accessKeyId":"AKIAEXAMPLE","secretAccessKey":"synthetic-secret-access-key","serverSideEncryption":"AES256"}"""
    private fun settingsBody(version: Int, enabled: Boolean) =
        """{"version":$version,"enabled":$enabled,"whoCanPublish":"everyone","publicSharing":"admins","quotaMemberBytes":2147483648,"quotaTeamBytes":53687091200,"quotaArtifactsPerMember":500,"quotaPublishesPerHour":60,"personalRetentionDays":null,"sharedRetentionDays":90,"maxVersionsPerArtifact":50}"""

    @Test fun administratorsConfigureTestAndTurnOnArtifactsOverTheApi() = testApplication {
        val store = Store(); val objects = Objects()
        application { configureGateway(gateway, team = team(), artifactAdmin = ArtifactAdministrationService(store, objects)) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token); if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        val features = Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features
        assertFalse(features.any { it.startsWith("artifacts") }, "Nothing is advertised before artifacts can be published and served")

        for (token in listOf("member", "admin-cloud", "monitor")) {
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/artifacts/storage", token).status, "$token does not read the storage")
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/artifacts/storage", token, storageBody).status, "$token does not save it")
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/artifacts/settings", token).status, "$token does not read the settings")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/artifacts/storage").status)

        val empty = Serialization.json.decodeFromString<ArtifactStorageView>(call(HttpMethod.Get, "/api/v1/artifacts/storage", "admin").bodyAsText())
        assertFalse(empty.configured)
        val missing = Serialization.json.decodeFromString<ArtifactSettingsView>(call(HttpMethod.Get, "/api/v1/artifacts/settings", "admin").bodyAsText())
        assertFalse(missing.active); assertEquals("artifact_storage_missing", missing.inactiveReason)
        val refused = call(HttpMethod.Put, "/api/v1/artifacts/settings", "admin", settingsBody(0, true))
        assertEquals(HttpStatusCode.Conflict, refused.status); assertTrue("artifact_storage_missing" in refused.bodyAsText())

        val saved = call(HttpMethod.Put, "/api/v1/artifacts/storage", "admin", storageBody)
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        for (body in listOf(saved.bodyAsText(), call(HttpMethod.Get, "/api/v1/artifacts/storage", "admin").bodyAsText())) {
            assertFalse("synthetic-secret-access-key" in body, "The secret is never returned")
            val view = Serialization.json.decodeFromString<ArtifactStorageView>(body)
            assertTrue(view.configured && view.secretConfigured); assertEquals("AES256", view.serverSideEncryption); assertNull(view.verifiedAt)
        }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/artifacts/storage", "admin", storageBody.replace("\"version\":0", "\"version\":1").replace("https://s3", "http://s3")).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/artifacts/storage", "admin", storageBody.replace("\"version\":0", "\"version\":1").replace("AES256", "aws:kms")).status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/v1/artifacts/storage", "admin", storageBody).status, "The version being replaced is checked")
        assertEquals("artifact_storage_unverified", Serialization.json.decodeFromString<ArtifactSettingsView>(call(HttpMethod.Get, "/api/v1/artifacts/settings", "admin").bodyAsText()).inactiveReason)
        assertTrue("artifact_storage_unverified" in call(HttpMethod.Put, "/api/v1/artifacts/settings", "admin", settingsBody(0, true)).bodyAsText())

        objects.denied = true
        val failed = Serialization.json.decodeFromString<ArtifactStorageTestView>(call(HttpMethod.Post, "/api/v1/artifacts/storage/test", "admin").bodyAsText())
        assertFalse(failed.ok); assertEquals(listOf(ArtifactStorageTestStepView("put", false, 403, "AccessDenied")), failed.steps)
        objects.denied = false
        val passed = Serialization.json.decodeFromString<ArtifactStorageTestView>(call(HttpMethod.Post, "/api/v1/artifacts/storage/test", "admin").bodyAsText())
        assertTrue(passed.ok); assertEquals(4, passed.steps.size)

        val on = Serialization.json.decodeFromString<ArtifactSettingsView>(call(HttpMethod.Put, "/api/v1/artifacts/settings", "admin", settingsBody(0, true)).bodyAsText())
        assertTrue(on.enabled && on.active); assertNull(on.inactiveReason); assertEquals("admins", on.publicSharing); assertEquals(90, on.sharedRetentionDays)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/artifacts/settings", "admin", settingsBody(1, true).replace("\"admins\"", "\"anyone\"")).status)

        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/v1/artifacts/storage", "admin").status)
        val removed = Serialization.json.decodeFromString<ArtifactSettingsView>(call(HttpMethod.Get, "/api/v1/artifacts/settings", "admin").bodyAsText())
        assertFalse(removed.enabled || removed.active); assertEquals("artifact_storage_missing", removed.inactiveReason)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/artifacts/storage", "admin").status)
    }

    @Test fun theSettingsPageIsForAdministratorsAndNeverShowsTheSecret() = testApplication {
        val store = Store(); val objects = Objects()
        application { configureGateway(gateway, team = team(), artifactAdmin = ArtifactAdministrationService(store, objects), publicOrigin = "http://localhost") }
        val csrf = FakeTeam().authenticate("admin")!!.tokenId
        suspend fun form(path: String, body: String) = client.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$body")
        }
        val page = client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue("No storage is configured" in page && "Artifacts are off" in page && "Save storage" in page)
        assertTrue("href=\"$ARTIFACT_SETTINGS_PATH\"" in page, "The menu links to the page for administrators")
        val memberPage = client.get("/dashboard/settings") { header(HttpHeaders.Cookie, "teamcode_session=member") }.bodyAsText()
        assertFalse(ARTIFACT_SETTINGS_PATH in memberPage, "Members do not see the link")
        assertEquals(HttpStatusCode.Forbidden, client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)

        val saved = form("$ARTIFACT_SETTINGS_PATH/storage", "version=0&endpoint=https%3A%2F%2Fs3.gra.io.cloud.ovh.net&region=gra&bucket=artifacts&prefix=&pathStyle=on&accessKeyId=AKIAEXAMPLE&secretAccessKey=synthetic-secret-access-key&serverSideEncryption=none")
        assertEquals(HttpStatusCode.Found, saved.status, saved.bodyAsText())
        assertTrue(store.stored!!.pathStyle); assertEquals("synthetic-secret-access-key", store.secret)
        val again = client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertFalse("synthetic-secret-access-key" in again); assertTrue("not tested since the last change" in again && "Test the connection" in again)
        // An empty secret keeps the stored one.
        form("$ARTIFACT_SETTINGS_PATH/storage", "version=1&endpoint=https%3A%2F%2Fs3.gra.io.cloud.ovh.net&region=gra&bucket=artifacts&prefix=&accessKeyId=AKIAOTHER&secretAccessKey=&serverSideEncryption=none")
        assertEquals("synthetic-secret-access-key", store.secret); assertEquals("AKIAOTHER", store.stored!!.accessKeyId)

        val tested = form("$ARTIFACT_SETTINGS_PATH/storage/test", "").bodyAsText()
        assertTrue("The bucket works" in tested, tested)
        val on = form("$ARTIFACT_SETTINGS_PATH/settings", "version=0&enabled=on&whoCanPublish=admins&publicSharing=off&quotaMemberGib=1.5&quotaTeamGib=20&quotaArtifactsPerMember=100&quotaPublishesPerHour=30&personalRetentionDays=&sharedRetentionDays=60&maxVersionsPerArtifact=20")
        assertEquals(HttpStatusCode.Found, on.status, on.bodyAsText())
        assertEquals(ArtifactSettings(true, WhoCanPublish.ADMINS, PublicSharing.OFF, ArtifactQuotas(1_610_612_736, 20 * ArtifactAdministrationService.GIB, 100, 30), ArtifactRetention(null, 60, 20), 1), store.settings)
        // A quota that is not a round number of GiB survives a save of another field.
        store.settings = store.settings.copy(quotas = store.settings.quotas.copy(memberBytes = 1_572_864))
        val shown = client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        val typed = Regex("name=\"quotaMemberGib\"[^>]*value=\"([^\"]*)\"").find(shown)!!.groupValues[1]
        form("$ARTIFACT_SETTINGS_PATH/settings", "version=1&enabled=on&whoCanPublish=admins&publicSharing=off&quotaMemberGib=$typed&quotaMemberBytes=1572864&quotaTeamGib=20&quotaTeamBytes=${20 * ArtifactAdministrationService.GIB}&quotaArtifactsPerMember=100&quotaPublishesPerHour=30&personalRetentionDays=&sharedRetentionDays=60&maxVersionsPerArtifact=21")
        assertEquals(1_572_864, store.settings.quotas.memberBytes); assertEquals(21, store.settings.retention.maxVersionsPerArtifact)
        assertTrue("Artifacts are on" in client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText())
        assertTrue("No artifact is stored" in shown)
        // Usage per member, escaped, against the member quota.
        store.usage = listOf(ArtifactMemberUsage("bob-id", "<bob>", 3, 786_432), ArtifactMemberUsage("gone-id", "", 1, 1024))
        val usage = client.get(ARTIFACT_SETTINGS_PATH) { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertFalse("<bob>" in usage)
        assertTrue("""<tr><td>&lt;bob&gt;</td><td class="num">3</td><td class="num">768.0 KiB</td><td class="num">50 %</td></tr>""" in usage, usage)
        assertTrue("<td>gone-id</td>" in usage, "A removed member is shown by id")
        assertTrue("769.0 KiB of 20.0 GiB stored for the team" in usage)
        assertEquals(HttpStatusCode.Forbidden, client.post("$ARTIFACT_SETTINGS_PATH/storage/delete") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://evil.example"); contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf")
        }.status, "A form from another origin is refused")
    }
}
