package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/**
 * Sessions started on a member's device by its daemon, on real MySQL with two pools standing in for two replicas: the
 * daemon publishes its folders and polls on one, the session is created and hosted on the other.
 */
class DeviceDaemonPersistenceTest {
    private val password = "daemon colleague replacement password"

    @Test fun onlyTheOwnerStartsASessionOnAnOnlineDeviceInAPublishedFolder() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(Devices, DeviceFolders, CloudSessions, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                scenario(a, b)
            } }
        }
    }

    private suspend fun scenario(a: Database, b: Database) {
        val team = SqlTeamService(a)
        val bootstrap = team.bootstrap()!!
        team.changePassword(team.login("admin", bootstrap, "test").principal, bootstrap, "daemon admin replacement password")
        val admin = team.login("admin", "daemon admin replacement password", "Admin laptop", "9f0e0d0c-0b0a-4009-8807-060504030201", "macos", "15.1").principal
        val created = team.createMember(admin, "daemon-colleague", Role.MEMBER)
        team.changePassword(team.login("daemon-colleague", created.temporaryPassword, "test").principal, created.temporaryPassword, password)
        val laptop = team.login("daemon-colleague", password, "Laptop", "1f0e0d0c-0b0a-4009-8807-060504030201", "linux", "6.8").principal
        val desktop = team.login("daemon-colleague", password, "Desktop", "2f0e0d0c-0b0a-4009-8807-060504030201", "windows", "11").principal
        val phone = team.login("daemon-colleague", password, "Phone", "3f0e0d0c-0b0a-4009-8807-060504030201", "ios", "18.0").principal
        val browser = team.login("daemon-colleague", password, "Browser").principal
        val key = team.authenticate(SqlServiceKeyService(a).create(laptop, laptop.member.id, "ci", setOf(ServiceScope.CLOUD_WRITE, ServiceScope.CLOUD_READ), 1).accessToken)!!

        val daemonStore = SqlDeviceDaemonStore(a)
        var onSleep: suspend () -> Unit = {}
        val daemon = DeviceDaemonUseCase(daemonStore, sleep = { onSleep() })
        val sessionStore = SqlCloudSessionStore(b)
        val sessions = CloudSessionService(sessionStore, daemons = SqlDeviceDaemonStore(b))
        val devices = SqlDeviceService(b)
        val laptopId = sessionStore.deviceOf(laptop)!!
        val adminDevice = sessionStore.deviceOf(admin)!!
        fun draft(folder: String? = "api", device: String? = laptopId) =
            RemoteSessionDraft("From the phone", "claude", null, null, emptyMap(), null, RemotePermissionMode.BYPASS, deviceId = device, folderId = folder)

        // The daemon channel takes the device's own interactive login, nothing else.
        for (caller in listOf(browser, key)) failure("daemon_requires_device") { daemon.poll(caller, 0, "1.0.0") }
        failure("daemon_requires_device") { daemon.publish(key, emptyList()) }
        failure("invalid_version") { daemon.poll(laptop, 0, "1.0 beta") }

        // Never connected: no presence, and a start is refused before anything is queued.
        assertNull(devices.devices(laptop).single { it.id == laptopId }.daemon)
        failure("device_offline") { sessions.createRemote(phone, null, draft(), null) }

        // Folders: the daemon's own keys and the folders' names, never paths; replaced as a whole.
        failure("invalid_folders") { daemon.publish(laptop, listOf(DeviceFolder("api", "api"), DeviceFolder("api", "web"))) }
        failure("invalid_folders") { daemon.publish(laptop, listOf(DeviceFolder("api", "/home/someone/api"))) }
        failure("invalid_folders") { daemon.publish(laptop, listOf(DeviceFolder("a/b", "api"))) }
        failure("invalid_folders") { daemon.publish(laptop, (0..DeviceDaemonRules.MAX_FOLDERS).map { DeviceFolder("f$it", "f$it") }) }
        daemon.publish(laptop, listOf(DeviceFolder("old", "old")))
        daemon.publish(laptop, listOf(DeviceFolder("teamcode", "teamcode", "~/git/teamcode"), DeviceFolder("api", "api", "~/work/api")))
        daemon.publish(desktop, listOf(DeviceFolder("api", "api")))

        // The first poll marks the daemon online, with its version; the device list reads it from the device row.
        assertEquals(emptyList(), daemon.poll(laptop, 0, "1.0.0").orders)
        val presence = devices.devices(phone).single { it.id == laptopId }.daemon!!
        assertTrue(presence.online); assertEquals("1.0.0", presence.version)
        assertEquals(listOf("teamcode", "api"), daemon.owned(phone, laptopId).folders.map { it.id })
        assertEquals("~/git/teamcode", daemon.owned(phone, laptopId).folders.first().label)

        // Owner only: an administrator neither reads another member's folders nor starts a session on their device.
        failure("device_not_found") { daemon.owned(admin, laptopId) }
        failure("device_not_found") { sessions.createRemote(admin, null, draft(), null) }
        failure("on_behalf_forbidden") { sessions.createRemote(admin, laptop.member.id, draft(), null) }
        failure("device_not_found") { sessions.createRemote(phone, null, draft(device = adminDevice), null) }
        failure("device_not_found") { sessions.createRemote(phone, null, draft(device = "00000000-0000-4000-8000-000000000000"), null) }
        failure("remote_requires_login") { sessions.createRemote(key, null, draft(), null) }
        // Only a folder the device published; the session's directory is the folder's name.
        failure("folder_not_published") { sessions.createRemote(phone, null, draft(folder = "old"), null) }
        failure("invalid_folder") { sessions.createRemote(phone, null, draft(folder = null), null) }
        failure("invalid_folder") { sessions.createRemote(laptop, null, draft(device = null).copy(directoryName = "api"), null) }
        failure("invalid_directory_name") { sessions.createRemote(phone, null, draft().copy(directoryName = "web"), null) }

        // From the phone (or the dashboard's login, which has no device): queued for the laptop's daemon only.
        val first = (sessions.createRemote(phone, null, draft(), null) as CloudCreateOutcome.Created).session
        val second = (sessions.createRemote(browser, null, draft(folder = "teamcode").copy(permissionMode = RemotePermissionMode.ASK), null) as CloudCreateOutcome.Created).session
        val read = sessions.get(phone, first.id)
        assertEquals(CREATING to CloudSessionService.WAITING_HOST, read.state to read.phase)
        assertEquals(Triple(laptopId, "api", "api"), Triple(read.host!!.deviceId, read.host!!.directoryName, read.host!!.folderId))
        assertEquals(RemotePermissionMode.BYPASS, read.host!!.permissionMode)
        // A cloud pod's execution credential never becomes a host device, never shows online and never starts a session
        // on a device, whatever its scopes or device: here the worst case, a link to a cloud execution on a token that
        // otherwise looks like the laptop's interactive login.
        val podToken = "tcex_" + "synthetic-execution-credential-0123456789"
        a.query {
            val now = nowMillis()
            Tokens.insert {
                it[id] = "pod-token"; it[memberId] = laptop.member.id; it[tokenHash] = me.nathanfallet.teamcode.infrastructure.secrets.Passwords.digest(podToken)
                it[device] = "cloud session"; it[deviceId] = laptopId; it[createdAt] = now; it[expiresAt] = now + 3_600_000
            }
            CloudInferenceCredentials.insert {
                it[tokenId] = "pod-token"; it[sessionId] = first.id; it[epoch] = 1; it[teamId] = laptop.teamId; it[createdAt] = now; it[expiresAt] = now + 3_600_000
            }
        }
        val pod = team.authenticate(podToken)!!
        val devicesBefore = devices.devices(phone).map { it.id to it.name }
        failure("cloud_session_credential") { daemon.poll(pod, 0, "1.0.0") }
        failure("cloud_session_credential") { daemon.publish(pod, listOf(DeviceFolder("workspace", "workspace"))) }
        failure("cloud_session_credential") { daemon.offline(pod) }
        failure("cloud_session_credential") { sessions.createRemote(pod, null, draft(), null) }
        // Nor does it host one, or create one on "its" device: the session store never sees a device behind it.
        assertNull(sessionStore.deviceOf(pod))
        failure("remote_requires_device") { sessions.host(pod, first.id) }
        failure("remote_requires_device") { sessions.createRemote(pod, null, draft(device = null, folder = null).copy(directoryName = "workspace"), null) }
        assertEquals(devicesBefore, devices.devices(phone).map { it.id to it.name }, "No device registered for the pod")
        assertEquals(listOf("teamcode", "api"), daemon.owned(phone, laptopId).folders.map { it.id }, "The pod published nothing")
        a.query { Tokens.deleteWhere { Tokens.id eq "pod-token" } }

        // A session started on the device itself is not the daemon's: `teamcode remote start` hosts it.
        sessions.createRemote(laptop, null, RemoteSessionDraft("Local", "codex", null, null, emptyMap(), "teamcode", RemotePermissionMode.ASK), null)

        val orders = daemon.poll(laptop, 0, "1.0.0").orders
        assertEquals(listOf(first.id, second.id), orders.map { it.sessionId })
        assertEquals(DaemonOrder(first.id, "api", "api", "claude", RemotePermissionMode.BYPASS, CREATING, orders.first().requestedAt), orders.first())
        assertEquals(emptyList(), daemon.poll(desktop, 0, null).orders, "Another device of the owner sees nothing")

        // Refusal: only the session's device, for a session that still waits; it ends with host_refused.
        failure("invalid_reason") { sessions.refuseHost(laptop, second.id, "Folder Missing") }
        failure("remote_host_mismatch") { sessions.refuseHost(desktop, second.id, "folder_missing") }
        failure("not_found") { sessions.refuseHost(admin, second.id, "folder_missing") }
        val refused = sessions.refuseHost(laptop, second.id, "folder_missing")
        assertEquals(DesiredState.TERMINATED to "host_refused", refused.desiredState to refused.terminatedReason)
        assertEquals(CloudProblem("host_refused", "The device refused to start this session (folder_missing).", false), refused.failure)
        failure("not_waiting_host") { sessions.refuseHost(laptop, second.id, "folder_missing") }

        // Accepting is the existing host call: the session leaves the queue.
        assertEquals(1, sessions.host(laptop, first.id).currentEpoch)
        assertEquals(emptyList(), daemon.poll(laptop, 0, "1.0.0").orders)

        // Paused, then woken by a message from the phone: the session is back in the daemon's queue, as `resuming`.
        // Nothing else starts its host (the desktop app no longer supervises hosts); a session the device started
        // itself, without a folder, stays out of the queue when woken.
        val runner = RunnerChannelUseCase(sessionStore)
        val reconciler = ReconcileCloudSessionsUseCase(sessionStore, FakeClusterPort())
        val conversations = SqlCloudConversationStore(b)
        val access = CloudAccessService(conversations, sessionStore)
        val conversation = CloudConversationService(conversations, sessionStore, access, { _, _ -> false }, sleep = { delay(1) })
        suspend fun pass() { b.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }; reconciler() }
        suspend fun pauseHosted(id: String, epoch: Int) {
            runner.register(laptop.teamId, id, epoch, CloudSessionService.hostExecutionName(epoch))
            runner.heartbeat(laptop.teamId, id, epoch, ready = true)
            sessions.pause(phone, null, id, false)
            pass()
            runner.checkpoint(laptop.teamId, id, epoch, completed = true)
            pass()
            assertEquals(PAUSED, sessionStore.find(laptop.teamId, id)!!.state)
        }
        pauseHosted(first.id, 1)
        val local = sessions.list(laptop, CloudSessionQuery(kinds = setOf(CloudSessionKind.REMOTE))).sessions.single { it.name == "Local" }
        pauseHosted(local.id, sessions.host(laptop, local.id).currentEpoch)
        assertEquals(emptyList(), daemon.poll(laptop, 0, "1.0.0").orders, "A paused session waits for a message")
        for (id in listOf(first.id, local.id)) conversation.send(phone, id, InputRequest("Continue", true, null, null, null))
        pass()
        assertEquals(RESUMING to CloudSessionService.WAITING_HOST, sessionStore.find(laptop.teamId, local.id)!!.let { it.state to it.phase })
        val woken = daemon.poll(laptop, 0, "1.0.0").orders
        assertEquals(listOf(first.id), woken.map { it.sessionId }, "The woken daemon session only, not the one without a folder")
        assertEquals(listOf(RESUMING), woken.map { it.state })
        assertEquals(listOf("api" to RemotePermissionMode.BYPASS), woken.map { it.folderId to it.permissionMode })
        assertEquals(2, sessions.host(laptop, first.id).currentEpoch)
        assertEquals(emptyList(), daemon.poll(laptop, 0, "1.0.0").orders)

        // A waiting poll returns as soon as an order arrives, without a transaction held between reads.
        var started: String? = null
        onSleep = { if (started == null) started = (sessions.createRemote(phone, null, draft(folder = "teamcode"), null) as CloudCreateOutcome.Created).session.id }
        val waited = daemon.poll(laptop, 10_000, "1.0.0").orders
        assertEquals(listOf(started), waited.map { it.sessionId })
        onSleep = {}

        // A daemon that says it stops is offline at once: nothing new can be started there.
        daemon.offline(laptop)
        assertNull(devices.devices(phone).single { it.id == laptopId }.daemon)
        failure("device_offline") { sessions.createRemote(phone, null, draft(), null) }
        // So is one whose last poll is older than the presence window.
        daemon.poll(laptop, 0, "1.0.1")
        a.query { Devices.update({ Devices.id eq laptopId }) { it[daemonSeenAt] = nowMillis() - DeviceDaemonRules.ONLINE_TTL_MILLIS - 1 } }
        assertEquals(false, devices.devices(phone).single { it.id == laptopId }.daemon?.online)
        failure("device_offline") { sessions.createRemote(phone, null, draft(), null) }

        // Removing the device removes its folders with it.
        devices.revokeDevice(phone, laptopId)
        assertEquals(0L, a.query { DeviceFolders.selectAll().where { DeviceFolders.deviceId eq laptopId }.count() })
    }

    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
