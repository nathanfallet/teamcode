package me.nathanfallet.teamcode.companion

import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.domain.models.PushMessage
import me.nathanfallet.teamcode.companion.infrastructure.credentials.MemoryCredentialStore
import me.nathanfallet.teamcode.companion.infrastructure.push.AndroidPushPlatform
import me.nathanfallet.teamcode.companion.infrastructure.push.PushCipher
import kotlin.test.*

/** The Android side of the push payload, against a payload the server's `PushPayloads.encrypt` produced. */
class PushCipherTest {
    private val privateKey = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDxcuMBl/f7cFY/r6/pXLl6rHLiBQklWHcc+6gJhgerxQ=="
    private val publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUXfv+QmuPyO4WFWi2AlXsn37L/o3/3Ih7ALP/fBmBem1U2c1vI7/lnje3+WSeDDq9ZOSs+rkjVAcU0NA+pfTsw=="
    private val sealed = "AQRvZEcQSwV+45qorfM66Q9jzDJPYU+jUMluUjj5LsQ4I7biGW2j6NMQPAKUyoEqC5vwZXv3LaOO6c73KkBHEHS2B0IcBaUjrTAAF5otL3EVlOLr48HwdnWO4aWnY9Z01B2w9jz1jfTvT1YHnZWeBU0/J0tc6wWbN+FQESlLY70C9PM/dbePymCuw/6K2HZUzKsD/YuQ+tTXF842Y0UExSGIg263O1xUBa1VIqoeBVeIG5zGk3uhgWIpGJEE8BW5j6HJqWE2LNmo8z7LXmav5gUrNF59l68sMyiHJu/tjBEzSaAwaJzAsHTiKXA0FaSKvaHa//xDiM5pXBxkFVBFb46Z7Kq20g=="
    private val plaintext = "{\"v\":1,\"id\":\"n1\",\"origin\":\"https://team.example\",\"memberId\":\"m1\",\"kind\":\"waiting\",\"title\":\"Ada is waiting\",\"body\":\"Review the plan\",\"sessionId\":\"s1\",\"at\":1700000000000}"

    @Test fun aServerPayloadDecryptsWithTheDeviceKey() {
        assertEquals(plaintext, PushCipher.decrypt(PushCipher.decodePrivate(privateKey), PushCipher.decodePublic(publicKey), sealed))
    }

    @Test fun anotherKeyOrADamagedPayloadIsRefused() {
        val other = PushCipher.generate()
        assertFails { PushCipher.decrypt(other.private, other.public as ECPublicKey, sealed) }
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val damaged = java.util.Base64.getEncoder().encodeToString(bytes)
        for (payload in listOf(damaged, "", "AQ==", sealed.drop(8)))
            assertFails(payload) { PushCipher.decrypt(PushCipher.decodePrivate(privateKey), PushCipher.decodePublic(publicKey), payload) }
    }

    @Test fun thePlatformOpensAPushWithTheStoredKey() = runTest {
        val store = MemoryCredentialStore().apply { write(AndroidPushPlatform.KEY_ENTRY, "$privateKey $publicKey") }
        val push = AndroidPushPlatform(store).open(sealed)
        assertEquals(PushMessage("n1", "https://team.example", "m1", "Ada is waiting", "Review the plan", "s1", null), push)
    }

    @Test fun theKeyPairIsMadeOnceAndKeptInTheCredentialStore() = runTest {
        val store = MemoryCredentialStore()
        val first = AndroidPushPlatform(store).publicKey()
        assertEquals(first, AndroidPushPlatform(store).publicKey()) // A restarted app keeps its key.
        assertTrue(store.read(AndroidPushPlatform.KEY_ENTRY)!!.endsWith(" $first"))
        store.delete(AndroidPushPlatform.KEY_ENTRY)
        assertNotEquals(first, AndroidPushPlatform(store).publicKey())
    }
}
