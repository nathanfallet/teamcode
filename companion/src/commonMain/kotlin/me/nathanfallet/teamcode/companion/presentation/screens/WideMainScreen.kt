package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.services.PlatformInfo
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.artifactViewerUrl
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsUiState
import me.nathanfallet.teamcode.companion.resources.artifacts_mine
import me.nathanfallet.teamcode.companion.resources.sessions_filter_all
import me.nathanfallet.teamcode.companion.resources.sessions_filter_ended
import me.nathanfallet.teamcode.companion.resources.sessions_team
import org.koin.compose.koinInject
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
import me.nathanfallet.teamcode.companion.presentation.components.UpdateBanner
import me.nathanfallet.teamcode.companion.presentation.components.DemoBanner
import me.nathanfallet.teamcode.companion.presentation.components.underDemoBanner
import me.nathanfallet.teamcode.companion.presentation.components.TransfersButton
import me.nathanfallet.teamcode.companion.presentation.components.windowTitleBarPadding
import me.nathanfallet.teamcode.companion.presentation.navigation.AccountRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationEditRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationTestRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationTestViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationsViewModel
import me.nathanfallet.teamcode.companion.resources.automation_title
import me.nathanfallet.teamcode.companion.resources.automations_title
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionDestination
import me.nathanfallet.teamcode.companion.presentation.navigation.detailRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionsListRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.GitHubLinkRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.McpServersRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SubscriptionAccountsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WaitsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRunRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowStartRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowStartViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowsViewModel
import me.nathanfallet.teamcode.companion.resources.workflow_title
import me.nathanfallet.teamcode.companion.resources.workflows_title
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import me.nathanfallet.teamcode.companion.resources.waits_title
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.GitHubLinkViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.McpServersViewModel
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
    waits: WaitsViewModel = koinViewModel(),
) {
    val sheets = rememberMainSheets()
    var palette by remember { mutableStateOf(false) }
    val preferences: PreferencesRepository = koinInject()
    val platform: PlatformInfo = koinInject()
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
    // One list of sessions and one of artifacts for the sidebar and the content panel's full lists: one load and one
    // live feed each, the read marks and the filters shared. The same view models as the phone layout's tabs (the same
    // keys in the same store), so a window crossing the breakpoint keeps them too. The sidebar keeps them current while
    // the window shows it; the panel's list screens do not start or stop them.
    val sidebarSessions: SessionsViewModel = koinViewModel()
    val sessionsState by sidebarSessions.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(sidebarSessions) {
        sidebarSessions.start()
        onStopOrDispose { sidebarSessions.stop() }
    }
    // The waits follow the app's one feed while the window shows the sidebar, whatever the panel shows: the sidebar's count stays live.
    LifecycleStartEffect(waits) {
        waits.start()
        onStopOrDispose { waits.stop() }
    }
    val waitsState by waits.uiState.collectAsStateWithLifecycle()
    val signedIn = account?.takeIf { it.signedIn }
    val sidebarArtifacts: ArtifactsViewModel? = signedIn?.let { key(it.id) { koinViewModel(key = artifactsKey(it.id)) { parametersOf(ArtifactsRoute(it.id)) } } }
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

    // The latest automations for the sidebar, the content panel's full list too; the section shows once the server answered.
    val sidebarAutomations: AutomationsViewModel? = signedIn?.let { key(it.id) { koinViewModel(key = automationsKey(it.id)) { parametersOf(AutomationsRoute(it.id)) } } }
    val automationsState = sidebarAutomations?.uiState?.collectAsStateWithLifecycle()?.value

    val workflowsOffered = rememberWorkflowsOffered(signedIn)
    val current by detail.currentBackStackEntryAsState()
    val selection = current.selection()
    val selectionNow = rememberUpdatedState(selection)
    val sessionsNow = rememberUpdatedState(sessionsState.list?.sessions.orEmpty())
    LaunchedEffect(Unit) {
        AppCommands.commands.collect { command ->
            val active = home.uiState.value.account
            when (command) {
                AppCommand.NEW_SESSION -> active?.takeIf { it.signedIn }?.let { sheets.newSession(it.id) }
                AppCommand.SHOW_SESSIONS -> show(SessionsListRoute)
                AppCommand.SHOW_ARTIFACTS -> active?.let { show(ArtifactsRoute(it.id)) }
                AppCommand.SHOW_ACCOUNT -> show(AccountRoute)
                AppCommand.COMMAND_PALETTE -> palette = !palette
                AppCommand.PREVIOUS_SESSION, AppCommand.NEXT_SESSION -> {
                    val open = (selectionNow.value as? SidebarSelection.Session)?.id
                    val step = if (command == AppCommand.NEXT_SESSION) 1 else -1
                    val target = neighbourSession(sessionsNow.value.map { it.id }, open, step)
                    if (active != null && target != null) { show(SessionRoute(active.id, target)); sidebarSessions.opened(target) }
                }
            }
        }
    }

    // The sidebar's automations follow what the panel did: a page, a form or a test was open, something may have changed.
    LaunchedEffect(selection) { if (selection is SidebarSelection.Automation || selection == SidebarSelection.AllAutomations) sidebarAutomations?.reload() }

    // However a session was opened (the panel's list, a link, a new session), the sidebar's row is read.
    LaunchedEffect(selection) { (selection as? SidebarSelection.Session)?.let { sidebarSessions.opened(it.id) } }
    val now = rememberNow()

    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfoV2()).copy(horizontalPartitionSpacerSize = 0.dp)
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = directive)
    val windowSize = currentWindowAdaptiveInfoV2().windowSizeClass
    val narrow = !windowSize.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    // The divider snaps to three sidebar widths: the one the member left it on, else the narrowest on a medium window.
    val anchors = remember { SidebarWidths.map { PaneExpansionAnchor.Offset.fromStart(it) } }
    val expansion = rememberPaneExpansionState(
        keyProvider = navigator.scaffoldValue,
        anchors = anchors,
        initialAnchoredIndex = sidebarWidthIndex(preferences.sidebarWidth, narrow),
    )
    LaunchedEffect(expansion) {
        // Its first value is the width it opened on, not a choice: saving it would pin the default as the member's.
        snapshotFlow { expansion.currentAnchor }.drop(1).collect { anchor ->
            anchors.indexOf(anchor).takeIf { it >= 0 && it != preferences.sidebarWidth }?.let(preferences::setSidebarWidth)
        }
    }
    LaunchedEffect(Unit) { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }

    val actions = remember(detail, sheets) {
        SidebarActions(
            onNewSession = { sheets.newSession(it.id) },
            onOpenSession = { session -> home.uiState.value.account?.let { show(SessionRoute(it.id, session.id)) }; sidebarSessions.opened(session.id) },
            onOpenSessionArtifacts = { session -> home.uiState.value.account?.let { show(ArtifactsRoute(it.id, session.id)) } },
            onAllSessions = { show(SessionsListRoute) },
            onOpenArtifact = { artifact -> home.uiState.value.account?.let { show(ArtifactRoute(it.id, artifact.id)) } },
            onAllArtifacts = { home.uiState.value.account?.let { show(ArtifactsRoute(it.id)) } },
            onOpenAutomation = { automation -> home.uiState.value.account?.let { show(AutomationRoute(it.id, automation.id)) } },
            onAllAutomations = { home.uiState.value.account?.let { show(AutomationsRoute(it.id)) } },
            onOpenWaits = { home.uiState.value.account?.let { show(WaitsRoute(it.id)) } },
            onOpenWorkflows = { home.uiState.value.account?.let { show(WorkflowsRoute(it.id)) } },
            onSwitchAccount = { settings.switchTo(it) },
            onSignInAgain = onSignInAgain,
            onAddAccount = onAddAccount,
            onSettings = { show(AccountRoute) },
            onMarkSessionRead = { sidebarSessions.opened(it.id) },
            artifactLink = { artifact -> home.uiState.value.account?.let { artifactViewerUrl(it.origin, artifact.id) } },
            onSearch = { palette = true },
            searchShortcut = paletteShortcutLabel(platform),
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
                            automations = automationsState?.automations,
                            waitsOpen = waitsState.takeIf { it.available }?.openCount, waitsMore = waitsState.moreOpen, workflowsOffered = workflowsOffered,
                            selection = selection, now = now,
                            sessionsFilter = sessionsFilterLabel(sessionsState), artifactsFilter = artifactsFilterLabel(artifactsState),
                        ),
                        actions,
                        Modifier.weight(1f),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(TeamCode.colors.line))
                }
            }
        },
        detailPane = {
            // Under macOS's title bar the panel starts below it (the bar stays the window's to drag and zoom); the update banner heads it.
            AnimatedPane { Column(Modifier.fillMaxSize().windowTitleBarPadding()) {
                UpdateBanner()
                DemoBanner(account)
                NavHost(
                    detail, startDestination = SessionsListRoute, modifier = Modifier.weight(1f).fillMaxWidth().underDemoBanner(account).testTag("content-panel"),
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
                            onOpenSessionArtifacts = { a, session -> detail.navigate(ArtifactsRoute(a.id, session.id)) },
                            viewModel = home,
                            sessionsViewModel = sidebarSessions,
                            waitsViewModel = waits,
                            onOpenWaits = { a -> show(WaitsRoute(a.id)) },
                            follow = false,
                        )
                    }
                    composable<SessionRoute> { entry ->
                        SessionDestination(entry.toRoute(), onBack = detail.backFrom(entry), onOpenArtifacts = { detail.navigate(it) }, backLabel = detail.backLabel(entry))
                    }
                    composable<ArtifactsRoute> { entry ->
                        val route = entry.toRoute<ArtifactsRoute>()
                        // The account's full gallery is the sidebar's list; a session's artifacts are a list of their own.
                        val shared = sidebarArtifacts?.takeIf { route.sessionId == null && route.accountId == signedIn.id }
                        val viewModel: ArtifactsViewModel = shared ?: koinViewModel { parametersOf(route) }
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
                    composable<GitHubLinkRoute> { entry ->
                        val viewModel: GitHubLinkViewModel = koinViewModel { parametersOf(entry.toRoute<GitHubLinkRoute>()) }
                        GitHubLinkScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry))
                    }
                    composable<McpServersRoute> { entry ->
                        val viewModel: McpServersViewModel = koinViewModel { parametersOf(entry.toRoute<McpServersRoute>()) }
                        McpServersScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry))
                    }
                    composable<AutomationsRoute> { entry ->
                        val route = entry.toRoute<AutomationsRoute>()
                        val viewModel: AutomationsViewModel = sidebarAutomations?.takeIf { route.accountId == signedIn?.id } ?: koinViewModel { parametersOf(route) }
                        AutomationsScreen(viewModel, onOpen = { detail.navigate(AutomationRoute(route.accountId, it.id)) }, onCreate = { detail.navigate(AutomationEditRoute(route.accountId)) })
                    }
                    composable<WorkflowsRoute> { entry ->
                        val route = entry.toRoute<WorkflowsRoute>()
                        val runs: WorkflowRunsViewModel = koinViewModel(key = workflowRunsKey(route.accountId)) { parametersOf(route) }
                        val definitions: WorkflowsViewModel = koinViewModel(key = workflowsKey(route.accountId)) { parametersOf(route) }
                        WorkflowsScreen(runs, definitions, onOpenRun = { detail.navigate(WorkflowRunRoute(route.accountId, it.id)) }, onOpenWorkflow = { detail.navigate(WorkflowRoute(route.accountId, it.id)) },
                            onStartWorkflow = { detail.navigate(WorkflowStartRoute(route.accountId, it.id)) }, onOpenWaits = { show(WaitsRoute(route.accountId)) })
                    }
                    composable<WorkflowRunRoute> { entry ->
                        val route = entry.toRoute<WorkflowRunRoute>()
                        val viewModel: WorkflowRunViewModel = koinViewModel { parametersOf(route) }
                        WorkflowRunScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry), onOpenSession = { detail.navigate(SessionRoute(route.accountId, it)) },
                            onOpenRun = { detail.navigate(WorkflowRunRoute(route.accountId, it)) }, onOpenWaits = { show(WaitsRoute(route.accountId)) })
                    }
                    composable<WorkflowRoute> { entry ->
                        val route = entry.toRoute<WorkflowRoute>()
                        val viewModel: WorkflowViewModel = koinViewModel { parametersOf(route) }
                        WorkflowScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry), onStart = { detail.navigate(WorkflowStartRoute(route.accountId, it.id)) })
                    }
                    composable<WorkflowStartRoute> { entry ->
                        val route = entry.toRoute<WorkflowStartRoute>()
                        val viewModel: WorkflowStartViewModel = koinViewModel { parametersOf(route) }
                        WorkflowStartScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry), onStarted = { run ->
                            detail.navigate(WorkflowRunRoute(route.accountId, run.id)) { popUpTo<WorkflowStartRoute> { inclusive = true } }
                        })
                    }
                    composable<WaitsRoute> {
                        WaitsScreen(waits, onOpenSession = { accountId, sessionId -> detail.navigate(SessionRoute(accountId, sessionId)) })
                    }
                    composable<AutomationRoute> { entry ->
                        val route = entry.toRoute<AutomationRoute>()
                        val viewModel: AutomationViewModel = koinViewModel { parametersOf(route) }
                        AutomationScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry),
                            onEdit = { detail.navigate(AutomationEditRoute(route.accountId, it.id)) },
                            onTest = { detail.navigate(AutomationTestRoute(route.accountId, it.id)) },
                            onOpenSession = { detail.navigate(SessionRoute(route.accountId, it)) },
                            onOpenWorkflowRun = { detail.navigate(WorkflowRunRoute(route.accountId, it)) },
                            onDeleted = { show(AutomationsRoute(route.accountId)) })
                    }
                    composable<AutomationEditRoute> { entry ->
                        val route = entry.toRoute<AutomationEditRoute>()
                        val viewModel: AutomationEditViewModel = koinViewModel { parametersOf(route) }
                        AutomationEditScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry), onSaved = { id ->
                            // A new automation's form gives way to its page (which shows its secret); a changed one goes back to the page it came from.
                            if (route.automationId == null) detail.navigate(AutomationRoute(route.accountId, id)) { popUpTo<AutomationEditRoute> { inclusive = true } } else detail.popBackStack()
                        })
                    }
                    composable<AutomationTestRoute> { entry ->
                        val route = entry.toRoute<AutomationTestRoute>()
                        val viewModel: AutomationTestViewModel = koinViewModel { parametersOf(route) }
                        AutomationTestScreen(viewModel, onBack = detail.backFrom(entry), backLabel = detail.backLabel(entry), onOpenSession = { detail.navigate(SessionRoute(route.accountId, it)) },
                            onOpenWorkflowRun = { detail.navigate(WorkflowRunRoute(route.accountId, it)) })
                    }
                    composable<AccountRoute> {
                        AccountScreen(onAddAccount = onAddAccount, onSignInAgain = onSignInAgain, onSwitchAccount = { sheets.switcher = true },
                            onOpenSubscriptions = { detail.navigate(SubscriptionAccountsRoute(it.id)) },
                            onOpenGitHub = { detail.navigate(GitHubLinkRoute(it.id)) },
                            onOpenMcp = { detail.navigate(McpServersRoute(it.id)) }, viewModel = settings)
                    }
                }
            } }
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

    if (palette) CommandPalette(
        paletteItems(account, settingsState.accounts, sessionsState.list?.sessions.orEmpty(), artifactsState?.artifacts.orEmpty(), PaletteActions(
            onNewSession = actions.onNewSession, onAllSessions = actions.onAllSessions, onAllArtifacts = actions.onAllArtifacts,
            onTransfers = { show(TransfersRoute(it.id)) }, onSettings = actions.onSettings, onAddAccount = onAddAccount, onSwitchAccount = actions.onSwitchAccount,
            onSignInAgain = onSignInAgain, onOpenSession = actions.onOpenSession, onOpenArtifact = actions.onOpenArtifact,
        )),
        onDismiss = { palette = false },
    )
}

