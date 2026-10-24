package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Sessions and executions in MySQL. Writes are conditional updates on `generation` (and on the reconciler
 * lease when one is named); no transaction is held across cluster calls, which happen in the domain between
 * these short transactions.
 */
class SqlCloudSessionStore(private val database: Database) : CloudSessionStore {
    override suspend fun now(): Long = database.query { nowMillis() }
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }
    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()?.let(::readMember)
    }
    override suspend fun profile(teamId: String, profileId: String): CloudProfile? = database.query { readProfile(teamId, profileId) }
    override suspend fun profileVersion(teamId: String, profileId: String, version: Int): CloudProfileSpec? = database.query {
        (CloudProfileVersions innerJoin CloudProfiles).select(CloudProfileVersions.spec).where {
            (CloudProfiles.id eq profileId) and (CloudProfiles.teamId eq teamId) and (CloudProfileVersions.version eq version)
        }.singleOrNull()?.let { decodeProfileSpec(it[CloudProfileVersions.spec]) }
    }
    override suspend fun cluster(teamId: String, clusterId: String): CloudCluster? = database.query {
        CloudClusters.selectAll().where { (CloudClusters.id eq clusterId) and (CloudClusters.teamId eq teamId) }.singleOrNull()?.let(::readCluster)
    }
    override suspend fun settings(teamId: String): CloudSettings = database.query { readCloudSettings(teamId) }

    override suspend fun insert(session: CloudSession, actor: CloudActor, idempotency: IdempotencyRequest?, retainedLimit: Int): CloudCreateOutcome = database.query {
        val principal = actor.principal
        requireCurrent(this, principal)
        // Serialize per member (sorted to avoid lock-order deadlocks): retained caps and idempotency records.
        for (id in setOf(principal.member.id, actor.effectiveMemberId).sorted()) {
            val row = Members.selectAll().where { (Members.id eq id) and (Members.teamId eq principal.teamId) }.forUpdate().singleOrNull()
            if (row == null || row[Members.disabled] || row[Members.mustChangePassword])
                throw TeamFailure("invalid_effective_member", 422, "The effective member must be an enabled team member who completed password setup.")
        }
        val now = nowMillis()
        if (idempotency != null) {
            val scope = (CloudIdempotency.teamId eq principal.teamId) and (CloudIdempotency.initiatorId eq principal.member.id) and
                (CloudIdempotency.route eq idempotency.route) and (CloudIdempotency.key eq idempotency.key)
            val existing = CloudIdempotency.selectAll().where { scope }.forUpdate().singleOrNull()
            if (existing != null && existing[CloudIdempotency.expiresAt] > now) {
                if (existing[CloudIdempotency.requestDigest] != idempotency.requestDigest)
                    throw TeamFailure("idempotency_conflict", 422, "This Idempotency-Key was already used with a different request.")
                val replay = readSession(principal.teamId, existing[CloudIdempotency.sessionId])
                    ?: throw TeamFailure("idempotency_conflict", 422, "This Idempotency-Key refers to a session that no longer exists.")
                return@query CloudCreateOutcome.Replayed(replay)
            }
            if (existing != null) CloudIdempotency.deleteWhere { scope }
        }
        // The cap bounds the workspaces a member keeps on clusters: remote sessions keep none, and a device has no cap.
        if (session.kind == CloudSessionKind.CLOUD) {
            val retained = CloudSessions.select(CloudSessions.id).where {
                (CloudSessions.teamId eq principal.teamId) and (CloudSessions.ownerId eq actor.effectiveMemberId) and (CloudSessions.kind eq CloudSessionKind.CLOUD.wire) and
                    (CloudSessions.state notInList listOf(CloudSessionState.TERMINATED.wire, CloudSessionState.FAILED.wire))
            }.count()
            if (retained >= retainedLimit) throw TeamFailure("cloud_session_limit", 409, "The member already retains the maximum number of cloud sessions. Delete one first.")
        }
        val created = session.copy(generation = 1, createdAt = now, updatedAt = now, lastActivityAt = now)
        CloudSessions.insert {
            it[id] = created.id; it[teamId] = created.teamId; it[ownerId] = created.ownerId; it[name] = created.name
            it[initiatorId] = created.createdBy.initiatorId; it[credentialId] = created.createdBy.credentialId; it[effectiveMemberId] = created.createdBy.effectiveMemberId
            val cloud = created.kind == CloudSessionKind.CLOUD
            it[profileId] = created.profileId.takeIf { cloud }; it[profileVersion] = created.profileVersion; it[clusterId] = created.clusterId.takeIf { cloud }; it[namespace] = created.namespace.takeIf { cloud }
            it[claimName] = created.workspace.claimName; it[storageClass] = created.workspace.storageClass; it[workspaceSize] = created.workspace.sizeGibibytes
            it[environment] = encodeEnvironment(created.environment)
            it[kind] = created.kind.wire; it[hostDeviceId] = created.host?.deviceId; it[hostDirectoryName] = created.host?.directoryName
            it[permissionMode] = created.host?.permissionMode?.wire; it[hostFolderId] = created.host?.folderId
            it[createdAt] = now
            writeSessionMutable(it, created)
            it[reconcileAfter] = now; it[reconcileRequested] = true
        }
        for ((key, value) in created.labels) CloudSessionLabels.insert { it[sessionId] = created.id; it[CloudSessionLabels.key] = key; it[CloudSessionLabels.value] = value }
        if (idempotency != null) CloudIdempotency.insert {
            it[teamId] = principal.teamId; it[initiatorId] = principal.member.id; it[route] = idempotency.route; it[key] = idempotency.key
            it[requestDigest] = idempotency.requestDigest; it[sessionId] = created.id; it[createdAt] = now; it[expiresAt] = now + IDEMPOTENCY_MILLIS
        }
        audit(principal.teamId, principal.member.id, "cloud.session.created", created.id, actor.effectiveMemberId, principal.tokenId)
        publishTeamEvent(created.teamId, TeamEventType.SESSION_CREATED, created.id, created.ownerId, sessionData(created, null, null), now)
        CloudCreateOutcome.Created(created)
    }

    override suspend fun find(teamId: String, id: String): CloudSession? = database.query { readSession(teamId, id) }

    override suspend fun list(teamId: String, query: CloudSessionQuery): CloudSessionPage = database.query {
        val cursor = query.beforeId?.let { before ->
            CloudSessions.select(CloudSessions.createdAt).where { (CloudSessions.id eq before) and (CloudSessions.teamId eq teamId) }.singleOrNull()?.get(CloudSessions.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown session cursor.")
        }
        var condition: Op<Boolean> = CloudSessions.teamId eq teamId
        query.owner?.let { owner -> condition = condition and (CloudSessions.ownerId eq owner) }
        if (query.states.isNotEmpty()) condition = condition and (CloudSessions.state inList query.states.map { it.wire })
        for ((key, value) in query.labels) condition = condition and (CloudSessions.id inSubQuery CloudSessionLabels.select(CloudSessionLabels.sessionId)
            .where { (CloudSessionLabels.key eq key) and (CloudSessionLabels.value eq value) })
        if (cursor != null) condition = condition and ((CloudSessions.createdAt less cursor) or ((CloudSessions.createdAt eq cursor) and (CloudSessions.id less query.beforeId!!)))
        // One bounded page per kind, each read in order from its kind index, merged here: without a kind condition the
        // optimizer reads every session of the owner through the foreign key's index and sorts them.
        val kinds = query.kinds.ifEmpty { CloudSessionKind.entries.toSet() }.sortedBy { it.ordinal }
        val rows = kinds.flatMap { kind ->
            sessionsWithHost.selectAll().where { condition and (CloudSessions.kind eq kind.wire) }
                .orderBy(CloudSessions.createdAt to SortOrder.DESC, CloudSessions.id to SortOrder.DESC).limit(query.limit + 1).toList()
        }.sortedWith(compareByDescending<ResultRow> { it[CloudSessions.createdAt] }.thenByDescending { it[CloudSessions.id] }).take(query.limit + 1)
        val page = rows.take(query.limit)
        val labels = labelsFor(page.map { it[CloudSessions.id] })
        val sessions = page.map { readSessionRow(it, labels[it[CloudSessions.id]].orEmpty()) }
        CloudSessionPage(sessions, if (rows.size > query.limit) sessions.last().id else null)
    }

    override suspend fun executions(teamId: String, sessionId: String): List<CloudExecution> = database.query {
        (CloudExecutions innerJoin CloudSessions).selectAll().where { (CloudSessions.id eq sessionId) and (CloudSessions.teamId eq teamId) }
            .orderBy(CloudExecutions.epoch to SortOrder.DESC).limit(RunnerChannelUseCase.EXECUTION_HISTORY_LIMIT).map(::readExecution)
    }
    override suspend fun execution(sessionId: String, epoch: Int): CloudExecution? = database.query {
        CloudExecutions.selectAll().where { (CloudExecutions.sessionId eq sessionId) and (CloudExecutions.epoch eq epoch) }.singleOrNull()?.let(::readExecution)
    }

    override suspend fun compareAndSet(expected: CloudSession, next: CloudSession, write: CloudWrite): CloudSession? = database.query {
        write.actor?.let { requireCurrent(this, it.principal) }
        val now = nowMillis()
        val updated = CloudSessions.update({ sessionGuard(expected, write.leaseOwner, now) }) {
            writeSessionMutable(it, next.copy(generation = expected.generation + 1))
            if (write.reconcileAt != null) {
                it[reconcileAfter] = write.reconcileAt
                if (write.leaseOwner == null) it[reconcileRequested] = true
            }
        }
        if (updated != 1) return@query null
        write.updateExecution?.let { execution ->
            require(execution.sessionId == expected.id)
            CloudExecutions.update({ (CloudExecutions.sessionId eq execution.sessionId) and (CloudExecutions.epoch eq execution.epoch) }) { writeExecution(it, execution) }
        }
        // Inference credentials die with their execution, in the transaction that ends it.
        revokeInferenceCredentialsOnWrite(expected, next, write.updateExecution?.takeIf { it.state == ExecutionState.ENDED }?.epoch)
        write.auditAction?.let { action ->
            val actor = write.actor
            if (actor != null) audit(expected.teamId, actor.principal.member.id, action, expected.id, actor.effectiveMemberId, actor.principal.tokenId)
            else audit(expected.teamId, "system", action, expected.id, expected.ownerId, null)
        }
        publishSessionChange(expected, next, write.auditAction, now)
        next.copy(generation = expected.generation + 1)
    }

    override suspend fun startExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int): StartOutcome = database.query {
        val cluster = CloudClusters.selectAll().where { (CloudClusters.id eq expected.clusterId) and (CloudClusters.teamId eq expected.teamId) }.forUpdate().singleOrNull()
            ?: return@query StartOutcome.Conflict
        val now = nowMillis()
        fun running(owner: String?): Long = (CloudExecutions innerJoin CloudSessions).select(CloudExecutions.sessionId).where {
            (CloudSessions.clusterId eq expected.clusterId) and (CloudExecutions.epoch eq CloudSessions.currentEpoch) and
                (CloudExecutions.state neq ExecutionState.ENDED.wire) and (owner?.let { CloudSessions.ownerId eq it } ?: Op.TRUE)
        }.count()
        if (running(null) >= cluster[CloudClusters.maxRunningPods] || running(expected.ownerId) >= memberRunningLimit) return@query StartOutcome.WaitingCapacity
        // FIFO across the cluster: an older waiting session whose owner is under its cap goes first.
        val since = expected.waitingSince ?: now
        val ahead = CloudSessions.select(CloudSessions.id, CloudSessions.ownerId).where {
            (CloudSessions.clusterId eq expected.clusterId) and CloudSessions.waitingSince.isNotNull() and (CloudSessions.id neq expected.id) and
                ((CloudSessions.waitingSince less since) or ((CloudSessions.waitingSince eq since) and (CloudSessions.id less expected.id))) and
                (CloudSessions.desiredState eq DesiredState.RUNNING.wire) and (CloudSessions.state inList listOf(CloudSessionState.CREATING.wire, CloudSessionState.RESUMING.wire))
        }.orderBy(CloudSessions.waitingSince to SortOrder.ASC).limit(50).map { it[CloudSessions.ownerId] }.distinct()
        if (ahead.any { owner -> owner == expected.ownerId || running(owner) < memberRunningLimit }) return@query StartOutcome.WaitingCapacity
        val updated = CloudSessions.update({ sessionGuard(expected, leaseOwner, now) }) { writeSessionMutable(it, next.copy(generation = expected.generation + 1)) }
        if (updated != 1) return@query StartOutcome.Conflict
        CloudExecutions.insert {
            it[sessionId] = execution.sessionId; it[epoch] = execution.epoch; it[createdAt] = now
            writeExecution(it, execution.copy(createdAt = now))
        }
        revokeInferenceCredentialsBefore(expected.id, execution.epoch)
        publishSessionChange(expected, next, null, now)
        StartOutcome.Started(next.copy(generation = expected.generation + 1))
    }

    override suspend fun claimDue(owner: String, leaseMillis: Long, limit: Int): List<CloudSession> = database.query {
        val now = nowMillis()
        val candidates = CloudSessions.select(CloudSessions.id).where {
            CloudSessions.reconcileAfter.isNotNull() and (CloudSessions.reconcileAfter lessEq now) and
                (CloudSessions.reconcileOwner.isNull() or (CloudSessions.reconcileUntil lessEq now))
        }.orderBy(CloudSessions.reconcileAfter to SortOrder.ASC).limit(limit).map { it[CloudSessions.id] }
        candidates.filter { id ->
            CloudSessions.update({ (CloudSessions.id eq id) and (CloudSessions.reconcileOwner.isNull() or (CloudSessions.reconcileUntil lessEq now)) }) {
                it[reconcileOwner] = owner; it[reconcileUntil] = now + leaseMillis; it[reconcileRequested] = false
            } == 1
        }.mapNotNull { id -> CloudSessions.selectAll().where { CloudSessions.id eq id }.singleOrNull()?.let { readSessionRow(it, labelsFor(listOf(id))[id].orEmpty()) } }
    }

    override suspend fun release(sessionId: String, owner: String, reconcileAt: Long?) = database.query {
        val row = CloudSessions.select(CloudSessions.reconcileRequested).where { (CloudSessions.id eq sessionId) and (CloudSessions.reconcileOwner eq owner) }.forUpdate().singleOrNull()
            ?: return@query
        val now = nowMillis()
        CloudSessions.update({ (CloudSessions.id eq sessionId) and (CloudSessions.reconcileOwner eq owner) }) {
            it[reconcileOwner] = null; it[reconcileUntil] = 0; it[reconcileRequested] = false
            it[reconcileAfter] = if (row[CloudSessions.reconcileRequested]) now else reconcileAt
        }
        Unit
    }

    override suspend fun deviceOf(principal: Principal): String? = database.query {
        if (principal.serviceScopes != null) null
        // A cloud session's execution credential is never a host device, whatever its row says: one pod runs one session.
        else Tokens.select(Tokens.deviceId).where { (Tokens.id eq principal.tokenId) and notExists(CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId)
            .where { CloudInferenceCredentials.tokenId eq Tokens.id }) }.singleOrNull()?.get(Tokens.deviceId)
    }

    override suspend fun startHostExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, ended: CloudExecution?, actor: CloudActor): CloudSession? = database.query {
        requireCurrent(this, actor.principal)
        val now = nowMillis()
        val committed = next.copy(generation = expected.generation + 1)
        val updated = CloudSessions.update({ sessionGuard(expected, null, now) }) {
            writeSessionMutable(it, committed)
            // The reconciler watches the new execution from now on (registration, heartbeats).
            it[reconcileAfter] = now; it[reconcileRequested] = true
        }
        if (updated != 1) return@query null
        ended?.let { previous ->
            CloudExecutions.update({ (CloudExecutions.sessionId eq previous.sessionId) and (CloudExecutions.epoch eq previous.epoch) }) { writeExecution(it, previous) }
        }
        CloudExecutions.insert {
            it[sessionId] = execution.sessionId; it[epoch] = execution.epoch; it[createdAt] = now
            writeExecution(it, execution.copy(createdAt = now))
        }
        audit(expected.teamId, actor.principal.member.id, "cloud.session.host_started", expected.id, actor.effectiveMemberId, actor.principal.tokenId)
        publishSessionChange(expected, next, "cloud.session.host_started", now)
        committed
    }

    override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean): Boolean = database.query {
        val current = CloudSessions.select(CloudSessions.currentEpoch).where { CloudSessions.id eq sessionId }.singleOrNull()?.get(CloudSessions.currentEpoch)
        if (current != epoch) return@query false
        val now = nowMillis()
        CloudExecutions.update({ (CloudExecutions.sessionId eq sessionId) and (CloudExecutions.epoch eq epoch) and (CloudExecutions.state eq ExecutionState.RUNNING.wire) }) {
            it[lastHeartbeatAt] = now; it[CloudExecutions.runnerReady] = runnerReady
        } == 1
    }

    override suspend fun liveObjects(clusterId: String): LiveObjects = database.query {
        val claims = CloudSessions.select(CloudSessions.claimName).where {
            (CloudSessions.clusterId eq clusterId) and CloudSessions.claimDeletedAt.isNull()
        }.map { it[CloudSessions.claimName] }.toSet()
        val pods = (CloudExecutions innerJoin CloudSessions).select(CloudExecutions.podName).where {
            (CloudSessions.clusterId eq clusterId) and (CloudExecutions.epoch eq CloudSessions.currentEpoch) and (CloudExecutions.state neq ExecutionState.ENDED.wire)
        }.map { it[CloudExecutions.podName] }.toSet()
        LiveObjects(claims, pods)
    }

    override suspend fun namespaces(clusterId: String): Set<String> = database.query {
        CloudSessions.select(CloudSessions.namespace).withDistinct().where {
            (CloudSessions.clusterId eq clusterId) and (CloudSessions.deletionState neq DeletionState.PROVEN.wire)
        }.mapNotNull { it[CloudSessions.namespace] }.toSet()
    }

    override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int) = database.query {
        val now = nowMillis()
        CloudClusters.update({ CloudClusters.id eq clusterId }) { it[CloudClusters.orphanedVolumes] = orphanedVolumes; it[lastGarbageCollectionAt] = now }
        Unit
    }

    /** Clusters due for garbage collection, for the scheduled worker. */
    /** All clusters, in id order, one bounded page after [after] at a time. */
    suspend fun clusters(after: String? = null, limit: Int = 100): List<CloudCluster> = database.query {
        CloudClusters.selectAll().apply { if (after != null) where { CloudClusters.id greater after } }
            .orderBy(CloudClusters.id).limit(limit).map(::readCluster)
    }

    /** Expired idempotency records, in bounded batches. */
    suspend fun maintain() = database.query {
        val now = nowMillis()
        CloudIdempotency.deleteWhere(limit = 500) { expiresAt lessEq now }
        Unit
    }

    companion object { const val IDEMPOTENCY_MILLIS = 24 * 3_600_000L }
}

