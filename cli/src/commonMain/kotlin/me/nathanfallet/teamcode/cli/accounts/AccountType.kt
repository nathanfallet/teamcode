package me.nathanfallet.teamcode.cli.accounts

import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.client.API_KEY_UNSUPPORTED
import me.nathanfallet.teamcode.client.AccountType


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
