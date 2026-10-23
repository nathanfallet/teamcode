package me.nathanfallet.teamcode.cli.update

import me.nathanfallet.teamcode.api.models.DesktopReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.cli.CliOperationException
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import kotlin.test.*

class DesktopReleaseVerificationTest {
    private val verifier = VerifyReleaseManifest(listOf(DesktopReleaseFixtures.key))
    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private fun failure(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<CliOperationException>(block = block).code)

    @Test fun desktopManifestNamesOnePackagePerTargetAndOnlyTheirExactBytesPass() {
        val release = verifier.verifyDesktop(bytes(DesktopReleaseFixtures.validPayload), bytes(DesktopReleaseFixtures.validEnvelope))
        assertEquals("1.2.3", release.manifest.version)
        assertEquals("teamcode-desktop-1.2.3-macos-arm64.zip", release.manifest.assets.getValue("macos-arm64").fileName)
        assertEquals("teamcode-desktop-1.2.3-windows-x64.msi", release.manifest.assets.getValue("windows-x64").fileName)
        assertEquals("teamcode-desktop-1.2.3-linux-x64.tar.gz", release.manifest.assets.getValue("linux-x64").fileName)
        for (target in ReleaseFormat.targets) {
            assertEquals(DesktopReleaseFormat.fileName("1.2.3", target), release.manifest.assets.getValue(target).fileName)
            release.verifyBinary(Buffer().writeUtf8(DesktopReleaseFixtures.packageText), target)
        }
        failure("release_binary_invalid") { release.verifyBinary(Buffer().writeUtf8("X" + DesktopReleaseFixtures.packageText.drop(1)), "linux-x64") }
        failure("release_binary_invalid") { release.verifyBinary(Buffer().writeUtf8(DesktopReleaseFixtures.packageText + "!"), "macos-arm64") }
    }

    @Test fun neitherManifestStandsInForTheOther() {
        // A CLI manifest signed by the same key is not a desktop manifest, and the other way round.
        failure("release_manifest_invalid") { verifier.verifyDesktop(bytes(DesktopReleaseFixtures.cliPayload), bytes(DesktopReleaseFixtures.cliEnvelope)) }
        failure("release_manifest_invalid") { verifier.verify(bytes(DesktopReleaseFixtures.validPayload), bytes(DesktopReleaseFixtures.validEnvelope)) }
        assertEquals("1.2.3", verifier.verify(bytes(DesktopReleaseFixtures.cliPayload), bytes(DesktopReleaseFixtures.cliEnvelope)).manifest.version)
        failure("release_manifest_invalid") { verifier.verifyDesktop(bytes(DesktopReleaseFixtures.wrongProductPayload), bytes(DesktopReleaseFixtures.wrongProductEnvelope)) }
        failure("release_manifest_invalid") { verifier.verifyDesktop(bytes(DesktopReleaseFixtures.cliNamesPayload), bytes(DesktopReleaseFixtures.cliNamesEnvelope)) }
    }

    @Test fun aModifiedByteOrAnotherKeyIsRefused() {
        val payload = bytes(DesktopReleaseFixtures.validPayload).also { it[10] = (it[10].toInt() xor 1).toByte() }
        failure("release_signature_invalid") { verifier.verifyDesktop(payload, bytes(DesktopReleaseFixtures.validEnvelope)) }
        failure("release_key_untrusted") {
            VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)).verifyDesktop(bytes(DesktopReleaseFixtures.validPayload), bytes(DesktopReleaseFixtures.validEnvelope))
        }
    }
}
