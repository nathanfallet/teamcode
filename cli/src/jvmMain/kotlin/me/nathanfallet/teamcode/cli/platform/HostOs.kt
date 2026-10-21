package me.nathanfallet.teamcode.cli.platform

internal actual fun hostOs(): String = System.getProperty("os.name").orEmpty().lowercase().let {
    when { it.startsWith("mac") -> "macos"; it.startsWith("windows") -> "windows"; else -> "linux" }
}
