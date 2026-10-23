package me.nathanfallet.teamcode.cli.platform

import me.nathanfallet.teamcode.config.JvmPrivateFiles

actual fun writePrivateFile(path: String, content: String) { JvmPrivateFiles.write(path, content) }
actual fun createPrivateFile(path: String, content: String): Boolean = JvmPrivateFiles.create(path, content)
