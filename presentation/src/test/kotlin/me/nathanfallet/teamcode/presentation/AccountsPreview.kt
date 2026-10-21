package me.nathanfallet.teamcode.presentation

import java.nio.file.Files
import java.nio.file.Path
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.*

/** Generate a standalone preview of the subscription accounts page through the real renderer, with synthetic accounts. */
fun main(args: Array<String>) {
    val target = Path.of(args.single()).toAbsolutePath()
    val actor = Principal(Member("preview-admin", "admin", Role.ADMIN, false, false), "preview-session", "default")
    fun account(id: String, name: String, state: AccountState, email: String?) = AccountRecord(id, AccountIdentity("default", "anthropic", id, "org"), name, 1, false, null, 0, state, email)
    val accounts = listOf(account("preview-1", "Max one", AccountState.PENDING_HANDOVER, "max-one@example.com"), account("preview-2", "Max two", AccountState.PENDING_HANDOVER, null))
    Files.createDirectories(target.parent)
    // The confirmation of the first account is shown open, as after a click on Handover.
    val content = accountsContent(actor, accounts).replaceFirst("<details class=\"confirm\">", "<details class=\"confirm\" open>")
    Files.writeString(target, page("Subscription accounts · synthetic preview", content))
    println("Synthetic accounts preview: $target")
}
