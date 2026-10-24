package me.nathanfallet.teamcode.cli.update

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.ReleaseFormat
import me.nathanfallet.teamcode.cli.CliOperationException
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import kotlin.test.*

class ReleaseVerificationTest {
    private val first = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey))
    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private fun envelope(value: String, name: String, field: JsonElement): ByteArray {
        val original = Json.parseToJsonElement(bytes(value).decodeToString()).jsonObject
        return JsonObject(original + (name to field)).toString().encodeToByteArray()
    }
    private fun failure(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<CliOperationException>(block = block).code)
    private fun valid() = first.verify(bytes(ReleaseFixtures.validPayload), bytes(ReleaseFixtures.validEnvelope))

    @Test fun authenticManifestOnlyApprovesMatchingBinaryBytesForTheSelectedTarget() {
        val release = valid()
        assertEquals("1.2.3-alpha.1", release.manifest.version)
        assertEquals(release.manifest.version, release.manifest.tag)
        assertEquals(ReleaseFixtures.firstKey.id, release.keyId)
        for (target in ReleaseFormat.targets) release.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText), target)
        failure("release_binary_invalid") { release.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText + "extra"), "linux-x64") }
        failure("release_binary_invalid") { release.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText.dropLast(1)), "linux-x64") }
        failure("release_binary_invalid") { release.verifyBinary(Buffer().writeUtf8("X" + ReleaseFixtures.binaryText.drop(1)), "linux-x64") }
        failure("release_target_unsupported") { release.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText), "../../escape") }
    }
    @Test fun authenticationPrecedesPayloadParsingAndMalformedAuthenticatedPayloadsStillFail() {
        failure("release_signature_invalid") { first.verify("not JSON".encodeToByteArray(), bytes(ReleaseFixtures.validEnvelope)) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.nonJsonPayload), bytes(ReleaseFixtures.nonJsonEnvelope)) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.prefixedTagPayload), bytes(ReleaseFixtures.prefixedTagEnvelope)) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.wrongSchemaPayload), bytes(ReleaseFixtures.wrongSchemaEnvelope)) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.unsafePathPayload), bytes(ReleaseFixtures.unsafePathEnvelope)) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.oversizedAssetPayload), bytes(ReleaseFixtures.oversizedAssetEnvelope)) }
    }
    @Test fun modifiedPayloadsSignaturesEnvelopesAndUnknownRootsAreRejected() {
        val data = bytes(ReleaseFixtures.validPayload).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        failure("release_signature_invalid") { first.verify(data, bytes(ReleaseFixtures.validEnvelope)) }
        for ((name, value) in listOf("algorithm" to JsonPrimitive("RSA-NONE"), "schemaVersion" to JsonPrimitive(2), "schemaVersion" to JsonPrimitive("1"), "extra" to JsonPrimitive("unsupported"), "signature" to JsonPrimitive("AAAA"))) {
            failure("release_signature_invalid") { first.verify(bytes(ReleaseFixtures.validPayload), envelope(ReleaseFixtures.validEnvelope, name, value)) }
        }
        val original = Json.parseToJsonElement(bytes(ReleaseFixtures.validEnvelope).decodeToString()).jsonObject.getValue("signature").jsonPrimitive.content
        val modified = (if (original.first() == 'A') "B" else "A") + original.drop(1)
        failure("release_signature_invalid") { first.verify(bytes(ReleaseFixtures.validPayload), envelope(ReleaseFixtures.validEnvelope, "signature", JsonPrimitive(modified))) }
        failure("release_key_untrusted") { first.verify(bytes(ReleaseFixtures.validPayload), envelope(ReleaseFixtures.validEnvelope, "keyId", JsonPrimitive("0".repeat(64)))) }
        failure("release_signature_invalid") { first.verify(bytes(ReleaseFixtures.rotatedPayload), envelope(ReleaseFixtures.rotatedEnvelope, "keyId", JsonPrimitive(ReleaseFixtures.firstKey.id))) }
    }
    @Test fun explicitRootRotationCanBridgeThenRetireAnOldKey() {
        failure("release_key_untrusted") { first.verify(bytes(ReleaseFixtures.rotatedPayload), bytes(ReleaseFixtures.rotatedEnvelope)) }
        val bridge = VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey, ReleaseFixtures.secondKey))
        bridge.verify(bytes(ReleaseFixtures.validPayload), bytes(ReleaseFixtures.validEnvelope))
        bridge.verify(bytes(ReleaseFixtures.rotatedPayload), bytes(ReleaseFixtures.rotatedEnvelope))
        val retired = VerifyReleaseManifest(listOf(ReleaseFixtures.secondKey))
        retired.verify(bytes(ReleaseFixtures.rotatedPayload), bytes(ReleaseFixtures.rotatedEnvelope))
        failure("release_key_untrusted") { retired.verify(bytes(ReleaseFixtures.validPayload), bytes(ReleaseFixtures.validEnvelope)) }
        assertFailsWith<IllegalArgumentException> { VerifyReleaseManifest(emptyList()) }
        assertFailsWith<IllegalArgumentException> { VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey, ReleaseFixtures.firstKey)) }
    }
    @Test fun rsaParametersCannotDowngradeAndMalformedPublicKeysCannotVerify() {
        for (encoded in listOf(ReleaseFixtures.salt20Envelope, ReleaseFixtures.mgf384Envelope, ReleaseFixtures.pkcs1Envelope)) {
            failure("release_signature_invalid") { first.verify(bytes(ReleaseFixtures.validPayload), bytes(encoded)) }
        }
        val raw = Json.parseToJsonElement(bytes(ReleaseFixtures.validEnvelope).decodeToString()).jsonObject.getValue("signature").jsonPrimitive.content
        assertFalse(verifyReleaseRsaPss(ReleasePublicKey("AAAA", "AAAA"), bytes(ReleaseFixtures.validPayload), bytes(raw)))
        assertFalse(verifyReleaseRsaPss(ReleaseFixtures.secondKey, bytes(ReleaseFixtures.validPayload), bytes(raw)))
    }
    @Test fun malformedUtf8HasTheSameBoundedFailureAsOtherInvalidSignatureMetadata() {
        failure("release_signature_invalid") { first.verify(bytes(ReleaseFixtures.validPayload), byteArrayOf(0xff.toByte())) }
        failure("release_manifest_invalid") { first.verify(bytes(ReleaseFixtures.invalidUtf8Payload), bytes(ReleaseFixtures.invalidUtf8Envelope)) }
    }
    @Test fun sizesAndForwardCompatibleExtraTargetsRemainBounded() {
        failure("release_metadata_size") { first.verify(ByteArray(ReleaseFormat.MAX_MANIFEST_BYTES + 1), bytes(ReleaseFixtures.validEnvelope)) }
        failure("release_metadata_size") { first.verify(bytes(ReleaseFixtures.validPayload), ByteArray(ReleaseFormat.MAX_SIGNATURE_BYTES + 1)) }
        failure("release_metadata_size") { first.verify(byteArrayOf(), bytes(ReleaseFixtures.validEnvelope)) }
        val future = first.verify(bytes(ReleaseFixtures.futureTargetPayload), bytes(ReleaseFixtures.futureTargetEnvelope))
        assertTrue("linux-riscv64" in future.manifest.assets)
        future.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText), "linux-x64")
        failure("release_target_missing") { future.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText), "macos-arm64") }
        failure("release_target_unsupported") { future.verifyBinary(Buffer().writeUtf8(ReleaseFixtures.binaryText), "linux-riscv64") }
    }
    @Test fun artifactVersionsAndNamesDoNotPermitPathOrNumericAmbiguity() {
        for (version in listOf("0.1.0", "1.2.3-alpha.1", "0.1.0-SNAPSHOT")) assertTrue(ReleaseFormat.validVersion(version), version)
        for (version in listOf("../escape", "v1.2.3", "1.2", "01.2.3", "1.2.3-01", "1.2.3-alpha.01", "1.2.3+unsupported")) assertFalse(ReleaseFormat.validVersion(version), version)
        assertEquals("teamcode-1.2.3-windows-x64.exe", ReleaseFormat.fileName("1.2.3", "windows-x64"))
    }
}
