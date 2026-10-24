package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class CloudEnvironmentRulesTest {
    private val repository = CloudRepository("https://github.com/example/api.git", "api", "main", "task-1")
    private val spec = CloudEnvironmentSpec(listOf(repository), "api", CloudSetup(listOf("npm ci"), listOf("npm run db:migrate")), listOf("NPM_TOKEN"))

    @Test fun acceptsARealisticRecipe() {
        CloudEnvironmentRules.validate(spec)
        CloudEnvironmentRules.validate(CloudEnvironmentSpec(listOf(repository, repository.copy(path = "libs/shared", ref = "0f3c2a1", branch = null))))
    }

    @Test fun refusesUnsafeRepositoriesPathsAndRefs() {
        fun refused(spec: CloudEnvironmentSpec) = assertEquals("invalid_environment", assertFailsWith<TeamFailure> { CloudEnvironmentRules.validate(spec) }.code)
        for (url in listOf("http://github.com/example/api", "https://user:pass@github.com/example/api", "https://github.com/example/api?x=1",
            "git@github.com:example/api.git", "https://github.com/../etc", "https://github.com//api", "file:///etc"))
            refused(spec.copy(repositories = listOf(repository.copy(url = url))))
        for (path in listOf("/etc", "../up", "a/../b", ".teamcode", ".home/x", "", "a b", "./a"))
            refused(spec.copy(repositories = listOf(repository.copy(path = path))))
        for (ref in listOf("-c", "--upload-pack=x", "a..b", "a b", "/main", "main/", "x.lock"))
            refused(spec.copy(repositories = listOf(repository.copy(ref = ref))))
        refused(spec.copy(repositories = listOf(repository, repository.copy(path = "api/nested"))))
        refused(spec.copy(repositories = List(17) { repository.copy(path = "r$it") }))
        refused(spec.copy(workingDirectory = "../outside"))
        refused(spec.copy(setup = CloudSetup(create = listOf(" "))))
        refused(spec.copy(setup = CloudSetup(start = listOf("x".repeat(4097)))))
        refused(spec.copy(requiredVariables = listOf("lower")))
        refused(spec.copy(requiredVariables = listOf("A", "A")))
        refused(spec.copy(gitEmailDomain = "not a domain"))
        for (host in listOf("169.254.169.254", "kubernetes.default.svc", "localhost"))
            refused(spec.copy(repositories = listOf(repository.copy(url = "https://$host/latest/x"))))
        CloudEnvironmentRules.validate(spec.copy(repositoryHosts = listOf("git.example.com"), repositories = listOf(repository.copy(url = "https://git.example.com/team/api"))))
        assertEquals("invalid_environment", assertFailsWith<TeamFailure> {
            CloudEnvironmentRules.validate(CloudEnvironmentOverride(listOf(repository.copy(url = "http://x/y"))))
        }.code)
    }

    @Test fun aSessionOverridesOnlyRepositoriesAndWorkingDirectory() {
        val other = CloudRepository("https://github.com/example/web", "web", "release")
        val merged = CloudEnvironmentRules.merge(spec, CloudEnvironmentOverride(listOf(other), "web"))
        assertEquals(listOf(other), merged.repositories); assertEquals("web", merged.workingDirectory)
        assertEquals(spec.setup, merged.setup); assertEquals(spec.requiredVariables, merged.requiredVariables)
        assertEquals(spec, CloudEnvironmentRules.merge(spec, null))
        assertEquals(spec, CloudEnvironmentRules.merge(spec, CloudEnvironmentOverride()))
        assertEquals("invalid_environment", assertFailsWith<TeamFailure> { CloudEnvironmentRules.merge(spec, CloudEnvironmentOverride(listOf(other))) }.code,
            "Replacing the repositories must not keep a working directory outside them")
        assertEquals("api/src", CloudEnvironmentRules.merge(spec.copy(workingDirectory = "api/src"), CloudEnvironmentOverride(listOf(repository.copy(ref = "dev")))).workingDirectory)
    }

    @Test fun gitIdentityFollowsTheOwner() {
        val linked = GitHubAccess.Linked(GitHubUserToken("ghu_synthetic", null, "octo", 42))
        assertEquals(GitIdentity("octo", "42+octo@users.noreply.github.com"), CloudEnvironmentRules.gitIdentity("ada", linked, spec))
        assertEquals(GitIdentity("Ada Lovelace", "ada-lovelace@example.com"), CloudEnvironmentRules.gitIdentity("Ada Lovelace", GitHubAccess.NotLinked, spec.copy(gitEmailDomain = "example.com")))
        assertEquals("ada@users.noreply.teamcode.invalid", CloudEnvironmentRules.gitIdentity("ada", GitHubAccess.Unavailable, spec).email)
        assertFalse(linked.toString().contains("ghu_synthetic"), "Tokens are redacted from diagnostics")
        assertFalse(CloudVariables(mapOf("NPM_TOKEN" to "npm_secret")).toString().contains("npm_secret"))
    }

    @Test fun failureMessagesAreWrittenByTheServer() {
        val clone = CloudEnvironmentRules.message(PreparationFailure(PreparationStep.CLONE, 0, 128), spec, 3)
        assertTrue(clone.contains("https://github.com/example/api.git") && clone.contains("exit code 128") && clone.contains("prepare-e3.log"))
        assertTrue(CloudEnvironmentRules.message(PreparationFailure(PreparationStep.SETUP_START, 0, 1), spec, 1).startsWith("Setup command 1 (start) failed"))
        assertNull(PreparationStep.parse("rm -rf /"))
    }

    @Test fun aWorkspaceIsUntrustedUntilItsOwnerPreparedItSinceAnotherMemberWorkedOnIt() {
        fun execution(epoch: Int, owner: String?, ready: Boolean = true) =
            CloudExecution("s1", epoch, "tc-s1-e$epoch", ExecutionState.ENDED, createdAt = 0, runnerReady = ready, ownerId = owner)
        fun untrusted(owner: String, epoch: Int, vararg executions: CloudExecution, truncated: Boolean = false) =
            CloudEnvironmentRules.untrustedWorkspace(owner, "a", epoch, executions.toList(), truncated)
        assertFalse(untrusted("a", 1), "A new session's first execution prepares a fresh workspace")
        assertFalse(untrusted("a", 3, execution(2, "a"), execution(1, null)), "The owner's own workspace, from before transfers existed too")
        assertTrue(untrusted("b", 2, execution(1, "a")), "The recipient's first execution after a transfer")
        assertTrue(untrusted("b", 2, execution(1, null)), "An execution without an owner ran for the creator")
        assertTrue(untrusted("b", 3, execution(2, "b", ready = false), execution(1, "a")), "A recipient execution that never got ready did not finish the reset")
        assertFalse(untrusted("b", 4, execution(3, "b"), execution(2, "b", ready = false), execution(1, "a")), "Trusted again once the recipient prepared it")
        assertTrue(untrusted("a", 4, execution(3, "b"), execution(2, "a"), execution(1, "a")), "Given back to the former owner after someone else's work")
        assertFalse(untrusted("a", 2, execution(2, "b"), execution(1, "a")), "The current execution itself never counts")
        assertTrue(untrusted("b", 101, *Array(100) { execution(100 - it, "b", ready = false) }, truncated = true), "A truncated history is not proof")
        assertTrue(CloudEnvironmentRules.message(PreparationFailure(PreparationStep.WORKSPACE, 0), spec, 2).contains("Git configuration of api"))
        assertEquals(PreparationStep.WORKSPACE, PreparationStep.parse("workspace"))
        assertTrue("securing_workspace" in CloudEnvironmentRules.PHASES)
    }
}
