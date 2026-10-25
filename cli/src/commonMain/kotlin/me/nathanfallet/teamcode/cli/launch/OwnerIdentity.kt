package me.nathanfallet.teamcode.cli.launch

/**
 * The sentence that tells the agent who runs its session, added to its instructions beside the artifact instructions:
 * `I am <username>[, known as <login> on GitHub].` The username is the member's TeamCode username (members have no
 * other name), the login the GitHub account they linked. Never an email or a credential.
 *
 * Both values come from the server, but they reach the agent's system prompt, so they are bounded here: a username
 * keeps only letters, digits, `.`, `_` and `-` (what TeamCode accepts for a new member), at most [MAX_USERNAME]
 * characters, so no line break, control character, space or punctuation can carry an instruction out of the sentence;
 * a login that is not a valid GitHub login is left out.
 */
internal object OwnerIdentity {
    const val MAX_USERNAME = 64
    /** GitHub's own rule: 1 to 39 letters, digits or single hyphens, not starting or ending with one. */
    private val GITHUB_LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

    fun username(value: String?): String? =
        value?.filter { it.isLetterOrDigit() || it in "._-" }?.take(MAX_USERNAME)?.trim('.', '_', '-')?.ifEmpty { null }

    fun githubLogin(value: String?): String? = value?.takeIf { GITHUB_LOGIN.matches(it) }

    /** The sentence, or null when there is no usable username (nothing is said then, rather than a wrong name). */
    fun sentence(username: String?, githubLogin: String?): String? {
        val name = username(username) ?: return null
        val login = githubLogin(githubLogin)
        return "I am $name" + (if (login != null) ", known as $login on GitHub" else "") + "."
    }
}
