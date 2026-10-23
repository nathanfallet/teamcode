package me.nathanfallet.teamcode.companion.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import me.nathanfallet.teamcode.companion.presentation.screens.AddServerScreen
import me.nathanfallet.teamcode.companion.presentation.screens.HomeScreen
import me.nathanfallet.teamcode.companion.presentation.screens.LoginScreen
import me.nathanfallet.teamcode.companion.presentation.screens.NewSessionScreen
import me.nathanfallet.teamcode.companion.presentation.screens.SessionScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.screens.SettingsScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import me.nathanfallet.teamcode.companion.domain.usecases.links.LinkTarget
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactScreen
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactsScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.link_new_server
import me.nathanfallet.teamcode.companion.resources.link_new_server_confirm
import me.nathanfallet.teamcode.companion.resources.link_new_server_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TeamCodeNavHost(rootViewModel: RootViewModel = koinViewModel()) {
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    // Captured once: the first sign-in navigates explicitly instead of rebuilding the graph.
    val startDestination = remember { if (rootViewModel.hasAccounts.value) HomeRoute else AddServerRoute }
    val resolveLink: ResolveLinkUseCase = koinInject()
    // A link to a server no account is on: shown before any sign-in page, with its origin in full.
    var newServer by remember { mutableStateOf<String?>(null) }
    /** Opens an artifact link; false when it is not one. */
    fun openLink(link: String): Boolean = when (val target = resolveLink(link)) {
        is LinkTarget.Artifact -> { navController.navigate(ArtifactRoute(target.accountId, target.artifactId, target.version)); true }
        is LinkTarget.UnknownServer -> { newServer = target.origin; true }
        null -> false
    }
    val incoming by IncomingLinks.link.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        val link = incoming ?: return@LaunchedEffect
        IncomingLinks.consumed(link)
        openLink(link)
    }

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
                onOpenSession = { account, session -> navController.navigate(SessionRoute(account.id, session.id)) },
                onNewSession = { account -> navController.navigate(NewSessionRoute(account.id)) },
                onOpenArtifacts = { account -> navController.navigate(ArtifactsRoute(account.id)) },
            )
        }
        composable<ArtifactsRoute> { entry ->
            val route = entry.toRoute<ArtifactsRoute>()
            val viewModel: ArtifactsViewModel = koinViewModel { parametersOf(route) }
            ArtifactsScreen(viewModel, forSession = route.sessionId != null, onBack = { navController.popBackStack() },
                onOpen = { artifact -> navController.navigate(ArtifactRoute(route.accountId, artifact.id)) }, onOpenLink = ::openLink)
        }
        composable<ArtifactRoute> { entry ->
            val route = entry.toRoute<ArtifactRoute>()
            val viewModel: ArtifactViewModel = koinViewModel { parametersOf(route) }
            ArtifactScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<NewSessionRoute> { entry ->
            val route = entry.toRoute<NewSessionRoute>()
            val viewModel: NewSessionViewModel = koinViewModel { parametersOf(route) }
            NewSessionScreen(viewModel, onBack = { navController.popBackStack() }, onCreated = { session ->
                navController.navigate(SessionRoute(route.accountId, session.id)) { popUpTo<NewSessionRoute> { inclusive = true } }
            })
        }
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            val viewModel: SessionViewModel = koinViewModel { parametersOf(route) }
            val accounts: AccountRepository = koinInject()
            SessionScreen(viewModel, memberId = accounts.accounts.value.firstOrNull { it.id == route.accountId }?.memberId, onBack = { navController.popBackStack() },
                onOpenArtifacts = { navController.navigate(ArtifactsRoute(route.accountId, route.sessionId)) })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(AddServerRoute) },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
            )
        }
    }

    newServer?.let { origin ->
        AlertDialog(
            onDismissRequest = { newServer = null },
            title = { Text(stringResource(Res.string.link_new_server_title)) },
            text = { Text(stringResource(Res.string.link_new_server, origin), style = MonospaceStyle) },
            confirmButton = { TextButton(onClick = { newServer = null; navController.navigate(LoginRoute(origin)) }) { Text(stringResource(Res.string.link_new_server_confirm)) } },
            dismissButton = { TextButton(onClick = { newServer = null }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }

    // The last account signed out: back to adding a server. Only on that change, not on every new composition (a
    // rotation while signing in for the first time keeps the login screen).
    var hadAccounts by rememberSaveable { mutableStateOf(hasAccounts) }
    LaunchedEffect(hasAccounts) {
        if (hadAccounts && !hasAccounts) navController.navigate(AddServerRoute) { popUpTo(0) { inclusive = true } }
        hadAccounts = hasAccounts
    }
}
