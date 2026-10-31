package me.nathanfallet.teamcode.companion.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import me.nathanfallet.teamcode.companion.presentation.screens.AutomationEditScreen
import me.nathanfallet.teamcode.companion.presentation.screens.WorkflowEditScreen
import me.nathanfallet.teamcode.companion.presentation.screens.WorkflowRunScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditViewModel
import me.nathanfallet.teamcode.companion.presentation.screens.WorkflowScreen
import me.nathanfallet.teamcode.companion.presentation.screens.WorkflowStartScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowStartViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowViewModel
import me.nathanfallet.teamcode.companion.presentation.screens.AutomationScreen
import me.nathanfallet.teamcode.companion.presentation.screens.AutomationTestScreen
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationTestViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationViewModel
import me.nathanfallet.teamcode.companion.presentation.components.LocalWideLayout
import me.nathanfallet.teamcode.companion.presentation.components.windowTitleBarPadding
import me.nathanfallet.teamcode.companion.presentation.screens.WideMainScreen
import me.nathanfallet.teamcode.companion.resources.sessions_title
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
import me.nathanfallet.teamcode.companion.presentation.screens.MainTab
import me.nathanfallet.teamcode.companion.presentation.screens.SessionScreen
import me.nathanfallet.teamcode.companion.presentation.screens.GitHubLinkScreen
import me.nathanfallet.teamcode.companion.presentation.screens.McpServersScreen
import me.nathanfallet.teamcode.companion.presentation.screens.SubscriptionAccountsScreen
import me.nathanfallet.teamcode.companion.presentation.screens.TransferSheet
import me.nathanfallet.teamcode.companion.presentation.screens.TransfersScreen
import me.nathanfallet.teamcode.companion.presentation.components.SheetViewModels
import me.nathanfallet.teamcode.companion.presentation.components.TransferMenuItem
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import me.nathanfallet.teamcode.companion.presentation.theme.MonospaceStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.wfe_copy_suffix
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
    // A wide window (desktop, tablet, a wide split screen): the signed-in app is a sidebar and a content panel, whose
    // own navigation is [detail]. A narrow one keeps the tabs, with screens pushed on [navController].
    val wide = LocalWideLayout.current
    val detail = rememberNavController()
    // Captured once: the first sign-in navigates explicitly instead of rebuilding the graph.
    val startDestination = remember { if (rootViewModel.hasAccounts.value) MainRoute else AddServerRoute }
    val resolveLink: ResolveLinkUseCase = koinInject()
    val accountRepository: AccountRepository = koinInject()
    // A link to a server no account is on: shown before any sign-in page, with its origin in full.
    var newServer by remember { mutableStateOf<String?>(null) }
    /** Opens an artifact link; false when it is not one. */
    fun openLink(link: String): Boolean = when (val target = resolveLink(link)) {
        is LinkTarget.Artifact -> {
            val route = ArtifactRoute(target.accountId, target.artifactId, target.version)
            if (wide && navController.currentBackStackEntry?.destination?.hasRoute<MainRoute>() == true) detail.navigate(route) else navController.navigate(route)
            true
        }
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

    // The window crossed the 600 dp breakpoint (a resized window, a split screen changed): what was open stays open.
    // Narrow to wide, the screens pushed over the tabs move to the content panel; wide to narrow, the panel's screens
    // are pushed over the tabs.
    var pendingDetail by remember { mutableStateOf<List<Any>>(emptyList()) }
    // The phone layout's tab: the panel opens on the same list, and the tab on the panel's first screen.
    var tab by rememberSaveable { mutableStateOf(MainTab.SESSIONS) }
    var wasWide by remember { mutableStateOf(wide) }
    LaunchedEffect(wide) {
        if (wide == wasWide) return@LaunchedEffect
        wasWide = wide
        if (wide) {
            val stack = navController.currentBackStack.value
            val main = stack.indexOfLast { it.destination.hasRoute<MainRoute>() }
            val above = if (main < 0) emptyList() else stack.drop(main + 1)
            val routes = above.mapNotNull { it.detailRoute() }
            // Only screens the panel shows: a sign-in in progress stays where it is.
            if (routes.size == above.size && main >= 0) {
                if (routes.isNotEmpty()) navController.popBackStack<MainRoute>(inclusive = false)
                // The tab's list first (a session opened from the artifacts goes back to them), unless a pushed screen is already its own.
                val first = tabRoute(tab, accountRepository.activeAccountId.value)
                pendingDetail = listOfNotNull(first.takeIf { it != routes.firstOrNull() }) + routes
            }
        } else if (navController.currentBackStackEntry?.destination?.hasRoute<MainRoute>() == true) {
            val panel = detail.currentBackStack.value.filter { it.destination !is androidx.navigation.NavGraph }
            val root = panel.firstOrNull()?.let { panelTab(it) }
            // The panel's first screen is a tab's (the sessions, the artifacts, Settings): that tab opens, the screens above are pushed.
            tab = root ?: MainTab.SESSIONS
            panel.drop(if (root != null) 1 else 0).mapNotNull { it.detailRoute() }.forEach { navController.navigate(it) }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        // The sign-in screens fill the window: below macOS's title bar when the app draws under it.
        composable<AddServerRoute> {
            Box(Modifier.windowTitleBarPadding()) { AddServerScreen(
                canGoBack = navController.previousBackStackEntry != null,
                onBack = { navController.popBackStack() },
                onContinue = { origin -> navController.navigate(LoginRoute(origin)) },
            ) }
        }
        composable<LoginRoute> { entry ->
            Box(Modifier.windowTitleBarPadding()) { LoginScreen(
                route = entry.toRoute(),
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    // The first account's arrival may already have opened the tabs.
                    if (navController.currentBackStackEntry?.destination?.hasRoute<MainRoute>() != true)
                        navController.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                },
            ) }
        }
        composable<MainRoute> {
            if (wide) WideMainScreen(
                detail = detail,
                pending = pendingDetail,
                onPendingShown = { pendingDetail = emptyList() },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
                onOpenLink = ::openLink,
                onAddAccount = { navController.navigate(AddServerRoute) },
            ) else MainScreen(
                initialTab = tab,
                onTabChange = { tab = it },
                onSignInAgain = { account -> navController.navigate(LoginRoute(account.origin, account.username)) },
                onOpenSession = { accountId, sessionId -> navController.navigate(SessionRoute(accountId, sessionId)) },
                onOpenArtifact = { accountId, artifactId -> navController.navigate(ArtifactRoute(accountId, artifactId)) },
                onOpenLink = ::openLink,
                onAddAccount = { navController.navigate(AddServerRoute) },
                onOpenTransfers = { accountId -> navController.navigate(TransfersRoute(accountId)) },
                onOpenSubscriptions = { accountId -> navController.navigate(SubscriptionAccountsRoute(accountId)) },
                onOpenGitHub = { accountId -> navController.navigate(GitHubLinkRoute(accountId)) },
                onOpenMcp = { accountId -> navController.navigate(McpServersRoute(accountId)) },
                onOpenAutomation = { accountId, automationId -> navController.navigate(AutomationRoute(accountId, automationId)) },
                onCreateAutomation = { accountId -> navController.navigate(AutomationEditRoute(accountId)) },
                onOpenWorkflowRun = { accountId, runId -> navController.navigate(WorkflowRunRoute(accountId, runId)) },
                onOpenWorkflow = { accountId, workflowId -> navController.navigate(WorkflowRoute(accountId, workflowId)) },
                onStartWorkflow = { accountId, workflowId -> navController.navigate(WorkflowStartRoute(accountId, workflowId)) },
                onCreateWorkflow = { accountId -> navController.navigate(WorkflowEditRoute(accountId)) },
                onDuplicateWorkflow = { accountId, workflowId, suffix -> navController.navigate(WorkflowEditRoute(accountId, copyOf = workflowId, copySuffix = suffix)) },
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
            SessionDestination(entry.toRoute(), onBack = { navController.popBackStack() }, onOpenArtifacts = { navController.navigate(it) })
        }
        composable<SubscriptionAccountsRoute> { entry ->
            val route = entry.toRoute<SubscriptionAccountsRoute>()
            val viewModel: SubscriptionAccountsViewModel = koinViewModel { parametersOf(route) }
            SubscriptionAccountsScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<GitHubLinkRoute> { entry ->
            val route = entry.toRoute<GitHubLinkRoute>()
            val viewModel: GitHubLinkViewModel = koinViewModel { parametersOf(route) }
            GitHubLinkScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<McpServersRoute> { entry ->
            val route = entry.toRoute<McpServersRoute>()
            val viewModel: McpServersViewModel = koinViewModel { parametersOf(route) }
            McpServersScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable<TransfersRoute> { entry ->
            val route = entry.toRoute<TransfersRoute>()
            val viewModel: TransfersViewModel = koinViewModel { parametersOf(route) }
            TransfersScreen(viewModel, onBack = { navController.popBackStack() }, onOpenSession = { navController.navigate(SessionRoute(route.accountId, it)) })
        }
        automationDestinations(navController)
        // Waiting for you is a tab of the phone: back to the tabs, on it.
        workflowDestinations(navController, onOpenWaits = { tab = MainTab.WAITS; navController.popBackStack(MainRoute, inclusive = false) })
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

    // Only on a change of the account list, not on every new composition (a rotation while signing in for the first
    // time keeps the login screen).
    var hadAccounts by rememberSaveable { mutableStateOf(hasAccounts) }
    LaunchedEffect(hasAccounts) {
        destinationAfterAccountsChange(hadAccounts, hasAccounts)
            // The sign-in that saved the account may already have opened the tabs.
            ?.takeUnless { it == MainRoute && navController.currentBackStackEntry?.destination?.hasRoute<MainRoute>() == true }
            ?.let { navController.navigate(it) { popUpTo(0) { inclusive = true } } }
        hadAccounts = hasAccounts
    }
}

/** Where the whole stack goes when the account list goes from [had] accounts to [has]; null to stay where it is. */
internal fun destinationAfterAccountsChange(had: Boolean, has: Boolean): Any? = when {
    // The last account signed out: back to adding a server.
    had && !has -> AddServerRoute
    // A first account, saved by a sign-in or from outside it (the desktop's CLI account, described after the window
    // opened on adding a server): the tabs.
    !had && has -> MainRoute
    else -> null
}

/** A session with its "…" menu and the transfer sheet; [onOpenArtifacts] opens the session's artifacts. */
@Composable
internal fun SessionDestination(
    route: SessionRoute, onBack: (() -> Unit)?, onOpenArtifacts: (ArtifactsRoute) -> Unit, backLabel: String = stringResource(Res.string.sessions_title),
) {
    val viewModel: SessionViewModel = koinViewModel { parametersOf(route) }
    val accounts: AccountRepository = koinInject()
    var transferring by remember { mutableStateOf<TransferRoute?>(null) }
    SessionScreen(viewModel, memberId = accounts.accounts.value.firstOrNull { it.id == route.accountId }?.memberId, onBack = onBack,
        onOpenArtifacts = { onOpenArtifacts(ArtifactsRoute(route.accountId, route.sessionId)) }, backLabel = backLabel,
        menuItems = { session, close -> TransferMenuItem(route.accountId, session) { close(); transferring = TransferRoute(route.accountId, session.id, session.ownerId) } })
    transferring?.let { transfer ->
        SheetViewModels {
            val sheet: TransferViewModel = koinViewModel { parametersOf(transfer) }
            TransferSheet(sheet, onDismiss = { transferring = null })
        }
    }
}

/** The workflow screens pushed over the phone's tabs: a run, a saved workflow, the form that starts one; starting lands on the new run. */
private fun NavGraphBuilder.workflowDestinations(navController: NavHostController, onOpenWaits: () -> Unit) {
    composable<WorkflowRunRoute> { entry ->
        val route = entry.toRoute<WorkflowRunRoute>()
        val viewModel: WorkflowRunViewModel = koinViewModel { parametersOf(route) }
        WorkflowRunScreen(viewModel, onBack = { navController.popBackStack() }, onOpenSession = { navController.navigate(SessionRoute(route.accountId, it)) },
            onOpenRun = { navController.navigate(WorkflowRunRoute(route.accountId, it)) }, onOpenWaits = onOpenWaits)
    }
    composable<WorkflowRoute> { entry ->
        val route = entry.toRoute<WorkflowRoute>()
        val viewModel: WorkflowViewModel = koinViewModel { parametersOf(route) }
        val suffix = stringResource(Res.string.wfe_copy_suffix)
        WorkflowScreen(viewModel, onBack = { navController.popBackStack() }, onStart = { navController.navigate(WorkflowStartRoute(route.accountId, it.id)) },
            onEdit = { navController.navigate(WorkflowEditRoute(route.accountId, it.id)) },
            onDuplicate = { navController.navigate(WorkflowEditRoute(route.accountId, copyOf = it.id, copySuffix = suffix)) },
            onDeleted = { navController.popBackStack() })
    }
    composable<WorkflowEditRoute> { entry ->
        val route = entry.toRoute<WorkflowEditRoute>()
        val viewModel: WorkflowEditViewModel = koinViewModel { parametersOf(route) }
        WorkflowEditScreen(viewModel, onBack = { navController.popBackStack() }, onSaved = { id ->
            // A new workflow replaces the form with its page; a changed one goes back to the page it came from.
            if (route.workflowId == null) navController.navigate(WorkflowRoute(route.accountId, id)) { popUpTo<WorkflowEditRoute> { inclusive = true } } else navController.popBackStack()
        })
    }
    composable<WorkflowStartRoute> { entry ->
        val route = entry.toRoute<WorkflowStartRoute>()
        val viewModel: WorkflowStartViewModel = koinViewModel { parametersOf(route) }
        WorkflowStartScreen(viewModel, onBack = { navController.popBackStack() }, onStarted = { run ->
            navController.navigate(WorkflowRunRoute(route.accountId, run.id)) { popUpTo<WorkflowStartRoute> { inclusive = true } }
        })
    }
}

/** The automation screens pushed over the phone's tabs: the page, the form, the test; each leaves for the page or the list when done. */
private fun NavGraphBuilder.automationDestinations(navController: NavHostController) {
    composable<AutomationRoute> { entry ->
        val route = entry.toRoute<AutomationRoute>()
        val viewModel: AutomationViewModel = koinViewModel { parametersOf(route) }
        AutomationScreen(viewModel, onBack = { navController.popBackStack() },
            onEdit = { navController.navigate(AutomationEditRoute(route.accountId, it.id)) },
            onTest = { navController.navigate(AutomationTestRoute(route.accountId, it.id)) },
            onOpenSession = { navController.navigate(SessionRoute(route.accountId, it)) },
            onOpenWorkflowRun = { navController.navigate(WorkflowRunRoute(route.accountId, it)) },
            onDeleted = { navController.popBackStack() })
    }
    composable<AutomationEditRoute> { entry ->
        val route = entry.toRoute<AutomationEditRoute>()
        val viewModel: AutomationEditViewModel = koinViewModel { parametersOf(route) }
        AutomationEditScreen(viewModel, onBack = { navController.popBackStack() }, onSaved = { id ->
            // A new automation replaces the form with its page (the secret is shown there); a changed one goes back to it.
            if (route.automationId == null) navController.navigate(AutomationRoute(route.accountId, id)) { popUpTo<AutomationEditRoute> { inclusive = true } } else navController.popBackStack()
        })
    }
    composable<AutomationTestRoute> { entry ->
        val route = entry.toRoute<AutomationTestRoute>()
        val viewModel: AutomationTestViewModel = koinViewModel { parametersOf(route) }
        AutomationTestScreen(viewModel, onBack = { navController.popBackStack() }, onOpenSession = { navController.navigate(SessionRoute(route.accountId, it)) },
            onOpenWorkflowRun = { navController.navigate(WorkflowRunRoute(route.accountId, it)) })
    }
}

/** The tab of the phone layout that [entry], the content panel's first screen, is; null for a screen pushed over the tabs. */
internal fun panelTab(entry: NavBackStackEntry): MainTab? = when {
    entry.destination.hasRoute<SessionsListRoute>() -> MainTab.SESSIONS
    entry.destination.hasRoute<AccountRoute>() -> MainTab.ACCOUNT
    entry.destination.hasRoute<ArtifactsRoute>() && entry.toRoute<ArtifactsRoute>().sessionId == null -> MainTab.ARTIFACTS
    entry.destination.hasRoute<AutomationsRoute>() -> MainTab.AUTOMATIONS
    entry.destination.hasRoute<WaitsRoute>() -> MainTab.WAITS
    entry.destination.hasRoute<WorkflowsRoute>() -> MainTab.WORKFLOWS
    else -> null
}

/** The content panel's screen for the phone layout's [tab]; the artifacts need the active account. */
internal fun tabRoute(tab: MainTab, accountId: String?): Any? = when (tab) {
    MainTab.SESSIONS -> SessionsListRoute
    MainTab.ACCOUNT -> AccountRoute
    MainTab.ARTIFACTS -> accountId?.let { ArtifactsRoute(it) }
    MainTab.AUTOMATIONS -> accountId?.let { AutomationsRoute(it) }
    MainTab.WAITS -> accountId?.let { WaitsRoute(it) }
    MainTab.WORKFLOWS -> accountId?.let { WorkflowsRoute(it) }
}

/** The route of a screen both layouts show (pushed over the tabs, or in the content panel); null for the others. */
internal fun NavBackStackEntry.detailRoute(): Any? = when {
    destination.hasRoute<SessionRoute>() -> toRoute<SessionRoute>()
    destination.hasRoute<ArtifactRoute>() -> toRoute<ArtifactRoute>()
    destination.hasRoute<ArtifactsRoute>() -> toRoute<ArtifactsRoute>()
    destination.hasRoute<TransfersRoute>() -> toRoute<TransfersRoute>()
    destination.hasRoute<SubscriptionAccountsRoute>() -> toRoute<SubscriptionAccountsRoute>()
    destination.hasRoute<GitHubLinkRoute>() -> toRoute<GitHubLinkRoute>()
    destination.hasRoute<McpServersRoute>() -> toRoute<McpServersRoute>()
    destination.hasRoute<AutomationRoute>() -> toRoute<AutomationRoute>()
    destination.hasRoute<WorkflowRunRoute>() -> toRoute<WorkflowRunRoute>()
    destination.hasRoute<WorkflowRoute>() -> toRoute<WorkflowRoute>()
    destination.hasRoute<WorkflowStartRoute>() -> toRoute<WorkflowStartRoute>()
    destination.hasRoute<WorkflowEditRoute>() -> toRoute<WorkflowEditRoute>()
    destination.hasRoute<AutomationEditRoute>() -> toRoute<AutomationEditRoute>()
    destination.hasRoute<AutomationTestRoute>() -> toRoute<AutomationTestRoute>()
    else -> null
}
