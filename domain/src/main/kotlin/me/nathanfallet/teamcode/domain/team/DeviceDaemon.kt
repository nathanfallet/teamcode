package me.nathanfallet.teamcode.domain.team

/**
 * A folder a device's daemon lets its owner start remote sessions in. [id] is the daemon's own key for the folder,
 * which it maps to a path in its configuration; [name] is the folder's last segment (what a session shows); [label] is
 * how the owner recognizes it (for example `~/git/teamcode`), shown to the owner only. The server never holds a path.
 */
data class DeviceFolder(val id: String, val name: String, val label: String? = null)

/**
 * Whether a device's daemon is connected: it polled for orders less than [DeviceDaemonRules.ONLINE_TTL_MILLIS] ago.
 * [features]: what it announced at its latest recorded poll ([DeviceDaemonRules.RESUME_FEATURE]).
 */
data class DaemonPresence(val online: Boolean, val lastSeenAt: Long, val version: String?, val features: List<String> = emptyList()) {
    companion object {
        /** Null when the daemon never connected, or said it stopped. */
        fun of(lastSeenAt: Long?, version: String?, now: Long, features: List<String> = emptyList()) =
            lastSeenAt?.let { DaemonPresence(now - it < DeviceDaemonRules.ONLINE_TTL_MILLIS, it, version, features) }
    }
}

/**
 * An agent conversation of a device's folder that a session can continue (`cloud.sessions.remote.resume.v1`), as the
 * device's daemon describes it: its id, a short title, its last activity and the number of messages the member sent.
 * Only the device's owner sees it, and the server never logs it.
 */
data class ResumableSession(val id: String, val title: String, val lastActiveAt: Long, val turns: Int) {
    override fun toString() = "ResumableSession(id=$id, title=[REDACTED], lastActiveAt=$lastActiveAt, turns=$turns)"
}
/** The daemon's answer to a listing: a page of [sessions] and the cursor of the next, or the reason it [refused]. */
data class ResumableAnswer(val sessions: List<ResumableSession>, val nextBefore: Long? = null, val refused: String? = null)
/** A member waiting for a folder's conversations, as the daemon's poll hands it over. */
data class DaemonListing(val requestId: String, val folderId: String, val limit: Int, val before: Long?)

/** One of the caller's own devices, as a start order needs it: its presence and its published folders. */
data class OwnedDevice(val id: String, val name: String, val daemon: DaemonPresence?, val folders: List<DeviceFolder>)

object DeviceDaemonRules {
    /** The daemon is online while its last poll is younger than this; a poll waits at most [MAX_WAIT_MILLIS]. */
    const val ONLINE_TTL_MILLIS = 60_000L
    const val MAX_WAIT_MILLIS = 20_000L
    /** How often a waiting poll reads the queue again: cost x frequency, one indexed read per waiting daemon per second. */
    const val POLL_MILLIS = 1_000L
    /** A poll within this delay of the previous one does not write the device row again. */
    const val TOUCH_MILLIS = 5_000L
    const val ORDER_BATCH = 16
    const val MAX_FOLDERS = 64

    /** The daemon lists a folder's conversations and starts a session from one (`cloud.sessions.remote.resume.v1`). */
    const val RESUME_FEATURE = "resume.v1"
    const val MAX_FEATURES = 8
    /** How long a member waits for the daemon's listing; the request is dropped when the wait ends, answered or not. */
    const val LISTING_WAIT_MILLIS = 10_000L
    /** How often the waiting member's request reads its answer: one primary-key read, only while a member waits. */
    const val LISTING_POLL_MILLIS = 250L
    /** A request left behind (its replica stopped while it waited) is ignored after this, and removed at the device's next one. */
    const val LISTING_TTL_MILLIS = 30_000L
    const val LISTING_BATCH = 4
    const val DEFAULT_LISTING_LIMIT = 20
    const val MAX_LISTING_LIMIT = 50
    const val TITLE_LENGTH = 80
    /** A Claude Code session id: a lowercase UUID, the name of its transcript on the device. */
    private val conversationId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val feature = Regex("[a-z0-9._-]{1,32}")

    private val folderId = Regex("[A-Za-z0-9_-]{1,64}")
    private val version = Regex("[A-Za-z0-9._+-]{1,32}")
    private val reason = Regex("[a-z_]{1,64}")

    fun validateFolders(folders: List<DeviceFolder>) {
        if (folders.size > MAX_FOLDERS) throw TeamFailure("invalid_folders", 400, "Publish at most $MAX_FOLDERS folders.")
        if (folders.map { it.id }.toSet().size != folders.size) throw TeamFailure("invalid_folders", 400, "Folder ids must be unique.")
        for (folder in folders) {
            if (!folderId.matches(folder.id)) throw TeamFailure("invalid_folders", 400, "Use folder ids of 1–64 characters from A-Z a-z 0-9 _ -.")
            if (!validDirectoryName(folder.name)) throw TeamFailure("invalid_folders", 400, "Send each folder's name (its last segment, 1–255 characters), never its path.")
            folder.label?.let { if (it.isBlank() || it.length > 512 || it.any(Char::isISOControl)) throw TeamFailure("invalid_folders", 400, "Use folder labels of 1–512 characters without control characters.") }
        }
    }
    fun validateVersion(value: String?) {
        if (value != null && !version.matches(value)) throw TeamFailure("invalid_version", 400, "Send the daemon's version as 1–32 characters from A-Z a-z 0-9 . _ + -.")
    }
    fun validateReason(value: String) {
        if (!reason.matches(value)) throw TeamFailure("invalid_reason", 400, "Send a stable reason code of 1–64 characters from a-z and _.")
    }
    fun validFolderId(value: String) = folderId.matches(value)
    fun validConversationId(value: String) = conversationId.matches(value)
    fun validateFeatures(values: List<String>) {
        if (values.size > MAX_FEATURES || values.any { !feature.matches(it) })
            throw TeamFailure("invalid_features", 400, "Send at most $MAX_FEATURES daemon features of 1–32 characters from a-z 0-9 . _ -.")
    }
    fun validateListing(limit: Int, before: Long?) {
        if (limit !in 1..MAX_LISTING_LIMIT) throw TeamFailure("invalid_limit", 400, "Use a limit from 1 to $MAX_LISTING_LIMIT.")
        if (before != null && before < 0) throw TeamFailure("invalid_cursor", 400, "Use the nextBefore of the previous page.")
    }
    /** What the daemon may answer: ids, short one-line titles, times and counts, nothing else and nothing larger. */
    fun validateAnswer(answer: ResumableAnswer) {
        answer.refused?.let(::validateReason)
        if (answer.sessions.size > MAX_LISTING_LIMIT || (answer.refused != null && answer.sessions.isNotEmpty()))
            throw TeamFailure("invalid_listing", 400, "Send at most $MAX_LISTING_LIMIT conversations, or a refusal alone.")
        if ((answer.nextBefore ?: 0) < 0) throw TeamFailure("invalid_listing", 400, "Send a cursor of zero or more.")
        for (session in answer.sessions) {
            if (!validConversationId(session.id) || session.lastActiveAt < 0 || session.turns < 0 ||
                session.title.isBlank() || session.title.length > TITLE_LENGTH || session.title.any { it.isISOControl() })
                throw TeamFailure("invalid_listing", 400, "Send each conversation's id (a lowercase UUID), a one-line title of 1–$TITLE_LENGTH characters, its last activity and its count of messages.")
        }
    }
    /** A directory's last segment: what a remote session shows, never a path. */
    fun validDirectoryName(value: String) = value.isNotBlank() && value.length <= 255 && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }
}
