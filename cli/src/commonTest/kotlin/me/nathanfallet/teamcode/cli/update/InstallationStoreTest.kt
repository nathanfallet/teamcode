package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.api.Serialization
import okio.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import kotlin.test.*

class InstallationStoreTest {
    private val fs = FileSystem.SYSTEM
    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private suspend fun scratch(block: suspend (InstallationStore) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-install é " + secureRandomBytes(12).toByteString().hex())
        val store = InstallationStore(InstallationPaths(root, "linux-x64"), verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)))
        try { store.initialize(); block(store) }
        finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }
    private suspend fun stage(store: InstallationStore, newer: Boolean = false): InstalledVersion =
        store.stage(bytes(if (newer) ReleaseFixtures.newerPayload else ReleaseFixtures.validPayload),
            bytes(if (newer) ReleaseFixtures.newerEnvelope else ReleaseFixtures.validEnvelope)) { path ->
            fs.write(path) { writeUtf8(ReleaseFixtures.binaryText) }
        }

    @Test fun onlyAuthenticatedExactBytesCanBecomeAPublishedImmutableVersion() = runTest {
        scratch { store ->
            var wrote = false
            assertFailsWith<CliOperationException> { store.stage("untrusted".encodeToByteArray(), bytes(ReleaseFixtures.validEnvelope)) { wrote = true } }
            assertFalse(wrote)
            assertNull(store.state())
            assertFailsWith<CliOperationException> { store.stage(bytes(ReleaseFixtures.validPayload), bytes(ReleaseFixtures.validEnvelope)) { path -> fs.write(path) { writeUtf8("wrong binary") } } }
            assertTrue(fs.list(store.paths.root).none { it.name.startsWith(".staging-") })
            val first = stage(store)
            store.validate(first)
            assertEquals(first, stage(store), "An identical staged release must reuse verified immutable bytes")
            store.ensureLauncher(first)
            val launcher = fs.read(store.paths.launcher) { readByteArray() }
            store.ensureLauncher(first)
            assertContentEquals(launcher, fs.read(store.paths.launcher) { readByteArray() })
            assertEquals(first, store.launcherVersion())
            assertNull(store.state(), "Staging must not activate a release")
        }
    }

    @Test fun updateAndOfflineRollbackKeepTheOriginalLauncherAndBothPayloads() = runTest {
        scratch { store ->
            store.lock(exclusive = true).use {
                val first = stage(store)
                store.ensureLauncher(first)
                store.activate(first)
                val second = stage(store, newer = true)
                assertNotEquals(first.manifestSha256, second.manifestSha256)
                assertEquals(first.binarySha256, second.binarySha256, "Distinct signed versions may contain identical payload bytes")
                assertNotEquals(store.paths.binary(first), store.paths.binary(second))
                store.ensureLauncher(second)
                assertEquals(first, store.launcherVersion(), "The running launcher must never be overwritten")
                store.activate(second)
                assertEquals(InstallationState(current = second, previous = first), store.state())
                store.activate(first, rollback = true)
                assertEquals(InstallationState(current = first, previous = second), store.state())
                store.validate(first); store.validate(second)
            }
        }
    }

    @Test fun failedPreparationAndStartupRestoreThePreviousSelectionBeforeRecovery() = runTest {
        scratch { store ->
            val first = stage(store)
            val second = stage(store, newer = true)
            store.ensureLauncher(first); store.activate(first)
            val before = store.state()
            for (failPrepare in listOf(true, false)) {
                var recovered = false
                assertFailsWith<IllegalStateException> {
                    store.activate(second,
                        prepare = { if (failPrepare) error("fixture preparation failure") },
                        start = { assertEquals(second, store.state()?.current); error("fixture startup failure") },
                        recover = { assertEquals(before, store.state()); recovered = true })
                }
                assertEquals(before, store.state())
                assertTrue(recovered)
                store.validate(first); store.validate(second)
            }
        }
    }

    @Test fun cancellationDuringActivationRestoresStateAndStillCancelsTheCaller() = runTest {
        scratch { store ->
            val first = stage(store)
            val second = stage(store, newer = true)
            store.ensureLauncher(first); store.activate(first)
            var recovered = false
            assertFailsWith<CancellationException> {
                store.activate(second, start = { throw CancellationException("fixture cancellation") }, recover = { recovered = true })
            }
            assertTrue(recovered)
            assertEquals(first, store.state()?.current)
        }
    }

    @Test fun corruptionAndInvalidRollbackFailBeforeServiceOrSelectionChanges() = runTest {
        scratch { store ->
            val first = stage(store)
            val second = stage(store, newer = true)
            store.ensureLauncher(first); store.activate(first)
            assertFailsWith<CliOperationException> { store.activate(second, rollback = true) }
            fs.write(store.paths.binary(second)) { writeUtf8("corrupted") }
            var prepared = false
            assertFailsWith<CliOperationException> { store.activate(second, prepare = { prepared = true }) }
            assertFalse(prepared)
            assertEquals(first, store.state()?.current)
            fs.write(store.paths.directory(first) / "manifest.json") { writeUtf8("{}") }
            assertFailsWith<CliOperationException> { store.validate(first) }
        }
    }

    @Test fun competingWritersAreRejectedAndClosingALockPreservesItsPersistentFile() = runTest {
        scratch { store ->
            store.lock(exclusive = true).use {
                val error = assertFailsWith<CliOperationException> { store.lock(exclusive = true) }
                assertEquals("release_installation_busy", error.code)
            }
            assertTrue(fs.metadata(store.paths.lock).isRegularFile)
            store.lock(exclusive = true).close()
        }
    }

    @Test fun stagingUsesTheSameAuthenticatedSnapshotEvenIfItsCallerMutatesInputBuffers() = runTest {
        scratch { store ->
            val payload = bytes(ReleaseFixtures.validPayload)
            val signature = bytes(ReleaseFixtures.validEnvelope)
            val original = payload.copyOf()
            val staged = store.stage(payload, signature) { path ->
                payload.fill(0)
                signature.fill(0)
                fs.write(path) { writeUtf8(ReleaseFixtures.binaryText) }
            }
            store.validate(staged)
            assertContentEquals(original, fs.read(store.paths.directory(staged) / "manifest.json") { readByteArray() })
        }
    }

    @Test fun aPersistedInterruptedTransactionRestoresTheLastWorkingVersionOffline() = runTest {
        scratch { store ->
            val first = stage(store)
            val second = stage(store, newer = true)
            store.ensureLauncher(first); store.activate(first)
            val before = requireNotNull(store.state())
            val after = InstallationState(current = second, previous = first)
            val pending = InstallationJournal(before = before, after = after)
            // This is the disk state after selection but before startup commits, including a process exit.
            writePrivateFile(store.paths.journal.toString(), Serialization.json.encodeToString(InstallationJournal.serializer(), pending))
            writePrivateFile(store.paths.state.toString(), Serialization.json.encodeToString(InstallationState.serializer(), after))
            var recovered = false
            assertTrue(store.recoverInterrupted { journal ->
                assertEquals(pending, journal)
                assertEquals(before, store.state())
                recovered = true
            })
            assertTrue(recovered)
            assertEquals(before, store.state())
            assertNull(store.journal())
            assertFalse(store.recoverInterrupted())
        }
    }

    @Test fun failedRecoveryKeepsAJournalForRetryInsteadOfClaimingAHealthyInstallation() = runTest {
        scratch { store ->
            val first = stage(store)
            val second = stage(store, newer = true)
            store.ensureLauncher(first); store.activate(first)
            assertFailsWith<CliOperationException> {
                store.activate(second, start = { error("fixture startup failure") }, recover = { error("fixture recovery failure") })
            }
            assertEquals(first, store.state()?.current)
            assertNotNull(store.journal())
            assertFailsWith<CliOperationException> { store.activate(second) }
            assertTrue(store.recoverInterrupted())
            assertNull(store.journal())
        }
    }
}
