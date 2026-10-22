package me.nathanfallet.teamcode.companion.domain.models

data class Member(
    val id: String,
    val username: String,
    val role: String,
    val passwordChangeRequired: Boolean,
)

/** A successful password login: a revocable token and the member it belongs to. */
class LoginSession(val accessToken: String, val expiresAt: Long, val member: Member) {
    override fun toString() = "LoginSession([REDACTED])"
}
