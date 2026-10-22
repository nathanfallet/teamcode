package me.nathanfallet.teamcode.companion.infrastructure.credentials

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import me.nathanfallet.teamcode.companion.domain.services.CredentialStore
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Tokens in the iOS Keychain: generic passwords of service `me.nathanfallet.teamcode`, account = the account id,
 * readable after the first unlock and on this device only (no iCloud Keychain sync, no restore to another device).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainCredentialStore(private val service: String = SERVICE) : CredentialStore {
    override val persistent = true

    override suspend fun read(accountId: String): String? = withQuery(accountId, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            when (status) {
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
                errSecItemNotFound -> null
                else -> throw IllegalStateException("The Keychain refused to read the token ($status).")
            }
        }
    }

    override suspend fun write(accountId: String, token: String) {
        val data = CFBridgingRetain(token.nsData())
        try {
            val updated = withQuery(accountId) { query ->
                withDictionary(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { attributes ->
                    SecItemUpdate(query, attributes)
                }
            }
            val status = if (updated == errSecItemNotFound) {
                withQuery(accountId, kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { SecItemAdd(it, null) }
            } else updated
            check(status == errSecSuccess) { "The Keychain refused to store the token ($status)." }
        } finally {
            CFRelease(data)
        }
    }

    override suspend fun delete(accountId: String) {
        val status = withQuery(accountId) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "The Keychain refused to delete the token ($status)." }
    }

    /**
     * Removes every token of the service. Keychain items survive the app's removal while its settings do not: on a
     * reinstall, the old items belong to accounts nothing refers to any more.
     */
    fun deleteAll() {
        val serviceRef = CFBridgingRetain(service.nsString())
        try {
            val status = withDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef) { SecItemDelete(it) }
            check(status == errSecSuccess || status == errSecItemNotFound) { "The Keychain refused to delete the tokens ($status)." }
        } finally {
            CFRelease(serviceRef)
        }
    }

    private fun <T> withQuery(accountId: String, vararg extra: Pair<CFTypeRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val serviceRef = CFBridgingRetain(service.nsString())
        val accountRef = CFBridgingRetain(accountId.nsString())
        try {
            return withDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef, *extra, block = block)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    private fun <T> withDictionary(vararg entries: Pair<CFTypeRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, entries.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            for ((key, value) in entries) CFDictionaryAddValue(dictionary, key, value)
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    companion object {
        const val SERVICE = "me.nathanfallet.teamcode"
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.nsString(): NSString = this as NSString

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.nsData(): NSData = (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
