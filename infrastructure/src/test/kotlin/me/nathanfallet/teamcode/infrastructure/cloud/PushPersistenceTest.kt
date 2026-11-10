package me.nathanfallet.teamcode.infrastructure.cloud

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.SqlPushDeviceStore
import me.nathanfallet.teamcode.infrastructure.database.SqlPushSettingsStore
import me.nathanfallet.teamcode.infrastructure.database.SqlRelayIdentityStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.push.RelayIdentity
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** Push destinations, settings and the relay identity on real MySQL. */
class PushPersistenceTest {
    private val token = "c".repeat(40) + ":APA91b"
    private fun key() = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)

    @Test @Timeout(240)
    fun pushStateHoldsOnMySql() = runBlocking {
        AutomationWorld.run(84) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Devices, PushRelayInstances, PushSettingsTable, withLogs = false),
                    "The reviewed migrations match the Exposed tables")
            }
            val devices = SqlPushDeviceStore(w.a.database)
            val registration = PushRegistrationService(devices)
            val password = "automation owner replacement password"
            // The member's phone and computer, each signed in with its own device; the CLI login of the fixture has none.
            val phone = w.team.login("automation-owner", password, "Phone", UUID.randomUUID().toString(), "ios", "18.1").principal
            val laptop = w.team.login("automation-owner", password, "Laptop", UUID.randomUUID().toString(), "macos", "15").principal
            val phoneKey = key()
            registration.register(phone, token, phoneKey)
            assertEquals("push_unsupported", assertFailsWith<TeamFailure> { registration.register(laptop, token, key()) }.code)
            val member = w.member.member.id
            val targets = devices.targets(w.teamId, setOf(member, w.admin.member.id))
            assertEquals(1, targets.size)
            assertEquals(PushTarget(targets.single().deviceId, member, token, phoneKey, "ios"), targets.single())
            assertEquals(emptyList(), devices.targets("another-team", setOf(member)), "another team never reaches this member")
            assertTrue(me.nathanfallet.teamcode.infrastructure.database.SqlDeviceService(w.a.database).devices(phone).any { it.id == targets.single().deviceId && it.push }, "the dashboard shows push on")

            // A rotation replaces the token; clearing the old one afterwards keeps the new one.
            registration.register(phone, token + "2", phoneKey)
            devices.clear(targets.single().deviceId, onlyToken = token)
            assertEquals(token + "2", devices.targets(w.teamId, setOf(member)).single().token)
            devices.clear(targets.single().deviceId, onlyToken = token + "2")
            assertEquals(emptyList(), devices.targets(w.teamId, setOf(member)))

            // A device signed out (its sessions gone) keeps its row but receives nothing.
            registration.register(phone, token, phoneKey)
            w.team.logout(phone)
            assertEquals(emptyList(), devices.targets(w.teamId, setOf(member)))
            val again = w.team.login("automation-owner", password, "Phone", w.a.database.query {
                Devices.select(Devices.installationId).where { Devices.id eq targets.single().deviceId }.single()[Devices.installationId]
            }, "ios", "18.1").principal
            assertEquals(1, devices.targets(w.teamId, setOf(member)).size, "the same installation signed in again")
            registration.clear(again)
            assertEquals(emptyList(), devices.targets(w.teamId, setOf(member)))

            // An app login that reported no platform (the companion apps before 1.7.1): the app's word makes it a phone,
            // and a platform the login reported is never replaced.
            val tablet = w.team.login("automation-owner", password, "Tablet", UUID.randomUUID().toString(), null, null).principal
            assertEquals("push_unsupported", assertFailsWith<TeamFailure> { registration.register(tablet, token, phoneKey) }.code)
            registration.register(tablet, token, phoneKey, "android")
            assertEquals(listOf("android"), devices.targets(w.teamId, setOf(member)).map { it.platform })
            registration.register(again, token, phoneKey, "android")
            assertEquals(setOf("android", "ios"), devices.targets(w.teamId, setOf(member)).map { it.platform }.toSet(), "the phone stays ios")

            // Settings: off by default, versioned, the Firebase key sealed and removed when Firebase stops sending.
            val cipher = CredentialCipher(ByteArray(32) { 84 })
            val settings = SqlPushSettingsStore(w.a.database, cipher)
            assertEquals(PushSettings(), settings.settings(w.admin))
            assertEquals(emptyMap(), settings.connections(setOf(w.teamId)))
            val relay = settings.save(w.admin, PushProvider.RELAY, "https://relay.example.org", null, 0)
            assertEquals(1, relay.version)
            assertEquals(PushProvider.RELAY, settings.connections(setOf(w.teamId, "other")).getValue(w.teamId).provider)
            assertEquals("push_settings_conflict", assertFailsWith<TeamFailure> { settings.save(w.admin, PushProvider.OFF, "https://relay.example.org", null, 0) }.code)
            assertEquals("push_firebase_credentials_required", assertFailsWith<TeamFailure> { settings.save(w.admin, PushProvider.FIREBASE, "https://relay.example.org", null, 1) }.code)
            val account = FirebaseServiceAccount("teamcode-app", "push@teamcode-app.iam.gserviceaccount.com", """{"type":"service_account","private_key":"-----BEGIN PRIVATE KEY-----"}""")
            val firebase = settings.save(w.admin, PushProvider.FIREBASE, "https://relay.example.org", account, 1)
            assertEquals("teamcode-app", firebase.firebaseProjectId)
            val stored = w.a.database.query { PushSettingsTable.selectAll().where { PushSettingsTable.teamId eq w.teamId }.single()[PushSettingsTable.firebaseCredentials]!! }
            assertFalse("PRIVATE" in stored, "sealed")
            assertEquals(account.json, settings.connections(setOf(w.teamId)).getValue(w.teamId).firebaseServiceAccount)
            // Saving Firebase again without a key keeps it; leaving Firebase removes it.
            assertEquals("teamcode-app", settings.save(w.admin, PushProvider.FIREBASE, "https://relay.example.org", null, 2).firebaseProjectId)
            assertEquals(account.json, settings.connections(setOf(w.teamId)).getValue(w.teamId).firebaseServiceAccount)
            val off = settings.save(w.admin, PushProvider.OFF, "https://relay.example.org", null, 3)
            assertNull(off.firebaseProjectId)
            assertNull(w.a.database.query { PushSettingsTable.selectAll().where { PushSettingsTable.teamId eq w.teamId }.single()[PushSettingsTable.firebaseCredentials] })
            assertEquals(emptyMap(), settings.connections(setOf(w.teamId)))
            assertTrue(w.a.database.query { AuditEvents.selectAll().where { AuditEvents.action eq "push.firebase_key_saved" }.count() } == 1L)

            // The relay identity: the first replica's key wins, the instance id is set only for that key.
            val identitiesA = SqlRelayIdentityStore(w.a.database); val identitiesB = SqlRelayIdentityStore(w.b.database)
            val first = identitiesA.create("https://relay.example.org", RelayIdentity("key-a", "sealed-a", null))
            val second = identitiesB.create("https://relay.example.org", RelayIdentity("key-b", "sealed-b", null))
            assertEquals(first, second)
            identitiesB.setInstance("https://relay.example.org", "key-b", "wrong")
            assertNull(identitiesA.load("https://relay.example.org")!!.instanceId)
            identitiesB.setInstance("https://relay.example.org", "key-a", "inst-1")
            assertEquals("inst-1", identitiesA.load("https://relay.example.org")!!.instanceId)
        }
    }
}
