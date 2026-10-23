package me.nathanfallet.teamcode.companion.presentation

import me.nathanfallet.teamcode.companion.presentation.navigation.AddServerRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.MainRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.destinationAfterAccountsChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stack follows the account list: the tabs once there is an account, adding a server once there is none. */
class NavigationTest {
    @Test fun anAccountAddedOutsideTheSignInLeavesThePagesBeforeAnyAccount() {
        // The desktop's first launch: the window opens on adding a server, then the CLI's account is described.
        assertEquals(MainRoute, destinationAfterAccountsChange(had = false, has = true))
    }

    @Test fun theLastAccountSignedOutGoesBackToAddingAServer() {
        assertEquals(AddServerRoute, destinationAfterAccountsChange(had = true, has = false))
    }

    @Test fun anUnchangedListStaysWhereItIs() {
        assertNull(destinationAfterAccountsChange(had = true, has = true))
        assertNull(destinationAfterAccountsChange(had = false, has = false))
    }
}