private fun labelsFor(ids: List<String>): Map<String, Map<String, String>> = if (ids.isEmpty()) emptyMap() else
    CloudSessionLabels.selectAll().where { CloudSessionLabels.sessionId inList ids }.groupBy({ it[CloudSessionLabels.sessionId] }) { it[CloudSessionLabels.key] to it[CloudSessionLabels.value] }
        .mapValues { (_, pairs) -> pairs.toMap() }
/**
 * Sessions with their host device's name and system and their current execution (for a remote host's liveness), two
 * primary-key lookups per row; cloud sessions have no host.
 */
internal val sessionsWithHost get() = CloudSessions.join(Devices, JoinType.LEFT, CloudSessions.hostDeviceId, Devices.id)
    .join(CloudExecutions, JoinType.LEFT, additionalConstraint = { (CloudExecutions.sessionId eq CloudSessions.id) and (CloudExecutions.epoch eq CloudSessions.currentEpoch) })
internal fun readSession(teamId: String, id: String): CloudSession? =
    sessionsWithHost.selectAll().where { (CloudSessions.id eq id) and (CloudSessions.teamId eq teamId) }.singleOrNull()?.let { readSessionRow(it, labelsFor(listOf(id))[id].orEmpty()) }

private fun problem(code: String?, message: String?, retryable: Boolean) = code?.let { CloudProblem(it, message.orEmpty(), retryable) }
internal fun readSessionRow(row: ResultRow, labels: Map<String, String>) = CloudSession(
    id = row[CloudSessions.id], teamId = row[CloudSessions.teamId], ownerId = row[CloudSessions.ownerId], name = row[CloudSessions.name],
    createdBy = Attribution(row[CloudSessions.initiatorId], row[CloudSessions.credentialId], row[CloudSessions.effectiveMemberId]),
    profileId = row[CloudSessions.profileId].orEmpty(), profileVersion = row[CloudSessions.profileVersion], clusterId = row[CloudSessions.clusterId].orEmpty(),
    namespace = row[CloudSessions.namespace].orEmpty(), imageReference = row[CloudSessions.imageReference].orEmpty(), imageDigest = row[CloudSessions.imageDigest],
    agentKind = row[CloudSessions.agentKind], agentVersion = row[CloudSessions.agentVersion], agentModel = row[CloudSessions.agentModel], agentEffort = row[CloudSessions.agentEffort],
    labels = labels, state = CloudSessionState.parse(row[CloudSessions.state]), phase = row[CloudSessions.phase], desiredState = DesiredState.parse(row[CloudSessions.desiredState]),
    generation = row[CloudSessions.generation], currentEpoch = row[CloudSessions.currentEpoch],
    idleTimeoutMinutes = row[CloudSessions.idleTimeoutMinutes], lastActivityAt = row[CloudSessions.lastActivityAt],
    resumeWorkspace = WorkspaceResume.parse(row[CloudSessions.resumeWorkspace]), resumeConversation = ConversationResume.parse(row[CloudSessions.resumeConversation]),
    failure = problem(row[CloudSessions.failureCode], row[CloudSessions.failureMessage], row[CloudSessions.failureRetryable]),
    resumeBlocked = problem(row[CloudSessions.blockedCode], row[CloudSessions.blockedMessage], row[CloudSessions.blockedRetryable]),
    pauseRequestedAt = row[CloudSessions.pauseRequestedAt], pauseForce = row[CloudSessions.pauseForce], pausedAt = row[CloudSessions.pausedAt],
    expiresAt = row[CloudSessions.expiresAt], terminatedReason = row[CloudSessions.terminatedReason], waitingSince = row[CloudSessions.waitingSince],
    workspace = CloudWorkspace(row[CloudSessions.claimName], row[CloudSessions.storageClass], row[CloudSessions.workspaceSize], row[CloudSessions.volumeName], row[CloudSessions.volumeHandle],
        DeletionState.parse(row[CloudSessions.deletionState]), row[CloudSessions.claimDeletedAt], row[CloudSessions.volumeDeletedAt],
        problem(row[CloudSessions.deletionCode], row[CloudSessions.deletionMessage], false)),
    createdAt = row[CloudSessions.createdAt], updatedAt = row[CloudSessions.updatedAt], transferId = row[CloudSessions.transferId],
    environment = decodeEnvironment(row[CloudSessions.environment]),
    humanWait = readHumanWait(row),
    kind = CloudSessionKind.parse(row[CloudSessions.kind]),
    host = readHost(row),
)
/**
 * The host of a remote session; the device's name and system, and the liveness of the current execution, only when
 * [row] was read from [sessionsWithHost] (and the device still exists).
 */
