package me.nathanfallet.teamcode.cli.accounts

import me.nathanfallet.teamcode.cli.CliUserException

/**
 * The kinds of account `teamcode accounts add` offers, in the order of the numbered selector (the same menu as
 * TeamClaude's login). [supported] is false for a kind the server cannot hold yet: it is listed so the menu is final,
 * and choosing it explains why instead of silently doing something else.
 */
internal enum class AccountType(val flag: String, val label: String, val provider: String?, val supported: Boolean = true) {
    ANTHROPIC_SUBSCRIPTION("anthropic-subscription", "Anthropic subscription (Claude Pro, Max, Team, Enterprise)", "anthropic"),
    CODEX_SUBSCRIPTION("codex-subscription", "Codex subscription (ChatGPT Plus, Pro, Team)", "codex"),
    ANTHROPIC_API("anthropic-api", "Anthropic API key (Claude Console, console.anthropic.com)", null, supported = false);

    companion object {
        val flags = entries.map { it.flag }
        /** `--provider` predates `--type`: claude and claude-max meant the Anthropic subscription. */
        fun fromProvider(name: String) = if (name == "codex") CODEX_SUBSCRIPTION else ANTHROPIC_SUBSCRIPTION
    }
}

internal const val API_KEY_UNSUPPORTED = "Anthropic API key accounts are not supported by TeamCode servers yet: the pool holds subscription sign-ins (refreshable OAuth credentials) only. Add an Anthropic or Codex subscription instead."

internal fun requireSupported(type: AccountType): AccountType {
    if (!type.supported) throw CliUserException(API_KEY_UNSUPPORTED)
    return type
}

/** The selector text, one numbered line per kind. */
internal fun accountTypeMenu(): List<String> =
    listOf("Which account type do you want to add?", "") + AccountType.entries.mapIndexed { index, type ->
        "  ${index + 1}. ${type.label}${if (type.supported) "" else " (not available yet)"}"
    } + ""

/** Parses an answer to the selector: a number, or a flag name; empty selects 1. Null when it names nothing. */
internal fun parseAccountTypeChoice(answer: String): AccountType? {
    val text = answer.trim()
    if (text.isEmpty()) return AccountType.entries.first()
    text.toIntOrNull()?.let { return AccountType.entries.getOrNull(it - 1) }
    return AccountType.entries.firstOrNull { it.flag.equals(text, ignoreCase = true) }
}

/**
 * Which account type to add. `--type` wins and never prompts (scripts); `--provider` alone keeps its old meaning;
 * otherwise the numbered selector reads a line from the terminal, or from piped stdin (the numbered fallback). The
 * selector goes through [echo], which a caller points at stderr-safe output; it never prints a secret.
 */
internal fun chooseAccountType(type: String?, provider: String?, echo: (String) -> Unit, readLine: () -> String?): AccountType {
    val fromProvider = provider?.let(AccountType::fromProvider)
    if (type != null) {
        val chosen = AccountType.entries.first { it.flag == type }
        if (fromProvider != null && fromProvider != chosen) throw CliUserException("--type $type and --provider $provider name different accounts. Use --type alone.")
        return requireSupported(chosen)
    }
    if (fromProvider != null) return fromProvider
    accountTypeMenu().forEach(echo)
    echo("Enter 1-${AccountType.entries.size} (default 1), or run with --type ${AccountType.flags.joinToString("|")}.")
    val answer = readLine() ?: throw CliUserException("No account type given on standard input. Choose one with --type ${AccountType.flags.joinToString("|")}.")
    val chosen = parseAccountTypeChoice(answer) ?: throw CliUserException("Invalid choice \"${answer.trim().take(40)}\". Enter a number from 1 to ${AccountType.entries.size}.")
    return requireSupported(chosen)
}
