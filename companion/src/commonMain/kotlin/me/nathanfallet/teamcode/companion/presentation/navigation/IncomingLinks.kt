package me.nathanfallet.teamcode.companion.presentation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Links handed to the app by the system (`teamcode://open?url=…`, a shared text on Android), kept until the
 * navigation reads them: a link that starts the app arrives before its first screen.
 */
object IncomingLinks {
    private val pending = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = pending.asStateFlow()

    /** Called by the platform shells. */
    fun open(link: String) { pending.value = link }

    fun consumed(link: String) { pending.compareAndSet(link, null) }
}
