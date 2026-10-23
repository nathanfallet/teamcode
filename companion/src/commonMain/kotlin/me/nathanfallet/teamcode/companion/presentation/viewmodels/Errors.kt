package me.nathanfallet.teamcode.companion.presentation.viewmodels

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.services.LocalHostException

/** The sentence shown for a failure: the domain's own messages, never a server-provided text. */
internal fun Throwable.userMessage(): String = when (this) {
    is CompanionException.RateLimited -> retryAfterSeconds?.let { "Too many attempts. Try again in $it s." } ?: (message ?: "")
    is CompanionException -> message ?: "Something went wrong."
    // The CLI's own explanation (a folder refused, a server refusal): TeamCode's text, never a response body.
    is LocalHostException -> message
    is IllegalArgumentException -> message ?: "Check what you entered."
    else -> "Something went wrong."
}
