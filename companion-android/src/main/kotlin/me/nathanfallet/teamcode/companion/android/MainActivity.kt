package me.nathanfallet.teamcode.companion.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.nathanfallet.teamcode.companion.App
import me.nathanfallet.teamcode.companion.presentation.navigation.IncomingLinks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (a rotation) already handled the link that started it.
        if (savedInstanceState == null) handle(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** An artifact link from the viewer page or shared as text; the app checks it before opening anything. */
    private fun handle(intent: Intent?) {
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("teamcode://") }
            else -> null
        }
        link?.take(MAX_LINK)?.let(IncomingLinks::open)
    }

    private companion object {
        const val MAX_LINK = 4_096
    }
}
