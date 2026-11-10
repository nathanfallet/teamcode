package me.nathanfallet.teamcode.companion.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import org.koin.core.context.GlobalContext
import me.nathanfallet.teamcode.companion.App
import me.nathanfallet.teamcode.companion.presentation.navigation.IncomingLinks

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (a rotation) already handled the link that started it.
        if (savedInstanceState == null) handle(intent)
        setContent { App() }
        askForNotifications()
    }

    /**
     * Android 13+ shows pushes only with the member's permission: asked once, when an account is first signed in (a
     * prompt before any sign-in would not say what for). Refused, it stays off until the member turns it on in Settings.
     */
    private fun askForNotifications() {
        if (!PushNotifications.configured || Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(PUSH_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(ASKED, false)) return
        val accounts = GlobalContext.get().get<AccountRepository>()
        lifecycleScope.launch {
            accounts.accounts.first { list -> list.any { it.signedIn } }
            if (preferences.getBoolean(ASKED, false)) return@launch
            preferences.edit().putBoolean(ASKED, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        const val PUSH_PREFERENCES = "teamcode_push"
        const val ASKED = "notifications_asked"
    }
}
