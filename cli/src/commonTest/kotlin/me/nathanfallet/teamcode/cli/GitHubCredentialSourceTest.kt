package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.RunnerGitHubView
import me.nathanfallet.teamcode.cli.cloud.agent.*
import kotlin.test.*

class GitHubCredentialSourceTest {
    private val github = CredentialRequest("https", "github.com", "owner/repo.git")

    @Test fun answersOnlyForHttpsGitHubAndNeverCallsTheServerForOtherRemotes() = runTest {
        var calls = 0
        val source = GitHubCredentialSource({ calls++; RunnerGitHubView("linked", "ghu_token", 10_000_000) }, now = { 0 })
        assertNull(source.credential(CredentialRequest("https", "evil.example.test")))
        assertNull(source.credential(CredentialRequest("http", "github.com")))
        assertNull(source.credential(CredentialRequest("https", "github.com.evil.test")))
        assertEquals(0, calls)
        assertEquals(Credential("x-access-token", "ghu_token"), source.credential(github))
    }

    @Test fun keepsTheTokenUntilShortlyBeforeItExpiresThenAsksAgain() = runTest {
        var calls = 0; var clock = 0L
        val source = GitHubCredentialSource({ calls++; RunnerGitHubView("linked", "ghu_$calls", 1_000_000) }, now = { clock })
        assertEquals("ghu_1", source.credential(github)?.password)
        clock = 1_000_000 - GitHubCredentialSource.RENEW_MARGIN_MILLIS - 1
        assertEquals("ghu_1", source.credential(github)?.password)
        assertEquals(1, calls)
        clock += 1
        assertEquals("ghu_2", source.credential(github)?.password)
        assertEquals(2, calls)
    }

    @Test fun noLinkMeansNoCredentialAndTheServerIsNotAskedForEveryGitCall() = runTest {
        var calls = 0; var clock = 0L; var status = "not_linked"
        val source = GitHubCredentialSource({ calls++; if (status == "linked") RunnerGitHubView("linked", "ghu_late", null) else RunnerGitHubView(status) }, now = { clock })
        assertNull(source.credential(github)); assertNull(source.credential(github))
        assertEquals(1, calls)
        status = "linked"; clock = GitHubCredentialSource.RETRY_MILLIS
        assertEquals("ghu_late", source.credential(github)?.password)
        // Unknown expiry: asked again after a few minutes.
        clock += GitHubCredentialSource.UNKNOWN_EXPIRY_MILLIS
        source.credential(github)
        assertEquals(3, calls)
    }

    @Test fun theSameTokenAndTheLinkedLoginServeGh() = runTest {
        var calls = 0
        val source = GitHubCredentialSource({ calls++; RunnerGitHubView("linked", "ghu_token", 10_000_000, "octo") }, now = { 0 })
        assertEquals(0, source.renewsAt())
        val token = source.token()!!
        assertEquals("ghu_token", token.token); assertEquals("octo", token.login)
        assertFalse("ghu_token" in token.toString())
        assertEquals(10_000_000 - GitHubCredentialSource.RENEW_MARGIN_MILLIS, source.renewsAt())
        assertEquals("ghu_token", source.credential(github)?.password)
        assertEquals(1, calls, "git and gh share one cached token")
    }

    @Test fun aFailingServerCallSurfacesToTheBrokerWhichTreatsItAsNoCredential() = runTest {
        val source = GitHubCredentialSource({ throw IllegalStateException("boom") }, now = { 0 })
        assertFailsWith<IllegalStateException> { source.credential(github) }
    }
}
