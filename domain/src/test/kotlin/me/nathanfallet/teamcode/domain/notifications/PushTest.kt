package me.nathanfallet.teamcode.domain.notifications

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class PushTest {
    private val alice = Principal(Member("alice", "alice", Role.MEMBER, false, false), "token-a", "team")
    private val admin = Principal(Member("root", "root", Role.ADMIN, false, false), "token-r", "team")
    private val key = Principal(Member("alice", "alice", Role.MEMBER, false, false), "key-a", "team", setOf(ServiceScope.INFERENCE))

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun KeyPair.publicBase64() = Base64.getEncoder().encodeToString(public.encoded)
    private val token = "f".repeat(30) + ":APA91b-token_value"

    /** What the app does: the documented format, decrypted with the device's private key. */
    private fun decrypt(privateKey: PrivateKey, recipientPublic: String, payload: String): String {
        val bytes = Base64.getDecoder().decode(payload)
        assertEquals(1, bytes[0].toInt())
        val ephemeralPoint = bytes.copyOfRange(1, 66)
        val nonce = bytes.copyOfRange(66, 78)
        val ephemeral = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(
            // The SPKI header of an uncompressed P-256 point.
            Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgA=") + ephemeralPoint))
        val secret = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(ephemeral, true); generateSecret() }
        val aes = PushPayloads.hkdf(secret, ephemeralPoint + PushPayloads.point(PushPayloads.publicKey(recipientPublic)), "teamcode-push-v1".encodeToByteArray(), 32)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, nonce)); doFinal(bytes.copyOfRange(78, bytes.size))
        }.decodeToString()
    }

    private fun notification(id: String = "n1", member: String = "alice", team: String = "team") =
        Notification(id, team, member, NotificationKind.WAITING, "Waiting for you", "Review the plan", "s1", "w1", "wait:w1", 1_000, 7)

    private class Store(var targets: List<PushTarget>, var device: PushDevice? = PushDevice("d1", "alice", "ios")) : PushDeviceStore {
        val cleared = mutableListOf<Pair<String, String?>>()
        val registered = mutableListOf<Triple<String, String, String>>()
        val platforms = mutableListOf<String?>()
        var reads = 0
        override suspend fun currentDevice(actor: Principal) = device
        override suspend fun register(deviceId: String, token: String, publicKey: String, platform: String?) { registered += Triple(deviceId, token, publicKey); platforms += platform }
        override suspend fun clear(deviceId: String, onlyToken: String?) { cleared += deviceId to onlyToken }
        override suspend fun targets(teamId: String, memberIds: Set<String>): List<PushTarget> { reads++; return targets.filter { it.memberId in memberIds } }
    }

    private class Sender(var answer: (PushMessage) -> PushResult = { PushResult.SENT }) : PushSender {
        val sent = java.util.Collections.synchronizedList(mutableListOf<PushMessage>())
        override suspend fun send(message: PushMessage): PushResult { sent += message; return answer(message) }
    }

    @Test
    fun theDeviceDecryptsWhatTheProviderCannotRead() {
        val device = pair()
        val plaintext = PushPayloads.plaintext(notification(), "https://teamcode.example.org")
        val sealed = PushPayloads.encrypt(device.publicBase64(), plaintext)
        assertFalse("Review the plan" in Base64.getDecoder().decode(sealed).decodeToString())
        assertEquals(plaintext, decrypt(device.private, device.publicBase64(), sealed))
        assertNotEquals(sealed, PushPayloads.encrypt(device.publicBase64(), plaintext), "a fresh ephemeral key and nonce every time")
        val json = Json.parseToJsonElement(plaintext).jsonObject
        assertEquals("waiting", json["kind"]!!.jsonPrimitive.content)
        assertEquals("https://teamcode.example.org", json["origin"]!!.jsonPrimitive.content)
        assertEquals("w1", json["waitId"]!!.jsonPrimitive.content)
        assertTrue(sealed.length < 1_000, "well under the payload limit for a typical notification")
        // Another device's key cannot open it.
        assertFails { decrypt(pair().private, device.publicBase64(), sealed) }
    }

    @Test
    fun theCollapseKeyIsOpaqueAndStable() {
        assertEquals(PushPayloads.collapseKey("team:wait:w1"), PushPayloads.collapseKey("team:wait:w1"))
        assertNotEquals(PushPayloads.collapseKey("team:wait:w1"), PushPayloads.collapseKey("other:wait:w1"))
        assertFalse("w1" in PushPayloads.collapseKey("team:wait:w1"))
        assertEquals(32, PushPayloads.collapseKey("x").length)
    }

    @Test
    fun aDeviceWhoseLoginReportedNoPlatformTakesTheOneTheAppSends() = runTest {
        val store = Store(emptyList(), PushDevice("d1", "alice", null))
        val service = PushRegistrationService(store)
        val device = pair().publicBase64()
        // An older app sends no platform: nothing says the device is a phone.
        assertEquals("push_unsupported", assertFailsWith<TeamFailure> { service.register(alice, token, device) }.code)
        assertEquals("push_unsupported", assertFailsWith<TeamFailure> { service.register(alice, token, device, "macos") }.code)
        assertEquals("push_unsupported", assertFailsWith<TeamFailure> { service.register(alice, token, device, "symbian") }.code)
        service.register(alice, token, device, "ios")
        assertEquals(listOf(Triple("d1", token, device)), store.registered)
        assertEquals(listOf<String?>("ios"), store.platforms)
    }

    @Test
    fun aMemberRegistersOnlyAPhoneWithAValidTokenAndKey() = runTest {
        val store = Store(emptyList())
        val service = PushRegistrationService(store)
        val device = pair().publicBase64()
        service.register(alice, token, device)
        assertEquals(listOf(Triple("d1", token, device)), store.registered)
        assertEquals("invalid_push_token", assertFailsWith<TeamFailure> { service.register(alice, "short", device) }.code)
        assertEquals("invalid_push_token", assertFailsWith<TeamFailure> { service.register(alice, "$token\nx", device) }.code)
        assertEquals("invalid_push_key", assertFailsWith<TeamFailure> { service.register(alice, token, "not a key") }.code)
        val rsa = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public.encoded)
        assertEquals("invalid_push_key", assertFailsWith<TeamFailure> { service.register(alice, token, rsa) }.code)
        val p384 = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded)
        assertEquals("invalid_push_key", assertFailsWith<TeamFailure> { service.register(alice, token, p384) }.code)
        assertFailsWith<TeamFailure> { service.register(key, token, device) }
        store.device = PushDevice("d2", "alice", "macos")
        assertEquals("push_unsupported", assertFailsWith<TeamFailure> { service.register(alice, token, device) }.code)
        assertEquals("push_unsupported", assertFailsWith<TeamFailure> { service.register(alice, token, device, "ios") }.code, "a desktop stays a desktop")
        store.device = null
        assertEquals("device_required", assertFailsWith<TeamFailure> { service.register(alice, token, device) }.code)
        assertEquals("device_required", assertFailsWith<TeamFailure> { service.clear(alice) }.code)
        assertEquals(listOf<String?>(null), store.platforms, "a platform the login reported is not written again")
        store.device = PushDevice("d1", "alice", "android")
        service.clear(alice)
        assertEquals(listOf<Pair<String, String?>>("d1" to null), store.cleared)
    }

    @Test
    fun theChannelSendsOncePerInstallationThroughTheTeamsProvider() = runTest {
        val phone = pair(); val tablet = pair()
        val store = Store(listOf(PushTarget("d1", "alice", token, phone.publicBase64(), "ios"), PushTarget("d2", "alice", token.reversed(), tablet.publicBase64(), "android"),
            PushTarget("d3", "bob", token + "b", phone.publicBase64(), "ios")))
        val sender = Sender()
        val channel = PushChannel(store, { teams -> teams.filter { it == "team" }.associateWith { sender } }, "https://teamcode.example.org")
        assertEquals(setOf("alice"), channel.reachable("team", setOf("alice", "carol")))
        assertEquals(emptySet<String>(), channel.reachable("off-team", setOf("alice")), "a team whose push is off reaches nobody")

        store.reads = 0
        val results = channel.deliver(listOf(notification("n1"), notification("n2", member = "bob"), notification("n3", team = "off-team")))
        assertEquals(ChannelResult.DELIVERED, results["n1"]); assertEquals(ChannelResult.DELIVERED, results["n2"])
        assertEquals(ChannelResult.failed("push_off"), results["n3"])
        assertEquals(1, store.reads, "one destination read for the team of the batch")
        assertEquals(3, sender.sent.size)
        val toPhone = sender.sent.single { it.token == token }
        assertEquals("ios", toPhone.platform)
        assertEquals(PushPayloads.collapseKey("team:wait:w1"), toPhone.collapseKey)
        assertEquals(3600, toPhone.ttlSeconds)
        assertEquals("Review the plan", Json.parseToJsonElement(decrypt(phone.private, phone.publicBase64(), toPhone.payload)).jsonObject["body"]!!.jsonPrimitive.content)
        assertEquals("n1", Json.parseToJsonElement(decrypt(tablet.private, tablet.publicBase64(), sender.sent.single { it.token == token.reversed() }.payload)).jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun outcomesFollowTheInstallations() = runTest {
        val phone = pair()
        val targets = listOf(PushTarget("d1", "alice", token, phone.publicBase64(), "ios"), PushTarget("d2", "alice", token + "2", phone.publicBase64(), "android"))
        val store = Store(targets)
        val sender = Sender()
        val channel = PushChannel(store, { teams -> teams.associateWith { sender } }, "https://o")

        // One phone got it: delivered, and the token the provider forgot is cleared, guarded by its value.
        sender.answer = { if (it.token == token) PushResult.SENT else PushResult.UNREGISTERED }
        assertEquals(ChannelResult.DELIVERED, channel.deliver(listOf(notification())).getValue("n1"))
        assertEquals(listOf<Pair<String, String?>>("d2" to token + "2"), store.cleared)

        sender.answer = { if (it.token == token) PushResult.retry("relay_unavailable") else PushResult.rejected("relay_refused") }
        assertEquals(ChannelResult.retry("relay_unavailable"), channel.deliver(listOf(notification())).getValue("n1"))
        sender.answer = { PushResult.UNREGISTERED }
        assertEquals(ChannelResult.failed("unregistered"), channel.deliver(listOf(notification())).getValue("n1"))
        sender.answer = { PushResult.rejected("fcm_400") }
        assertEquals(ChannelResult.failed("fcm_400"), channel.deliver(listOf(notification())).getValue("n1"))
        store.targets = emptyList()
        assertEquals(ChannelResult.failed("no_device"), channel.deliver(listOf(notification())).getValue("n1"))
        assertEquals(emptyMap<String, ChannelResult>(), channel.deliver(emptyList()))
    }

    @Test
    fun aSlowProviderIsARetry() = runTest {
        val phone = pair()
        val slow = PushSender { delay(60_000); PushResult.SENT }
        val channel = PushChannel(Store(listOf(PushTarget("d1", "alice", token, phone.publicBase64(), "ios"))), { teams -> teams.associateWith { slow } }, "https://o")
        assertEquals(ChannelResult.retry("timeout"), channel.deliver(listOf(notification())).getValue("n1"))
    }

    @Test
    fun anAdministratorTestsTheirOwnPhones() = runTest {
        val phone = pair()
        val store = Store(listOf(PushTarget("d9", "root", token, phone.publicBase64(), "android")))
        val sender = Sender()
        var on = false
        val channel = PushChannel(store, { teams -> if (on) teams.associateWith { sender } else emptyMap() }, "https://o", clock = { 42 })
        val settings = object : PushSettingsStore {
            override suspend fun settings(actor: Principal) = PushSettings()
            override suspend fun save(actor: Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int) = PushSettings(provider, relayUrl, serviceAccount?.projectId, serviceAccount?.clientEmail, version + 1)
            override suspend fun connections(teamIds: Set<String>) = emptyMap<String, PushConnection>()
        }
        val service = PushAdministrationService(settings, channel)
        assertEquals("push_off", assertFailsWith<TeamFailure> { service.test(admin) }.code)
        on = true
        val result = service.test(admin)
        assertTrue(result.ok)
        val json = Json.parseToJsonElement(decrypt(phone.private, phone.publicBase64(), sender.sent.single().payload)).jsonObject
        assertEquals("test", json["kind"]!!.jsonPrimitive.content)
        assertEquals("root", json["memberId"]!!.jsonPrimitive.content)
        store.targets = emptyList()
        assertEquals("push_no_device", assertFailsWith<TeamFailure> { service.test(admin) }.code)
        assertFailsWith<TeamFailure> { service.test(alice) }
        assertFailsWith<TeamFailure> { service.settings(alice) }
    }

    @Test
    fun settingsAcceptAnHttpsRelayAndAGoogleServiceAccountOnly() = runTest {
        val saved = mutableListOf<Triple<PushProvider, String, FirebaseServiceAccount?>>()
        val settings = object : PushSettingsStore {
            override suspend fun settings(actor: Principal) = PushSettings()
            override suspend fun save(actor: Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int): PushSettings {
                saved += Triple(provider, relayUrl, serviceAccount); return PushSettings(provider, relayUrl, serviceAccount?.projectId, serviceAccount?.clientEmail, version + 1)
            }
            override suspend fun connections(teamIds: Set<String>) = emptyMap<String, PushConnection>()
        }
        val service = PushAdministrationService(settings, PushChannel(Store(emptyList()), { emptyMap() }, "https://o"))
        assertEquals(PushAdministrationService.DEFAULT_RELAY_URL, service.save(admin, PushSettingsInput(PushProvider.RELAY, " ", null, 0)).relayUrl)
        assertEquals("https://relay.example.org", service.save(admin, PushSettingsInput(PushProvider.RELAY, "https://relay.example.org/", null, 1)).relayUrl)
        for (bad in listOf("http://relay.example.org", "https://relay.example.org/path", "https://user@relay.example.org", "ftp://x"))
            assertEquals("invalid_push_settings", assertFailsWith<TeamFailure>(bad) { service.save(admin, PushSettingsInput(PushProvider.RELAY, bad, null, 1)) }.code)
        assertEquals("push_relay_host_not_allowed", assertFailsWith<TeamFailure> { service.save(admin, PushSettingsInput(PushProvider.RELAY, "https://169.254.169.254", null, 1)) }.code)

        val account = buildJsonObject {
            put("type", "service_account"); put("project_id", "teamcode-app"); put("private_key_id", "abc")
            put("private_key", "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n"); put("client_email", "push@teamcode-app.iam.gserviceaccount.com")
            put("token_uri", "https://oauth2.googleapis.com/token")
        }
        val firebase = service.save(admin, PushSettingsInput(PushProvider.FIREBASE, "", account.toString(), 2))
        assertEquals("teamcode-app", firebase.firebaseProjectId)
        assertEquals(account.toString(), saved.last().third!!.json)
        assertFalse("PRIVATE" in saved.last().third.toString() || "PRIVATE" in PushSettingsInput(PushProvider.FIREBASE, "", account.toString(), 2).toString(), "never in a log line")
        suspend fun refused(json: String) = assertEquals("invalid_push_settings", assertFailsWith<TeamFailure> { service.save(admin, PushSettingsInput(PushProvider.FIREBASE, "", json, 3)) }.code)
        refused("{")
        refused(JsonObject(account + ("type" to JsonPrimitive("authorized_user"))).toString())
        refused(JsonObject(account + ("token_uri" to JsonPrimitive("https://attacker.example/token"))).toString())
        refused(JsonObject(account - "private_key").toString())
        refused(JsonObject(account + ("project_id" to JsonPrimitive("Bad Project"))).toString())
        refused("{\"type\":\"service_account\",\"pad\":\"${"x".repeat(PushAdministrationService.MAX_SERVICE_ACCOUNT_CHARS)}\"}")
        assertFailsWith<TeamFailure> { service.save(alice, PushSettingsInput(PushProvider.OFF, "", null, 3)) }
    }
}
