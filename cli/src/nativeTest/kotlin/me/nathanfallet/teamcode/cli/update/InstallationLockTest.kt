package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.*

class InstallationLockTest {
    @Test fun nativeReadersShareWhileAnUpdaterRequiresExclusiveAccess() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-native-lock-" + secureRandomBytes(12).toByteString().hex())
        val store = InstallationStore(InstallationPaths(root, installationTarget()))
        try {
            store.initialize()
            store.lock(exclusive = false).use {
                validatePrivateInstallationPath(store.paths.lock.toString(), directory = false)
                store.lock(exclusive = false).use {
                    assertNull(acquireInstallationLock(store.paths.lock.toString(), exclusive = true))
                }
                assertNull(acquireInstallationLock(store.paths.lock.toString(), exclusive = true))
            }
            store.lock(exclusive = true).use {
                assertNull(acquireInstallationLock(store.paths.lock.toString(), exclusive = false))
                assertNull(acquireInstallationLock(store.paths.lock.toString(), exclusive = true))
            }
            store.lock(exclusive = false).close()
            assertTrue(fs.metadata(store.paths.lock).isRegularFile)
        } finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }
}
