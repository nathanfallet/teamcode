package me.nathanfallet.teamcode.companion.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import me.nathanfallet.teamcode.companion.presentation.screens.AddServerScreen
import me.nathanfallet.teamcode.companion.presentation.screens.HomeScreen
import me.nathanfallet.teamcode.companion.presentation.screens.LoginScreen
import me.nathanfallet.teamcode.companion.presentation.screens.SettingsScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TeamCodeNavHost(rootViewModel: RootViewModel = koinViewModel()) {
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    // Captured once, as in nutrimaxing: the first sign-in navigates explicitly instead of rebuilding the graph.
    val startDestination = remember { if (rootViewModel.hasAccounts.value) HomeRoute else AddServerRoute }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<AddServerRoute> {
            AddServerScreen(
                canGoBack = navController.previousBackStackEntry != null,
                onBack = { navController.popBackStack() },
                onContinue = { origin -> navController.navigate(LoginRoute(origin)) },
            )
        }
        composable<LoginRoute> { entry ->
            LoginScreen(
                route = entry.toRoute(),
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(AddServerRoute) },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
            )
        }
    }

    // The last account signed out: back to adding a server.
    LaunchedEffect(hasAccounts) {
        if (!hasAccounts) navController.navigate(AddServerRoute) { popUpTo(0) { inclusive = true } }
    }
}
