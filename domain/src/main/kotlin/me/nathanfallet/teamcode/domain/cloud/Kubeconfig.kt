package me.nathanfallet.teamcode.domain.cloud

import java.util.Base64
import me.nathanfallet.teamcode.domain.team.TeamFailure
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.FailsafeSchema

/**
 * One context of a kubeconfig, reduced to what a TeamCode cluster stores: an API URL, a CA bundle, a bearer token and a
 * namespace. A context that needs anything else carries the reason in [unsupported] and is never turned into a cluster.
 * [caCertificate] and [token] are secrets: they never appear in [toString] and never reach a client.
 */
class KubeconfigContext(
    val name: String,
    val apiUrl: String?,
    val namespace: String?,
    val caCertificate: String?,
    val token: String?,
    val unsupported: String?,
) {
    override fun toString() = "KubeconfigContext(name=$name, apiUrl=$apiUrl, namespace=$namespace, unsupported=$unsupported)"
}

/** A parsed kubeconfig. Nothing is read from the server's disk: only what the document itself embeds counts. */
class Kubeconfig(val contexts: List<KubeconfigContext>, val currentContext: String?) {
    /** `current-context` when it can be imported, otherwise the first context that can. */
    val defaultContext: KubeconfigContext? get() =
        contexts.firstOrNull { it.name == currentContext && it.unsupported == null } ?: contexts.firstOrNull { it.unsupported == null }

    override fun toString() = "Kubeconfig(contexts=${contexts.map { it.name }}, currentContext=$currentContext)"

