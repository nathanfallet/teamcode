package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.components.TransfersButton
import me.nathanfallet.teamcode.companion.presentation.navigation.AccountRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionDestination
import me.nathanfallet.teamcode.companion.presentation.navigation.detailRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionsListRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SubscriptionAccountsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.account_title
import me.nathanfallet.teamcode.companion.resources.action_back
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import me.nathanfallet.teamcode.companion.resources.session_title_back
import me.nathanfallet.teamcode.companion.resources.sessions_title
import me.nathanfallet.teamcode.companion.resources.sidebar_resize
import me.nathanfallet.teamcode.companion.resources.transfers_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The signed-in app on a wide window (600 dp and more: the desktop, a tablet, a wide split screen): Material's
 * list-detail scaffold with the [Sidebar] as its list pane and the content panel, a navigation of its own ([detail]),
 * as its detail pane. Both panes always show (two panes from the medium width class on); the divider between them
 * drags to resize the sidebar. The content panel shows the same screens a phone pushes over its tabs.
 * [pending] are screens to open in the panel once (those a narrower window had pushed).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun WideMainScreen(
    detail: NavHostController,
    onSignInAgain: (Account) -> Unit,
    onOpenLink: (String) -> Boolean,
    onAddAccount: () -> Unit,
    pending: List<Any> = emptyList(),
    onPendingShown: () -> Unit = {},
    home: HomeViewModel = koinViewModel(),
    settings: SettingsViewModel = koinViewModel(),
) {
    val sheets = rememberMainSheets()
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val account = homeState.account

    /**
     * Shows [route] as the content panel's only screen: what the sidebar opens replaces what was open. Already shown
     * alone, it stays as it is (its search, filters and scroll kept).
     */
    fun show(route: Any) {
        val stack = detail.currentBackStack.value.filter { it.destination !is androidx.navigation.NavGraph }
        if (stack.size == 1 && stack.single().detailRouteOrRoot() == route) return
        detail.navigate(route) {
            popUpTo(detail.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        // In place of what the panel showed before the window narrowed: never stacked on it.
        pending.forEachIndexed { index, route -> if (index == 0) show(route) else detail.navigate(route) }
        onPendingShown()
    }
    // Another account: what the panel showed belonged to the previous one.
    var shownAccount by remember { mutableStateOf(account?.id) }
    LaunchedEffect(account?.id) {
        if (account?.id != shownAccount && shownAccount != null) show(SessionsListRoute)
        shownAccount = account?.id
    }
    LaunchedEffect(Unit) {
        AppCommands.commands.collect { command ->
            val active = home.uiState.value.account
            when (command) {
                AppCommand.NEW_SESSION -> active?.takeIf { it.signedIn }?.let { sheets.newSession(it.id) }
                AppCommand.SHOW_SESSIONS -> show(SessionsListRoute)
                AppCommand.SHOW_ARTIFACTS -> active?.let { show(ArtifactsRoute(it.id)) }
                AppCommand.SHOW_ACCOUNT -> show(AccountRoute)
            }
        }
    }

    // The sidebar's lists: their own view models, kept current while the window shows them.
    val sidebarSessions: SessionsViewModel = koinViewModel(key = "sidebar-sessions")
    val sessionsState by sidebarSessions.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(sidebarSessions) {
        sidebarSessions.start()
        onStopOrDispose { sidebarSessions.stop() }
    }
    val signedIn = account?.takeIf { it.signedIn }
    val sidebarArtifacts: ArtifactsViewModel? = signedIn?.let { key(it.id) { koinViewModel(key = "sidebar-artifacts-${it.id}") { parametersOf(ArtifactsRoute(it.id)) } } }
    val artifactsState = sidebarArtifacts?.uiState?.collectAsStateWithLifecycle()?.value
    // Back to the window after a while elsewhere: the artifacts may have changed (sessions follow their live feed).
    // A new view model (another account) loads on its own: no second request.
    var started by remember(sidebarArtifacts) { mutableStateOf(false) }
    LifecycleStartEffect(sidebarArtifacts) {
        if (started) sidebarArtifacts?.reload()
        started = true
        onStopOrDispose { }
    }
    // Sessions publish artifacts: a turn that finishes may have added one, so the list reloads then.
    val finishedTurns = sessionsState.list?.finishedTurns.orEmpty()
    var seenTurns by remember(sidebarArtifacts) { mutableStateOf(finishedTurns) }
    LaunchedEffect(finishedTurns) {
        if ((finishedTurns - seenTurns).isNotEmpty()) sidebarArtifacts?.reload()
        seenTurns = finishedTurns
    }

    val current by detail.currentBackStackEntryAsState()
    val selection = current.selection()
    // However a session was opened (the panel's list, a link, a new session), the sidebar's row is read.
    LaunchedEffect(selection) { (selection as? SidebarSelection.Session)?.let { sidebarSessions.opened(it.id) } }
    val now = rememberNow()

    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()).copy(horizontalPartitionSpacerSize = 0.dp)
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = directive)
    val windowSize = currentWindowAdaptiveInfoV2().windowSizeClass
    val narrow = !windowSize.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    // The divider snaps to three sidebar widths; a medium window starts on the narrowest.
    val expansion = rememberPaneExpansionState(
        keyProvider = navigator.scaffoldValue,
        anchors = SidebarWidths.map { PaneExpansionAnchor.Offset.fromStart(it) },
        initialAnchoredIndex = if (narrow) 0 else 1,
    )
    LaunchedEffect(Unit) { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }

    val actions = remember(detail, sheets) {
        SidebarActions(
            onNewSession = { sheets.newSession(it.id) },
            onOpenSession = { session -> home.uiState.value.account?.let { show(SessionRoute(it.id, session.id)) }; sidebarSessions.opened(session.id) },
            onOpenSessionArtifacts = { session -> home.uiState.value.account?.let { show(ArtifactsRoute(it.id, session.id)) } },
            onAllSessions = { show(SessionsListRoute) },
            onOpenArtifact = { artifact -> home.uiState.value.account?.let { show(ArtifactRoute(it.id, artifact.id)) } },
            onAllArtifacts = { home.uiState.value.account?.let { show(ArtifactsRoute(it.id)) } },
            onSwitchAccount = { settings.switchTo(it) },
            onSignInAgain = onSignInAgain,
            onAddAccount = onAddAccount,
            onSettings = { show(AccountRoute) },
        )
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        modifier = Modifier.background(TeamCode.colors.background).testTag("wide-layout"),
        listPane = {
            AnimatedPane {
                Row {
                    Sidebar(
                        SidebarState(
                            account = account, accounts = settingsState.accounts,
                            sessions = sessionsState.list?.sessions.orEmpty().take(SIDEBAR_ROWS), sessionsLoading = sessionsState.loading || sessionsState.list == null,
                            finishedTurns = sessionsState.list?.finishedTurns.orEmpty(),
                            artifacts = artifactsState?.artifacts.orEmpty().take(SIDEBAR_ROWS), artifactsLoading = artifactsState?.loading ?: false,
                            artifactsError = artifactsState?.error,
                            selection = selection, now = now,
                        ),
                        actions,
                        Modifier.weight(1f),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(TeamCode.colors.line))
                }
            }
        },
        detailPane = {
            AnimatedPane {
                NavHost(
                    detail, startDestination = SessionsListRoute, modifier = Modifier.fillMaxSize().testTag("content-panel"),
                    enterTransition = { fadeIn(tween(120)) }, exitTransition = { fadeOut(tween(90)) },
                    popEnterTransition = { fadeIn(tween(120)) }, popExitTransition = { fadeOut(tween(90)) },
                ) {
                    composable<SessionsListRoute> {
                        SessionsScreen(
                            onSignInAgain = onSignInAgain,
                            onOpenSession = { a, session -> detail.navigate(SessionRoute(a.id, session.id)) },
                            onNewSession = { sheets.newSession(it.id) },
                            onSwitchAccount = { sheets.switcher = true },
                            titleActions = { TransfersButton(it.id) { detail.navigate(TransfersRoute(it.id)) } },
                            viewModel = home,
                        )
                    }
                    composable<SessionRoute> { entry ->
                        SessionDestination(entry.toRoute(), onBack = detail.backFrom(entry), onOpenArtifacts = { detail.navigate(it) }, backLabel = detail.backLabel(entry))
                    }
                    composable<ArtifactsRoute> { entry ->
                        val route = entry.toRoute<ArtifactsRoute>()
                        val viewModel: ArtifactsViewModel = koinViewModel { parametersOf(route) }
                        ArtifactsScreen(viewModel, forSession = route.sessionId != null, onBack = detail.backFrom(entry),
                            onOpen = { detail.navigate(ArtifactRoute(route.accountId, it.id)) }, onOpenLink = onOpenLink, backLabel = detail.backLabel(entry))
                    }
                    composable<ArtifactRoute> { entry ->
                        val viewModel: ArtifactViewModel = koinViewModel { parametersOf(entry.toRoute<ArtifactRoute>()) }
                        ArtifactScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry))
                    }
                    composable<TransfersRoute> { entry ->
                        val route = entry.toRoute<TransfersRoute>()
                        val viewModel: TransfersViewModel = koinViewModel { parametersOf(route) }
                        TransfersScreen(viewModel, onBack = detail.backFrom(entry), onOpenSession = { detail.navigate(SessionRoute(route.accountId, it)) }, backLabel = detail.backLabel(entry))
                    }
                    composable<SubscriptionAccountsRoute> { entry ->
                        val viewModel: SubscriptionAccountsViewModel = koinViewModel { parametersOf(entry.toRoute<SubscriptionAccountsRoute>()) }
                        SubscriptionAccountsScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry))
                    }
                    composable<AccountRoute> {
                        AccountScreen(onAddAccount = onAddAccount, onSignInAgain = onSignInAgain, onSwitchAccount = { sheets.switcher = true },
                            onOpenSubscriptions = { detail.navigate(SubscriptionAccountsRoute(it.id)) }, viewModel = settings)
                    }
                }
            }
        },
        paneExpansionState = expansion,
        paneExpansionDragHandle = { state ->
            val interactions = remember { MutableInteractionSource() }
            val description = stringResource(Res.string.sidebar_resize)
            VerticalDragHandle(
                modifier = Modifier.paneExpansionDraggable(state, 16.dp, interactions) { contentDescription = description }.testTag("sidebar-resize"),
                interactionSource = interactions,
            )
        },
    )

    MainSheetsHost(sheets, settings, onOpenSession = { accountId, sessionId -> show(SessionRoute(accountId, sessionId)) }, onSignInAgain, onAddAccount,
        onManageAccounts = { show(AccountRoute) })
}

