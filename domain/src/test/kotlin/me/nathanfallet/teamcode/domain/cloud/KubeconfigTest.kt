package me.nathanfallet.teamcode.domain.cloud

import java.util.Base64
import kotlin.test.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

class KubeconfigTest {
    private val pem = "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----"
    private val ca = Base64.getEncoder().encodeToString(pem.toByteArray())

    private fun config(clusters: String = "- name: lab\n  cluster: {server: 'https://k8s.example.test:6443', certificate-authority-data: $ca}",
        users: String = "- name: robot\n  user: {token: abcdefghijklmnopqrstuv}",
        contexts: String = "- name: lab\n  context: {cluster: lab, user: robot, namespace: teamcode}", current: String? = "lab") =
        "apiVersion: v1\nkind: Config\n" + (current?.let { "current-context: $it\n" } ?: "") + "clusters:\n$clusters\nusers:\n$users\ncontexts:\n$contexts\n"

    private fun refused(text: String): TeamFailure = assertFailsWith<TeamFailure> { Kubeconfig.parse(text) }

    @Test fun readsServerCaTokenAndNamespaceOfTheCurrentContext() {
        val parsed = Kubeconfig.parse(config())
        val context = assertNotNull(parsed.defaultContext)
        assertEquals("lab", context.name)
        assertEquals("https://k8s.example.test:6443", context.apiUrl)
        assertEquals(pem, context.caCertificate, "The embedded base64 data is decoded to a PEM bundle")
        assertEquals("abcdefghijklmnopqrstuv", context.token)
        assertEquals("teamcode", context.namespace)
        assertNull(context.unsupported)
    }

    @Test fun scalarsKeepTheirExactSpellingWhateverTheyLookLike() {
        val parsed = Kubeconfig.parse(config(users = "- name: 42\n  user: {token: 0123456789012345}", contexts = "- name: 42\n  context: {cluster: lab, user: 42, namespace: 1234}", current = "42"))
        val context = assertNotNull(parsed.defaultContext)
        assertEquals("0123456789012345", context.token, "A numeric-looking token is not dropped or reformatted")
        assertEquals("1234", context.namespace); assertEquals("42", context.name)
        assertNotNull(Kubeconfig.parse(config(users = "- name: robot\n  user: {token: null}")).contexts.single().let { assertNull(it.token); it })
        assertNotNull(Kubeconfig.parse(config(clusters = "- name: lab\n  cluster: {server: 'https://k.example.test', insecure-skip-tls-verify: \"true\"}")).contexts.single().unsupported)
    }

    @Test fun jsonIsAccepted() {
        val json = """{"kind":"Config","current-context":"a","clusters":[{"name":"c","cluster":{"server":"https://k.example.test","certificate-authority-data":"$ca"}}],
            "users":[{"name":"u","user":{"token":"abcdefghijklmnopqrstuv"}}],"contexts":[{"name":"a","context":{"cluster":"c","user":"u"}}]}"""
        val context = assertNotNull(Kubeconfig.parse(json).defaultContext)
        assertEquals("https://k.example.test", context.apiUrl)
        assertNull(context.namespace, "No namespace is invented")
    }

    @Test fun severalContextsAreListedAndTheCurrentOneIsTheDefault() {
        val parsed = Kubeconfig.parse(config(
            clusters = "- name: a\n  cluster: {server: 'https://a.example.test', certificate-authority-data: $ca}\n- name: b\n  cluster: {server: 'https://b.example.test', certificate-authority-data: $ca}",
            users = "- name: ua\n  user: {token: aaaaaaaaaaaaaaaaaaaa}\n- name: ub\n  user: {token: bbbbbbbbbbbbbbbbbbbb}",
            contexts = "- name: first\n  context: {cluster: a, user: ua}\n- name: second\n  context: {cluster: b, user: ub, namespace: sandbox}", current = "second"))
        assertEquals(listOf("first", "second"), parsed.contexts.map { it.name })
        assertEquals("second", parsed.defaultContext?.name)
        assertEquals("bbbbbbbbbbbbbbbbbbbb", parsed.defaultContext?.token, "Each context carries its own cluster and user")
        assertEquals("aaaaaaaaaaaaaaaaaaaa", parsed.contexts.first().token)
    }

    @Test fun anUnsupportedCurrentContextFallsBackToTheFirstUsableOne() {
        val parsed = Kubeconfig.parse(config(
            users = "- name: robot\n  user: {token: abcdefghijklmnopqrstuv}\n- name: sso\n  user: {exec: {command: aws}}",
            contexts = "- name: sso\n  context: {cluster: lab, user: sso}\n- name: lab\n  context: {cluster: lab, user: robot}", current = "sso"))
        assertNotNull(parsed.contexts.first().unsupported)
        assertEquals("lab", parsed.defaultContext?.name)
    }

    @Test fun missingCurrentContextStillPicksTheFirstUsableContext() {
        assertEquals("lab", Kubeconfig.parse(config(current = null)).defaultContext?.name)
    }