    companion object {
        /** A kubeconfig with a few clusters is a few KiB; 128 KiB leaves room for embedded certificates. */
        const val MAX_CHARACTERS = 128 * 1024

        /**
         * Reads a kubeconfig (YAML, or JSON, which is YAML). Malformed documents are refused as a whole
         * (`422 invalid_kubeconfig`); a context TeamCode cannot use is kept with its reason, so the dashboard can say why
         * it cannot be chosen. The error messages never quote the document, which holds credentials.
         */
        fun parse(text: String): Kubeconfig {
            fun invalid(message: String): Nothing = throw TeamFailure("invalid_kubeconfig", 422, message)
            if (text.isBlank()) invalid("The kubeconfig is empty.")
            if (text.length > MAX_CHARACTERS) invalid("The kubeconfig is larger than ${MAX_CHARACTERS / 1024} KiB.")
            val root = try {
                Load(LoadSettings.builder().setCodePointLimit(MAX_CHARACTERS).setMaxAliasesForCollections(10).setAllowDuplicateKeys(false).setAllowRecursiveKeys(false).setSchema(FailsafeSchema()).build()).loadFromString(text)
            } catch (_: YamlEngineException) {
                invalid("This is not a valid kubeconfig: the YAML could not be read.")
            } catch (_: StackOverflowError) {
                invalid("This is not a valid kubeconfig: the YAML is nested too deeply.")
            } catch (_: RuntimeException) {
                invalid("This is not a valid kubeconfig: the YAML could not be read.")
            }
            val config = root.asMap() ?: invalid("This is not a kubeconfig: expected a YAML mapping with clusters, users and contexts.")
            config["kind"]?.let { if (text(it) != "Config") invalid("This is not a kubeconfig: its kind is not Config.") }
            val clusters = named(config["clusters"], "cluster", ::invalid)
            val users = named(config["users"], "user", ::invalid)
            val contexts = named(config["contexts"], "context", ::invalid)
            if (contexts.isEmpty()) invalid("The kubeconfig has no contexts.")
            val current = text(config["current-context"])
            return Kubeconfig(contexts.map { (name, context) -> context(name, context, clusters, users) }, current)
        }

        private fun context(name: String, context: Map<String, Any?>, clusters: Map<String, Map<String, Any?>>, users: Map<String, Map<String, Any?>>): KubeconfigContext {
            fun refused(reason: String, cluster: Map<String, Any?>? = null) = KubeconfigContext(name, text(cluster?.get("server")), null, null, null, reason)
            val clusterName = text(context["cluster"])
            val cluster = clusterName?.let { clusters[it] } ?: return refused("it refers to the cluster ${clusterName?.let { "\"$it\"" } ?: "(none)"}, which the kubeconfig does not define.")
            val userName = text(context["user"])
            val user = userName?.let { users[it] }
            if (userName != null && user == null) return refused("it refers to the user \"$userName\", which the kubeconfig does not define.", cluster)

            val server = text(cluster["server"]).orEmpty()
            if (server.isEmpty()) return refused("its cluster has no server.", cluster)
            if (!server.startsWith("https://")) return refused("its server is not an https URL.", cluster)
            cluster["proxy-url"]?.let { return refused("its cluster uses a proxy (proxy-url), which TeamCode does not support.", cluster) }
            cluster["tls-server-name"]?.let { return refused("its cluster sets tls-server-name, which TeamCode does not support.", cluster) }
            if (text(cluster["insecure-skip-tls-verify"])?.lowercase() in setOf("true", "yes", "on")) return refused("its cluster skips TLS verification (insecure-skip-tls-verify); TeamCode always verifies the cluster against its CA bundle.", cluster)
            cluster["certificate-authority"]?.let {
                return refused("its CA is a file path (certificate-authority). A file on your computer cannot be read here: embed it with `kubectl config view --raw --flatten`, or paste the PEM in the form.", cluster)
            }
            val ca = text(cluster["certificate-authority-data"])?.let { data ->
                val pem = try { String(Base64.getMimeDecoder().decode(data.trim()), Charsets.UTF_8) } catch (_: IllegalArgumentException) { null }
                if (pem == null || !pem.contains("-----BEGIN CERTIFICATE-----")) return refused("certificate-authority-data is not a base64-encoded PEM certificate.", cluster)
                pem.trim()
            }

            var token: String? = null
            if (user != null) {
                if ("exec" in user) return refused("its user authenticates with an exec plugin, which the server cannot run. Use a service-account token instead.", cluster)
                if ("auth-provider" in user) return refused("its user authenticates with an auth-provider plugin, which the server cannot run. Use a service-account token instead.", cluster)
                if ("tokenFile" in user) return refused("its token is a file path (tokenFile). A file on your computer cannot be read here: use a token embedded in the kubeconfig, or paste it in the form.", cluster)
                if (listOf("client-certificate", "client-certificate-data", "client-key", "client-key-data").any { it in user })
                    return refused("its user authenticates with a client certificate. TeamCode authenticates with a service-account token, not a client certificate.", cluster)
                if ("username" in user || "password" in user) return refused("its user authenticates with a username and password. TeamCode authenticates with a service-account token.", cluster)
                if (listOf("as", "as-uid", "as-groups", "as-user-extra").any { it in user }) return refused("its user impersonates another identity (as), which TeamCode does not support.", cluster)
                token = text(user["token"])
            }
            return KubeconfigContext(name, server, text(context["namespace"]), ca, token, null)
        }

        /** `clusters`, `users` or `contexts`: a list of `{name, <key>: {...}}`, indexed by name. */
        private fun named(value: Any?, key: String, invalid: (String) -> Nothing): Map<String, Map<String, Any?>> {
            if (value == null) return emptyMap()
            val items = value as? List<*> ?: invalid("This is not a kubeconfig: ${key}s must be a list.")
            val byName = LinkedHashMap<String, Map<String, Any?>>()
            for (item in items) {
                val entry = item.asMap() ?: invalid("This is not a kubeconfig: every $key entry must be a mapping.")
                val name = text(entry["name"])?.takeIf { it.length <= 253 && it.none(Char::isISOControl) } ?: invalid("This is not a kubeconfig: every $key needs a name.")
                if (name in byName) invalid("The $key \"$name\" appears twice in the kubeconfig.")
                byName[name] = entry[key].asMap() ?: emptyMap()
            }
            return byName
        }

        /**
         * Every scalar is read as text (the YAML failsafe schema), so a token or namespace made of digits, or a name like
         * `true`, keeps its exact spelling. Blank, `null` and `~` mean absent, as for kubectl.
         */
        private fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "~" }

        private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.let { map ->
            if (map.keys.any { it !is String }) null else map.entries.associate { it.key as String to it.value }
        }
    }
}
