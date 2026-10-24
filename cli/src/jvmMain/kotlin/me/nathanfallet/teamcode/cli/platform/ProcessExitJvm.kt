package me.nathanfallet.teamcode.cli.platform

actual fun processRunning(pid: Long): Boolean = pid > 0 && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
