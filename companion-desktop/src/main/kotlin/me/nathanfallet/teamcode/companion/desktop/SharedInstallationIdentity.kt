package me.nathanfallet.teamcode.companion.desktop

import com.russhwolf.settings.Settings
import java.io.File
import me.nathanfallet.teamcode.companion.domain.services.InstallationIdentity
import me.nathanfallet.teamcode.config.InstallationIdStore
import me.nathanfallet.teamcode.config.JvmPrivateFiles
import me.nathanfallet.teamcode.config.configSibling
import me.nathanfallet.teamcode.config.isInstallationId

/**
 * The CLI's `installation-id`, beside `config.json`: the app and the CLI of one computer are one device on the server.
 * An app that drew its own id before (in its preferences) publishes it there when the CLI has none yet, so its device
 * keeps its name and sessions; the file is created without replacement, so a CLI that wrote one first wins.
 */
class SharedInstallationIdentity(configPath: String, private val settings: Settings) : InstallationIdentity {
    private val path = configSibling(configPath, "installation-id")

    override fun id(): String {
        if (!File(path).exists()) settings.getStringOrNull(LEGACY_KEY)?.takeIf(::isInstallationId)?.let { JvmPrivateFiles.create(path, it) }
        return InstallationIdStore(path, JvmPrivateFiles).id()
    }

    private companion object {
        /** The key of `SettingsInstallationIdentity`, the app's own id before it shared the CLI's. */
        const val LEGACY_KEY = "installation.id"
    }
}