    @Test fun execAndAuthProviderPluginsAreRefusedWithAClearReason() {
        val exec = Kubeconfig.parse(config(users = "- name: robot\n  user:\n    exec:\n      apiVersion: client.authentication.k8s.io/v1\n      command: aws\n      args: [eks, get-token]")).contexts.single()
        assertNull(exec.token); assertNull(exec.caCertificate)
        assertContains(assertNotNull(exec.unsupported), "exec plugin")
        val provider = Kubeconfig.parse(config(users = "- name: robot\n  user:\n    auth-provider: {name: gcp, config: {cmd-path: gcloud}}")).contexts.single()
        assertContains(assertNotNull(provider.unsupported), "auth-provider")
        // The service refuses the whole file when nothing else can be chosen: see KubeconfigImportServiceTest.
    }

    @Test fun clientCertificatesPasswordsAndImpersonationAreRefused() {
        for ((user, word) in listOf(
            "{client-certificate-data: $ca, client-key-data: $ca}" to "client certificate",
            "{token: abcdefghijklmnopqrstuv, client-certificate-data: $ca}" to "client certificate",
            "{username: admin, password: hunter2}" to "username and password",
            "{token: abcdefghijklmnopqrstuv, as: root}" to "impersonates",
        )) assertContains(assertNotNull(Kubeconfig.parse(config(users = "- name: robot\n  user: $user")).contexts.single().unsupported), word)
    }

    @Test fun filesReferencedByPathAreRefusedAndExplained() {
        val caPath = assertNotNull(Kubeconfig.parse(config(clusters = "- name: lab\n  cluster: {server: 'https://k.example.test', certificate-authority: /home/me/ca.crt}")).contexts.single().unsupported)
        assertContains(caPath, "file path"); assertContains(caPath, "kubectl config view --raw --flatten")
        val tokenFile = assertNotNull(Kubeconfig.parse(config(users = "- name: robot\n  user: {tokenFile: /var/run/secrets/token}")).contexts.single().unsupported)
        assertContains(tokenFile, "file path"); assertContains(tokenFile, "tokenFile")
    }

    @Test fun insecureTlsPlainHttpAndProxiesAreRefused() {
        fun reason(cluster: String) = assertNotNull(Kubeconfig.parse(config(clusters = "- name: lab\n  cluster: $cluster")).contexts.single().unsupported)
        assertContains(reason("{server: 'https://k.example.test', insecure-skip-tls-verify: true}"), "skips TLS verification")
        assertContains(reason("{server: 'http://k.example.test'}"), "https")
        assertContains(reason("{server: 'https://k.example.test', proxy-url: 'http://proxy:3128'}"), "proxy")
        assertContains(reason("{server: 'https://k.example.test', certificate-authority-data: bm90IGEgY2VydA==}"), "not a base64-encoded PEM")
        assertContains(reason("{certificate-authority-data: $ca}"), "no server")
    }

    @Test fun aContextWithoutCredentialsKeepsTheFieldsForManualEntry() {
        val context = Kubeconfig.parse(config(clusters = "- name: lab\n  cluster: {server: 'https://k.example.test'}", users = "- name: robot\n  user: {}")).contexts.single()
        assertNull(context.unsupported); assertNull(context.caCertificate); assertNull(context.token)
    }

    @Test fun danglingReferencesAreRefusedPerContext() {
        assertContains(assertNotNull(Kubeconfig.parse(config(contexts = "- name: lab\n  context: {cluster: nope, user: robot}")).contexts.single().unsupported), "nope")
        assertContains(assertNotNull(Kubeconfig.parse(config(contexts = "- name: lab\n  context: {cluster: lab, user: ghost}")).contexts.single().unsupported), "ghost")
    }

    @Test fun malformedDocumentsAreRefusedWithoutQuotingThem() {
        val secret = "super-secret-token-value"
        for (text in listOf("", "   ", "just text", "- a\n- b", "kind: Pod\nclusters: []", "key: [unclosed $secret", "clusters: $secret", "clusters: [1]\ncontexts: []",
            config(contexts = "[]"), "x: " + "[".repeat(50_000), "a: &a [*a]\nb: *a", "x: " + "y".repeat(Kubeconfig.MAX_CHARACTERS + 1)))
            assertEquals("invalid_kubeconfig", refused(text).code, text.take(40))
        assertFalse(secret in refused("key: [unclosed $secret").message.orEmpty(), "A parse error never repeats the document")
        assertEquals("invalid_kubeconfig", refused(config(contexts = "- name: lab\n  context: {cluster: lab}\n- name: lab\n  context: {cluster: lab}")).code, "Duplicate context names are ambiguous")
    }

    @Test fun aliasBombsAreBounded() {
        val bomb = buildString {
            append("a: &a [x,x,x,x,x,x,x,x,x]\n")
            var previous = "a"
            for (i in 1..12) { append("l$i: &l$i [" + List(9) { "*$previous" }.joinToString(",") + "]\n"); previous = "l$i" }
        }
        assertEquals("invalid_kubeconfig", refused(bomb).code)
    }

    @Test fun secretsAreNotPrinted() {
        val parsed = Kubeconfig.parse(config())
        assertFalse("abcdefghijklmnopqrstuv" in parsed.toString() + parsed.contexts.single().toString())
        assertFalse("MIIBfake" in parsed.contexts.single().toString())
    }
}
