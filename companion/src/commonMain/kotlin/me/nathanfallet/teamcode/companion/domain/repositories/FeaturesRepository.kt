package me.nathanfallet.teamcode.companion.domain.repositories

/** What an account's server announces in `GET /capabilities`, kept in memory and read again on [refresh]. */
interface FeaturesRepository {
    suspend fun features(accountId: String, refresh: Boolean = false): Set<String>
}

/** Capabilities the app reads. */
object Features {
    const val SESSIONS = "cloud.sessions.v1"
    const val TRANSCRIPT = "cloud.transcript.v1"
    const val MESSAGES = "cloud.messages.v1"
    const val EVENTS = "cloud.events.v1"
    const val INITIAL_MESSAGE = "cloud.sessions.initial-message.v1"
    const val ARTIFACTS = "artifacts.v1"
    const val ARTIFACT_TICKETS = "artifacts.tickets.v1"
}
