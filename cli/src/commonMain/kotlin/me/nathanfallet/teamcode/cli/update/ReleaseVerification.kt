package me.nathanfallet.teamcode.cli.update

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.CliOperationException
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Source
import okio.use
import okio.IOException
import okio.Path

/** Only build-generated or explicitly injected trusted roots belong here. Never deserialize a root from a manifest/server. */
internal class ReleasePublicKey(spkiBase64: String, pkcs1Base64: String) {
    val spki: ByteArray = requireNotNull(spkiBase64.decodeBase64()).toByteArray()
    val pkcs1: ByteArray = requireNotNull(pkcs1Base64.decodeBase64()).toByteArray()
    val id: String = spki.toByteString().sha256().hex()
}
internal expect fun verifyReleaseRsaPss(key: ReleasePublicKey, payload: ByteArray, signature: ByteArray): Boolean

internal class VerifiedRelease internal constructor(val manifest: ReleaseManifest, val keyId: String) {
    fun verifyBinary(path: Path, target: String, fs: FileSystem = FileSystem.SYSTEM) {
        try { verifyBinary(fs.source(path), target) }
        catch (_: IOException) { throw failure("release_file_unreadable") }
    }
    fun verifyBinary(source: Source, target: String) = source.use {
        if (target !in ReleaseFormat.targets) throw failure("release_target_unsupported")
        val asset = manifest.assets[target] ?: throw failure("release_target_missing")
        val hash = HashingSource.sha256(it)
        var total = 0L
        val buffer = Buffer()
        while (true) {
            val read = hash.read(buffer, minOf(8192L, asset.size - total + 1))
            if (read == -1L) break
            total += read; buffer.skip(read)
            if (total > asset.size) throw failure("release_binary_invalid")
        }
        if (total != asset.size || hash.hash.hex() != asset.sha256) throw failure("release_binary_invalid")
    }
}

/** Authentication precedes manifest parsing. The payload is copied once so parsing uses the verified bytes. */
internal class VerifyReleaseManifest(trustedKeys: List<ReleasePublicKey> = ShippedReleaseKeys.keys) {
    private val trustedKeys = trustedKeys.toList()
    init { require(trustedKeys.isNotEmpty() && trustedKeys.size <= 8 && trustedKeys.map { it.id }.distinct().size == trustedKeys.size) }
    fun verify(manifest: ByteArray, signature: ByteArray): VerifiedRelease {
        if (manifest.size !in 1..ReleaseFormat.MAX_MANIFEST_BYTES || signature.size !in 1..ReleaseFormat.MAX_SIGNATURE_BYTES)
            throw failure("release_metadata_size")
        val payload = manifest.copyOf()
        val envelope = parseObject(signature.copyOf(), "release_signature_invalid")
        if (envelope.keys != setOf("schemaVersion", "algorithm", "keyId", "signature") || envelope["schemaVersion"] != JsonPrimitive(1) ||
            envelope.string("algorithm") != "RSA-PSS-SHA256") throw failure("release_signature_invalid")
        val key = trustedKeys.singleOrNull { it.id == envelope.string("keyId") } ?: throw failure("release_key_untrusted")
        val encoded = envelope.string("signature")
        if (encoded == null || !Regex("[A-Za-z0-9+/]{512}").matches(encoded)) throw failure("release_signature_invalid")
        val raw = encoded.decodeBase64()?.toByteArray() ?: throw failure("release_signature_invalid")
        if (raw.size != 384 || !verifyReleaseRsaPss(key, payload, raw)) throw failure("release_signature_invalid")
        val data = parseObject(payload, "release_manifest_invalid")
        if (data.keys != setOf("schemaVersion", "version", "tag", "sourceCommit", "assets") || data["schemaVersion"] != JsonPrimitive(1))
            throw failure("release_manifest_invalid")
        val version = data.string("version")?.takeIf(ReleaseFormat::validVersion) ?: throw failure("release_manifest_invalid")
        val tag = data.string("tag")?.takeIf { it == version } ?: throw failure("release_manifest_invalid")
        val commit = data.string("sourceCommit")?.takeIf { Regex("[0-9a-f]{40}").matches(it) } ?: throw failure("release_manifest_invalid")
        val assets = data["assets"] as? JsonObject ?: throw failure("release_manifest_invalid")
        if (assets.size !in 1..32) throw failure("release_manifest_invalid")
        val decoded = assets.mapValues { (target, value) ->
            if (!ReleaseFormat.validTarget(target)) throw failure("release_manifest_invalid")
            val asset = value as? JsonObject ?: throw failure("release_manifest_invalid")
            if (asset.keys != setOf("fileName", "sha256", "size") || asset.string("fileName") != ReleaseFormat.fileName(version, target))
                throw failure("release_manifest_invalid")
            val hash = asset.string("sha256")?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: throw failure("release_manifest_invalid")
            val size = (asset["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?.takeIf { it in 1..ReleaseFormat.MAX_BINARY_BYTES } ?: throw failure("release_manifest_invalid")
            ReleaseAsset(ReleaseFormat.fileName(version, target), hash, size)
        }
        return VerifiedRelease(ReleaseManifest(1, version, tag, commit, decoded), key.id)
    }
}

internal fun readReleaseFile(path: Path, limit: Int, fs: FileSystem = FileSystem.SYSTEM): ByteArray = try {
    val metadata = fs.metadataOrNull(path) ?: throw failure("release_file_unreadable")
    if (!metadata.isRegularFile || metadata.symlinkTarget != null) throw failure("release_file_unreadable")
    if ((metadata.size ?: 0) > limit) throw failure("release_metadata_size")
    fs.source(path).use { source ->
        val buffer = Buffer()
        while (buffer.size <= limit) {
            val read = source.read(buffer, minOf(8192L, limit - buffer.size + 1))
            if (read == -1L) break
        }
        if (buffer.size > limit) throw failure("release_metadata_size")
        buffer.readByteArray()
    }
} catch (_: IOException) { throw failure("release_file_unreadable") }
private fun parseObject(bytes: ByteArray, code: String): JsonObject = try {
    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: throw failure(code)
} catch (_: CharacterCodingException) { throw failure(code) }
catch (_: IllegalArgumentException) { throw failure(code) }
private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun failure(code: String) = CliOperationException(code)
