package me.nathanfallet.teamcode.companion.domain.repositories

/** What an account's server announces in `GET /capabilities`, kept in memory and read again on [refresh]. */
interface FeaturesRepository {
    suspend fun features(accountId: String, refresh: Boolean = false): Set<String>
}

/** Capabilities the app reads. */
object Features {
    const val SESSIONS = "cloud.sessions.v1"
    /** Ownership transfers: an administrator's transfer, an owner's offer, accept, decline and cancel. */
    const val TRANSFERS = "cloud.transfer.v1"
    /** The members a session can be given to, listed to members too (`GET /cloud/transfer-recipients`). */
    const val TRANSFER_RECIPIENTS = "cloud.transfer.recipients.v1"
    const val TRANSCRIPT = "cloud.transcript.v1"
    const val MESSAGES = "cloud.messages.v1"
    const val EVENTS = "cloud.events.v1"
    /** Permission requests relayed from Claude Code, answered with `POST …/permissions`. */
    const val PERMISSIONS = "cloud.permissions.v1"
    const val INITIAL_MESSAGE = "cloud.sessions.initial-message.v1"
    const val ARTIFACTS = "artifacts.v1"
    const val ARTIFACT_TICKETS = "artifacts.tickets.v1"
    /** Sessions on a member's device: the list and the feed take `kind`, sessions carry their host. */
    const val REMOTE_SESSIONS = "cloud.sessions.remote.v1"
    /** Sessions started on one of the member's online devices by its daemon, in a folder it published (`GET /devices/{id}/folders`). */
    const val DEVICE_SESSIONS = "cloud.sessions.remote.daemon.v1"
    /** Files sent with messages: `POST …/files`, then their ids in the message's `fileIds`. */
    const val FILES = "cloud.files.v1"
    /** The member's GitHub link (`/github/link`), offered with cloud sessions and kept when they are off. */
    const val GITHUB_LINK = "github.link.v1"
}
