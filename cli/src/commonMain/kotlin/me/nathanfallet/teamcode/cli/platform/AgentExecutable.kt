package me.nathanfallet.teamcode.cli.platform

import me.nathanfallet.teamcode.cli.CliUserException

/** A way to start an npm-installed agent on Windows without evaluating its .cmd wrapper. */
internal data class NpmLaunchCandidate(val relativePath: String, val needsNode: Boolean)

/**
 * Where a global npm install (the directory holding `<command>.cmd`) keeps the real entry point, best first.
 * Current claude-code releases ship a native `bin/claude.exe` (linked from a per-platform optional package);
 * older ones shipped `cli.js`, and codex still ships a `bin/codex.js` launcher.
 */
internal fun npmLaunchCandidates(command: String, architecture: String): List<NpmLaunchCandidate> = when (command) {
    "claude" -> {
        val package_ = "node_modules/@anthropic-ai/claude-code"
        val platforms = if (architecture == "arm64") listOf("win32-arm64", "win32-x64") else listOf("win32-x64")
        platforms.map { NpmLaunchCandidate("$package_/node_modules/@anthropic-ai/claude-code-$it/claude.exe", false) } +
            platforms.map { NpmLaunchCandidate("node_modules/@anthropic-ai/claude-code-$it/claude.exe", false) } +
            NpmLaunchCandidate("$package_/bin/claude.exe", false) +
            NpmLaunchCandidate("$package_/cli.js", true)
    }
    "codex" -> listOf(NpmLaunchCandidate("node_modules/@openai/codex/bin/codex.js", true))
    else -> emptyList()
}

internal fun agentNotFoundMessage(command: String): String = when (command) {
    "claude" -> "Cannot find the claude executable on PATH. Install Claude Code (PowerShell: irm https://claude.ai/install.ps1 | iex; or npm install -g @anthropic-ai/claude-code) and make sure its directory is on PATH. The native installer puts claude.exe in %USERPROFILE%\\.local\\bin."
    "codex" -> "Cannot find the codex executable on PATH. Install Codex (npm install -g @openai/codex) and make sure its directory is on PATH."
    else -> "Cannot find the \"$command\" executable on PATH. Install it and make it available on PATH."
}

internal fun nodeMissingMessage(command: String) =
    "Node.js is required to run the npm installation of $command, but node.exe was not found next to it or on PATH. Install Node.js, or install $command with its native installer."

internal fun cannotLaunchMessage(command: String) =
    "Cannot start $command. Check that the executable exists, can be run by this user, and is not blocked by antivirus or file permissions."

internal fun cannotLaunchError(command: String) = CliUserException(cannotLaunchMessage(command))
