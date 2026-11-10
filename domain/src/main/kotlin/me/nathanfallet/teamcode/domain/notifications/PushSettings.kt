package me.nathanfallet.teamcode.domain.notifications

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.artifacts.ArtifactAdministrationService
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import me.nathanfallet.teamcode.domain.team.*

/** Who sends a team's pushes: nobody, the TeamCode Push relay of the published apps, or the team's own Firebase project. */
enum class PushProvider(val wire: String) {
    OFF("off"), RELAY("relay"), FIREBASE("firebase");
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

/**
 * A team's push settings, set by an administrator on the dashboard or the API. The Firebase service account key is never
 * part of it: it stays sealed in persistence; [firebaseProjectId] and [firebaseClientEmail] say which one is stored.
 */
data class PushSettings(
    val provider: PushProvider = PushProvider.OFF,
    val relayUrl: String = PushAdministrationService.DEFAULT_RELAY_URL,
    val firebaseProjectId: String? = null,
    val firebaseClientEmail: String? = null,
    /** 0 until saved once; a save names the version it replaces. */
    val version: Int = 0,
    val updatedAt: Long? = null,
)

/** What an administrator saves. A null [firebaseServiceAccount] keeps the stored one while Firebase stays the provider. */
class PushSettingsInput(val provider: PushProvider, val relayUrl: String, val firebaseServiceAccount: String?, val version: Int) {
    override fun toString() = "PushSettingsInput(provider=$provider, relayUrl=$relayUrl, firebaseServiceAccount=[REDACTED], version=$version)"
}

/** A Google service account JSON key, checked: the parts shown on the dashboard, and the whole JSON to seal. */
class FirebaseServiceAccount(val projectId: String, val clientEmail: String, val json: String) {
    override fun toString() = "FirebaseServiceAccount(projectId=$projectId, clientEmail=$clientEmail, json=[REDACTED])"
}

/** A team's provider with its secret opened, for the sender only. Never logged or serialized. */
class PushConnection(val teamId: String, val provider: PushProvider, val relayUrl: String, val firebaseServiceAccount: String?, val version: Int) {
    override fun toString() = "PushConnection(teamId=$teamId, provider=$provider, relayUrl=$relayUrl, version=$version)"
}

interface PushSettingsStore {
    /** Revalidates [actor]; the defaults (push off) when never saved. */
    suspend fun settings(actor: Principal): PushSettings
    /**
     * Saves under the team lock, `409 push_settings_conflict` unless [version] is current. With Firebase, a null
     * [serviceAccount] keeps the stored key, and none stored is refused with `400 push_firebase_credentials_required`;
     * any other provider removes the stored key.
     */
    suspend fun save(actor: Principal, provider: PushProvider, relayUrl: String, serviceAccount: FirebaseServiceAccount?, version: Int): PushSettings
    /** The connections of [teamIds] whose provider is not off, in one read. */
    suspend fun connections(teamIds: Set<String>): Map<String, PushConnection>
}

/** The senders of teams whose push is on, built from their settings and kept while the settings do not change. */
fun interface PushSenders {
    suspend fun senders(teamIds: Set<String>): Map<String, PushSender>
}

/**
 * Push administration: the settings are for interactive administrators only; the test push is for every interactive
 * member, to their own phones, at most once per [TEST_COOLDOWN_MILLIS] on a replica.
 */
class PushAdministrationService(
    private val store: PushSettingsStore,
    private val channel: PushChannel,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastTests = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun settings(actor: Principal): PushSettings { actor.requireAdmin(); return store.settings(actor) }

    suspend fun save(actor: Principal, input: PushSettingsInput): PushSettings {
        actor.requireAdmin()
        val relayUrl = input.relayUrl.trim().removeSuffix("/").ifEmpty { DEFAULT_RELAY_URL }
        validateRelayUrl(relayUrl)
        val account = input.firebaseServiceAccount?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseServiceAccount)
        return store.save(actor, input.provider, relayUrl, account, input.version)
    }

    /** Sends a test notification to the caller's own installations, through the provider in force. */
    suspend fun test(actor: Principal): PushTestResult {
        actor.requireInteractive()
        val now = clock()
        val key = "${actor.teamId}/${actor.member.id}"
        // A member's own phones only, yet a button pressed in a loop would still spend the provider's quotas.
        if (lastTests.size > MAX_TRACKED_TESTS) lastTests.entries.removeIf { now - it.value >= TEST_COOLDOWN_MILLIS }
        val previous = lastTests[key]
        if (previous != null && now - previous < TEST_COOLDOWN_MILLIS) throw TeamFailure("push_test_rate_limited", 429, "Wait a few seconds before sending another test.")
        val result = channel.test(actor.teamId, actor.member.id)
        lastTests[key] = now
        return result
    }

    companion object {
        const val TEST_COOLDOWN_MILLIS = 10_000L
        private const val MAX_TRACKED_TESTS = 10_000
        const val DEFAULT_RELAY_URL = "https://push.teamcode.nathanfallet.me"
        const val MAX_SERVICE_ACCOUNT_CHARS = 16_384
        /** The only token endpoint a stored key may name: the server posts the signed assertion there. */
        const val GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"
        private val projectId = Regex("[a-z][a-z0-9-]{4,62}")
        private val clientEmail = Regex("[A-Za-z0-9._%+-]{1,128}@[A-Za-z0-9.-]{1,128}")

        fun validateRelayUrl(url: String) {
            if (!ArtifactAdministrationService.validEndpoint(url)) throw TeamFailure("invalid_push_settings", 400, "Use an https relay URL without a path, such as $DEFAULT_RELAY_URL.")
            if (!CloudAdministrationService.allowedApiHost(url)) throw TeamFailure("push_relay_host_not_allowed", 400, "The relay URL must not point at a link-local or cloud metadata address.")
        }

        fun parseServiceAccount(json: String): FirebaseServiceAccount {
            fun invalid(): Nothing = throw TeamFailure("invalid_push_settings", 400, "Paste the JSON key of a Google service account of the Firebase project (type service_account).")
            if (json.length > MAX_SERVICE_ACCOUNT_CHARS) invalid()
            val root = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null } ?: invalid()
            fun field(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (field("type") != "service_account") invalid()
            val project = field("project_id")?.takeIf(projectId::matches) ?: invalid()
            val email = field("client_email")?.takeIf(clientEmail::matches) ?: invalid()
            if (field("private_key")?.contains("PRIVATE KEY-----") != true || field("private_key_id").isNullOrEmpty()) invalid()
            if (field("token_uri") != GOOGLE_TOKEN_URI) throw TeamFailure("invalid_push_settings", 400, "The key's token_uri must be $GOOGLE_TOKEN_URI.")
            return FirebaseServiceAccount(project, email, json)
        }
    }
}

/** What a test push did: one result per installation of the administrator, `sent`, `unregistered`, `retry` or `rejected`. */
data class PushTestResult(val results: List<PushResult>) {
    val ok get() = results.any { it.status == PushResult.Status.SENT }
}