/** The key of an account's artifact list in the signed-in app's store: the phone's tab and the wide layout share it. */
internal fun artifactsKey(accountId: String) = "artifacts-$accountId"

/** The sidebar width to start on: the saved one when it is still one of [SidebarWidths], else the default for the window. */
internal fun sidebarWidthIndex(saved: Int?, narrow: Boolean): Int = saved?.takeIf { it in SidebarWidths.indices } ?: if (narrow) 0 else 1

/** Cmd+K on Apple platforms, Ctrl+K elsewhere, as the platform writes it. */
internal fun paletteShortcutLabel(platform: PlatformInfo): String = if (platform.platform == "macOS" || platform.platform == "iOS") "⌘K" else "Ctrl+K"

/** The shared session list's filter, when it is not the default (my active and paused sessions). */
@Composable
private fun sessionsFilterLabel(state: SessionsUiState): String? = listOfNotNull(
    when (state.filter) { SessionFilter.OPEN -> null; SessionFilter.ALL -> stringResource(Res.string.sessions_filter_all); SessionFilter.ENDED -> stringResource(Res.string.sessions_filter_ended) },
    if (state.scope == SessionScope.TEAM) stringResource(Res.string.sessions_team) else null,
).joinToString(" · ").ifEmpty { null }

/** The shared artifact list's filter, when it is not the default (the team's). */
@Composable
private fun artifactsFilterLabel(state: ArtifactsUiState?): String? = if (state?.scope == ArtifactScope.MINE) stringResource(Res.string.artifacts_mine) else null

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
    destination.hasRoute<AutomationsRoute>() -> toRoute<AutomationsRoute>()
    destination.hasRoute<WaitsRoute>() -> toRoute<WaitsRoute>()
    destination.hasRoute<WorkflowsRoute>() -> toRoute<WorkflowsRoute>()
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
        previous.hasRoute<AutomationsRoute>() -> Res.string.automations_title
        previous.hasRoute<WaitsRoute>() -> Res.string.waits_title
        previous.hasRoute<WorkflowsRoute>() -> Res.string.workflows_title
        previous.hasRoute<WorkflowRoute>() -> Res.string.workflow_title
        previous.hasRoute<AutomationRoute>() -> Res.string.automation_title
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
        destination.hasRoute<AutomationsRoute>() -> SidebarSelection.AllAutomations
        destination.hasRoute<WaitsRoute>() -> SidebarSelection.AllWaits
        destination.hasRoute<WorkflowsRoute>() || destination.hasRoute<WorkflowRunRoute>() || destination.hasRoute<WorkflowRoute>() || destination.hasRoute<WorkflowStartRoute>() -> SidebarSelection.AllWorkflows
        destination.hasRoute<AutomationRoute>() -> SidebarSelection.Automation(toRoute<AutomationRoute>().automationId)
        destination.hasRoute<AutomationEditRoute>() -> toRoute<AutomationEditRoute>().automationId?.let { SidebarSelection.Automation(it) } ?: SidebarSelection.AllAutomations
        destination.hasRoute<AutomationTestRoute>() -> SidebarSelection.Automation(toRoute<AutomationTestRoute>().automationId)
        destination.hasRoute<AccountRoute>() || destination.hasRoute<SubscriptionAccountsRoute>() || destination.hasRoute<GitHubLinkRoute>() || destination.hasRoute<McpServersRoute>() -> SidebarSelection.Settings
        else -> SidebarSelection.None
    }
}

/**
 * The session [step] rows (1 next, -1 previous) from [open] in [ids], the full list the sidebar draws from, not its
 * first rows. It stops at either end rather than wrapping. With no session open, or one the filter hides, next takes
 * the first and previous the last. Null when there is nowhere to go.
 */
fun neighbourSession(ids: List<String>, open: String?, step: Int): String? {
    val at = ids.indexOf(open)
    if (at < 0) return if (step > 0) ids.firstOrNull() else ids.lastOrNull()
    return ids.getOrNull(at + step)
}
