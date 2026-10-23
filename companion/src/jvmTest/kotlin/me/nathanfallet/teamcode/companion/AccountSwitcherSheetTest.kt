package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.screens.AccountSwitcherSheet
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import kotlin.test.*

/** The account switcher on the desktop target: the active account checked, another one made active, a signed-out one signed in again. */
@OptIn(ExperimentalTestApi::class)
class AccountSwitcherSheetTest {
    @Test fun anotherAccountIsChosenAndASignedOutOneSignsInAgain() = runComposeUiTest {
        val active = account("a1", username = "ada")
        val other = account("a2", origin = "https://other.example", memberId = "m2", username = "grace")
        val signedOut = account("a3", origin = "https://old.example", memberId = "m3", username = "alan", signedIn = false)
        var switched: Account? = null
        var signIn: Account? = null
        var added = false
        var managed = false
        setContent {
            TeamCodeTheme {
                AccountSwitcherSheet(listOf(active, other, signedOut), activeAccountId = "a1", onSwitch = { switched = it }, onSignInAgain = { signIn = it },
                    onAddAccount = { added = true }, onManage = { managed = true }, onDismiss = {})
            }
        }

        onNodeWithTag("switch-ada-team.example").assertIsSelected()
        onNodeWithTag("switch-grace-other.example").performClick()
        assertEquals("a2", switched?.id)
        onNodeWithText("signed out").assertExists()
        onNodeWithText("Sign in").performClick()
        assertEquals("a3", signIn?.id)
        onNodeWithTag("accounts-add").performClick()
        assertTrue(added)
        onNodeWithTag("accounts-manage").performClick()
        assertTrue(managed)
    }
}