private fun readHost(row: ResultRow): CloudSessionHost? {
    if (row[CloudSessions.kind] != CloudSessionKind.REMOTE.wire) return null
    val device = row.getOrNull(Devices.id)?.let { HostDevice(row[Devices.name], row[Devices.platform], row[Devices.osVersion]) }
    val execution = row.getOrNull(CloudExecutions.state)
    return CloudSessionHost(row[CloudSessions.hostDeviceId], row[CloudSessions.hostDirectoryName], device,
        RemotePermissionMode.parse(row[CloudSessions.permissionMode]) ?: RemotePermissionMode.ASK,
        online = execution == ExecutionState.RUNNING.wire, lastHeartbeatAt = row.getOrNull(CloudExecutions.lastHeartbeatAt), folderId = row[CloudSessions.hostFolderId])
}
/** The wait columns of a session row, null when nothing was set or the row holds a reason this version does not know. */
internal fun readHumanWait(row: ResultRow): CloudHumanWait? {
    val reason = CloudHumanWait.Reason.parse(row[CloudSessions.awaitingReason]) ?: return null
    return CloudHumanWait(reason, row[CloudSessions.awaitingRequestId], row[CloudSessions.awaitingSince] ?: return null,
        row[CloudSessions.awaitingEventSeq] ?: return null, row[CloudSessions.awaitingEpoch] ?: return null)
}
internal fun readExecution(row: ResultRow) = CloudExecution(
    row[CloudExecutions.sessionId], row[CloudExecutions.epoch], row[CloudExecutions.podName], ExecutionState.parse(row[CloudExecutions.state]), row[CloudExecutions.createdAt],
    row[CloudExecutions.registeredAt], row[CloudExecutions.lastHeartbeatAt], row[CloudExecutions.runnerReady], row[CloudExecutions.nodeName], row[CloudExecutions.imageDigest],
    row[CloudExecutions.endedAt], row[CloudExecutions.exitReason], row[CloudExecutions.checkpoint], row[CloudExecutions.ownerId],
)

