package me.nathanfallet.teamcode.companion.domain.services

/**
 * Where access tokens live, keyed by account id: the Keychain on iOS, an Android Keystore key on Android, the
 * operating system's keyring on the desktop. Never a plain file or a preference.
 */
interface CredentialStore {
    /** False when this device has no usable secure store: tokens are then kept in memory only, until the app quits. */
    val persistent: Boolean
    suspend fun read(accountId: String): String?
    suspend fun write(accountId: String, token: String)
    suspend fun delete(accountId: String)
}
