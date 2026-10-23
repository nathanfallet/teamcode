package me.nathanfallet.teamcode.companion.infrastructure.system

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import me.nathanfallet.teamcode.companion.domain.services.SecureRandom
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** `SecRandomCopyBytes`, the system's cryptographically secure generator. */
@OptIn(ExperimentalForeignApi::class)
object IosSecureRandom : SecureRandom {
    override fun nextBytes(count: Int): ByteArray {
        require(count > 0)
        val bytes = ByteArray(count)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "This device could not generate a secure random value." }
        return bytes
    }
}
