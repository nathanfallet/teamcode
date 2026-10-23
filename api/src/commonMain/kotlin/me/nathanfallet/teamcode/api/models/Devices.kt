package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceView(
    val id: String, val memberId: String, val name: String,
    val createdAt: Long, val lastLoginAt: Long, val activeSessions: Int, val current: Boolean,
    /** `macos`, `windows`, `linux`, `ios` or `android`, as reported at the installation's latest login; null until one did. */
    val platform: String? = null, val osVersion: String? = null,
)
@Serializable
data class AuthSessionView(
    val id: String, val memberId: String, val name: String, val deviceId: String?,
    val createdAt: Long?, val expiresAt: Long, val current: Boolean,
)
@Serializable
data class RenameDeviceRequest(val name: String)
