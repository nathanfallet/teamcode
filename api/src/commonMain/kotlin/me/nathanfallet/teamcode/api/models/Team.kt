package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
class LoginRequest(
    val username: String, val password: String, val device: String = "TeamCode CLI", val installationId: String? = null,
    /** The installation's operating system, recorded on its device: `macos`, `windows`, `linux`, `ios` or `android`. */
    val platform: String? = null,
    /** Its version as the system reports it, for example `15.1`; only with [platform]. */
    val osVersion: String? = null,
) {
    override fun toString() = "LoginRequest([REDACTED])"
}
@Serializable
data class MemberView(val id: String, val username: String, val role: String, val disabled: Boolean, val passwordChangeRequired: Boolean)
@Serializable
class LoginResponse(val accessToken: String, val expiresAt: Long, val member: MemberView) {
    override fun toString() = "LoginResponse([REDACTED])"
}
@Serializable
class PasswordRequest(val currentPassword: String, val newPassword: String) {
    override fun toString() = "PasswordRequest([REDACTED])"
}
@Serializable
data class CreateMemberRequest(val username: String, val role: String = "MEMBER")
@Serializable
data class UpdateMemberRequest(val role: String, val disabled: Boolean)
@Serializable
class CreatedMemberResponse(val member: MemberView, val temporaryPassword: String) {
    override fun toString() = "CreatedMemberResponse([REDACTED])"
}
@Serializable
data class SuccessResponse(val success: Boolean = true)
