package me.nathanfallet.teamcode.companion

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule

fun main() {
    initKoin(platformModule())
    application {
        Window(onCloseRequest = ::exitApplication, title = "TeamCode", state = rememberWindowState(width = 1000.dp, height = 720.dp)) {
            App()
        }
    }
}
