package me.nathanfallet.teamcode.companion.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.links.LinkTarget
import me.nathanfallet.teamcode.companion.domain.usecases.links.ResolveLinkUseCase
import me.nathanfallet.teamcode.companion.presentation.components.ButtonKind
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.screens.AddServerScreen
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactScreen
import me.nathanfallet.teamcode.companion.presentation.screens.ArtifactsScreen
import me.nathanfallet.teamcode.companion.presentation.screens.LoginScreen
import me.nathanfallet.teamcode.companion.presentation.screens.MainScreen
import me.nathanfallet.teamcode.companion.presentation.screens.SessionScreen
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_cancel
import me.nathanfallet.teamcode.companion.resources.link_new_server
import me.nathanfallet.teamcode.companion.resources.link_new_server_confirm
import me.nathanfallet.teamcode.companion.resources.link_new_server_title
import me.nathanfallet.teamcode.companion.resources.session_title_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Before any account, a stack without tabs: add a server, sign in, change a temporary password. Then the tabs
 * ([MainRoute]), with sessions and artifacts pushed above them.
 */
@Composable
fun TeamCodeNavHost(rootViewModel: RootViewModel = koinViewModel()) {
    val hasAccounts by rootViewModel.hasAccounts.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    // Captured once: the first sign-in navigates explicitly instead of rebuilding the graph.
    val startDestination = remember { if (rootViewModel.hasAccounts.value) MainRoute else AddServerRoute }
    val resolveLink: ResolveLinkUseCase = koinInject()
    // A link to a server no account is on: shown before any sign-in page, with its origin in full.
    var newServer by remember { mutableStateOf<String?>(null) }
    /** Opens an artifact link; false when it is not one. */
    fun openLink(link: String): Boolean = when (val target = resolveLink(link)) {
        is LinkTarget.Artifact -> { navController.navigate(ArtifactRoute(target.accountId, target.artifactId, target.version)); true }
        is LinkTarget.SignInAgain -> { navController.navigate(LoginRoute(target.origin, target.username)); true }
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
                    navController.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<MainRoute> {
            MainScreen(
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
                onOpenSession = { accountId, sessionId -> navController.navigate(SessionRoute(accountId, sessionId)) },
                onOpenArtifact = { accountId, artifactId -> navController.navigate(ArtifactRoute(accountId, artifactId)) },
                onOpenLink = ::openLink,
                onAddAccount = { navController.navigate(AddServerRoute) },
            )
        }
        composable<ArtifactsRoute> { entry ->
            val route = entry.toRoute<ArtifactsRoute>()
            val viewModel: ArtifactsViewModel = koinViewModel { parametersOf(route) }
            ArtifactsScreen(viewModel, forSession = route.sessionId != null, onBack = { navController.popBackStack() },
                onOpen = { artifact -> navController.navigate(ArtifactRoute(route.accountId, artifact.id)) }, onOpenLink = ::openLink,
                backLabel = stringResource(Res.string.session_title_back))
        }
        composable<ArtifactRoute> { entry ->
            val route = entry.toRoute<ArtifactRoute>()
            val viewModel: ArtifactViewModel = koinViewModel { parametersOf(route) }
            ArtifactScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            val viewModel: SessionViewModel = koinViewModel { parametersOf(route) }
            val accounts: AccountRepository = koinInject()
            SessionScreen(viewModel, memberId = accounts.accounts.value.firstOrNull { it.id == route.accountId }?.memberId, onBack = { navController.popBackStack() },
                onOpenArtifacts = { navController.navigate(ArtifactsRoute(route.accountId, route.sessionId)) })
        }
    }

    newServer?.let { origin ->
        Dialog(onDismissRequest = { newServer = null }) {
            val colors = TeamCode.colors
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.link_new_server_title), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.link_new_server, origin), style = MonospaceStyle, color = colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TcButton(stringResource(Res.string.action_cancel), onClick = { newServer = null }, kind = ButtonKind.SECONDARY, modifier = Modifier.weight(1f))
                    TcButton(stringResource(Res.string.link_new_server_confirm), onClick = { newServer = null; navController.navigate(LoginRoute(origin)) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    // The last account signed out: back to adding a server. Only on that change, not on every new composition (a
    // rotation while signing in for the first time keeps the login screen).
    var hadAccounts by rememberSaveable { mutableStateOf(hasAccounts) }
    LaunchedEffect(hasAccounts) {
        if (hadAccounts && !hasAccounts) navController.navigate(AddServerRoute) { popUpTo(0) { inclusive = true } }
        hadAccounts = hasAccounts
    }
}
