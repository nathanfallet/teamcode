package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialDecryptionException
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Variants, assignments and exclusions in shared MySQL. Writes take the team lock and revalidate the interactive
 * administrator in the same transaction; audit events carry identifiers only, never values, endpoints or credentials
 * (a hosted command's creation also names its transport, launcher and package). [stdioLaunchers] are the launchers of
 * the server's MCP host; empty, the default, means no host, and declaring a hosted command answers `409 mcp_disabled`.
 */
class SqlVariantService(
    private val database: Database, private val cipher: CredentialCipher, private val stdioLaunchers: Set<StdioLauncher> = emptySet(),
) : VariantService, EffectiveVariantSource {
    override suspend fun list(actor: Principal): List<Variant> = database.query {
        authorize(this, actor)
        Variants.selectAll().where { Variants.teamId eq actor.teamId }
            .orderBy(Variants.kind to SortOrder.ASC, Variants.target to SortOrder.ASC, Variants.name to SortOrder.ASC)
            .limit(VariantService.VARIANT_LIMIT.toInt()).map(::variant)
    }

    override suspend fun create(actor: Principal, variant: NewVariant): Variant {
        // Authorization first, so a non-administrator learns nothing, not even whether an MCP host is configured.
        actor.requireAdmin()
        VariantService.validate(variant, stdioLaunchers)
        return database.query {
            write(this, actor)
            if (Variants.select(Variants.id).where { Variants.teamId eq actor.teamId }.count() >= VariantService.VARIANT_LIMIT)
                throw TeamFailure("variant_limit", 409, "Delete an unused variant before creating another.")
            if (!Variants.select(Variants.id).where { (Variants.teamId eq actor.teamId) and slot(variant.kind, variant.target) and (Variants.name eq variant.name) }.empty())
                throw TeamFailure("variant_exists", 409, "A variant with this name already exists for this target.")
            val id = UUID.randomUUID().toString()
            val now = nowMillis()
            Variants.insert {
                it[Variants.id] = id; it[teamId] = actor.teamId; it[kind] = variant.kind.name; it[target] = variant.target
                it[name] = variant.name; it[binding] = variant.binding.name; it[version] = 1; it[endpoint] = variant.endpoint
                it[config] = Json.encodeToString(variant.config); it[sharedSecret] = variant.sharedSecret?.let { secret -> seal(secret, actor.teamId, id) }
                it[createdAt] = now; it[updatedAt] = now
                if (variant.kind == VariantKind.MCP && variant.transport == McpTransport.STDIO) variant.command?.let { command ->
                    it[transport] = variant.transport.wire; it[Variants.command] = command.launcher.wire; it[args] = Json.encodeToString(listOf(command.pkg) + command.args)
                }
            }
            audit(actor.teamId, actor.member.id, "variant.created", id, details = variant.command?.takeIf { variant.transport == McpTransport.STDIO }?.let { command ->
                // Which code the server will run, so the journal can answer it; never the arguments, variables or values.
                buildJsonObject { put("transport", variant.transport.wire); put("launcher", command.launcher.wire); put("package", command.pkg) }.toString()
            })
            requireVariant(actor.teamId, id)
        }
    }

    override suspend fun update(actor: Principal, id: String, update: VariantUpdate): Variant = database.query {
        write(this, actor)
        val current = requireVariant(actor.teamId, id, lock = true)
        VariantService.validate(current, update)
        if (current.version != update.expectedVersion) throw TeamFailure("variant_conflict", 409, "This variant changed since you loaded it. Reload and try again.")
        if (current.kind == VariantKind.ENV && current.binding == VariantBinding.SHARED && !current.hasSharedSecret && update.sharedSecret == null)
            throw TeamFailure("invalid_variant", 400, "A shared environment variant needs a value.")
        if (!Variants.select(Variants.id).where { (Variants.teamId eq actor.teamId) and slot(current.kind, current.target) and (Variants.name eq update.name) and (Variants.id neq id) }.empty())
            throw TeamFailure("variant_exists", 409, "A variant with this name already exists for this target.")
        Variants.update({ (Variants.id eq id) and (Variants.teamId eq actor.teamId) }) {
            it[name] = update.name; it[config] = Json.encodeToString(update.config)
            if (update.clearSharedSecret) it[sharedSecret] = null
            update.sharedSecret?.let { secret -> it[sharedSecret] = seal(secret, actor.teamId, id) }
            it[version] = current.version + 1; it[updatedAt] = nowMillis()
        }
        audit(actor.teamId, actor.member.id, "variant.updated", id)
        requireVariant(actor.teamId, id)
    }

    override suspend fun delete(actor: Principal, id: String) = database.query {
        write(this, actor)
        requireVariant(actor.teamId, id, lock = true)
        VariantAssignments.deleteWhere { (variantId eq id) and (teamId eq actor.teamId) }
        Variants.deleteWhere { (Variants.id eq id) and (teamId eq actor.teamId) }
        audit(actor.teamId, actor.member.id, "variant.deleted", id)
    }

    override suspend fun assign(actor: Principal, memberId: String, variantId: String, memberSecret: SecretValue?): VariantAssignment = database.query {
        write(this, actor)
        requireMember(actor.teamId, memberId)
        val variant = requireVariant(actor.teamId, variantId, lock = true)
        VariantService.validateMemberSecret(variant, memberSecret)
        if (excluded(memberId, variant.slot)) throw TeamFailure("variant_excluded", 409, "This member is excluded from this target. Remove the exclusion first.")
        val now = nowMillis()
        VariantAssignments.deleteWhere { (VariantAssignments.memberId eq memberId) and (kind eq variant.kind.name) and (target eq variant.target) }
        VariantAssignments.insert { it.assignment(actor.teamId, memberId, variant, AssignmentSource.DIRECT, memberSecret?.let { secret -> seal(secret, actor.teamId, variant.id, memberId) }, now) }
        audit(actor.teamId, actor.member.id, "variant.assigned", memberId)
        VariantAssignment(memberId, variant.slot, variant.id, variant.name, variant.version, AssignmentSource.DIRECT, memberSecret != null, now)
    }

    override suspend fun unassign(actor: Principal, memberId: String, target: VariantTarget) = database.query {
        VariantService.validateTarget(target)
        write(this, actor)
        requireMember(actor.teamId, memberId)
        val removed = VariantAssignments.deleteWhere { (VariantAssignments.memberId eq memberId) and (teamId eq actor.teamId) and (kind eq target.kind.name) and (VariantAssignments.target eq target.name) }
        if (removed > 0) audit(actor.teamId, actor.member.id, "variant.unassigned", memberId)
    }

    override suspend fun bulkAssign(actor: Principal, variantId: String): BulkAssignment = database.query {
        write(this, actor)
        val variant = requireVariant(actor.teamId, variantId, lock = true)
        if (variant.binding != VariantBinding.SHARED) throw TeamFailure("invalid_variant", 400, "Only shared variants can be assigned in bulk; assign per-member variants individually.")
        // Member state changes take the same team lock, so this read is stable until commit.
        val members = Members.select(Members.id).where { (Members.teamId eq actor.teamId) and (Members.disabled eq false) }.map { it[Members.id] }
        val excluded = VariantExclusions.select(VariantExclusions.memberId).where { (VariantExclusions.teamId eq actor.teamId) and excludedSlot(variant) }.map { it[VariantExclusions.memberId] }.toSet()
        val direct = VariantAssignments.select(VariantAssignments.memberId).where {
            (VariantAssignments.teamId eq actor.teamId) and assignedSlot(variant) and (VariantAssignments.assignmentSource eq AssignmentSource.DIRECT.name)
        }.map { it[VariantAssignments.memberId] }.toSet()
        val eligible = members.filter { it !in excluded && it !in direct }
        if (eligible.isNotEmpty()) {
            val now = nowMillis()
            VariantAssignments.deleteWhere { (teamId eq actor.teamId) and (kind eq variant.kind.name) and (target eq variant.target) and (memberId inList eligible) }
            VariantAssignments.batchInsert(eligible, shouldReturnGeneratedValues = false) { member -> assignment(actor.teamId, member, variant, AssignmentSource.BULK, null, now) }
            // One event per affected member, so the journal can say who received the variant.
            AuditEvents.batchInsert(eligible, shouldReturnGeneratedValues = false) { member ->
                this[AuditEvents.teamId] = actor.teamId; this[AuditEvents.actorId] = actor.member.id; this[AuditEvents.action] = "variant.assigned"
                this[AuditEvents.subjectId] = member; this[AuditEvents.occurredAt] = now
            }
        }
        audit(actor.teamId, actor.member.id, "variant.bulk_assigned", variant.id)
        BulkAssignment(eligible.size, members.count { it in direct && it !in excluded }, members.count { it in excluded })
    }

    override suspend fun exclude(actor: Principal, memberId: String, target: VariantTarget) = database.query {
        VariantService.validateTarget(target)
        write(this, actor)
        requireMember(actor.teamId, memberId)
        VariantAssignments.deleteWhere { (VariantAssignments.memberId eq memberId) and (teamId eq actor.teamId) and (kind eq target.kind.name) and (VariantAssignments.target eq target.name) }
        if (!excluded(memberId, target)) VariantExclusions.insert {
            it[VariantExclusions.memberId] = memberId; it[kind] = target.kind.name; it[VariantExclusions.target] = target.name
            it[teamId] = actor.teamId; it[createdAt] = nowMillis()
        }
        audit(actor.teamId, actor.member.id, "variant.excluded", memberId)
    }

    override suspend fun include(actor: Principal, memberId: String, target: VariantTarget) = database.query {
        VariantService.validateTarget(target)
        write(this, actor)
        requireMember(actor.teamId, memberId)
        val removed = VariantExclusions.deleteWhere { (VariantExclusions.memberId eq memberId) and (teamId eq actor.teamId) and (kind eq target.kind.name) and (VariantExclusions.target eq target.name) }
        if (removed > 0) audit(actor.teamId, actor.member.id, "variant.included", memberId)
    }

    override suspend fun assignments(actor: Principal, memberId: String?): List<MemberVariantAssignments> = database.query {
        authorize(this, actor)
        val members = Members.select(Members.id, Members.username, Members.disabled)
            .where { (Members.teamId eq actor.teamId) and (memberId?.let { Members.id eq it } ?: Op.TRUE) }
            .orderBy(Members.username).map { Triple(it[Members.id], it[Members.username], it[Members.disabled]) }
        if (memberId != null && members.isEmpty()) throw TeamFailure("not_found", 404, "Member not found.")
        val ids = members.map { it.first }
        val assignments = if (ids.isEmpty()) emptyMap() else (VariantAssignments innerJoin Variants)
            .select(VariantAssignments.memberId, VariantAssignments.kind, VariantAssignments.target, VariantAssignments.assignmentSource, VariantAssignments.memberSecret, VariantAssignments.assignedAt, Variants.id, Variants.name, Variants.version)
            .where { (VariantAssignments.teamId eq actor.teamId) and (VariantAssignments.memberId inList ids) }
            .map { row ->
                VariantAssignment(row[VariantAssignments.memberId], VariantTarget(VariantKind.valueOf(row[VariantAssignments.kind]), row[VariantAssignments.target]),
                    row[Variants.id], row[Variants.name], row[Variants.version], AssignmentSource.valueOf(row[VariantAssignments.assignmentSource]), row[VariantAssignments.memberSecret] != null, row[VariantAssignments.assignedAt])
            }.groupBy { it.memberId }
        val exclusions = if (ids.isEmpty()) emptyMap() else VariantExclusions.select(VariantExclusions.memberId, VariantExclusions.kind, VariantExclusions.target)
            .where { (VariantExclusions.teamId eq actor.teamId) and (VariantExclusions.memberId inList ids) }
            .map { it[VariantExclusions.memberId] to VariantTarget(VariantKind.valueOf(it[VariantExclusions.kind]), it[VariantExclusions.target]) }
            .groupBy({ it.first }, { it.second })
        val order = compareBy<VariantTarget>({ it.kind }, { it.name })
        members.map { (id, username, disabled) ->
            MemberVariantAssignments(id, username, disabled,
                assignments[id].orEmpty().sortedWith(compareBy(order) { it.target }),
                exclusions[id].orEmpty().sortedWith(order))
        }
    }

    /** Trusted resolution read: decrypts only this member's selected values. Authorization belongs to the caller. */
    override suspend fun state(teamId: String, memberId: String): MemberVariantState = database.query {
        // Same eligibility as authentication: a disabled member or one with a temporary password receives nothing.
        val member = Members.select(Members.disabled, Members.mustChangePassword).where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()
        if (member == null || member[Members.disabled] || member[Members.mustChangePassword]) return@query MemberVariantState(false, emptyList(), emptySet())
        val exclusions = VariantExclusions.select(VariantExclusions.kind, VariantExclusions.target)
            .where { (VariantExclusions.teamId eq teamId) and (VariantExclusions.memberId eq memberId) }
            .map { VariantTarget(VariantKind.valueOf(it[VariantExclusions.kind]), it[VariantExclusions.target]) }.toSet()
        val candidates = (VariantAssignments innerJoin Variants).selectAll()
            .where { (VariantAssignments.teamId eq teamId) and (VariantAssignments.memberId eq memberId) and (Variants.teamId eq teamId) }
            .mapNotNull { row ->
                val target = VariantTarget(VariantKind.valueOf(row[VariantAssignments.kind]), row[VariantAssignments.target])
                if (target in exclusions) return@mapNotNull null // Never decrypt a value the member cannot receive.
                val variant = variant(row)
                val source = AssignmentSource.valueOf(row[VariantAssignments.assignmentSource])
                val assignedAt = row[VariantAssignments.assignedAt]
                try {
                    AssignmentCandidate(target, source, variant,
                        row[Variants.sharedSecret]?.let { open(it, teamId, variant.id) },
                        row[VariantAssignments.memberSecret]?.let { open(it, teamId, variant.id, memberId) }, assignedAt)
                } catch (_: CredentialDecryptionException) {
                    AssignmentCandidate(target, source, variant, null, null, assignedAt, unreadable = true)
                }
            }
        MemberVariantState(true, candidates, exclusions)
    }

    private fun authorize(transaction: JdbcTransaction, actor: Principal) { actor.requireAdmin(); requireCurrent(transaction, actor) }
    private fun write(transaction: JdbcTransaction, actor: Principal) { actor.requireAdmin(); transaction.lockTeam(actor.teamId); requireCurrent(transaction, actor) }

    private fun requireVariant(team: String, id: String, lock: Boolean = false): Variant {
        val query = Variants.selectAll().where { (Variants.id eq id) and (Variants.teamId eq team) }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.let(::variant) ?: throw TeamFailure("not_found", 404, "Variant not found.")
    }
    private fun requireMember(team: String, id: String) {
        Members.select(Members.id).where { (Members.id eq id) and (Members.teamId eq team) }.singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Member not found.")
    }
    private fun excluded(member: String, target: VariantTarget) = !VariantExclusions.select(VariantExclusions.memberId).where {
        (VariantExclusions.memberId eq member) and (VariantExclusions.kind eq target.kind.name) and (VariantExclusions.target eq target.name)
    }.empty()
    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.assignment(team: String, member: String, variant: Variant, source: AssignmentSource, sealed: String?, now: Long) {
        this[VariantAssignments.memberId] = member; this[VariantAssignments.kind] = variant.kind.name; this[VariantAssignments.target] = variant.target
        this[VariantAssignments.teamId] = team; this[VariantAssignments.variantId] = variant.id; this[VariantAssignments.assignmentSource] = source.name
        this[VariantAssignments.memberSecret] = sealed; this[VariantAssignments.assignedAt] = now
    }
    private fun slot(kind: VariantKind, target: String) = (Variants.kind eq kind.name) and (Variants.target eq target)
    private fun assignedSlot(variant: Variant) = (VariantAssignments.kind eq variant.kind.name) and (VariantAssignments.target eq variant.target)
    private fun excludedSlot(variant: Variant) = (VariantExclusions.kind eq variant.kind.name) and (VariantExclusions.target eq variant.target)
    private fun seal(secret: SecretValue, team: String, variant: String, member: String? = null) =
        cipher.encrypt(secret.reveal().toByteArray(Charsets.UTF_8), context(team, variant, member))
    private fun open(envelope: String, team: String, variant: String, member: String? = null) =
        SecretValue(cipher.decrypt(envelope, context(team, variant, member)).toString(Charsets.UTF_8))
    private fun context(team: String, variant: String, member: String?) = listOfNotNull("variant", team, variant, member).joinToString(":")
    private fun variant(row: ResultRow): Variant {
        // A transport or launcher this version does not know (written by a newer replica) is never served: it reads as a
        // hosted command, without a command when the launcher is unknown, which resolution withholds.
        val transport = McpTransport.entries.find { it.wire == row[Variants.transport] } ?: McpTransport.STDIO
        val command = StdioLauncher.entries.find { it.wire == row[Variants.command] }?.let { launcher ->
            // An unreadable list fails only this variant (no command, so unavailable), never the team's whole list or resolution.
            val packageThenArgs = try { Json.decodeFromString<List<String>>(row[Variants.args] ?: "[]") } catch (_: IllegalArgumentException) { emptyList() }
            packageThenArgs.firstOrNull()?.let { StdioCommand(launcher, it, packageThenArgs.drop(1)) }
        }
        return Variant(
            row[Variants.id], VariantKind.valueOf(row[Variants.kind]), row[Variants.target], row[Variants.name], VariantBinding.valueOf(row[Variants.binding]),
            row[Variants.version], row[Variants.endpoint], Json.decodeFromString(row[Variants.config]), row[Variants.sharedSecret] != null,
            row[Variants.createdAt], row[Variants.updatedAt], transport, command,
        )
    }
}
