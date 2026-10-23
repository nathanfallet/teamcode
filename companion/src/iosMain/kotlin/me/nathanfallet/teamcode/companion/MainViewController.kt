package me.nathanfallet.teamcode.companion

import androidx.compose.ui.window.ComposeUIViewController
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule
import me.nathanfallet.teamcode.companion.presentation.navigation.IncomingLinks

private var koinStarted = false

private fun startKoinOnce() {
    if (!koinStarted) {
        koinStarted = true
        initKoin(platformModule())
    }
}

@Suppress("FunctionName", "unused") // Called from Swift (ContentView.swift).
fun MainViewController() = ComposeUIViewController(configure = { startKoinOnce() }) { App() }

/** A link the system handed to the app (ContentView.swift's `onOpenURL`). */
@Suppress("unused") // Called from Swift.
fun openLink(link: String) = IncomingLinks.open(link.take(4_096))
