package me.nathanfallet.teamcode.api

import me.nathanfallet.teamcode.api.models.AccountView
import me.nathanfallet.teamcode.api.models.accountLabel
import kotlin.test.*

class AccountLabelTest {
    @Test fun accountsAreNamedWithTheirEmailWhenItIsKnown() {
        assertEquals("Ada (ada@example.com)", accountLabel("Ada", "ada@example.com"))
        assertEquals("Ada", accountLabel("Ada", null), "Unknown email: the name alone")
        assertEquals("Ada", accountLabel("Ada", " "))
        assertEquals("ada@example.com", accountLabel("ada@example.com", "ADA@example.com"), "A name that already is the email is not repeated")
        assertEquals("Ada (ada@example.com)", AccountView("id", "anthropic", "Ada", 1, false, 0, "READY", email = "ada@example.com").label)
    }
}
