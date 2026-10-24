package me.nathanfallet.teamcode.domain.team

/** Ownership includes administration but reserves owner assignment and recovery to other owners. */
enum class Role {
    OWNER, ADMIN, MEMBER;
    val isAdministrator: Boolean get() = this == OWNER || this == ADMIN
}
data class Member(val id: String, val username: String, val role: Role, val disabled: Boolean, val passwordChangeRequired: Boolean)
data class Principal(val member: Member, val tokenId: String, val teamId: String, val serviceScopes: Set<ServiceScope>? = null)
/** [trustedBrowser] is the dashboard's new trusted-browser cookie, for a login that came with a [LoginOrigin.browser]. */
class LoginSession(val token: String, val principal: Principal, val expiresAt: Long, val trustedBrowser: String? = null) {
    override fun toString() = "LoginSession([REDACTED])"
}
/**
 * Where a login attempt comes from, used for throttling only. [sourceAddress] is the client's address (behind a trusted
 * proxy, the one it forwarded). [browser] is set for a dashboard login: the browser's trusted-browser cookie, or `""`
 * when it has none.
 */
data class LoginOrigin(val sourceAddress: String? = null, val browser: String? = null)
class NewMember(val member: Member, val temporaryPassword: String) {
    override fun toString() = "NewMember(member=$member, temporaryPassword=[REDACTED])"
}
/**
 * A refusal with a public message. [retryAfterSeconds] is when retrying can succeed, when known. [retryable] is false
 * for a state that waiting will not change soon (an account to hand over, a daily budget used up): clients are then
 * told not to retry, so the message reaches the user instead of a retry loop. A conflict (409) is final unless its
 * code says that submitting the same request again can succeed.
 */
class TeamFailure(val code: String, val status: Int, message: String, val retryAfterSeconds: Long? = null,
    val retryable: Boolean = status == 408 || status == 429 || status >= 500) : Exception(message)

/** All authorization and last-administrator invariants belong here or in its transactional adapter. */
interface TeamService {
    suspend fun login(username: String, password: String, device: String, installationId: String? = null): LoginSession
    /** A login that also records the installation's operating system on its device ([DeviceService.validatePlatform]). */
    suspend fun login(username: String, password: String, device: String, installationId: String?, platform: String?, osVersion: String?): LoginSession =
        login(username, password, device, installationId)
    /** A login with its [origin], so that throttling can tell an attacker's attempts from a member's known devices. */
    suspend fun login(username: String, password: String, device: String, installationId: String?, platform: String?, osVersion: String?, origin: LoginOrigin): LoginSession =
        login(username, password, device, installationId, platform, osVersion)
    suspend fun authenticate(token: String): Principal?
    suspend fun logout(principal: Principal)
    suspend fun changePassword(principal: Principal, current: String, replacement: String)
    suspend fun members(principal: Principal): List<Member>
    suspend fun createMember(principal: Principal, username: String, role: Role): NewMember
    suspend fun updateMember(principal: Principal, id: String, role: Role, disabled: Boolean): Member
    suspend fun resetPassword(principal: Principal, id: String): NewMember
}

fun Principal.requireAdmin() {
    requireInteractive()
    if (!member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Administrator access is required.")
    requireReady()
}
/** Service keys never inherit their member's ownership or interactive administration authority. */
fun Principal.requireOwner() {
    requireInteractive()
    if (member.role != Role.OWNER) throw TeamFailure("owner_required", 403, "Team owner access is required.")
    requireReady()
}
fun Principal.requireReady() {
    if (member.passwordChangeRequired) throw TeamFailure("password_change_required", 403, "Change your temporary password first.")
    if (member.disabled) throw TeamFailure("forbidden", 403, "This member is disabled.")
}
