package me.nathanfallet.teamcode.cli.config

import me.nathanfallet.teamcode.cli.platform.createPrivateFile
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.config.ConfigStore
import me.nathanfallet.teamcode.config.InstallationIdStore
import me.nathanfallet.teamcode.config.PrivateFiles
import me.nathanfallet.teamcode.config.configSibling

/** The CLI's private writers (protected DACL through the Windows interop, 0600 elsewhere) behind the shared `:config` stores. */
internal object CliPrivateFiles : PrivateFiles {
    override fun write(path: String, content: String) = writePrivateFile(path, content)
    override fun create(path: String, content: String): Boolean = createPrivateFile(path, content)
}

fun configPath(env: (String) -> String? = ::environment): String = me.nathanfallet.teamcode.config.configPath(env)

internal fun configStore(path: String = configPath()): ConfigStore = ConfigStore(path, CliPrivateFiles)

/** The installation ID shared with the desktop app, next to `config.json`. */
internal fun installationId(): String = InstallationIdStore(configSibling(configPath(), "installation-id"), CliPrivateFiles).id()
