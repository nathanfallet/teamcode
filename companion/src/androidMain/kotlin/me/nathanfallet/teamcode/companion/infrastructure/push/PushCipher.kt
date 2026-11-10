package me.nathanfallet.teamcode.companion.infrastructure.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The device side of a push's encrypted payload (docs/notifications.md, "Push payload"): ECIES on P-256,
 * `base64( 0x01 | ephemeral public key (65 bytes, uncompressed) | nonce (12) | AES-256-GCM ciphertext and tag )`, the key
 * being HKDF-SHA256 of the ECDH secret with salt `ephemeral point | recipient point` and info `teamcode-push-v1`.
 * The server's `PushPayloads` encrypts; the iOS service extension decrypts the same way with CryptoKit.
 */
object PushCipher {
    private const val INFO = "teamcode-push-v1"
    private const val POINT = 65
    private const val NONCE = 12

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** SubjectPublicKeyInfo, DER, base64: what `PUT /devices/current/push` takes. */
    fun encodePublic(key: ECPublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun encodePrivate(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)
    fun decodePublic(base64: String): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64))) as ECPublicKey
    fun decodePrivate(base64: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))

    /** The payload's JSON; throws when it is malformed or was not encrypted for this key. */
    fun decrypt(privateKey: PrivateKey, publicKey: ECPublicKey, payload: String): String {
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size > 1 + POINT + NONCE + 16 && bytes[0] == 1.toByte()) { "Not a version 1 push payload." }
        val ephemeralPoint = bytes.copyOfRange(1, 1 + POINT)
        require(ephemeralPoint[0] == 4.toByte()) { "Not an uncompressed point." }
        val nonce = bytes.copyOfRange(1 + POINT, 1 + POINT + NONCE)
        val sealed = bytes.copyOfRange(1 + POINT + NONCE, bytes.size)
        val ephemeral = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, ephemeralPoint.copyOfRange(1, 33)), BigInteger(1, ephemeralPoint.copyOfRange(33, 65))), publicKey.params),
        )
        val secret = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(ephemeral, true); generateSecret() }
        val key = hkdf(secret, ephemeralPoint + point(publicKey), INFO.encodeToByteArray(), 32)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(sealed)
        }.decodeToString(throwOnInvalidSequence = true)
    }

    /** The uncompressed SEC1 point: `0x04 | x | y`, 32 bytes each. */
    private fun point(key: ECPublicKey): ByteArray {
        fun fixed(value: BigInteger) = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(ikm) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        var block = ByteArray(0); var out = ByteArray(0); var counter = 1
        while (out.size < length) { mac.update(block); mac.update(info); mac.update(counter.toByte()); block = mac.doFinal(); out += block; counter++ }
        return out.copyOf(length)
    }
}
