package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class SqlTeamService(private val database: Database, private val passwords: Passwords = Passwords()) : TeamService {
    private val hashing = Semaphore(4) // Local CPU protection; the authoritative rate budget is in MySQL.
    private val loginBudget = SqlLoginBudget(database)
    private val access = CheckMemberAccessUseCase()
    private val dummyHash = passwords.hash(Passwords.secret())

    /** Return the initial password only for first creation; upgrade legacy ownership without a password reset. */
    suspend fun bootstrap(): String? = database.query {
        lockTeam("default")
        if (!Members.select(Members.id).where { Members.teamId eq "default" }.limit(1).empty()) {
            ensureLegacyOwner(this)
            return@query null
        }
        val password = Passwords.secret()
        val adminId = UUID.randomUUID().toString()
        Members.insert { it[id] = adminId; it[teamId] = "default"; it[username] = "admin"; it[passwordHash] = passwords.hash(password); it[role] = "ADMIN"; it[isOwner] = true }
        audit("default", "system", "member.bootstrap", adminId)
        password
    }
    override suspend fun login(username: String, password: String, device: String, installationId: String?): LoginSession {
        DeviceService.validateInstallation(installationId)
        if (installationId != null) DeviceService.validateName(device)
        if (!loginBudget.admit()) throw TeamFailure("login_rate_limit", 429, "Too many login attempts. Try again in a minute.")
        if (username.length !in 1..64 || password.length !in 1..1024) throw denied()
        val outcome = hashing.withPermit { database.query {
            val row = Members.selectAll().where { (Members.teamId eq "default") and (Members.username eq username.lowercase()) }.forUpdate().singleOrNull()
            val now = nowMillis()
            if (row != null && row[Members.lockedUntil] > now) return@query null
            val valid = passwords.verify(password, row?.get(Members.passwordHash) ?: dummyHash)
            if (row == null) return@query null
            val member = member(row)
            if (!valid || member.disabled) {
                val failures = row[Members.failedLogins] + 1
                Members.update({ Members.id eq member.id }) { it[failedLogins] = failures; it[lockedUntil] = if (failures >= 5) now + 60_000 else 0 }
                return@query null
            }
            Members.update({ Members.id eq member.id }) { it[failedLogins] = 0; it[lockedUntil] = 0 }
            Tokens.deleteWhere { (memberId eq member.id) and (expiresAt less now) and serviceScopes.isNull() }
            if (Tokens.selectAll().where { (Tokens.memberId eq member.id) and Tokens.serviceScopes.isNull() }.count() >= 50) {
                val oldest = Tokens.select(Tokens.id).where { (Tokens.memberId eq member.id) and Tokens.serviceScopes.isNull() }.orderBy(Tokens.expiresAt).limit(1).single()[Tokens.id]
                Tokens.deleteWhere { id eq oldest }
            }
            val token = Passwords.secret()
            val id = UUID.randomUUID().toString()
            val registeredDevice = installationId?.let { registerDevice(this, member, it, device, now) }
            val expires = now + if (device == "Web dashboard") 8 * 3_600_000L else 30 * 86_400_000L
            Tokens.insert { it[Tokens.id] = id; it[memberId] = member.id; it[tokenHash] = Passwords.digest(token); it[Tokens.device] = device.filter { char -> !char.isISOControl() }.take(120); it[deviceId] = registeredDevice; it[createdAt] = now; it[expiresAt] = expires }
            audit("default", member.id, "session.login", id)
            LoginSession(token, Principal(member, id, "default"), expires)
        } }
        return outcome ?: throw denied()
    }
    override suspend fun authenticate(token: String): Principal? {
        if (token.length !in 32..256) return null
        return database.query {
            (Tokens innerJoin Members).selectAll().where { (Tokens.tokenHash eq Passwords.digest(token)) and (Tokens.expiresAt greater nowMillis()) and (Members.disabled eq false) }
                .singleOrNull()?.let(::principal)
        }
    }
    override suspend fun logout(principal: Principal) { database.query {
        lockTeam(principal.teamId)
        val actor = fresh(this, principal)
        Tokens.deleteWhere { (id eq actor.tokenId) and (memberId eq actor.member.id) }
        audit(actor.teamId, actor.member.id, if (actor.serviceScopes == null) "session.logout" else "service_key.revoked", actor.tokenId)
    } }
    override suspend fun changePassword(principal: Principal, current: String, replacement: String) {
        if (replacement.length !in 14..1024 || replacement == current) throw TeamFailure("invalid_password", 400, "Choose a different password with at least 14 characters (up to 1024).")
        hashing.withPermit { database.query {
            lockTeam(principal.teamId)
            val actor = fresh(this, principal).also { it.requireInteractive() }
            val hash = Members.select(Members.passwordHash).where { Members.id eq actor.member.id }.forUpdate().single()[Members.passwordHash]
            if (!passwords.verify(current, hash)) throw denied()
            Members.update({ Members.id eq actor.member.id }) { it[passwordHash] = passwords.hash(replacement); it[mustChangePassword] = false; it[failedLogins] = 0; it[lockedUntil] = 0 }
            Tokens.deleteWhere { memberId eq actor.member.id }
            audit(actor.teamId, actor.member.id, "member.password_changed", actor.member.id)
        } }
    }
    override suspend fun members(principal: Principal): List<Member> = database.query {
        fresh(this, principal).requireAdmin()
        Members.selectAll().where { Members.teamId eq principal.teamId }.orderBy(Members.username).limit(1000).map(::member)
    }
    override suspend fun createMember(principal: Principal, username: String, role: Role): NewMember {
        val normalized = username.lowercase()
        if (!Regex("[a-z0-9][a-z0-9._-]{2,63}").matches(normalized)) throw TeamFailure("invalid_username", 400, "Use 3–64 letters, digits, dots, underscores or hyphens.")
        return hashing.withPermit { database.query {
            lockTeam(principal.teamId); val actor = fresh(this, principal); access.create(actor, role)
            if (!Members.select(Members.id).where { (Members.teamId eq principal.teamId) and (Members.username eq normalized) }.empty()) throw TeamFailure("username_exists", 409, "This username already exists.")
            if (Members.selectAll().where { Members.teamId eq principal.teamId }.count() >= 1000) throw TeamFailure("member_limit", 409, "The member limit has been reached.")
            val id = UUID.randomUUID().toString()
            val password = Passwords.secret()
            Members.insert { it[Members.id] = id; it[teamId] = principal.teamId; it[Members.username] = normalized; it[passwordHash] = passwords.hash(password); it[Members.role] = baseRole(role); it[isOwner] = role == Role.OWNER }
            audit(principal.teamId, principal.member.id, "member.created", id)
            if (role == Role.OWNER) audit(principal.teamId, principal.member.id, "member.owner_granted", id)
            NewMember(Member(id, normalized, role, false, true), password)
        } }
    }
    override suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member = database.query {
        lockTeam(principal.teamId); val actor = fresh(this, principal); actor.requireAdmin()
        val target = getMember(principal.teamId, id)
        access.change(actor, target, role, disabled, hasOtherReadyOwner(principal.teamId, id))
        if (target.role.isAdministrator && !target.disabled && (!role.isAdministrator || disabled)) {
            val admins = Members.selectAll().where { (Members.teamId eq principal.teamId) and ((Members.role eq "ADMIN") or (Members.isOwner eq true)) and (Members.disabled eq false) }.count()
            if (admins <= 1) throw TeamFailure("last_admin", 409, "The last active administrator cannot be disabled or demoted.")
        }
        Members.update({ Members.id eq id }) { it[Members.role] = baseRole(role); it[isOwner] = role == Role.OWNER; it[Members.disabled] = disabled }
        Tokens.deleteWhere { memberId eq id }
        audit(principal.teamId, principal.member.id, "member.updated", id)
        if (target.role != Role.OWNER && role == Role.OWNER) audit(principal.teamId, principal.member.id, "member.owner_granted", id)
        if (target.role == Role.OWNER && role != Role.OWNER) audit(principal.teamId, principal.member.id, "member.owner_removed", id)
        target.copy(role = role, disabled = disabled)
    }
    override suspend fun resetPassword(principal: Principal, id: String): NewMember = hashing.withPermit { database.query {
        lockTeam(principal.teamId); val actor = fresh(this, principal); actor.requireAdmin()
        val target = getMember(principal.teamId, id)
        access.resetPassword(actor, target, hasOtherReadyOwner(principal.teamId, id))
        val password = Passwords.secret()
        Members.update({ Members.id eq id }) { it[passwordHash] = passwords.hash(password); it[mustChangePassword] = true; it[failedLogins] = 0; it[lockedUntil] = 0 }
        Tokens.deleteWhere { memberId eq id }
        audit(principal.teamId, principal.member.id, "member.password_reset", id)
        NewMember(target.copy(passwordChangeRequired = true), password)
    } }
    private fun getMember(team: String, id: String): Member = Members.selectAll().where { (Members.teamId eq team) and (Members.id eq id) }.singleOrNull()?.let(::member)
        ?: throw TeamFailure("not_found", 404, "Member not found.")
    private fun fresh(c: JdbcTransaction, actor: Principal): Principal = (Tokens innerJoin Members).selectAll().where {
        (Tokens.id eq actor.tokenId) and (Members.id eq actor.member.id) and (Members.teamId eq actor.teamId) and (Tokens.expiresAt greater c.nowMillis()) and (Members.disabled eq false)
    }.singleOrNull()?.let(::principal) ?: throw denied()
    private fun principal(row: ResultRow) = Principal(member(row), row[Tokens.id], row[Members.teamId], readServiceScopes(row[Tokens.serviceScopes]))
    private fun member(row: ResultRow) = Member(row[Members.id], row[Members.username], readMemberRole(row), row[Members.disabled], row[Members.mustChangePassword])
    private fun baseRole(role: Role): String = if (role.isAdministrator) "ADMIN" else "MEMBER"
    private fun hasOtherReadyOwner(teamId: String, memberId: String): Boolean = !Members.select(Members.id).where {
        (Members.teamId eq teamId) and (Members.isOwner eq true) and (Members.id neq memberId) and
            (Members.disabled eq false) and (Members.mustChangePassword eq false)
    }.limit(1).empty()

    /** One-time serialized upgrade: preserve passwords/ledgers, revoke newly elevated credentials. */
    private fun ensureLegacyOwner(transaction: JdbcTransaction) {
        if (!Members.select(Members.id).where { (Members.teamId eq "default") and (Members.isOwner eq true) }.limit(1).empty()) return
        val candidates = Members.selectAll().where { (Members.teamId eq "default") and (Members.role eq "ADMIN") and (Members.disabled eq false) }
            .limit(1000).map(::member)
        val selected = candidates.minWithOrNull(compareBy<Member> { it.passwordChangeRequired }.thenBy { it.username != "admin" }.thenBy { it.id })
            ?: throw TeamFailure("owner_migration_required", 503, "No enabled legacy administrator is available for ownership. Restore administrator access before upgrading.")
        Members.update({ Members.id eq selected.id }) { it[isOwner] = true }
        Tokens.deleteWhere { memberId eq selected.id }
        transaction.audit("default", "system", "member.owner_migrated", selected.id)
    }
    private fun denied() = TeamFailure("invalid_credentials", 401, "Invalid credentials or temporarily locked account.")
}