internal fun sessionGuard(expected: CloudSession, leaseOwner: String?, now: Long): Op<Boolean> {
    var op: Op<Boolean> = (CloudSessions.id eq expected.id) and (CloudSessions.teamId eq expected.teamId) and (CloudSessions.generation eq expected.generation)
    if (leaseOwner != null) op = op and (CloudSessions.reconcileOwner eq leaseOwner) and (CloudSessions.reconcileUntil greater now)
    return op
}

internal fun writeSessionMutable(it: UpdateBuilder<*>, s: CloudSession) {
    it[CloudSessions.imageReference] = s.imageReference.takeIf { s.kind == CloudSessionKind.CLOUD }; it[CloudSessions.imageDigest] = s.imageDigest
    it[CloudSessions.agentKind] = s.agentKind; it[CloudSessions.agentVersion] = s.agentVersion; it[CloudSessions.agentModel] = s.agentModel; it[CloudSessions.agentEffort] = s.agentEffort
    it[CloudSessions.state] = s.state.wire; it[CloudSessions.phase] = s.phase?.take(64); it[CloudSessions.desiredState] = s.desiredState.wire
    it[CloudSessions.generation] = s.generation; it[CloudSessions.currentEpoch] = s.currentEpoch
    it[CloudSessions.idleTimeoutMinutes] = s.idleTimeoutMinutes; it[CloudSessions.lastActivityAt] = s.lastActivityAt
    it[CloudSessions.resumeWorkspace] = s.resumeWorkspace.wire; it[CloudSessions.resumeConversation] = s.resumeConversation.wire
    it[CloudSessions.failureCode] = s.failure?.code; it[CloudSessions.failureMessage] = s.failure?.message?.take(512); it[CloudSessions.failureRetryable] = s.failure?.retryable ?: false
    it[CloudSessions.blockedCode] = s.resumeBlocked?.code; it[CloudSessions.blockedMessage] = s.resumeBlocked?.message?.take(512); it[CloudSessions.blockedRetryable] = s.resumeBlocked?.retryable ?: false
    it[CloudSessions.pauseRequestedAt] = s.pauseRequestedAt; it[CloudSessions.pauseForce] = s.pauseForce; it[CloudSessions.pausedAt] = s.pausedAt
    it[CloudSessions.expiresAt] = s.expiresAt; it[CloudSessions.terminatedReason] = s.terminatedReason; it[CloudSessions.waitingSince] = s.waitingSince
    val w = s.workspace
    it[CloudSessions.volumeName] = w.volumeName; it[CloudSessions.volumeHandle] = w.volumeHandle?.take(512); it[CloudSessions.deletionState] = w.deletion.wire
    it[CloudSessions.claimDeletedAt] = w.claimDeletedAt; it[CloudSessions.volumeDeletedAt] = w.volumeDeletedAt
    it[CloudSessions.deletionCode] = w.deletionProblem?.code; it[CloudSessions.deletionMessage] = w.deletionProblem?.message?.take(512)
    it[CloudSessions.updatedAt] = s.updatedAt; it[CloudSessions.transferId] = s.transferId
    // The wait is only ever cleared here, never set (only the conversation store sets it, under the row lock): leaving
    // the states that take an answer ends it, since a pausing runner denies its open requests without an event, and a
    // pause cancelled on the same execution must not bring back a request already denied.
    if (!s.takesAnswers) {
        it[CloudSessions.awaitingReason] = null; it[CloudSessions.awaitingRequestId] = null; it[CloudSessions.awaitingSince] = null
        it[CloudSessions.awaitingEventSeq] = null; it[CloudSessions.awaitingEpoch] = null
    }
}
internal fun writeExecution(it: UpdateBuilder<*>, e: CloudExecution) {
    it[CloudExecutions.podName] = e.podName; it[CloudExecutions.state] = e.state.wire
    it[CloudExecutions.registeredAt] = e.registeredAt; it[CloudExecutions.lastHeartbeatAt] = e.lastHeartbeatAt; it[CloudExecutions.runnerReady] = e.runnerReady
    it[CloudExecutions.nodeName] = e.nodeName?.take(253); it[CloudExecutions.imageDigest] = e.imageDigest?.take(100)
    it[CloudExecutions.endedAt] = e.endedAt; it[CloudExecutions.exitReason] = e.exitReason?.take(64); it[CloudExecutions.checkpoint] = e.checkpoint
    it[CloudExecutions.ownerId] = e.ownerId
}
