package me.nathanfallet.teamcode.cli.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.client.ReleaseClient
import okio.ByteString.Companion.decodeBase64
import kotlin.test.*

class CheckReleaseTest {
    private fun bytes(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
    private fun transport() = ReleaseClient { configure -> HttpClient(MockEngine { request ->
        assertNull(request.headers[HttpHeaders.Authorization])
        respond(ByteReadChannel(bytes(if (request.url.encodedPath.endsWith("manifest.json")) ReleaseFixtures.validPayload else ReleaseFixtures.validEnvelope)))
    }) { configure(this) } }

    @Test fun aRequestedVersionRequiresASignatureAndAnExactVersionMatch() = runBlocking {
        transport().use { client ->
            val check = CheckRelease(client, VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)), "1.0.0")
            val result = assertNotNull(check("1.2.3-alpha.1"))
            assertTrue(result.updateAvailable)
            assertEquals("1.2.3-alpha.1", result.release.manifest.version)
            val mismatch = assertFailsWith<CliOperationException> { check("1.2.4") }
            assertEquals("release_version_mismatch", mismatch.code)
        }
        transport().use { client ->
            val error = assertFailsWith<CliOperationException> { CheckRelease(client, currentVersion = "1.0.0")("1.2.3-alpha.1") }
            assertEquals("release_key_untrusted", error.code, "Fixture roots must never replace compiled publisher roots")
        }
    }

    @Test fun explicitlyCheckingAnOlderReleaseDoesNotAdvertiseADowngradeAsAnUpdate() = runBlocking {
        transport().use { client ->
            assertFalse(assertNotNull(CheckRelease(client, VerifyReleaseManifest(listOf(ReleaseFixtures.firstKey)), "1.2.3")("1.2.3-alpha.1")).updateAvailable)
        }
    }

    @Test fun absentStableReleasesAreDifferentFromUnsignedOrTamperedReleases() = runBlocking {
        ReleaseClient { configure -> HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) { configure(this) } }.use { client ->
            assertNull(CheckRelease(client, currentVersion = "1.0.0")())
        }
        var calls = 0
        ReleaseClient { configure -> HttpClient(MockEngine {
            calls++
            respond(if (calls == 1) """{"draft":false,"prerelease":false,"tag_name":"2.0.0"}""" else "UNSIGNED")
        }) { configure(this) } }.use { client ->
            val error = assertFailsWith<CliOperationException> { CheckRelease(client, currentVersion = "1.0.0")() }
            assertEquals("release_signature_invalid", error.code)
            assertEquals(3, calls)
        }
    }
}
