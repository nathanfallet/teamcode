package me.nathanfallet.teamcode.domain.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import me.nathanfallet.teamcode.domain.team.*

/** Encrypts short-lived values handed to the browser and back; the server's credential key in production. */
interface SecretSealer {
    fun seal(plaintext: ByteArray, context: String): String
    /** Null when [envelope] was not sealed with this [context], was altered, or cannot be read. */
    fun open(envelope: String, context: String): ByteArray?
}

/** What the dashboard may show of a context: never its CA bundle or token, only whether it brings them. */
data class KubeconfigContextSummary(
    val name: String,
    val apiUrl: String?,
    val namespace: String?,
    val hasCaCertificate: Boolean,
    val hasToken: Boolean,
    /** Why this context cannot become a cluster; null when it can. */
    val unsupported: String?,
)

/** [sealed] holds the secrets of the contexts that can be imported, encrypted; hand it back to pick one. */
class KubeconfigOffer(val sealed: String, val contexts: List<KubeconfigContextSummary>, val defaultContext: String?) {
    override fun toString() = "KubeconfigOffer(contexts=${contexts.map { it.name }}, defaultContext=$defaultContext, sealed=[REDACTED])"
}

/** One context picked from an offer: its summary, and a [sealed] value holding only its secrets. */
class KubeconfigChoice(val sealed: String, val summary: KubeconfigContextSummary) {
    override fun toString() = "KubeconfigChoice(summary=$summary, sealed=[REDACTED])"
}

/**
 * Reads a kubeconfig on the server and lets an administrator turn one of its contexts into a cluster without the
 * secrets ever returning to the browser. The CA bundle and the token go through [SecretSealer] into an opaque value
 * that the next request hands back; it expires, belongs to one administrator session, and is opened only to fill the
 * same cluster input a manual entry fills. The kubeconfig itself is never stored.
 */
class KubeconfigImportService(private val sealer: SecretSealer, private val clock: () -> Long = System::currentTimeMillis, private val validityMillis: Long = 15 * 60_000) {
    fun offer(actor: Principal, text: String): KubeconfigOffer {
        actor.requireAdmin()
        val kubeconfig = Kubeconfig.parse(text)
        val usable = kubeconfig.contexts.filter { it.unsupported == null }
        if (usable.isEmpty()) {
            val reasons = kubeconfig.contexts.take(5).joinToString(" ") { "${it.name}: ${it.unsupported}" }
            throw TeamFailure("unsupported_kubeconfig", 422, "No context of this kubeconfig can be imported. $reasons")
        }
        return KubeconfigOffer(seal(actor, usable), kubeconfig.contexts.map { it.summary() }, kubeconfig.defaultContext?.name)
    }

    /**
     * Context [name] of a [sealed] offer, to prefill the form. The result holds a new sealed value with only that
     * context, so the other contexts' secrets are not carried (or replayable) beyond the choice.
     */
    fun choose(actor: Principal, sealed: String, name: String): KubeconfigChoice {
        val context = pick(actor, sealed, name)
        return KubeconfigChoice(seal(actor, listOf(context)), context.summary())
    }

    /** The context [name] of a [sealed] offer, secrets included, for the server-side save only. */
    fun open(actor: Principal, sealed: String, name: String): KubeconfigContext = pick(actor, sealed, name)

    private fun pick(actor: Principal, sealed: String, name: String): KubeconfigContext {
        actor.requireAdmin()
        return unseal(actor, sealed).firstOrNull { it.name == name } ?: throw TeamFailure("invalid_kubeconfig_context", 422, "Choose one of the kubeconfig's contexts.")
    }

    private fun context(actor: Principal) = "kubeconfig-import:${actor.teamId}:${actor.member.id}:${actor.tokenId}"

    private fun seal(actor: Principal, contexts: List<KubeconfigContext>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String?) { val raw = value?.toByteArray(Charsets.UTF_8); out.writeInt(raw?.size ?: -1); raw?.let(out::write) }
            out.writeLong(clock() + validityMillis)
            out.writeInt(contexts.size)
            for (c in contexts) { text(c.name); text(c.apiUrl); text(c.namespace); text(c.caCertificate); text(c.token) }
        }
        return sealer.seal(bytes.toByteArray(), context(actor))
    }

    private fun unseal(actor: Principal, sealed: String): List<KubeconfigContext> {
        fun expired(): Nothing = throw TeamFailure("kubeconfig_import_expired", 422, "This import expired or does not belong to your session. Read the kubeconfig again.")
        val raw = sealer.open(sealed, context(actor)) ?: expired()
        try {
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                fun text(): String? = input.readInt().takeIf { it >= 0 }?.let { size -> require(size <= Kubeconfig.MAX_CHARACTERS * 4); ByteArray(size).also(input::readFully).toString(Charsets.UTF_8) }
                if (input.readLong() < clock()) expired()
                return List(input.readInt().also { require(it in 1..1000) }) { KubeconfigContext(text() ?: throw IllegalArgumentException(), text(), text(), text(), text(), null) }
            }
        } catch (_: java.io.IOException) { expired() } catch (_: IllegalArgumentException) { expired() }
    }

    private fun KubeconfigContext.summary() = KubeconfigContextSummary(name, apiUrl, namespace, caCertificate != null, token != null, unsupported)
}
