package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** A repository cloned into `/workspace/<path>` on the first execution and only fetched afterwards. */
data class CloudRepository(
    /** `https://` clone URL without credentials, query or fragment. */
    val url: String,
    /** Relative to `/workspace`. */
    val path: String,
    /** Branch, tag or commit checked out after cloning; the remote default branch when null. */
    val ref: String? = null,
    /** Local branch created at [ref] after cloning, for example one branch per task. */
    val branch: String? = null,
)

/** Bootstrap commands run by `/bin/sh -c` in the working directory. */
data class CloudSetup(
    /** Run once per workspace, in order; each one is marked done on the workspace when it succeeds. */
    val create: List<String> = emptyList(),
    /** Run on every execution (first start and every resume), in order, after [create]. */
    val start: List<String> = emptyList(),
)

/**
 * The reproducible, non-secret preparation recipe of a session. A profile version holds the reusable default; a
 * session stores its resolved copy at creation, so a resume prepares exactly what the first execution did.
 * Secrets never appear here: variable values and GitHub tokens are resolved for the owner on every execution.
 */
data class CloudEnvironmentSpec(
    val repositories: List<CloudRepository> = emptyList(),
    /** Relative to `/workspace`; the workspace root when null. */
    val workingDirectory: String? = null,
    val setup: CloudSetup = CloudSetup(),
    /** Environment variables the owner must be assigned (environment variants); preparation fails without them. */
    val requiredVariables: List<String> = emptyList(),
    /** Preparation fails when the owner has no usable GitHub access. */
    val requireGitHub: Boolean = false,
    /** Email domain of the Git identity for owners without a linked GitHub account. */
    val gitEmailDomain: String? = null,
    /** Hosts repositories may be cloned from; keeps session requests from pointing the pod at internal endpoints. */
    val repositoryHosts: List<String> = listOf("github.com"),
)

/** What a session request may change in its profile's recipe. Null keeps the profile's value. */
data class CloudEnvironmentOverride(val repositories: List<CloudRepository>? = null, val workingDirectory: String? = null)

/** Environment variable values assigned to a member. Never logged, stored on a session or put in a pod spec. */
class CloudVariables(
    val values: Map<String, String>, val unavailable: List<String> = emptyList(),
    /** The answered waits' secrets of the session (docs/waits.md#secrets); only the running agent's route fills it. */
    val secrets: me.nathanfallet.teamcode.domain.waits.ServedSecrets = me.nathanfallet.teamcode.domain.waits.ServedSecrets(),
) {
    override fun toString() = "CloudVariables(names=${values.keys.sorted()}, unavailable=$unavailable, secrets=${secrets.values.keys.sorted()})"
}

/**
 * Where a member's environment variables come from. The server uses [ResolveCloudVariablesUseCase] (the environment
 * entries of the member's effective variants); [NONE] is for tests and servers without variants.
 */
fun interface CloudVariableSource {
    suspend fun resolve(teamId: String, memberId: String): CloudVariables
    companion object { val NONE = CloudVariableSource { _, _ -> CloudVariables(emptyMap()) } }
}

/** A GitHub App user-to-server token for one member: short-lived, scoped to what both the App and the user can reach. */
class GitHubUserToken(val token: String, val expiresAt: Long?, val login: String, val userId: Long) {
    override fun toString() = "GitHubUserToken(login=$login, token=[REDACTED])"
}
sealed interface GitHubAccess {
    data class Linked(val token: GitHubUserToken) : GitHubAccess
    /** The member never linked GitHub, or revoked the link. */
    data object NotLinked : GitHubAccess
    /** Linked, but no token could be obtained now (refresh failed, GitHub unreachable). */
    data object Unavailable : GitHubAccess
}

/**
 * GitHub App user tokens per member, refreshed on the server. Installation tokens and personal access tokens are
 * never handed to a session. The linking flow and the refresh store come with their own change; until then no
 * member is linked.
 */
fun interface GitHubAccessSource {
    suspend fun userAccess(teamId: String, memberId: String): GitHubAccess
    companion object { val NONE = GitHubAccessSource { _, _ -> GitHubAccess.NotLinked } }
}

data class GitIdentity(val name: String, val email: String)

/**
 * What the runner receives to prepare one execution. Holds secrets: never log or persist it. [untrustedWorkspace] is
 * true when another member worked on the workspace since the owner's last prepared execution (a transfer): the runner
 * then resets the agent's home and the repositories' Git configuration before anything runs with the owner's credentials.
 */
