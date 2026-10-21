package me.nathanfallet.teamcode.domain.cloud

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import me.nathanfallet.teamcode.domain.team.*

class KubeconfigImportServiceTest {
    /** A real AES-GCM sealer, so the tests see the same failure modes as production. */
    private class AesSealer : SecretSealer {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        override fun seal(plaintext: ByteArray, context: String): String {
            val nonce = ByteArray(12) { (it * 7).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(context.toByteArray()) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + cipher.doFinal(plaintext))
        }
        override fun open(envelope: String, context: String): ByteArray? = try {
            val bytes = Base64.getUrlDecoder().decode(envelope)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(context.toByteArray()) }.doFinal(bytes.copyOfRange(12, bytes.size))
        } catch (_: Exception) { null }
    }

    private val pem = "-----BEGIN CERTIFICATE-----\nMIIBsecretca\n-----END CERTIFICATE-----"
    private val ca = Base64.getEncoder().encodeToString(pem.toByteArray())
    private val token = "secret-token-0123456789"
    private val admin = Principal(Member("m1", "root", Role.ADMIN, false, false), "session-1", "team")
    private var now = 1_000L
    private val service = KubeconfigImportService(AesSealer(), { now }, 60_000)

    private fun config(vararg users: Pair<String, String>) = "current-context: ${users.first().first}\nclusters:\n- name: c\n  cluster: {server: 'https://k.example.test', certificate-authority-data: $ca}\nusers:\n" +
        users.joinToString("") { (name, user) -> "- name: $name\n  user: $user\n" } + "contexts:\n" + users.joinToString("") { (name, _) -> "- name: $name\n  context: {cluster: c, user: $name, namespace: ns-$name}\n" }

    @Test fun summariesNeverCarrySecretsAndTheSealedValueDoesNotLeakThem() {
        val offer = service.offer(admin, config("a" to "{token: $token}", "b" to "{token: $token-b}"))
        assertEquals("a", offer.defaultContext)
        assertEquals(listOf("a", "b"), offer.contexts.map { it.name })
        assertTrue(offer.contexts.all { it.hasCaCertificate && it.hasToken })
        val visible = offer.contexts.toString() + offer.sealed + offer.toString()
        assertFalse(token in visible || "MIIBsecretca" in visible || ca in visible, "Nothing the browser sees contains a secret")
    }

    @Test fun theChosenContextOpensWithItsSecretsForTheServerSideSave() {
        val offer = service.offer(admin, config("a" to "{token: $token}", "b" to "{token: $token-b}"))
        val opened = service.open(admin, offer.sealed, "b")
        assertEquals("$token-b", opened.token); assertEquals(pem, opened.caCertificate); assertEquals("ns-b", opened.namespace)
        assertEquals("ns-a", service.choose(admin, offer.sealed, "a").summary.namespace)
        assertFailsWith<TeamFailure> { service.open(admin, offer.sealed, "missing") }
    }

    @Test fun aChoiceKeepsOnlyTheChosenContextSecrets() {
        val offer = service.offer(admin, config("a" to "{token: $token}", "b" to "{token: $token-b}", "c" to "{token: $token-c}"))
        val choice = service.choose(admin, offer.sealed, "b")
        assertEquals("$token-b", service.open(admin, choice.sealed, "b").token)
        for (other in listOf("a", "c")) assertFailsWith<TeamFailure> { service.open(admin, choice.sealed, other) }
        assertFalse(token in choice.toString() + choice.sealed)
        assertTrue(choice.sealed.length < offer.sealed.length, "The envelope no longer grows with the file")
    }

    @Test fun unusableContextsAreListedWithTheirReasonButNeverSealed() {
        val offer = service.offer(admin, config("good" to "{token: $token}", "sso" to "{exec: {command: aws}}"))
        assertContains(assertNotNull(offer.contexts.single { it.name == "sso" }.unsupported), "exec")
        assertFailsWith<TeamFailure> { service.open(admin, offer.sealed, "sso") }
    }

    @Test fun aKubeconfigWithOnlyExecOrAuthProviderIsRefusedClearly() {
        for ((user, word) in listOf("{exec: {command: aws, args: [eks]}}" to "exec", "{auth-provider: {name: oidc}}" to "auth-provider")) {
            val failure = assertFailsWith<TeamFailure> { service.offer(admin, config("only" to user)) }
            assertEquals("unsupported_kubeconfig", failure.code); assertEquals(422, failure.status)
            assertContains(failure.message.orEmpty(), word); assertContains(failure.message.orEmpty(), "service-account token")
        }
    }

    @Test fun anOfferExpiresAndBelongsToOneSession() {
        val offer = service.offer(admin, config("a" to "{token: $token}"))
        assertEquals("a", service.open(admin, offer.sealed, "a").name)
        assertEquals("kubeconfig_import_expired", assertFailsWith<TeamFailure> { service.open(admin.copy(tokenId = "session-2"), offer.sealed, "a") }.code, "Another session cannot open it")
        assertFailsWith<TeamFailure> { service.open(admin.copy(member = admin.member.copy(id = "m2")), offer.sealed, "a") }
        assertFailsWith<TeamFailure> { service.open(admin.copy(teamId = "other"), offer.sealed, "a") }
        assertFailsWith<TeamFailure> { service.open(admin, offer.sealed.dropLast(2) + "AA", "a") }
        assertFailsWith<TeamFailure> { service.open(admin, "", "a") }
        now += 60_001
        assertEquals("kubeconfig_import_expired", assertFailsWith<TeamFailure> { service.open(admin, offer.sealed, "a") }.code)
    }

    @Test fun onlyAdministratorsImport() {
        val member = Principal(Member("m3", "dev", Role.MEMBER, false, false), "s", "team")
        assertFailsWith<TeamFailure> { service.offer(member, config("a" to "{token: $token}")) }
        val offer = service.offer(admin, config("a" to "{token: $token}"))
        assertFailsWith<TeamFailure> { service.open(member, offer.sealed, "a") }
    }
}
