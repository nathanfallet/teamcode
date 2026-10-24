package me.nathanfallet.teamcode.cli.ui

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.teamcode.api.models.DesktopReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseAsset
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.update.ReleasePublicKey
import me.nathanfallet.teamcode.cli.update.VerifyReleaseManifest
import me.nathanfallet.teamcode.client.DownloadedReleaseMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.*

/**
 * `teamcode ui`'s installer against a fixture release: a desktop manifest signed with a key made for the test, real
 * archives of a fake app (tar on Linux's layout, ditto on macOS), and a source that can change one byte of a package.
 */
class DesktopInstallTest {
    private val fs = FileSystem.SYSTEM
    private val work = Files.createTempDirectory("teamcode-desktop-install-test").toFile()
    private val home = File(work, "home").apply { mkdirs() }
    private val temporary = File(work, "tmp").apply { mkdirs() }
    private val environment = mapOf("HOME" to home.path, "XDG_DATA_HOME" to File(home, ".local/share").path, "LOCALAPPDATA" to File(home, "AppData/Local").path)
    private val commands = mutableListOf<List<String>>()

    @AfterTest fun clean() { work.deleteRecursively() }

    private class Fixture(val manifest: ByteArray, val signature: ByteArray, val packages: Map<String, ByteArray>)
    private class FixtureSource(val fixture: Fixture, val tamper: Boolean = false) : DesktopSource {
        var downloads = 0
        override suspend fun metadata(version: String) = DownloadedReleaseMetadata(fixture.manifest, fixture.signature)
        override suspend fun download(version: String, target: String, asset: ReleaseAsset, destination: Path) {
            downloads++
            val bytes = fixture.packages.getValue(target).copyOf()
            if (tamper) bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            File(destination.toString()).writeBytes(bytes)
        }
    }

    private fun fixture(version: String, packages: Map<String, ByteArray>): Fixture {
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("product", "desktop"); put("version", version); put("tag", version); put("sourceCommit", "d".repeat(40))
            putJsonObject("assets") {
                for ((target, bytes) in packages) putJsonObject(target) {
                    put("fileName", DesktopReleaseFormat.fileName(version, target))
                    put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    put("size", bytes.size)
                }
            }
        }.toString().encodeToByteArray()
        val signature = Signature.getInstance("RSASSA-PSS").run {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initSign(keys.private); update(manifest); sign()
        }
        val envelope = buildJsonObject {
            put("schemaVersion", 1); put("algorithm", "RSA-PSS-SHA256"); put("keyId", trusted.id); put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString().encodeToByteArray()
        return Fixture(manifest, envelope, packages)
    }

    /** A fake app folder named [name] whose resources say [version], archived with [archive]. */
    private fun app(name: String, version: String, launcher: String, resources: String, archive: (File, File) -> List<String>): ByteArray {
        val source = Files.createTempDirectory(work.toPath(), "app").toFile()
        File(source, "$name/$launcher").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        File(source, "$name/$resources/teamcode-desktop-version").apply { parentFile.mkdirs(); writeText("$version\n") }
        val output = File(work, "package-${System.nanoTime()}")
        val process = ProcessBuilder(archive(source, output)).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
        return output.readBytes()
    }
    private fun linuxApp(version: String) = app("TeamCode", version, "bin/TeamCode", "lib/app/resources") { source, output ->
        listOf("tar", "-czf", output.path, "-C", source.path, "TeamCode")
    }
    private fun macApp(version: String) = app("TeamCode.app", version, "Contents/MacOS/TeamCode", "Contents/app/resources") { source, output ->
        listOf("ditto", "-c", "-k", "--keepParent", File(source, "TeamCode.app").path, output.path)
    }

    private val realRun: suspend (String, List<String>) -> Int = { command, arguments ->
        commands += listOf(command) + arguments
        ProcessBuilder(listOf(command) + arguments).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }

    private fun installer(os: String, target: String, source: DesktopSource, run: suspend (String, List<String>) -> Int = realRun, teamId: String? = "ABCDE12345") =
        DesktopInstaller(os, target, DesktopLocations(os, environment::get), source, temporary.toOkioPath(), run, VerifyReleaseManifest(listOf(trusted)), teamId, windowsTimeout = 200.milliseconds)

