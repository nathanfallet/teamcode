package me.nathanfallet.teamcode.companion

import androidx.compose.ui.window.ComposeUIViewController
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule

private var koinStarted = false

private fun startKoinOnce() {
    if (!koinStarted) {
        koinStarted = true
        initKoin(platformModule())
    }
}

@Suppress("FunctionName", "unused") // Called from Swift (ContentView.swift).
fun MainViewController() = ComposeUIViewController(configure = { startKoinOnce() }) { App() }
