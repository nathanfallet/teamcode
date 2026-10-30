package me.nathanfallet.teamcode.companion.domain.usecases.automations

/**
 * The webhook secrets shown once: kept in memory from the creation or the rotation until the member says they stored it
 * (or the app closes). Never written anywhere, never read from the server.
 */
class AutomationSecrets {
    private val held = mutableMapOf<String, String>()
    fun hold(automationId: String, secret: String) { held[automationId] = secret }
    fun secret(automationId: String): String? = held[automationId]
    fun forget(automationId: String) { held.remove(automationId) }
}