/** The sidebar widths the divider snaps to. */
val SidebarWidths = listOf(232.dp, 280.dp, 360.dp)

/** Back within the panel, when something is under [entry]; null at its first screen (the sidebar is the way out). */
private fun NavHostController.backFrom(entry: NavBackStackEntry): (() -> Unit)? {
    val stack = currentBackStack.value.filter { it.destination !is androidx.navigation.NavGraph }
    val index = stack.indexOfFirst { it.id == entry.id }
    return if (index > 0) ({ popBackStack(); Unit }) else null
}

/** The route [this] shows, for the panel's screens and its first one. */
private fun NavBackStackEntry.detailRouteOrRoot(): Any? = when {
    destination.hasRoute<SessionsListRoute>() -> SessionsListRoute
    destination.hasRoute<AccountRoute>() -> AccountRoute
    else -> detailRoute()
}

/** The name of the screen under [entry], for its back button. */
@Composable
private fun NavHostController.backLabel(entry: NavBackStackEntry): String {
    val stack = currentBackStack.value.filter { it.destination !is androidx.navigation.NavGraph }
    val previous = stack.getOrNull(stack.indexOfFirst { it.id == entry.id } - 1)?.destination
    return stringResource(when {
        previous == null -> Res.string.action_back
        previous.hasRoute<SessionsListRoute>() -> Res.string.sessions_title
        previous.hasRoute<SessionRoute>() -> Res.string.session_title_back
        previous.hasRoute<ArtifactsRoute>() -> Res.string.artifacts_title
        previous.hasRoute<TransfersRoute>() -> Res.string.transfers_title
        previous.hasRoute<AccountRoute>() -> Res.string.account_title
        else -> Res.string.action_back
    })
}

/** What the sidebar marks as open, from the panel's current screen. */
private fun NavBackStackEntry?.selection(): SidebarSelection {
    val destination = this?.destination ?: return SidebarSelection.None
    return when {
        destination.hasRoute<SessionsListRoute>() -> SidebarSelection.AllSessions
        destination.hasRoute<SessionRoute>() -> SidebarSelection.Session(toRoute<SessionRoute>().sessionId)
        destination.hasRoute<ArtifactRoute>() -> SidebarSelection.Artifact(toRoute<ArtifactRoute>().artifactId)
        destination.hasRoute<ArtifactsRoute>() -> if (toRoute<ArtifactsRoute>().sessionId == null) SidebarSelection.AllArtifacts else SidebarSelection.None
        destination.hasRoute<AccountRoute>() || destination.hasRoute<SubscriptionAccountsRoute>() -> SidebarSelection.Settings
        else -> SidebarSelection.None
    }
}