    private fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<CliOperationException> { runBlocking { block() } }.code)

    @Test fun linuxInstallsAVersionedFolderTheCurrentLinkAndAMenuEntryThenUpdates() = runBlocking {
        val first = installer("linux", "linux-x64", FixtureSource(fixture("1.2.3", mapOf("linux-x64" to linuxApp("1.2.3")))))
        val installed = first.install(first.release("1.2.3"))
        val root = File(home, ".local/share/teamcode-desktop")
        assertEquals("1.2.3", installed.version)
        assertEquals(File(root, "current").path, installed.root.toString())
        assertEquals("1.2.3", Files.readSymbolicLink(File(root, "current").toPath()).toString())
        val entry = File(home, ".local/share/applications/teamcode-desktop.desktop").readText()
        assertTrue("Exec=\"${File(root, "current/bin/TeamCode").path}\"" in entry, entry)
        assertEquals(listOf("1.2.3", "current"), root.list()!!.sorted(), "no staging folder is left behind")
        assertEquals(listOf<String>(), temporary.list()!!.toList(), "the download folder is removed")

        val second = installer("linux", "linux-x64", FixtureSource(fixture("1.3.0", mapOf("linux-x64" to linuxApp("1.3.0")))))
        assertEquals("1.3.0", second.install(second.release("1.3.0")).version)
        assertEquals("1.3.0", Files.readSymbolicLink(File(root, "current").toPath()).toString())
        // The previous version stays until the next update, so an app still running from it keeps its files.
        assertEquals(listOf("1.2.3", "1.3.0", "current"), root.list()!!.sorted())
        val third = installer("linux", "linux-x64", FixtureSource(fixture("1.4.0", mapOf("linux-x64" to linuxApp("1.4.0")))))
        third.install(third.release("1.4.0"))
        assertEquals(listOf("1.3.0", "1.4.0", "current"), root.list()!!.sorted())
    }

    @Test fun aModifiedByteIsRefusedBeforeThePackageIsOpenedAndChangesNothing() {
        val source = FixtureSource(fixture("1.2.3", mapOf("linux-x64" to linuxApp("1.2.3"))), tamper = true)
        val installer = installer("linux", "linux-x64", source)
        failure("release_binary_invalid") { installer.install(installer.release("1.2.3")) }
        assertEquals(1, source.downloads)
        assertTrue(commands.isEmpty(), "tar never ran: ${commands}")
        assertFalse(File(home, ".local/share/teamcode-desktop/current").exists())
        assertEquals(listOf<String>(), temporary.list()!!.toList())
    }

    @Test fun aManifestOfAnotherVersionOrWithoutThisSystemIsRefused() {
        val packages = mapOf("linux-x64" to linuxApp("1.2.3"))
        failure("release_version_mismatch") { installer("linux", "linux-x64", FixtureSource(fixture("1.2.3", packages))).release("1.2.4") }
        assertFailsWith<CliUserException> { runBlocking { installer("linux", "linux-arm64", FixtureSource(fixture("1.2.3", packages))).release("1.2.3") } }
        // A package whose app reports another version than the signed manifest is not installed.
        val mislabelled = installer("linux", "linux-x64", FixtureSource(fixture("1.2.3", mapOf("linux-x64" to linuxApp("9.9.9")))))
        failure("desktop_version_mismatch") { mislabelled.install(mislabelled.release("1.2.3")) }
        assertFalse(File(home, ".local/share/teamcode-desktop/current").exists())
    }

    @Test fun macosRefusesAnAppWithoutTheExpectedDeveloperIdAndKeepsThePreviousOne() {
        if (DesktopLocationsTest.os() != "macos") return
        val previous = File(home, "Applications/TeamCode.app/Contents/MacOS/TeamCode").apply { parentFile.mkdirs(); writeText("previous") }
        val unsigned = installer("macos", "macos-arm64", FixtureSource(fixture("1.2.3", mapOf("macos-arm64" to macApp("1.2.3")))))
        // The real codesign: this fixture app has no Developer ID signature.
        failure("desktop_signature_invalid") { unsigned.install(unsigned.release("1.2.3")) }
        val check = commands.single { it.first() == "codesign" }
        assertEquals(listOf("--verify", "--deep", "--strict", "-R"), check.subList(1, 5))
        assertTrue(check[5].startsWith("=anchor apple generic and ") && check[5].endsWith("certificate leaf[subject.OU] = \"ABCDE12345\""), check[5])
        assertEquals("previous", previous.readText())
        assertEquals(listOf("TeamCode.app"), File(home, "Applications").list()!!.toList())
    }

    @Test fun macosReplacesThePreviousAppOnceTheSignatureIsAccepted() = runBlocking {
        if (DesktopLocationsTest.os() != "macos") return@runBlocking
        File(home, "Applications/TeamCode.app/Contents/MacOS/TeamCode").apply { parentFile.mkdirs(); writeText("previous") }
        val accepted: suspend (String, List<String>) -> Int = { command, arguments -> if (command == "codesign") 0 else realRun(command, arguments) }
        val installer = installer("macos", "macos-arm64", FixtureSource(fixture("1.2.3", mapOf("macos-arm64" to macApp("1.2.3")))), accepted)
        val installed = installer.install(installer.release("1.2.3"))
        assertEquals("1.2.3", installed.version)
        assertEquals("#!/bin/sh\n", File(home, "Applications/TeamCode.app/Contents/MacOS/TeamCode").readText())
        assertEquals(listOf("TeamCode.app"), File(home, "Applications").list()!!.toList(), "the previous app and the staging folder are gone")
    }

    @Test fun macosWithoutACompiledTeamIdPointsAtTheReleasePage() {
        val installer = installer("macos", "macos-arm64", FixtureSource(fixture("1.2.3", mapOf("macos-arm64" to "zip".encodeToByteArray()))), teamId = null)
        val error = assertFailsWith<CliUserException> { runBlocking { installer.install(installer.release("1.2.3")) } }
        assertTrue("releases/tag/1.2.3" in error.message.orEmpty())
        assertTrue(commands.isEmpty())
    }

    @Test fun windowsRunsThePerUserPackageAndChecksTheInstalledVersion() = runBlocking {
        val root = File(home, "AppData/Local/TeamCode App")
        val msiexec: suspend (String, List<String>) -> Int = { command, arguments ->
            commands += listOf(command) + arguments
            File(root, "TeamCode.exe").apply { parentFile.mkdirs(); writeText("exe") }
            File(root, "app/resources/teamcode-desktop-version").apply { parentFile.mkdirs(); writeText("1.2.3\n") }
            3010
        }
        val installer = installer("windows", "windows-x64", FixtureSource(fixture("1.2.3", mapOf("windows-x64" to "msi bytes".encodeToByteArray()))), msiexec)
        assertEquals("1.2.3", installer.install(installer.release("1.2.3")).version)
        val command = commands.single()
        assertEquals("msiexec.exe", command[0])
        assertEquals(listOf("/i"), command.subList(1, 2))
        assertTrue(command[2].endsWith("teamcode-desktop-1.2.3-windows-x64.msi"))
        assertEquals(listOf("/qn", "/norestart", "/l*v"), command.subList(3, 6))
        assertTrue(command[6].endsWith("teamcode-desktop-msiexec.log"))
        assertEquals("MSIINSTALLPERUSER=1", command[7])
        val failing = installer("windows", "windows-x64", FixtureSource(fixture("1.2.3", mapOf("windows-x64" to "msi bytes".encodeToByteArray()))), { _, _ -> 1603 })
        failure("desktop_msiexec_failed_1603") { failing.install(failing.release("1.2.3")) }
    }

    @Test fun msiexecArgumentsAreQuietOnlyWithoutAPersonAndAlwaysLogged() {
        val msi = "C:/tmp/app.msi".toPath()
        val log = "C:/tmp/app.log".toPath()
        assertEquals(listOf("/i", msi.toString(), "/qn", "/norestart", "/l*v", log.toString(), "MSIINSTALLPERUSER=1"), msiexecArguments(msi, log, quiet = true))
        assertEquals("/qb", msiexecArguments(msi, log, quiet = false)[2])
    }

    @Test fun anInstallerThatNeverReturnsIsStoppedAndReported() = runBlocking {
        val hanging = installer("windows", "windows-x64", FixtureSource(fixture("1.2.3", mapOf("windows-x64" to "msi bytes".encodeToByteArray()))), { _, _ -> awaitCancellation() })
        failure("desktop_msiexec_timeout") { hanging.install(hanging.release("1.2.3")) }
    }

    @Test fun uiUsesAnAppOfThisVersionOrNewerAndAsksBeforeInstalling() = runBlocking {
        val locations = DesktopLocations("linux", environment::get)
        val source = FixtureSource(fixture("1.2.3", mapOf("linux-x64" to linuxApp("1.2.3"))))
        val installer = installer("linux", "linux-x64", source)
        val questions = mutableListOf<String>()
        assertNull(DesktopUi("1.2.3", locations, installer, {}) { questions += it; false }.prepare())
        assertEquals(listOf("Install TeamCode Desktop 1.2.3 (less than 1 MB)?"), questions)
        assertEquals(0, source.downloads)
        assertEquals("1.2.3", DesktopUi("1.2.3", locations, installer, {}) { true }.prepare()?.version)
        assertEquals(1, source.downloads)
        // Already there, and an older CLI uses the newer app as is: nothing is downloaded or asked.
        assertEquals("1.2.3", DesktopUi("1.2.3", locations, installer, {}) { error("must not ask") }.prepare()?.version)
        assertEquals("1.2.3", DesktopUi("1.2.0", locations, installer, {}) { error("must not ask") }.prepare()?.version)
        assertEquals(1, source.downloads)
        // A newer CLI offers to update the older app.
        questions.clear()
        val newer = installer("linux", "linux-x64", FixtureSource(fixture("1.3.0", mapOf("linux-x64" to linuxApp("1.3.0")))))
        assertNull(DesktopUi("1.3.0", locations, newer, {}) { questions += it; false }.prepare())
        assertEquals(listOf("TeamCode Desktop 1.2.3 is older than this CLI. Update it to 1.3.0 (less than 1 MB)?"), questions)
    }

    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        private val trusted = Base64.getEncoder().encodeToString(keys.public.encoded).let { ReleasePublicKey(it, it) }
    }
}