class PreparedEnvironment(val spec: CloudEnvironmentSpec, val git: GitIdentity, val variables: CloudVariables, val github: GitHubAccess,
                          val untrustedWorkspace: Boolean = false) {
    override fun toString() = "PreparedEnvironment(repositories=${spec.repositories.size}, variables=$variables, github=${github::class.simpleName}, untrustedWorkspace=$untrustedWorkspace)"
}

enum class PreparationStep(val wire: String) {
    GIT_IDENTITY("git_identity"), CLONE("clone"), CHECKOUT("checkout"), FETCH("fetch"), WORKING_DIRECTORY("working_directory"),
    SETUP_CREATE("setup_create"), SETUP_START("setup_start"), WORKSPACE("workspace"), RUNNER("runner");
    companion object { fun parse(value: String) = entries.find { it.wire == value } }
}
/** A failed step, reported by the runner as structured fields only: no command output ever reaches the server. */
data class PreparationFailure(val step: PreparationStep, val index: Int? = null, val exitCode: Int? = null)

object CloudEnvironmentRules {
    const val PREPARATION_FAILED = "preparation_failed"
    /** Progress phases a runner may report while preparing; `phase` stays an open string for clients. */
    val PHASES = setOf("securing_workspace", "configuring_git", "cloning_repositories", "fetching_repositories", "running_setup")
    const val MAX_REPOSITORIES = 16
    const val MAX_COMMANDS = 16
    const val MAX_COMMAND_LENGTH = 4096

    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}")
    private val urlPattern = Regex("https://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?/[A-Za-z0-9._~/-]{1,400}")
    private val refPattern = Regex("[A-Za-z0-9._/-]{1,200}")
    private val variable = Regex("[A-Z_][A-Z0-9_]{0,127}")
    private val domain = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")
    /** Directories the runner owns on the workspace. */
    private val reserved = setOf(".teamcode", ".home")

    /**
     * Applies a session's overrides. Replacing the repositories while keeping a profile working directory that is
     * no longer inside any of them is refused here, rather than failing in the pod after a full clone.
     */
    fun merge(profile: CloudEnvironmentSpec, override: CloudEnvironmentOverride?): CloudEnvironmentSpec {
        if (override == null) return profile
        val repositories = override.repositories ?: profile.repositories
        val inherited = profile.workingDirectory?.trimEnd('/')
        if (override.repositories != null && override.workingDirectory == null && inherited != null &&
            repositories.none { inherited == it.path.trimEnd('/') || inherited.startsWith(it.path.trimEnd('/') + "/") })
            invalid("The profile's working directory $inherited is not inside the requested repositories; set workingDirectory too.")
        return profile.copy(repositories = repositories, workingDirectory = override.workingDirectory ?: profile.workingDirectory)
    }

    fun validate(spec: CloudEnvironmentSpec) {
        if (spec.repositoryHosts.size > 16 || spec.repositoryHosts.any { !domain.matches(it) }) invalid("Name at most 16 repository hosts as DNS names.")
        validateRepositories(spec.repositories)
        for (repository in spec.repositories) {
            val host = repository.url.removePrefix("https://").substringBefore('/').substringBefore(':')
            if (host !in spec.repositoryHosts) invalid("Repository host $host is not allowed by the profile (${spec.repositoryHosts.joinToString(", ")}).")
        }
        spec.workingDirectory?.let(::validatePath)
        for (commands in listOf(spec.setup.create, spec.setup.start))
            if (commands.size > MAX_COMMANDS || commands.any { it.isBlank() || it.length > MAX_COMMAND_LENGTH || '\u0000' in it })
                invalid("Use at most $MAX_COMMANDS setup commands per list, each 1–$MAX_COMMAND_LENGTH characters.")
        if (spec.requiredVariables.size > 64 || spec.requiredVariables.any { !variable.matches(it) } || spec.requiredVariables.toSet().size != spec.requiredVariables.size)
            invalid("Name at most 64 distinct required variables in UPPER_SNAKE_CASE.")
        if (spec.gitEmailDomain?.let { !domain.matches(it) } == true) invalid("Use a DNS name for the Git email domain.")
    }

    fun validate(override: CloudEnvironmentOverride) {
        override.repositories?.let(::validateRepositories)
        override.workingDirectory?.let(::validatePath)
    }

    private fun validateRepositories(repositories: List<CloudRepository>) {
        if (repositories.size > MAX_REPOSITORIES) invalid("Use at most $MAX_REPOSITORIES repositories.")
        for (repository in repositories) {
            if (!urlPattern.matches(repository.url) || "//" in repository.url.removePrefix("https://") || repository.url.split('/').any { it == ".." || it == "." })
                invalid("Use an https clone URL without credentials, query or fragment.")
            validatePath(repository.path)
            repository.ref?.let(::validateRef)
            repository.branch?.let(::validateRef)
        }
        val paths = repositories.map { it.path.trimEnd('/') }
        if (paths.any { path -> paths.count { it == path || it.startsWith("$path/") } > 1 })
            invalid("Clone each repository into its own directory, never inside another one.")
    }

    /** A relative path below `/workspace`, outside the runner's own directories. */
    fun validatePath(path: String) {
        val parts = path.trimEnd('/').split('/')
        if (path.length > 255 || path.startsWith("/") || parts.size > 8 || parts.any { !segment.matches(it) || it == "." || it == ".." } || parts.first() in reserved)
            invalid("Use a relative path below the workspace (letters, digits, '.', '_', '-'), outside .teamcode and .home.")
    }

    /** Refuses option-like and malformed refs before they reach `git`. */
    private fun validateRef(ref: String) {
        if (!refPattern.matches(ref) || ref.startsWith("-") || ref.startsWith("/") || ref.endsWith("/") || ref.endsWith(".lock") || ".." in ref || "//" in ref)
            invalid("Use a valid branch, tag or commit name.")
    }

    private fun invalid(message: String): Nothing = throw TeamFailure("invalid_environment", 400, message)

    /**
     * The owner's Git identity, rewritten on every execution so a transferred session commits as its new owner.
     * A linked GitHub account gives its no-reply address; otherwise the member name at [CloudEnvironmentSpec.gitEmailDomain].
     */
    fun gitIdentity(username: String, github: GitHubAccess, spec: CloudEnvironmentSpec): GitIdentity {
        if (github is GitHubAccess.Linked) return GitIdentity(github.token.login, "${github.token.userId}+${github.token.login}@users.noreply.github.com")
        val local = username.lowercase().map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '-' }.joinToString("").trim('.', '-').ifEmpty { "member" }
        return GitIdentity(username, "$local@${spec.gitEmailDomain ?: "users.noreply.teamcode.invalid"}")
    }

    /** A server-written message for a reported failure; runner-supplied text never reaches the session view. */
    fun message(failure: PreparationFailure, spec: CloudEnvironmentSpec, epoch: Int): String {
        val exit = failure.exitCode?.let { " (exit code $it)" } ?: ""
        val repository = failure.index?.let { spec.repositories.getOrNull(it) }
        val log = " Details are in /workspace/.teamcode/logs/prepare-e$epoch.log."
        return when (failure.step) {
            PreparationStep.GIT_IDENTITY -> "Configuring the Git identity failed$exit."
            PreparationStep.CLONE -> "Cloning ${repository?.url ?: "a repository"} into ${repository?.path ?: "the workspace"} failed$exit. Private repositories need the owner's GitHub access.$log"
            PreparationStep.CHECKOUT -> "Checking out ${repository?.ref ?: repository?.branch ?: "the requested ref"} in ${repository?.path ?: "a repository"} failed$exit.$log"
            PreparationStep.FETCH -> "Fetching ${repository?.path ?: "a repository"} failed$exit. Private repositories need the owner's GitHub access.$log"
            PreparationStep.WORKING_DIRECTORY -> "The working directory ${spec.workingDirectory ?: "."} does not exist after cloning."
            PreparationStep.SETUP_CREATE -> "Setup command ${(failure.index ?: 0) + 1} (create) failed$exit.$log"
            PreparationStep.SETUP_START -> "Setup command ${(failure.index ?: 0) + 1} (start) failed$exit.$log"
            PreparationStep.WORKSPACE -> "Resetting the agent's home and the Git configuration of ${repository?.path ?: "the workspace"} after a transfer failed$exit. A repository whose .git is not a plain directory is refused.$log"
            PreparationStep.RUNNER -> "The runner could not prepare the environment."
        }
    }

    /**
     * Whether the workspace of the execution [epoch] must be treated as untrusted input: another member's execution ran
     * on it after the last execution of [ownerId] that reached `ready`. [executions] are the session's, newest first and
     * possibly truncated; an execution recorded before transfers existed ([CloudExecution.ownerId] null) ran for the
     * session's creator. When the history is cut before an answer is found, the workspace is untrusted.
     */
    fun untrustedWorkspace(ownerId: String, creatorId: String, epoch: Int, executions: List<CloudExecution>, truncated: Boolean): Boolean {
        for (execution in executions.sortedByDescending { it.epoch }) {
            if (execution.epoch >= epoch) continue
            val ranFor = execution.ownerId ?: creatorId
            if (ranFor != ownerId) return true
            if (execution.runnerReady) return false
        }
        return truncated
    }
}
