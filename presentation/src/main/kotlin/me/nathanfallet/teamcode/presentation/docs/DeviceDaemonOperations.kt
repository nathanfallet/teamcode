package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.*
import me.nathanfallet.teamcode.api.models.*

private const val DAEMON_LOGIN = "Called by the device's TeamCode daemon with the device's own interactive login " +
    "(`cloud.sessions.remote.daemon.v1`); a service key, a browser session or a login without an installation is " +
    "refused (`daemon_requires_device`)."

/** The daemon channel of a member's device, and the owner's view of its folders. */
internal fun ApiCatalog.deviceDaemonOperations() {
    post("/api/v1/devices/current/daemon/poll") {
        operationId = "pollDeviceDaemon"; tag(ApiTags.DEVICES)
        summary = "Wait for sessions to host"
        description = "$DAEMON_LOGIN Marks the daemon online (a device stays online for `onlineTtlMillis` after a poll) " +
            "and returns the sessions waiting for this device's daemon, oldest first, at most 16: started on this device " +
            "from another client, woken by a message, or whose host was lost before it was ready. With `waitMillis` " +
            "(at most `maxWaitMillis`, 20 s), the call waits for one. The daemon checks `folderId` against its own " +
            "configuration, then starts the session with `POST /api/v1/cloud/sessions/{id}/host` or refuses it. Poll " +
            "again at once after each answer."
        body<DaemonPollRequest>("How long to wait, and the daemon's version.")
        responses {
            ok<DaemonPollView>("The sessions to host; empty after the wait.")
            badRequest("An invalid version (`invalid_version`).")
            error(HttpStatusCode.UnprocessableEntity, "Not the device's own interactive login (`daemon_requires_device`).")
        }
    }
    delete("/api/v1/devices/current/daemon") {
        operationId = "stopDeviceDaemon"; tag(ApiTags.DEVICES)
        summary = "Mark the daemon offline"
        description = "$DAEMON_LOGIN Called when the daemon stops: the device is offline at once, so no client starts a " +
            "session there. Sessions already queued stay queued until the daemon polls again."
        responses {
            empty("Offline.")
            error(HttpStatusCode.UnprocessableEntity, "Not the device's own interactive login (`daemon_requires_device`).")
        }
    }
    put("/api/v1/devices/current/folders") {
        operationId = "publishDeviceFolders"; tag(ApiTags.DEVICES)
        summary = "Publish the device's folders"
        description = "$DAEMON_LOGIN Replaces the folders the owner may start sessions in from another client, at most 64, " +
            "in order. `id` is the daemon's own key for the folder (1–64 characters from `A-Z a-z 0-9 _ -`), `name` its " +
            "last segment, `label` how the owner recognizes it (for example `~/git/teamcode`). Never send a path the " +
            "server should rely on: an order names the folder by `id`, and the daemon maps it to its configuration."
        body<PublishDeviceFoldersRequest>("Every folder the device offers.")
        responses {
            ok<List<DeviceFolderView>>("The published folders.")
            badRequest("Too many folders, a duplicate or invalid id, a path as name, or an invalid label (`invalid_folders`).")
            error(HttpStatusCode.UnprocessableEntity, "Not the device's own interactive login (`daemon_requires_device`).")
        }
    }
    post("/api/v1/devices/current/daemon/sessions/{id}/refuse") {
        operationId = "refuseDeviceDaemonSession"; tag(ApiTags.DEVICES)
        summary = "Refuse a session to host"
        description = "$DAEMON_LOGIN The daemon's own check rejected a session of its queue (the folder left its " +
            "configuration, the agent is not installed…): the session ends, with `failure.code` `host_refused` and the " +
            "reason in its message."
        parameters { pathId("id", "The session's identifier.") }
        body<RefuseDaemonOrderRequest>("A stable reason code (1–64 characters from `a-z` and `_`), such as `folder_missing`.")
        responses {
            ok<CloudSessionView>("The session, ending.")
            badRequest("An invalid reason (`invalid_reason`).")
            notFound("No such session started through this device's daemon (`not_found`).")
            conflict("Another device's session (`remote_host_mismatch`, `remote_host_removed`), or a session that no longer waits for its host (`not_waiting_host`).")
            error(HttpStatusCode.UnprocessableEntity, "Not the device's own interactive login (`daemon_requires_device`).")
        }
    }
    get("/api/v1/devices/{id}/folders") {
        operationId = "listDeviceFolders"; tag(ApiTags.DEVICES)
        summary = "List a device's folders"
        description = "The folders one of the caller's own devices offers, and whether its daemon is online: what a client " +
            "shows before starting a session there (`cloud.sessions.remote.daemon.v1`). Only the device's owner reads " +
            "them; anyone else, an administrator included, gets `device_not_found`."
        parameters { pathId("id", "The device's identifier.") }
        responses {
            ok<DeviceFoldersView>("The device's presence and folders.")
            notFound("Not one of the caller's devices (`device_not_found`).")
        }
    }
}