class DesktopLocationsTest {
    @Test fun eachSystemHasItsFoldersVersionFileAndLauncher() {
        val env = mapOf("HOME" to "/home/ada", "LOCALAPPDATA" to "C:\\Users\\ada\\AppData\\Local")
        val mac = DesktopLocations("macos", env::get)
        assertEquals(listOf("/home/ada/Applications/TeamCode.app", "/Applications/TeamCode.app"), mac.candidates().map { it.toString() })
        assertEquals("/home/ada/Applications/TeamCode.app/Contents/app/resources/teamcode-desktop-version", mac.versionFile(mac.candidates()[0]).toString())
        assertEquals("/home/ada/Applications/TeamCode.app/Contents/MacOS/TeamCode", mac.executable(mac.candidates()[0]).toString())
        val linux = DesktopLocations("linux", env::get)
        assertEquals(listOf("/home/ada/.local/share/teamcode-desktop/current", "/opt/teamcode"), linux.candidates().map { it.toString() })
        assertEquals("/data/teamcode-desktop/current", DesktopLocations("linux", (env + ("XDG_DATA_HOME" to "/data"))::get).candidates()[0].toString())
        assertEquals("/home/ada/.local/share/teamcode-desktop", DesktopLocations("linux", (env + ("XDG_DATA_HOME" to "relative"))::get).linuxRoot.toString())
        val app = DesktopInstallation(okio.Path.Companion.run { "/Applications/TeamCode.app".toPath() }, "1.2.3", okio.Path.Companion.run { "/x".toPath() })
        assertEquals(listOf("open", "/Applications/TeamCode.app", "--args", "--config-path", "/c.json"), desktopLaunchCommand("macos", app, listOf("--config-path", "/c.json")))
        assertEquals(listOf("/x", "--directory", "/p"), desktopLaunchCommand("linux", app, listOf("--directory", "/p")))
        assertTrue(app.usableWith("1.2.3") && app.usableWith("1.2.3-beta.1") && !app.usableWith("1.2.4"))
        assertFalse(app.copy(version = null).usableWith("0.0.1"))
    }

    @Test fun theLinuxMenuEntryQuotesItsLauncher() {
        val entry = linuxDesktopEntry(okio.Path.Companion.run { "/home/a \"b\"/teamcode-desktop".toPath() })
        assertTrue("Exec=\"/home/a \\\"b\\\"/teamcode-desktop/current/bin/TeamCode\"\n" in entry, entry)
        assertTrue(entry.startsWith("[Desktop Entry]\nType=Application\nName=TeamCode\n"))
    }

    companion object {
        fun os(): String = System.getProperty("os.name").orEmpty().lowercase().let { if (it.startsWith("mac")) "macos" else if (it.startsWith("windows")) "windows" else "linux" }
    }
}
