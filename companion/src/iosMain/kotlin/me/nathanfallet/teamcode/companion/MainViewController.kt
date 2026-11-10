package me.nathanfallet.teamcode.companion

import androidx.compose.ui.window.ComposeUIViewController
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule
import me.nathanfallet.teamcode.companion.di.startPush
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform
import me.nathanfallet.teamcode.companion.infrastructure.push.IosPushKeys
import me.nathanfallet.teamcode.companion.infrastructure.push.IosPushPlatform
import me.nathanfallet.teamcode.companion.presentation.navigation.IncomingLinks
import org.koin.dsl.module

private var koinStarted = false
private var push: IosPushPlatform? = null

private fun startKoinOnce() {
    if (!koinStarted) {
        koinStarted = true
        val platform = push
        val koin = initKoin(platformModule()) { platform?.let { modules(module { single<PushPlatform> { platform } }) } }.koin
        if (platform != null) startPush(koin)
    }
}

@Suppress("FunctionName", "unused") // Called from Swift (ContentView.swift).
fun MainViewController() = ComposeUIViewController(configure = { startKoinOnce() }) { App() }

/** A link the system handed to the app (ContentView.swift's `onOpenURL`, a tapped notification). */
@Suppress("unused") // Called from Swift.
fun openLink(link: String) = IncomingLinks.open(link.take(4_096))

/** Push is on in this build (AppDelegate.swift, with GoogleService-Info.plist): called before the first screen. */
@Suppress("unused") // Called from Swift.
fun enablePush(keys: IosPushKeys) { if (push == null) push = IosPushPlatform(keys) }

/** FCM gave or rotated the installation's token (AppDelegate.swift's MessagingDelegate). */
@Suppress("unused") // Called from Swift.
fun pushTokenChanged(token: String?) { push?.updateToken(token) }
