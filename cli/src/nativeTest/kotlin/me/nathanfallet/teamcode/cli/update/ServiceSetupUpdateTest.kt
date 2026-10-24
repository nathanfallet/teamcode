package me.nathanfallet.teamcode.cli.update

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.service.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.*

/** Decision 10: the service step runs after the binary update, and its failure never undoes the update. */
class ServiceSetupUpdateTest {
    private val fs = FileSystem.SYSTEM

    private class RefusingSystem : LoginServiceSystem {
        val calls = mutableListOf<String>()
        override suspend fun inspect(settings: ServiceSettings): LoginStartup { calls += "inspect"; return LoginStartup(ownsRegistration(settings), false, false, false) }
        override suspend fun register(settings: ServiceSettings) { calls += "register"; writeRegistration(settings) }
        override suspend fun enable(settings: ServiceSettings) { calls += "enable"; throw CliktError("The OS service manager rejected the operation.") }
        override suspend fun start(settings: ServiceSettings) { calls += "start" }
    }

    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()

    @Test fun aServiceFailureDuringAnUpdateKeepsTheNewBinaryAndIsReported() = runTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / ("teamcode-service-update-" + secureRandomBytes(12).toByteString().hex())
        fs.createDirectories(root / "home")
        try {
            val canonical = fs.canonicalize(root)
            val store = InstallationStore(InstallationPaths(canonical / "install", "linux-x64"), verifier = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)))
            store.initialize()
            suspend fun stage(newer: Boolean) = store.stage(bytes(if (newer) ReleaseFixtures.newerPayload else ReleaseFixtures.validPayload),
                bytes(if (newer) ReleaseFixtures.newerEnvelope else ReleaseFixtures.validEnvelope)) { fs.write(it) { writeUtf8(ReleaseFixtures.binaryText) } }
            val first = stage(newer = false)
            store.ensureLauncher(first)
            store.writerLock().use { activateInstallation(store, first, false, null, { null }) }
            val second = stage(newer = true)
            // The update itself: the new version is selected.
            store.writerLock().use { activateInstallation(store, second, false, null, { null }) }

            val config = (canonical / "home" / ".config" / "teamcode" / "config.json").toString()
            val system = RefusingSystem()
            val setup = setUpService(config, store.paths.launcher.toString(), null, cloudRunner = { false },
                platform = { ServicePlatform("linux", store.paths.launcher.toString(), "501") }, home = { (canonical / "home").toString() }, system = system)

            assertEquals(ServiceSetupOutcome.FAILED, setup.outcome)
            assertTrue(setup.failed, "Printed on the error stream")
            assertTrue("teamcode service install" in setup.line, setup.line)
            assertEquals(listOf("inspect", "register", "enable"), system.calls)
            assertEquals(second, store.state()?.current, "The updated binary stays selected")
            assertEquals(first, store.state()?.previous)
            store.validate(second)
        } finally { if (fs.metadataOrNull(root) != null) fs.deleteRecursively(root) }
    }
}
