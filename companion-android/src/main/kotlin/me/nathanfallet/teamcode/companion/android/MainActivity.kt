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
        // Relaunched from Recents, the activity gets its first intent again: that link was already opened.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            // Some apps share styled text, which getStringExtra would not return.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("teamcode://") }
            else -> null
        }
        link?.take(MAX_LINK)?.let(IncomingLinks::open)
    }

    private companion object {
        const val MAX_LINK = 4_096
    }
}
