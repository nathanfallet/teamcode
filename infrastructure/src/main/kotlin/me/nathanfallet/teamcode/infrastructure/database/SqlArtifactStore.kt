package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Artifacts in MySQL: drafts, uploads and their chunks, immutable versions, members and the bookkeeping of the bucket
 * folder (blobs held, blobs being collected, proof of deletion). The bucket itself is written by the domain service;
 * a blob is only trusted once it was checked and stored, and only within its upload or its artifact.
 */
class SqlArtifactStore(private val database: Database) : ArtifactStore {
    override suspend fun startUpload(actor: Principal, artifactId: String?, files: List<UploadFile>, origin: ArtifactOrigin): ArtifactUpload = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val settings = requireActive(actor.teamId)
        requirePublisher(actor, settings)
        val now = nowMillis()
        requireRate(actor, settings, now)
        // Chunks wait in MySQL: a member holds a few uploads at a time, each bounded by the publication limits.
        val open = ArtifactUploads.select(ArtifactUploads.id).where { (ArtifactUploads.memberId eq actor.member.id) and (ArtifactUploads.expiresAt greater now) }.count()
        if (open >= ArtifactRules.MAX_OPEN_UPLOADS)
            throw TeamFailure("artifact_uploads_busy", 429, "You have ${ArtifactRules.MAX_OPEN_UPLOADS} publications in progress. Finish them or wait for them to expire.", retryAfterSeconds = 60, retryable = true)
        val id = if (artifactId == null) {
            requireArtifactSlot(actor, settings)
            // Drafts do not count in the quota; abandoned ones are bounded per day until they expire.
            val drafts = Artifacts.select(Artifacts.id).where {
                (Artifacts.teamId eq actor.teamId) and (Artifacts.ownerId eq actor.member.id) and (Artifacts.state eq DRAFT)
            }.count()
            if (drafts >= ArtifactRules.MAX_DRAFTS)
                throw TeamFailure("artifact_uploads_busy", 429, "Too many unfinished new artifacts. Publish them, or wait a day for them to expire.", retryAfterSeconds = 3600, retryable = true)
            UUID.randomUUID().toString().also { id ->
                Artifacts.insert {
                    it[Artifacts.id] = id; it[teamId] = actor.teamId; it[ownerId] = actor.member.id; it[createdBy] = actor.member.id; it[credentialId] = actor.tokenId
                    it[originKind] = origin.kind; it[originRunId] = origin.runId; it[originSessionId] = origin.sessionId; it[originEpoch] = origin.epoch
                    it[title] = ""; it[description] = ""; it[icon] = ""; it[access] = ArtifactAccess.PERSONAL.wire; it[publicFollowsLatest] = true; it[publicVersion] = null
                    it[currentVersion] = 0; it[state] = DRAFT; it[storedBytes] = 0; it[generation] = 0; it[createdAt] = now; it[updatedAt] = now
                }
            }
        } else {
            val row = artifactRow(actor, artifactId, lock = true)
            requireWritable(actor, row)
            artifactId
        }
        val announced = files.map { it.sha256 }
        val blobs = ArtifactBlobs.select(ArtifactBlobs.sha256, ArtifactBlobs.collectingSince).where { (ArtifactBlobs.artifactId eq id) and (ArtifactBlobs.sha256 inList announced) }.toList()
        // A blob being removed from the folder cannot be sent again until its removal is done (no put can race the delete).
        if (blobs.any { it[ArtifactBlobs.collectingSince] != null })
            throw TeamFailure("artifact_busy", 409, "A file of this publication is being cleaned up from the artifact's storage. Try again in a minute.", retryAfterSeconds = 60, retryable = true)
        val held = blobs.map { it[ArtifactBlobs.sha256] }.toSet()
        val missing = files.filter { it.sha256 !in held }
        ArtifactRules.validateSent(missing)
        val uploadId = UUID.randomUUID().toString()
        val expires = now + ArtifactRules.UPLOAD_TTL_MILLIS
        ArtifactUploads.insert {
            it[ArtifactUploads.id] = uploadId; it[ArtifactUploads.artifactId] = id; it[teamId] = actor.teamId; it[memberId] = actor.member.id
            it[credentialId] = actor.tokenId; it[ArtifactUploads.files] = encodeFiles(files); it[receivedBytes] = 0; it[createdAt] = now; it[expiresAt] = expires
        }
        ArtifactUpload(uploadId, id, missing.map { it.sha256 }, expires)
    }

    override suspend fun putChunk(actor: Principal, uploadId: String, sha256: String, number: Int, bytes: ByteArray): ChunkReceipt = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        // The upload row alone is locked (never the artifact's, which a commit holds while it removes the upload): chunks
        // of one upload sent in parallel are counted one after the other, so the last one sees all the others.
        ArtifactUploads.select(ArtifactUploads.id).where { ArtifactUploads.id eq uploadId }.forUpdate().singleOrNull()
        val upload = uploadRow(actor, uploadId)
        val announced = decodeFiles(upload[ArtifactUploads.files])
        val size = announced[sha256] ?: throw TeamFailure("invalid_artifact_upload", 400, "This file was not announced in the upload.")
        val total = ArtifactRules.chunks(size)
        if (!ArtifactUploadBlobs.select(ArtifactUploadBlobs.sha256).where { (ArtifactUploadBlobs.uploadId eq uploadId) and (ArtifactUploadBlobs.sha256 eq sha256) }.empty())
            return@query ChunkReceipt(true, total, total, stored = true)
        if (number !in 0 until total || bytes.size != ArtifactRules.chunkSize(size, number))
            throw TeamFailure("invalid_artifact_chunk", 400, "Send chunk 0–${total - 1} of this file, each of 1 MiB except the last.")
        // One row per (upload, file, chunk number) at its exact size: what an upload holds is bounded by what it announced.
        ArtifactUploadChunks.insertIgnore {
            it[ArtifactUploadChunks.uploadId] = uploadId; it[ArtifactUploadChunks.sha256] = sha256; it[ArtifactUploadChunks.number] = number
            it[ArtifactUploadChunks.bytes] = bytes; it[createdAt] = nowMillis()
        }
        val received = ArtifactUploadChunks.select(ArtifactUploadChunks.number).where { (ArtifactUploadChunks.uploadId eq uploadId) and (ArtifactUploadChunks.sha256 eq sha256) }.count().toInt()
        ChunkReceipt(received == total, received, total)
    }

    override suspend fun assemble(actor: Principal, uploadId: String, sha256: String): Pair<UploadBlob, ByteArray> = database.query {
        requireCurrent(this, actor)
        val upload = uploadRow(actor, uploadId)
        val size = decodeFiles(upload[ArtifactUploads.files])[sha256] ?: throw TeamFailure("invalid_artifact_upload", 400, "This file was not announced in the upload.")
        val chunks = ArtifactUploadChunks.select(ArtifactUploadChunks.number, ArtifactUploadChunks.bytes)
            .where { (ArtifactUploadChunks.uploadId eq uploadId) and (ArtifactUploadChunks.sha256 eq sha256) }.orderBy(ArtifactUploadChunks.number).toList()
        if (chunks.map { it[ArtifactUploadChunks.number] } != (0 until ArtifactRules.chunks(size)).toList())
            throw TeamFailure("invalid_artifact_chunk", 409, "Some chunks of this file are missing. Send them again.")
        val content = ByteArray(size.toInt())
        var offset = 0
        for (chunk in chunks) { val part = chunk[ArtifactUploadChunks.bytes]; part.copyInto(content, offset); offset += part.size }
        UploadBlob(actor.teamId, upload[ArtifactUploads.artifactId], sha256, size) to content
    }

    override suspend fun recordBlob(actor: Principal, uploadId: String, sha256: String) = database.query {
        requireCurrent(this, actor)
        val upload = uploadRow(actor, uploadId)
        val size = decodeFiles(upload[ArtifactUploads.files]).getValue(sha256)
        ArtifactUploadBlobs.insertIgnore { it[ArtifactUploadBlobs.uploadId] = uploadId; it[ArtifactUploadBlobs.sha256] = sha256; it[ArtifactUploadBlobs.size] = size; it[storedAt] = nowMillis() }
        ArtifactUploadChunks.deleteWhere { (ArtifactUploadChunks.uploadId eq uploadId) and (ArtifactUploadChunks.sha256 eq sha256) }
        Unit
    }

    override suspend fun abandonBlob(blob: UploadBlob) = database.query {
        // Under the artifact lock, like an upload's expiry; a deleted artifact's folder goes as a whole.
        val state = Artifacts.select(Artifacts.state).where { (Artifacts.id eq blob.artifactId) and (Artifacts.teamId eq blob.teamId) }.forUpdate().singleOrNull()?.get(Artifacts.state)
        if (state == null || state == DELETED) return@query
        val now = nowMillis()
        val held = !ArtifactBlobs.select(ArtifactBlobs.sha256).where { (ArtifactBlobs.artifactId eq blob.artifactId) and (ArtifactBlobs.sha256 eq blob.sha256) }.empty()
        if (held || blob.sha256 in liveAnnouncements(blob.artifactId, "", now)) return@query
        ArtifactBlobs.insert {
            it[artifactId] = blob.artifactId; it[sha256] = blob.sha256; it[size] = blob.size; it[storedAt] = now; it[lastReferencedVersion] = null; it[collectingSince] = now
        }
        Unit
    }

    override suspend fun refuseBlob(actor: Principal, uploadId: String, sha256: String, code: String) = database.query {
        requireCurrent(this, actor)
        val upload = uploadRow(actor, uploadId)
        ArtifactUploadChunks.deleteWhere { (ArtifactUploadChunks.uploadId eq uploadId) and (ArtifactUploadChunks.sha256 eq sha256) }
        val artifactId = upload[ArtifactUploads.artifactId]
        audit(actor.teamId, actor.member.id, "artifact.publish_refused", "$artifactId:$code".take(100), credential = actor.tokenId)
        // A mismatch is sent again in the same upload. Any other refusal ends the publication: its upload stops counting
        // at once (the next run collects what it stored), and so does a new artifact's draft that nothing else uses,
        // so retries never use up the open uploads and drafts a member may hold.
        if (code != "artifact_blob_mismatch") {
            val now = nowMillis()
            // The artifact before the upload, in the order a commit takes them.
            val draft = Artifacts.select(Artifacts.state).where { Artifacts.id eq artifactId }.forUpdate().singleOrNull()?.get(Artifacts.state) == DRAFT
            ArtifactUploads.update({ ArtifactUploads.id eq uploadId }) { it[expiresAt] = now }
            if (draft && ArtifactUploads.select(ArtifactUploads.id).where { (ArtifactUploads.artifactId eq artifactId) and (ArtifactUploads.expiresAt greater now) }.empty())
                Artifacts.update({ (Artifacts.id eq artifactId) and (Artifacts.state eq DRAFT) }) { it[state] = DELETED; it[deletedAt] = now; it[updatedAt] = now }
        }
        Unit
    }

    override suspend fun commit(actor: Principal, artifactId: String, plan: CommitPlan): Published = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val settings = requireActive(actor.teamId)
        requirePublisher(actor, settings)
        val row = artifactRow(actor, artifactId, lock = true)
        val relation = requireWritable(actor, row)
        val now = nowMillis()
        // MCP has no idempotency key: the same commit replayed shortly after by the same credential is the same version.
        ArtifactVersions.selectAll().where {
            (ArtifactVersions.artifactId eq artifactId) and (ArtifactVersions.baseVersion eq plan.baseVersion) and (ArtifactVersions.manifestDigest eq plan.digest) and
                (ArtifactVersions.credentialId eq actor.tokenId) and (ArtifactVersions.createdAt greaterEq now - ArtifactRules.REPLAY_MILLIS)
        }.singleOrNull()?.let { return@query Published(toArtifact(row, relation), readVersion(it, withFiles = true), replayed = true) }
        val current = row[Artifacts.currentVersion]
        if (plan.baseVersion != current) throw ArtifactConflictFailure(if (current == 0) null else versionRow(artifactId, current)?.let { readVersion(it, withFiles = true) })
        val access = ArtifactAccess.parse(row[Artifacts.access])!!
        if (plan.access != null && plan.access != access) {
            if (access == ArtifactAccess.PUBLIC) throw TeamFailure("artifact_public_interactive", 403, "This artifact is public: its owner changes its access on the dashboard or with teamcode artifacts access.")
            if (relation != "owner") throw TeamFailure("artifact_access_forbidden", 403, "Only the owner changes who can see this artifact.")
        }
        val upload = uploadRow(actor, plan.uploadId)
        if (upload[ArtifactUploads.artifactId] != artifactId) throw TeamFailure("artifact_upload_not_found", 404, "This upload belongs to another artifact.")
        val uploaded = ArtifactUploadBlobs.select(ArtifactUploadBlobs.sha256, ArtifactUploadBlobs.size).where { ArtifactUploadBlobs.uploadId eq plan.uploadId }
            .associate { it[ArtifactUploadBlobs.sha256] to it[ArtifactUploadBlobs.size] }
        val held = ArtifactBlobs.select(ArtifactBlobs.sha256, ArtifactBlobs.size).where { (ArtifactBlobs.artifactId eq artifactId) and ArtifactBlobs.collectingSince.isNull() }
            .associate { it[ArtifactBlobs.sha256] to it[ArtifactBlobs.size] }
        // Only blobs this credential sent in this upload, checked and stored, or blobs the artifact already holds.
        val unknown = plan.files.count { (held[it.sha256] ?: uploaded[it.sha256]) != it.size }
        if (unknown > 0) throw TeamFailure("artifact_blob_missing", 409, "$unknown file(s) of this version were not received in this upload. Send them, then commit again.")
        requireRate(actor, settings, now)
        if (row[Artifacts.state] == DRAFT) requireArtifactSlot(actor, settings, owner = row[Artifacts.ownerId])
        val added = plan.files.filter { it.sha256 !in held }.associate { it.sha256 to it.size }
        val addedBytes = added.values.sum()
        val owner = row[Artifacts.ownerId]
        if (addedBytes > 0) {
            val stored = Artifacts.storedBytes.sum()
            val live = (Artifacts.teamId eq actor.teamId) and (Artifacts.state neq DELETED)
            val ownerBytes = Artifacts.select(stored).where { live and (Artifacts.ownerId eq owner) }.single()[stored] ?: 0
            val teamBytes = Artifacts.select(stored).where { live }.single()[stored] ?: 0
            if (ownerBytes + addedBytes > settings.quotas.memberBytes) throw TeamFailure("artifact_quota_exceeded", 409, "This publication would exceed the owner's artifact storage quota. Delete older artifacts or ask an administrator.")
            if (teamBytes + addedBytes > settings.quotas.teamBytes) throw TeamFailure("artifact_quota_exceeded", 409, "This publication would exceed the team's artifact storage quota. Ask an administrator.")
        }
        val number = current + 1
        for ((sha, size) in added) ArtifactBlobs.insert {
            it[ArtifactBlobs.artifactId] = artifactId; it[sha256] = sha; it[ArtifactBlobs.size] = size; it[storedAt] = now; it[lastReferencedVersion] = number; it[collectingSince] = null
        }
        ArtifactBlobs.update({ (ArtifactBlobs.artifactId eq artifactId) and (ArtifactBlobs.sha256 inList plan.files.map { it.sha256 }.distinct()) }) { it[lastReferencedVersion] = number }
        val versionId = UUID.randomUUID().toString()
        ArtifactVersions.insert {
            it[id] = versionId; it[ArtifactVersions.artifactId] = artifactId; it[ArtifactVersions.number] = number; it[entryPath] = plan.entry; it[manifestDigest] = plan.digest
            it[fileCount] = plan.files.size; it[totalBytes] = plan.files.sumOf { file -> file.size }; it[createdBy] = actor.member.id; it[credentialId] = actor.tokenId
            it[baseVersion] = plan.baseVersion; it[originSessionId] = null; it[originEpoch] = null; it[createdAt] = now
        }
        ArtifactVersionFiles.batchInsert(plan.files, shouldReturnGeneratedValues = false) { file ->
            this[ArtifactVersionFiles.versionId] = versionId; this[ArtifactVersionFiles.path] = file.path; this[ArtifactVersionFiles.sha256] = file.sha256
            this[ArtifactVersionFiles.size] = file.size; this[ArtifactVersionFiles.contentType] = file.contentType; this[ArtifactVersionFiles.role] = file.role
        }
        // Versions beyond the retention are pruned, never the one a public link is pinned to; what they alone used is collected.
        val oldest = number - settings.retention.maxVersionsPerArtifact
        val pinned = row[Artifacts.publicVersion]
        val pruned = if (oldest < 1) 0 else ArtifactVersions.deleteWhere {
            (ArtifactVersions.artifactId eq artifactId) and (ArtifactVersions.number lessEq oldest) and (if (pinned != null) ArtifactVersions.number neq pinned else Op.TRUE)
        }
        // Blobs this upload stored that the version does not use are marked like those of an expired upload.
        val unused = uploaded.keys - plan.files.map { it.sha256 }.toSet() - held.keys
        if (unused.isNotEmpty()) {
            val announced = liveAnnouncements(artifactId, plan.uploadId, now)
            val collectingHeld = ArtifactBlobs.select(ArtifactBlobs.sha256).where { (ArtifactBlobs.artifactId eq artifactId) and (ArtifactBlobs.sha256 inList unused) }.map { it[ArtifactBlobs.sha256] }.toSet()
            for (sha in unused - announced - collectingHeld) ArtifactBlobs.insert {
                it[ArtifactBlobs.artifactId] = artifactId; it[sha256] = sha; it[size] = uploaded.getValue(sha); it[storedAt] = now; it[lastReferencedVersion] = null; it[collectingSince] = now
            }
        }
        ArtifactUploads.deleteWhere { ArtifactUploads.id eq plan.uploadId }
        val collected = if (pruned > 0) markUnreferenced(artifactId, now) else 0L
        val nextAccess = plan.access ?: access
        val first = row[Artifacts.state] == DRAFT
        Artifacts.update({ Artifacts.id eq artifactId }) {
            it[state] = ACTIVE; it[currentVersion] = number; it[publishedAt] = now; it[updatedAt] = now; it[generation] = row[Artifacts.generation] + 1
            it[title] = plan.title ?: row[Artifacts.title].ifEmpty { ArtifactRules.defaultTitle(plan.entry) }
            plan.description?.let { value -> it[description] = value }
            plan.icon?.let { value -> it[icon] = value }
            it[Artifacts.access] = nextAccess.wire; it[expiresAt] = expiry(settings, nextAccess, now)
            it[storedBytes] = row[Artifacts.storedBytes] + addedBytes - collected
        }
        if (first) audit(actor.teamId, actor.member.id, "artifact.created", artifactId, owner, actor.tokenId)
        audit(actor.teamId, actor.member.id, "artifact.version_published", "$artifactId/$number", owner, actor.tokenId)
        val updated = Artifacts.selectAll().where { Artifacts.id eq artifactId }.single()
        Published(toArtifact(updated, relation), readVersion(versionRow(artifactId, number)!!, withFiles = true), replayed = false)
    }

    override suspend fun artifact(actor: Principal, artifactId: String): Artifact = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = false)
        toArtifact(row, requireReadable(actor, row))
    }

    override suspend fun list(actor: Principal, query: ArtifactQuery): ArtifactPage = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val me = actor.member.id
        val invited = ArtifactMembers.select(ArtifactMembers.artifactId).where { ArtifactMembers.memberId eq me }
        var condition: Op<Boolean> = (Artifacts.teamId eq actor.teamId) and (Artifacts.state eq ACTIVE)
        condition = condition and when (query.scope) {
            ArtifactScope.MINE -> Artifacts.ownerId eq me
            ArtifactScope.SHARED -> Artifacts.id inSubQuery invited
            ArtifactScope.TEAM -> (Artifacts.ownerId eq me) or (Artifacts.access inList listOf(ArtifactAccess.TEAM.wire, ArtifactAccess.PUBLIC.wire)) or (Artifacts.id inSubQuery invited)
            ArtifactScope.ALL -> Op.TRUE
        }
        query.ownerId?.let { condition = condition and (Artifacts.ownerId eq it) }
        query.sessionId?.let { condition = condition and (Artifacts.originSessionId eq it) }
        query.runId?.let { condition = condition and (Artifacts.originRunId eq it) }
        query.before?.let { cursor ->
            val updated = cursor.substringBefore(':').toLongOrNull()
            val id = cursor.substringAfter(':', "")
            if (updated == null || !ArtifactService.ID.matches(id)) throw TeamFailure("invalid_artifact_query", 400, "Use the nextBefore value of a previous page.")
            condition = condition and ((Artifacts.updatedAt less updated) or ((Artifacts.updatedAt eq updated) and (Artifacts.id less id)))
        }
        val rows = Artifacts.selectAll().where { condition }.orderBy(Artifacts.updatedAt to SortOrder.DESC, Artifacts.id to SortOrder.DESC).limit(query.limit + 1).toList()
        val page = rows.take(query.limit)
        val names = ownerNames(page.map { it[Artifacts.ownerId] })
        val roles = memberRoles(me, page.map { it[Artifacts.id] })
        ArtifactPage(page.map { toArtifact(it, relation(actor, it, roles[it[Artifacts.id]]) ?: "admin", names) },
            if (rows.size > query.limit) page.last().let { "${it[Artifacts.updatedAt]}:${it[Artifacts.id]}" } else null)
    }

    override suspend fun versions(actor: Principal, artifactId: String): List<ArtifactVersion> = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        requireReadable(actor, artifactRow(actor, artifactId, lock = false))
        ArtifactVersions.selectAll().where { ArtifactVersions.artifactId eq artifactId }.orderBy(ArtifactVersions.number, SortOrder.DESC).map { readVersion(it, withFiles = false) }
    }

    override suspend fun version(actor: Principal, artifactId: String, number: Int): ArtifactVersion = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = false)
        val relation = requireReadable(actor, row)
        val version = versionRow(artifactId, number) ?: throw TeamFailure("not_found", 404, "Version not found.")
        // An administrator reading another member's personal artifact leaves a trace.
        if (relation == "admin") audit(actor.teamId, actor.member.id, "artifact.admin_viewed", "$artifactId/$number", row[Artifacts.ownerId], actor.tokenId)
        readVersion(version, withFiles = true)
    }

    override suspend fun setAccess(actor: Principal, artifactId: String, access: ArtifactAccess, followLatest: Boolean, pinnedVersion: Int?): Artifact = database.query {
        requireCurrent(this, actor)
        val settings = requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = true)
        val relation = requireReadable(actor, row)
        val owner = relation == "owner"
        val administrator = actor.member.role.isAdministrator && actor.serviceScopes == null
        if (!owner && !administrator) throw TeamFailure("artifact_access_forbidden", 403, "Only the owner or an administrator changes who can see this artifact.")
        if (row[Artifacts.state] != ACTIVE) throw TeamFailure("not_found", 404, "Artifact not found.")
        if (access == ArtifactAccess.PUBLIC) when (settings.publicSharing) {
            PublicSharing.OFF -> throw TeamFailure("artifact_public_sharing_off", 403, "Public links are turned off for this team.")
            PublicSharing.ADMINS -> if (!administrator) throw TeamFailure("artifact_public_forbidden", 403, "Only administrators make artifacts public in this team.")
            PublicSharing.OWNERS -> if (!owner) throw TeamFailure("artifact_public_forbidden", 403, "Only the owner makes this artifact public.")
        }
        if (pinnedVersion != null && versionRow(artifactId, pinnedVersion) == null) throw TeamFailure("not_found", 404, "Version not found.")
        val previous = row[Artifacts.access]
        val now = nowMillis()
        Artifacts.update({ Artifacts.id eq artifactId }) {
            it[Artifacts.access] = access.wire; it[publicFollowsLatest] = pinnedVersion == null; it[publicVersion] = pinnedVersion
            // Counted from the change: a new access level never puts an artifact past its retention at once.
            it[expiresAt] = row[Artifacts.publishedAt]?.let { expiry(settings, access, now) }
            it[updatedAt] = now; it[generation] = row[Artifacts.generation] + 1
        }
        audit(actor.teamId, actor.member.id, "artifact.access_changed", "$artifactId:$previous->${access.wire}", row[Artifacts.ownerId], actor.tokenId)
        toArtifact(Artifacts.selectAll().where { Artifacts.id eq artifactId }.single(), relation)
    }

    override suspend fun members(actor: Principal, artifactId: String): ArtifactSharing = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = false)
        requireReadable(actor, row)
        readMembers(row)
    }

    override suspend fun share(actor: Principal, artifactId: String, member: String, role: ArtifactMemberRole): ArtifactSharing = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = true)
        requireInviter(actor, row)
        val target = resolveMember(actor.teamId, member, activeOnly = true) ?: throw TeamFailure("artifact_member_not_found", 404, "No active member of this team has this id, username or linked GitHub login.")
        if (target == row[Artifacts.ownerId]) throw TeamFailure("invalid_artifact_member", 400, "This member owns the artifact.")
        val now = nowMillis()
        val existing = ArtifactMembers.selectAll().where { (ArtifactMembers.artifactId eq artifactId) and (ArtifactMembers.memberId eq target) }.singleOrNull()
        if (existing == null) ArtifactMembers.insert {
            it[ArtifactMembers.artifactId] = artifactId; it[memberId] = target; it[ArtifactMembers.role] = role.wire; it[reason] = "invited"
            it[addedBy] = actor.member.id; it[originSessionId] = null; it[addedAt] = now
        } else if (existing[ArtifactMembers.role] != role.wire) ArtifactMembers.update({ (ArtifactMembers.artifactId eq artifactId) and (ArtifactMembers.memberId eq target) }) {
            it[ArtifactMembers.role] = role.wire; it[addedBy] = actor.member.id; it[addedAt] = now
        }
        if (existing?.get(ArtifactMembers.role) != role.wire)
            audit(actor.teamId, actor.member.id, "artifact.member_added", "$artifactId:${role.wire}:invited", row[Artifacts.ownerId], actor.tokenId, target)
        readMembers(row)
    }

    override suspend fun unshare(actor: Principal, artifactId: String, member: String): ArtifactSharing = database.query {
        requireCurrent(this, actor); requireActive(actor.teamId)
        val row = artifactRow(actor, artifactId, lock = true)
        requireInviter(actor, row)
        val target = resolveMember(actor.teamId, member, activeOnly = false)
        val removed = target != null && ArtifactMembers.deleteWhere { (ArtifactMembers.artifactId eq artifactId) and (ArtifactMembers.memberId eq target) } == 1
        if (!removed) throw TeamFailure("artifact_member_not_found", 404, "This member is not invited to the artifact.")
        audit(actor.teamId, actor.member.id, "artifact.member_removed", artifactId, row[Artifacts.ownerId], actor.tokenId, target)
        readMembers(row)
    }

    override suspend fun delete(actor: Principal, artifactId: String) = database.query {
        requireCurrent(this, actor)
        val row = artifactRow(actor, artifactId, lock = true)
        val relation = requireReadable(actor, row)
        if (relation != "owner" && !(actor.member.role.isAdministrator && actor.serviceScopes == null))
            throw TeamFailure("artifact_delete_forbidden", 403, "Only the owner or an administrator deletes an artifact.")
        val now = nowMillis()
        Artifacts.update({ Artifacts.id eq artifactId }) { it[state] = DELETED; it[deletedAt] = now; it[updatedAt] = now; it[expiresAt] = null; it[generation] = row[Artifacts.generation] + 1 }
        ArtifactUploads.deleteWhere { ArtifactUploads.artifactId eq artifactId }
        audit(actor.teamId, actor.member.id, "artifact.deleted", artifactId, row[Artifacts.ownerId], actor.tokenId)
        Unit
    }

    override suspend fun expireDrafts(limit: Int): Int = database.query {
        val cutoff = nowMillis() - ArtifactRules.DRAFT_TTL_MILLIS
        val ids = Artifacts.select(Artifacts.id).where { (Artifacts.state eq DRAFT) and (Artifacts.updatedAt less cutoff) }.limit(limit).map { it[Artifacts.id] }
        if (ids.isNotEmpty()) {
            val now = nowMillis()
            ArtifactUploads.deleteWhere { ArtifactUploads.artifactId inList ids }
            Artifacts.update({ (Artifacts.id inList ids) and (Artifacts.state eq DRAFT) }) { it[state] = DELETED; it[deletedAt] = now; it[updatedAt] = now }
        }
        ids.size
    }

    override suspend fun expireRetention(limit: Int): Int = database.query {
        val now = nowMillis()
        val rows = Artifacts.select(Artifacts.id, Artifacts.teamId, Artifacts.ownerId).where { (Artifacts.expiresAt less now) and (Artifacts.state eq ACTIVE) }.limit(limit).toList()
        for (row in rows) {
            val id = row[Artifacts.id]
            // Checked again in the update: a publication since the read moved expires_at forward.
            if (Artifacts.update({ (Artifacts.id eq id) and (Artifacts.state eq ACTIVE) and (Artifacts.expiresAt less now) }) { it[state] = DELETED; it[deletedAt] = now; it[updatedAt] = now; it[expiresAt] = null } == 1) {
                ArtifactUploads.deleteWhere { ArtifactUploads.artifactId eq id }
                audit(row[Artifacts.teamId], SYSTEM, "artifact.retention_expired", id, row[Artifacts.ownerId])
            }
        }
        rows.size
    }

    override suspend fun expireUploads(limit: Int): Int {
        val expired = database.query {
            ArtifactUploads.select(ArtifactUploads.id, ArtifactUploads.artifactId).where { ArtifactUploads.expiresAt less nowMillis() }.limit(limit)
                .map { it[ArtifactUploads.id] to it[ArtifactUploads.artifactId] }
        }
        for ((uploadId, artifactId) in expired) database.query {
            // Under the artifact lock, like a commit and a new upload: a blob is marked only if no live upload announced it.
            val state = Artifacts.select(Artifacts.state).where { Artifacts.id eq artifactId }.forUpdate().singleOrNull()?.get(Artifacts.state)
            if (state != null && state != DELETED) {
                val now = nowMillis()
                val held = ArtifactBlobs.select(ArtifactBlobs.sha256).where { ArtifactBlobs.artifactId eq artifactId }.map { it[ArtifactBlobs.sha256] }.toSet()
                val announced = liveAnnouncements(artifactId, uploadId, now)
                ArtifactUploadBlobs.selectAll().where { ArtifactUploadBlobs.uploadId eq uploadId }.toList()
                    .filter { it[ArtifactUploadBlobs.sha256] !in held && it[ArtifactUploadBlobs.sha256] !in announced }
                    .forEach { blob -> ArtifactBlobs.insert {
                        it[ArtifactBlobs.artifactId] = artifactId; it[sha256] = blob[ArtifactUploadBlobs.sha256]; it[size] = blob[ArtifactUploadBlobs.size]
                        it[storedAt] = blob[ArtifactUploadBlobs.storedAt]; it[lastReferencedVersion] = null; it[collectingSince] = now
                    } }
            }
            ArtifactUploads.deleteWhere { ArtifactUploads.id eq uploadId }
        }
        return expired.size
    }

    override suspend fun collectableBlobs(limit: Int): List<CollectedBlob> = database.query {
        (ArtifactBlobs innerJoin Artifacts).select(Artifacts.teamId, ArtifactBlobs.artifactId, ArtifactBlobs.sha256).where { ArtifactBlobs.collectingSince.isNotNull() }
            .orderBy(ArtifactBlobs.collectingSince).limit(limit).map { CollectedBlob(it[Artifacts.teamId], it[ArtifactBlobs.artifactId], it[ArtifactBlobs.sha256]) }
    }

    override suspend fun forgetBlobs(blobs: List<CollectedBlob>) = database.query {
        for (blob in blobs) ArtifactBlobs.deleteWhere { (ArtifactBlobs.artifactId eq blob.artifactId) and (ArtifactBlobs.sha256 eq blob.sha256) and ArtifactBlobs.collectingSince.isNotNull() }
    }

    override suspend fun deferBlobs(blobs: List<CollectedBlob>) = database.query {
        val now = nowMillis()
        for (blob in blobs) ArtifactBlobs.update({ (ArtifactBlobs.artifactId eq blob.artifactId) and (ArtifactBlobs.sha256 eq blob.sha256) and ArtifactBlobs.collectingSince.isNotNull() }) { it[collectingSince] = now }
    }

    override suspend fun deferDeletion(target: StorageTarget) = database.query {
        Artifacts.update({ (Artifacts.id eq target.artifactId) and (Artifacts.state eq DELETED) and Artifacts.storageDeletedAt.isNull() }) { it[updatedAt] = nowMillis() }
        Unit
    }

    override suspend fun pendingDeletions(limit: Int): List<StorageTarget> = database.query {
        val cutoff = nowMillis() - ArtifactRules.DELETION_GRACE_MILLIS
        Artifacts.select(Artifacts.teamId, Artifacts.id).where { (Artifacts.state eq DELETED) and (Artifacts.updatedAt less cutoff) and Artifacts.storageDeletedAt.isNull() }
            .orderBy(Artifacts.updatedAt).limit(limit).map { StorageTarget(it[Artifacts.teamId], it[Artifacts.id]) }
    }

    override suspend fun markStorageDeleted(target: StorageTarget) = database.query {
        val updated = Artifacts.update({ (Artifacts.id eq target.artifactId) and (Artifacts.state eq DELETED) and Artifacts.storageDeletedAt.isNull() }) { it[storageDeletedAt] = nowMillis(); it[storedBytes] = 0 }
        if (updated == 1) {
            // The folder is proven empty: what described its content goes too; the row stays as the record.
            ArtifactUploads.deleteWhere { ArtifactUploads.artifactId eq target.artifactId }
            ArtifactVersions.deleteWhere { ArtifactVersions.artifactId eq target.artifactId }
            ArtifactBlobs.deleteWhere { ArtifactBlobs.artifactId eq target.artifactId }
            ArtifactMembers.deleteWhere { ArtifactMembers.artifactId eq target.artifactId }
            audit(target.teamId, SYSTEM, "artifact.storage_deleted", target.artifactId)
        }
        Unit
    }

    /** Refuses every member call while artifacts are not active, with the reason as the code. */
    private fun requireActive(teamId: String): ArtifactSettings {
        val settings = readSettings(teamId)
        val availability = ArtifactAvailability.of(settings, readStorage(teamId))
        if (!availability.active) throw TeamFailure(availability.reason!!, 409, when (availability.reason) {
            "artifact_storage_missing" -> "Artifacts are not available: no storage is configured. An administrator sets it up in Dashboard → Settings → Artifacts."
            "artifact_storage_unverified" -> "Artifacts are not available: the storage has not passed its connection test since it changed. An administrator tests it in Dashboard → Settings → Artifacts."
            else -> "Artifacts are turned off for this team. An administrator turns them on in Dashboard → Settings → Artifacts."
        })
        return settings
    }

    private fun requirePublisher(actor: Principal, settings: ArtifactSettings) {
        if (settings.whoCanPublish == WhoCanPublish.ADMINS && !actor.member.role.isAdministrator)
            throw TeamFailure("artifact_publish_forbidden", 403, "Only administrators publish artifacts in this team.")
    }

    /** Publications per member and hour, counted from the audit trail (pruned versions still count). */
    private fun JdbcTransaction.requireRate(actor: Principal, settings: ArtifactSettings, now: Long) {
        val since = now - 3_600_000
        val times = AuditEvents.select(AuditEvents.occurredAt).where {
            (AuditEvents.teamId eq actor.teamId) and (AuditEvents.occurredAt greaterEq since) and (AuditEvents.action eq "artifact.version_published") and (AuditEvents.actorId eq actor.member.id)
        }.orderBy(AuditEvents.occurredAt).map { it[AuditEvents.occurredAt] }
        if (times.size >= settings.quotas.publishesPerHour) {
            val retry = maxOf(1, (times[times.size - settings.quotas.publishesPerHour] + 3_600_000 - now + 999) / 1000)
            throw TeamFailure("artifact_rate_limited", 429, "You have published ${settings.quotas.publishesPerHour} versions in the last hour, the team's limit.", retryAfterSeconds = retry)
        }
    }

    /** The per-member artifact quota counts published artifacts only; checked when a draft is created and when it is first published. */
    private fun requireArtifactSlot(actor: Principal, settings: ArtifactSettings, owner: String = actor.member.id) {
        val owned = Artifacts.select(Artifacts.id).where { (Artifacts.teamId eq actor.teamId) and (Artifacts.ownerId eq owner) and (Artifacts.state eq ACTIVE) }.count()
        if (owned >= settings.quotas.artifactsPerMember)
            throw TeamFailure("artifact_quota_exceeded", 409, "The team's limit of ${settings.quotas.artifactsPerMember} artifacts per member is reached. Delete some before publishing a new one.")
    }

    /** The artifact if the caller may know it exists: active, or a draft of their own. Anything else is 404. */
    private fun artifactRow(actor: Principal, artifactId: String, lock: Boolean): ResultRow {
        val query = Artifacts.selectAll().where { (Artifacts.id eq artifactId) and (Artifacts.teamId eq actor.teamId) }
        val row = (if (lock) query.forUpdate() else query).singleOrNull()
        if (row == null || row[Artifacts.state] == DELETED || (row[Artifacts.state] == DRAFT && row[Artifacts.ownerId] != actor.member.id))
            throw TeamFailure("not_found", 404, "Artifact not found.")
        return row
    }

    private fun relation(actor: Principal, row: ResultRow, invitedRole: String?): String? = when {
        row[Artifacts.ownerId] == actor.member.id -> "owner"
        invitedRole != null -> invitedRole
        row[Artifacts.access] != ArtifactAccess.PERSONAL.wire -> "team"
        actor.canReadTeam -> "admin"
        else -> null
    }

    private fun requireReadable(actor: Principal, row: ResultRow): String {
        val invited = ArtifactMembers.select(ArtifactMembers.role).where { (ArtifactMembers.artifactId eq row[Artifacts.id]) and (ArtifactMembers.memberId eq actor.member.id) }.singleOrNull()?.get(ArtifactMembers.role)
        // Not revealing that a personal artifact exists to whoever cannot read it.
        return relation(actor, row, invited) ?: throw TeamFailure("not_found", 404, "Artifact not found.")
    }

    private fun requireWritable(actor: Principal, row: ResultRow): String {
        val relation = requireReadable(actor, row)
        if (relation != "owner" && relation != ArtifactMemberRole.EDITOR.wire)
            throw TeamFailure("artifact_not_writable", 403, "Only the owner and invited editors publish versions of this artifact.")
        return relation
    }

    private fun requireInviter(actor: Principal, row: ResultRow) {
        if (row[Artifacts.state] != ACTIVE) throw TeamFailure("not_found", 404, "Artifact not found.")
        val relation = requireReadable(actor, row)
        if (relation != "owner" && relation != ArtifactMemberRole.EDITOR.wire)
            throw TeamFailure("artifact_not_writable", 403, "Only the owner and invited editors invite members to this artifact.")
    }

    private fun JdbcTransaction.uploadRow(actor: Principal, uploadId: String): ResultRow {
        val now = nowMillis()
        return (ArtifactUploads innerJoin Artifacts).selectAll().where {
        (ArtifactUploads.id eq uploadId) and (ArtifactUploads.teamId eq actor.teamId) and (ArtifactUploads.memberId eq actor.member.id) and
            (ArtifactUploads.credentialId eq actor.tokenId) and (ArtifactUploads.expiresAt greater now) and (Artifacts.state neq DELETED)
        }.singleOrNull() ?: throw TeamFailure("artifact_upload_not_found", 404, "This upload does not exist, has expired or belongs to another credential. Start a new one.")
    }

    private fun versionRow(artifactId: String, number: Int) =
        ArtifactVersions.selectAll().where { (ArtifactVersions.artifactId eq artifactId) and (ArtifactVersions.number eq number) }.singleOrNull()

    private fun readVersion(row: ResultRow, withFiles: Boolean) = ArtifactVersion(row[ArtifactVersions.artifactId], row[ArtifactVersions.number], row[ArtifactVersions.entryPath],
        row[ArtifactVersions.manifestDigest], row[ArtifactVersions.fileCount], row[ArtifactVersions.totalBytes], row[ArtifactVersions.createdBy], row[ArtifactVersions.createdAt],
        row[ArtifactVersions.baseVersion], row[ArtifactVersions.originSessionId], row[ArtifactVersions.originEpoch],
        if (!withFiles) emptyList() else ArtifactVersionFiles.selectAll().where { ArtifactVersionFiles.versionId eq row[ArtifactVersions.id] }.orderBy(ArtifactVersionFiles.path)
            .map { ArtifactFile(it[ArtifactVersionFiles.path], it[ArtifactVersionFiles.sha256], it[ArtifactVersionFiles.size], it[ArtifactVersionFiles.contentType], it[ArtifactVersionFiles.role]) })

    private fun toArtifact(row: ResultRow, relation: String, names: Map<String, String> = ownerNames(listOf(row[Artifacts.ownerId]))) = Artifact(
        row[Artifacts.id], row[Artifacts.teamId], row[Artifacts.ownerId], names[row[Artifacts.ownerId]].orEmpty(), row[Artifacts.title], row[Artifacts.description],
        row[Artifacts.icon], ArtifactAccess.parse(row[Artifacts.access]) ?: ArtifactAccess.PERSONAL, row[Artifacts.publicFollowsLatest], row[Artifacts.publicVersion],
        row[Artifacts.currentVersion], row[Artifacts.state], row[Artifacts.storedBytes],
        ArtifactOrigin(row[Artifacts.originKind], row[Artifacts.originRunId], row[Artifacts.originSessionId], row[Artifacts.originEpoch]),
        row[Artifacts.createdAt], row[Artifacts.updatedAt], row[Artifacts.publishedAt], row[Artifacts.expiresAt], relation,
    )

    private fun ownerNames(ids: List<String>): Map<String, String> = if (ids.isEmpty()) emptyMap() else
        Members.select(Members.id, Members.username).where { Members.id inList ids.distinct() }.associate { it[Members.id] to it[Members.username] }

    private fun memberRoles(member: String, artifacts: List<String>): Map<String, String> = if (artifacts.isEmpty()) emptyMap() else
        ArtifactMembers.select(ArtifactMembers.artifactId, ArtifactMembers.role).where { (ArtifactMembers.memberId eq member) and (ArtifactMembers.artifactId inList artifacts) }
            .associate { it[ArtifactMembers.artifactId] to it[ArtifactMembers.role] }

    private fun readMembers(row: ResultRow): ArtifactSharing {
        val rows = ArtifactMembers.selectAll().where { ArtifactMembers.artifactId eq row[Artifacts.id] }.orderBy(ArtifactMembers.addedAt).toList()
        val names = ownerNames(rows.map { it[ArtifactMembers.memberId] })
        return ArtifactSharing(row[Artifacts.ownerId], rows.map {
            ArtifactMember(it[ArtifactMembers.memberId], names[it[ArtifactMembers.memberId]].orEmpty(), ArtifactMemberRole.parse(it[ArtifactMembers.role]) ?: ArtifactMemberRole.VIEWER,
                it[ArtifactMembers.reason], it[ArtifactMembers.addedBy], it[ArtifactMembers.addedAt], it[ArtifactMembers.originSessionId])
        })
    }

    /**
     * A member of the team by id, then username, then linked GitHub login, in that order: a username that spells another
     * member's id never captures them. A GitHub login linked to more than one member is ambiguous and refused.
     */
    private fun resolveMember(teamId: String, reference: String, activeOnly: Boolean): String? {
        val team = (Members.teamId eq teamId) and (if (activeOnly) (Members.disabled eq false) and (Members.mustChangePassword eq false) else Op.TRUE)
        Members.select(Members.id).where { team and (Members.id eq reference) }.singleOrNull()?.let { return it[Members.id] }
        Members.select(Members.id).where { team and (Members.username eq reference) }.singleOrNull()?.let { return it[Members.id] }
        val linked = (GitHubLinks innerJoin Members).select(Members.id).where { team and (GitHubLinks.teamId eq teamId) and (GitHubLinks.githubLogin.lowerCase() eq reference.lowercase()) }
            .map { it[Members.id] }.distinct()
        if (linked.size > 1) throw TeamFailure("invalid_artifact_member", 400, "This GitHub login is linked to several members. Name the member by id or username.")
        return linked.singleOrNull()
    }

    /** Blobs announced by the artifact's other live uploads: their clients may send or rely on them. */
    private fun liveAnnouncements(artifactId: String, except: String, now: Long): Set<String> =
        ArtifactUploads.select(ArtifactUploads.files).where { (ArtifactUploads.artifactId eq artifactId) and (ArtifactUploads.id neq except) and (ArtifactUploads.expiresAt greater now) }
            .flatMap { decodeFiles(it[ArtifactUploads.files]).keys }.toSet()

    /** Marks the blobs no kept version uses and no live upload announced; returns their bytes, no longer counted. */
    private fun markUnreferenced(artifactId: String, now: Long): Long {
        val used = (ArtifactVersionFiles innerJoin ArtifactVersions).select(ArtifactVersionFiles.sha256).where { ArtifactVersions.artifactId eq artifactId }.withDistinct()
            .map { it[ArtifactVersionFiles.sha256] }.toSet() + liveAnnouncements(artifactId, "", now)
        val unused = ArtifactBlobs.select(ArtifactBlobs.sha256, ArtifactBlobs.size).where { (ArtifactBlobs.artifactId eq artifactId) and ArtifactBlobs.collectingSince.isNull() }
            .filter { it[ArtifactBlobs.sha256] !in used }
        if (unused.isEmpty()) return 0
        ArtifactBlobs.update({ (ArtifactBlobs.artifactId eq artifactId) and (ArtifactBlobs.sha256 inList unused.map { it[ArtifactBlobs.sha256] }) }) { it[collectingSince] = now }
        return unused.sumOf { it[ArtifactBlobs.size] }
    }

    private fun expiry(settings: ArtifactSettings, access: ArtifactAccess, from: Long): Long? =
        (if (access == ArtifactAccess.PERSONAL) settings.retention.personalDays else settings.retention.sharedDays)?.let { from + it * 86_400_000L }

    private companion object {
        const val DRAFT = "draft"
        const val ACTIVE = "active"
        const val DELETED = "deleted"
        const val SYSTEM = "system"
        private val filePattern = Regex("\"([0-9a-f]{64})\":(\\d{1,12})")
        fun encodeFiles(files: List<UploadFile>) = files.joinToString(",", "{", "}") { "\"${it.sha256}\":${it.size}" }
        fun decodeFiles(json: String): Map<String, Long> = filePattern.findAll(json).associate { it.groupValues[1] to it.groupValues[2].toLong() }
    }
}
