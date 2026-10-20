package me.nathanfallet.teamcode.cli.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.*

class PeriodicReleaseCheckTest {
    private val fs = FileSystem.SYSTEM
    private val verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey))
    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private fun release(): ReleaseCheck {
        val metadata = DownloadedReleaseMetadata(bytes(ReleaseFixtures.validPayload), bytes(ReleaseFixtures.validEnvelope))
        return ReleaseCheck("1.0.0", verifier.verify(metadata.manifest, metadata.signature), metadata)
    }
    private suspend fun scratch(block: suspend (InstallationPaths) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-update-cache é " + secureRandomBytes(12).toByteString().hex())
        val paths = InstallationPaths(root, "linux-x64")
        try { InstallationStore(paths).initialize(); block(paths) }
        finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }

    @Test fun authenticatedChecksAreSharedAcrossProcessesAndExpireDaily() = runTest {
        scratch { paths ->
            var instant = 2 * PeriodicReleaseCheck.DAY_MILLIS
            var requests = 0
            fun checker() = PeriodicReleaseCheck(paths, "1.0.0", verifier) { instant }
            suspend fun fetch() = release().also { requests++ }
            assertTrue(assertNotNull(checker().check { fetch() }).updateAvailable)
            instant += PeriodicReleaseCheck.DAY_MILLIS - 1
            assertTrue(assertNotNull(checker().check { fetch() }).updateAvailable)
            assertEquals(1, requests, "A new checker/process must reuse authenticated persisted metadata")
            instant++
            checker().check { fetch() }
            assertEquals(2, requests)
            instant--
            checker().check { fetch() }
            assertEquals(3, requests, "A backwards clock must not suppress checks indefinitely")
        }
    }

    @Test fun disabledNotificationsDoNoNetworkOrCacheWorkAndCanBeReenabled() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            assertTrue(checks.notificationsEnabled())
            checks.setNotifications(false)
            assertFalse(PeriodicReleaseCheck(paths, "1.0.0", verifier).notificationsEnabled())
            assertNull(checks.check { error("Disabled checks must not fetch") })
            assertFalse(fs.exists(paths.root / "update-check.lock"))
            assertFalse(fs.exists(paths.root / "update-check.json"))
            checks.setNotifications(true)
            assertTrue(assertNotNull(checks.check { release() }).updateAvailable)
            // A malformed local policy fails closed until explicitly replaced.
            writePrivateFile((paths.root / "update-settings.json").toString(), "INVALID")
            assertFalse(checks.notificationsEnabled())
            assertNull(checks.check { error("Invalid policy must not enable checks") })
        }
    }

    @Test fun concurrentWatchersDoNotFetchTwiceOrBlockEachOther() = runTest {
        scratch { paths ->
            var otherRequests = 0
            val first = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            val second = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            assertNotNull(first.check {
                assertNull(second.check { otherRequests++; release() })
                release()
            })
            assertEquals(0, otherRequests)
            assertNotNull(second.check { otherRequests++; release() })
            assertEquals(0, otherRequests)
        }
    }

    @Test fun failuresRetainOnlyPreviouslyAuthenticatedMetadataAndAreThrottled() = runTest {
        scratch { paths ->
            var instant = PeriodicReleaseCheck.DAY_MILLIS
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier) { instant }
            val first = assertNotNull(checks.check { release() })
            instant += PeriodicReleaseCheck.DAY_MILLIS
            val reports = mutableListOf<String>()
            val offline = assertNotNull(checks.check(reports::add) { error("DO_NOT_LOG_REMOTE_BODY") })
            assertEquals(first.release.manifest, offline.release.manifest)
            assertEquals(listOf("Release check unavailable; continuing with the current installation."), reports)
            assertNotNull(checks.check { error("A failed request must also be throttled") })
        }
    }

    @Test fun cacheTamperingAndRetiredRootsCannotCreateUpdateNotifications() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            assertNotNull(checks.check { release() })
            val cache = paths.root / "update-check.json"
            val original = Json.parseToJsonElement(fs.read(cache) { readUtf8() }).jsonObject
            writePrivateFile(cache.toString(), JsonObject(original + ("manifest" to JsonPrimitive("e30="))).toString())
            var fetched = false
            assertNull(checks.check { fetched = true; null })
            assertTrue(fetched, "An invalid signature cannot make a fresh cache authoritative")
            fs.delete(cache)
            assertNotNull(checks.check { release() })
            val retired = PeriodicReleaseCheck(paths, "1.0.0", VerifyReleaseManifest(listOf(ReleaseFixtures.secondKey)))
            assertNull(retired.check { null })
        }
    }

    @Test fun cancellationReleasesTheOsLockAndNeverWritesASuccessfulCheck() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            assertFailsWith<CancellationException> { checks.check { throw CancellationException("fixture") } }
            assertFalse(fs.exists(paths.root / "update-check.json"))
            assertNotNull(checks.check { release() })
        }
    }

    @Test fun forgedFetchedBytesNeverBecomeTrustedCacheMetadata() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            val valid = release()
            val forged = valid.copy(metadata = DownloadedReleaseMetadata("UNSIGNED".encodeToByteArray(), valid.metadata.signature))
            assertNull(checks.check { forged })
            assertNull(checks.check { error("A rejected check must be throttled without caching forged metadata") })
            val stored = fs.read(paths.root / "update-check.json") { readUtf8() }
            assertFalse(stored.contains("VU5TSUdORUQ="))
        }
    }

    @Test fun anOwnedTimeoutAndAnAbsentStableReleaseAreBoundedAndCached() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            val reports = mutableListOf<String>()
            assertNull(checks.check(reports::add) { delay(6_000); release() })
            assertEquals(1, reports.size)
            assertNull(checks.check { error("Timed-out checks must be throttled") })
            fs.delete(paths.root / "update-check.json")
            assertNull(checks.check { null })
            assertNull(checks.check { error("An absent stable release must be cached") })
        }
    }

    @Test fun explicitInstallationChecksAreIndependentOfNotificationsButRequireASuccessfulObservation() = runTest {
        scratch { paths ->
            var instant = PeriodicReleaseCheck.DAY_MILLIS
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier) { instant }
            checks.setNotifications(false)
            assertNotNull(checks.check(forInstallation = true) { release() })
            assertNull(checks.check { error("Notifications remain disabled") })
            instant += PeriodicReleaseCheck.DAY_MILLIS
            assertNull(checks.check(forInstallation = true) { error("DO_NOT_LOG_REMOTE_BODY") })
            assertNull(checks.check(forInstallation = true) { error("Failed checks remain throttled") })
            checks.setNotifications(true)
            assertNotNull(checks.check { error("An informational notification may retain authenticated old metadata") })
            instant += PeriodicReleaseCheck.DAY_MILLIS
            assertNotNull(checks.check(forInstallation = true) { release() })
        }
    }

    @Test fun legacyCacheWithoutSuccessfulObservationCannotAuthorizeAutomaticInstallation() = runTest {
        scratch { paths ->
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier)
            assertNotNull(checks.check { release() })
            val path = paths.root / "update-check.json"
            val value = Json.parseToJsonElement(fs.read(path) { readUtf8() }).jsonObject
            writePrivateFile(path.toString(), JsonObject(value - "successfulObservation").toString())
            assertNull(checks.check(forInstallation = true) { error("Legacy observations are throttled, not promoted") })
            assertNotNull(checks.check { error("Signature-authenticated notifications remain supported") })
        }
    }

    @Test fun aConcurrentRefreshCannotAuthorizeInstallationFromExpiredOrFutureDatedMetadata() = runTest {
        scratch { paths ->
            var instant = 2 * PeriodicReleaseCheck.DAY_MILLIS
            val checks = PeriodicReleaseCheck(paths, "1.0.0", verifier) { instant }
            assertNotNull(checks.check(forInstallation = true) { release() })
            assertNotNull(acquireInstallationLock((paths.root / "update-check.lock").toString(), exclusive = true)).use {
                assertNotNull(checks.check(forInstallation = true) { error("Another refresher owns the lock") })
                instant += PeriodicReleaseCheck.DAY_MILLIS
                assertNull(checks.check(forInstallation = true) { error("Do not install an expired observation while refresh is busy") })
                assertNotNull(checks.check { error("Old signed metadata remains informational") })
                instant = PeriodicReleaseCheck.DAY_MILLIS
                assertNull(checks.check(forInstallation = true) { error("A backwards clock does not make a future observation fresh") })
            }
            assertNotNull(checks.check(forInstallation = true) { release() })
        }
    }
}
