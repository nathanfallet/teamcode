package me.nathanfallet.teamcode.infrastructure.providers

import kotlinx.serialization.json.*
import java.util.Base64

/** Claims of a ChatGPT OAuth JWT. They select an account header and name an address; the signature is not checked here. */
internal object CodexTokenClaims {
    fun parse(jwt: String?): JsonObject? = try {
        val payload = jwt?.split('.')?.takeIf { it.size == 3 }?.get(1) ?: error("jwt")
        Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
    } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

    private fun auth(claims: JsonObject?) = claims?.get("https://api.openai.com/auth") as? JsonObject
    fun workspace(claims: JsonObject?): String? = auth(claims)?.text("chatgpt_account_id")
    fun user(claims: JsonObject?): String? = auth(claims)?.let { it.text("chatgpt_user_id") ?: it.text("user_id") }
    fun email(claims: JsonObject?): String? = providerEmail((claims?.get("https://api.openai.com/profile") as? JsonObject)?.text("email"))

    /** The identity and address a refresh response's `id_token` (or, lacking one, its access token) states. */
    fun reported(vararg tokens: String?): ReportedAccount? = tokens.firstNotNullOfOrNull { token ->
        parse(token)?.let { ReportedAccount(user(it), workspace(it), email(it)) }?.takeIf { it.email != null }
    }
}
