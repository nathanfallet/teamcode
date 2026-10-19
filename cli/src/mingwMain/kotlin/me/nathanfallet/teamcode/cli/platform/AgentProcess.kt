package me.nathanfallet.teamcode.cli.platform

/** The cloud runner runs in Linux session pods only. */
actual fun startAgentProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, workingDirectory: String): AgentProcess =
    throw UnsupportedOperationException("Cloud agents run in Linux session pods only.")
