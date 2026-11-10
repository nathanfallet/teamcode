package me.nathanfallet.teamcode.companion.presentation.notifications

import me.nathanfallet.teamcode.companion.domain.usecases.push.ActivityKind
import me.nathanfallet.teamcode.companion.domain.usecases.push.ActivityNotice
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.activity_permission
import me.nathanfallet.teamcode.companion.resources.activity_question
import me.nathanfallet.teamcode.companion.resources.activity_session_failed
import me.nathanfallet.teamcode.companion.resources.activity_turn_completed
import me.nathanfallet.teamcode.companion.resources.activity_turn_failed
import me.nathanfallet.teamcode.companion.resources.activity_wait
import org.jetbrains.compose.resources.getString

/** A desktop notification's title (the session's name, else "TeamCode") and text, in the app's language. */
suspend fun ActivityNotice.text(): Pair<String, String> {
    val body = when (kind) {
        ActivityKind.PERMISSION -> getString(Res.string.activity_permission)
        ActivityKind.QUESTION -> getString(Res.string.activity_question)
        ActivityKind.WAIT -> getString(Res.string.activity_wait, detail.orEmpty().take(200))
        ActivityKind.TURN_COMPLETED -> getString(Res.string.activity_turn_completed)
        ActivityKind.TURN_FAILED -> getString(Res.string.activity_turn_failed)
        ActivityKind.SESSION_FAILED -> getString(Res.string.activity_session_failed)
    }
    return (title?.takeIf { it.isNotBlank() }?.take(120) ?: "TeamCode") to body
}
