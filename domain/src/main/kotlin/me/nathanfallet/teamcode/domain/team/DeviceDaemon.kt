package me.nathanfallet.teamcode.domain.team

/**
 * A folder a device's daemon lets its owner start remote sessions in. [id] is the daemon's own key for the folder,
 * which it maps to a path in its configuration; [name] is the folder's last segment (what a session shows); [label] is
 * how the owner recognizes it (for example `~/git/teamcode`), shown to the owner only. The server never holds a path.
 */
data class DeviceFolder(val id: String, val name: String, val label: String? = null)

/** Whether a device's daemon is connected: it polled for orders less than [DeviceDaemonRules.ONLINE_TTL_MILLIS] ago. */
data class DaemonPresence(val online: Boolean, val lastSeenAt: Long, val version: String?) {
    companion object {
        /** Null when the daemon never connected, or said it stopped. */
        fun of(lastSeenAt: Long?, version: String?, now: Long) =
            lastSeenAt?.let { DaemonPresence(now - it < DeviceDaemonRules.ONLINE_TTL_MILLIS, it, version) }
    }
}

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
    /** A directory's last segment: what a remote session shows, never a path. */
    fun validDirectoryName(value: String) = value.isNotBlank() && value.length <= 255 && value != "." && value != ".." &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }
}
