package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommand
import me.nathanfallet.teamcode.companion.presentation.navigation.AppCommands
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Bell
import com.adamglin.phosphoricons.regular.FileText
import com.adamglin.phosphoricons.regular.TerminalWindow
import com.adamglin.phosphoricons.regular.UserCircle
import com.adamglin.phosphoricons.regular.Warning
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.components.ContentWidth
import me.nathanfallet.teamcode.companion.presentation.components.EmptyState
import me.nathanfallet.teamcode.companion.presentation.components.LargeTitle
import me.nathanfallet.teamcode.companion.presentation.components.SheetViewModels
import me.nathanfallet.teamcode.companion.presentation.components.TransfersButton
import me.nathanfallet.teamcode.companion.presentation.components.TcButton
import me.nathanfallet.teamcode.companion.presentation.components.UpdateBanner
import me.nathanfallet.teamcode.companion.presentation.components.DemoBanner
import me.nathanfallet.teamcode.companion.presentation.components.underDemoBanner
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationsRoute
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationsAvailabilityUseCase
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationsViewModel
import me.nathanfallet.teamcode.companion.resources.automations_title
import me.nathanfallet.teamcode.companion.resources.tab_automations
import me.nathanfallet.teamcode.companion.resources.tab_automations_short
import com.adamglin.phosphoricons.regular.Clock
import com.adamglin.phosphoricons.regular.FlowArrow
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowsAvailabilityUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditorsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowsViewModel
import me.nathanfallet.teamcode.companion.resources.tab_workflows
import me.nathanfallet.teamcode.companion.resources.workflows_title
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import org.koin.compose.getKoin
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsUiState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import me.nathanfallet.teamcode.companion.resources.tab_waits
import me.nathanfallet.teamcode.companion.resources.waits_title
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.wfe_copy_suffix
import me.nathanfallet.teamcode.companion.resources.artifacts_title
import me.nathanfallet.teamcode.companion.resources.home_sign_in_again
import me.nathanfallet.teamcode.companion.resources.home_signed_out
import me.nathanfallet.teamcode.companion.resources.sessions_signed_out_title
import me.nathanfallet.teamcode.companion.resources.tab_account
import me.nathanfallet.teamcode.companion.resources.tab_artifacts
import me.nathanfallet.teamcode.companion.resources.tab_sessions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The tabs of the phone layout; WAITS only on a server that has waits (`waits.v1`), AUTOMATIONS only on one that has automations (`automations.v1`), WORKFLOWS only on one that has workflows (`workflows.v1`). */
enum class MainTab { SESSIONS, WAITS, ARTIFACTS, AUTOMATIONS, WORKFLOWS, ACCOUNT }

/**
 * The sheets (dialogs on a wide window) the signed-in app opens over its content: a new session (on the desktop, first
 * where it runs), a session on this device, the account switcher. Shared by the phone's tabs and the wide layout.
 */
class MainSheets internal constructor(
    newSessionFor: MutableState<String?>,
    chooserFor: MutableState<String?>,
    localSessionFor: MutableState<String?>,
    localDirectory: MutableState<String?>,
    switcher: MutableState<Boolean>,
    /** The desktop alone hosts sessions on the device: there, a new session first asks where it runs. */
    val localHost: LocalHost?,
) {
    var newSessionFor by newSessionFor
    var chooserFor by chooserFor
    var localSessionFor by localSessionFor
    /** `teamcode ui DIRECTORY`: the app opens on a new session there, with the CLI's account (sessions here use it). */
    var localDirectory by localDirectory
    var switcher by switcher

    fun newSession(accountId: String) { if (localHost != null) chooserFor = accountId else newSessionFor = accountId }
}

@Composable
fun rememberMainSheets(): MainSheets {
    val koin = getKoin()
    val localHost = remember { koin.getOrNull<LocalHost>() }
    val newSessionFor = rememberSaveable { mutableStateOf<String?>(null) }
    val chooserFor = rememberSaveable { mutableStateOf<String?>(null) }
    val localSessionFor = rememberSaveable { mutableStateOf<String?>(null) }
    val localDirectory = rememberSaveable { mutableStateOf<String?>(null) }
    val switcher = rememberSaveable { mutableStateOf(false) }
    // One holder for the composition's lifetime: callers key their remembered actions on it.
    val sheets = remember(localHost) { MainSheets(newSessionFor, chooserFor, localSessionFor, localDirectory, switcher, localHost) }
    LaunchedEffect(localHost) {
        localHost?.takeInitialDirectory()?.let { directory ->
            sheets.localDirectory = directory
            sheets.localSessionFor = Account.CLI_ACCOUNT_ID
        }
    }
    return sheets
}

/** Shows whichever of [sheets] is open. */
@Composable
fun MainSheetsHost(
    sheets: MainSheets,
    settings: SettingsViewModel,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    onSignInAgain: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    sheets.newSessionFor?.let { accountId ->
        SheetViewModels {
            val viewModel: NewSessionViewModel = koinViewModel { parametersOf(NewSessionRoute(accountId)) }
            NewSessionSheet(viewModel, onDismiss = { sheets.newSessionFor = null }, onCreated = { session ->
                sheets.newSessionFor = null
                onOpenSession(accountId, session.id)
            })
        }
    }
    sheets.chooserFor?.let { accountId ->
        NewSessionChooserSheet(
            onHere = { sheets.chooserFor = null; sheets.localDirectory = null; sheets.localSessionFor = accountId },
            onCloud = { sheets.chooserFor = null; sheets.newSessionFor = accountId },
            onDismiss = { sheets.chooserFor = null },
        )
    }
    sheets.localSessionFor?.let { accountId ->
        SheetViewModels {
            val viewModel: LocalSessionViewModel = koinViewModel { parametersOf(NewSessionRoute(accountId, sheets.localDirectory)) }
            LocalSessionSheet(viewModel, onDismiss = { sheets.localSessionFor = null; sheets.localDirectory = null }, onStarted = { sessionId ->
                sheets.localSessionFor = null
                sheets.localDirectory = null
                onOpenSession(accountId, sessionId)
            })
        }
    }
    if (sheets.switcher) AccountSwitcherSheet(
        accounts = settingsState.accounts,
        activeAccountId = settingsState.activeAccountId,
        onSwitch = { settings.switchTo(it); sheets.switcher = false },
        onSignInAgain = { sheets.switcher = false; onSignInAgain(it) },
        onAddAccount = { sheets.switcher = false; onAddAccount() },
        onManage = { sheets.switcher = false; onManageAccounts() },
        onDismiss = { sheets.switcher = false },
    )
}

/**
 * The signed-in app on a narrow window (a phone, a tablet in a narrow split): three tabs over a bottom bar. The
 * new-session sheet and the account switcher open over it; sessions and artifacts are pushed above it by the
 * navigation. A wide window shows [WideMainScreen] instead.
 */
@Composable
fun MainScreen(
    onSignInAgain: (Account) -> Unit,
    onOpenSession: (accountId: String, sessionId: String) -> Unit,
    onOpenArtifact: (accountId: String, artifactId: String) -> Unit,
    onOpenLink: (String) -> Boolean,
    onAddAccount: () -> Unit,
    onOpenTransfers: (accountId: String) -> Unit = {},
    onOpenSubscriptions: (accountId: String) -> Unit = {},
    onOpenGitHub: (accountId: String) -> Unit = {},
    onOpenMcp: (accountId: String) -> Unit = {},
    onOpenAutomation: (accountId: String, automationId: String) -> Unit = { _, _ -> },
    onCreateAutomation: (accountId: String) -> Unit = {},
    onOpenWorkflowRun: (accountId: String, runId: String) -> Unit = { _, _ -> },
    onOpenWorkflow: (accountId: String, workflowId: String) -> Unit = { _, _ -> },
    onStartWorkflow: (accountId: String, workflowId: String) -> Unit = { _, _ -> },
    onCreateWorkflow: (accountId: String) -> Unit = {},
    onDuplicateWorkflow: (accountId: String, workflowId: String, copySuffix: String) -> Unit = { _, _, _ -> },
    /** The tab to open on (the screen a wider window showed); then the member's. */
    initialTab: MainTab = MainTab.SESSIONS,
    onTabChange: (MainTab) -> Unit = {},
    home: HomeViewModel = koinViewModel(),
    settings: SettingsViewModel = koinViewModel(),
    waits: WaitsViewModel = koinViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // The tab last told to the caller: its echo back as initialTab is not a new tab, and must not undo a later choice.
    var reported by remember { mutableStateOf(initialTab) }
    // A wider window narrowed on one of its lists or on Settings: that tab.
    LaunchedEffect(initialTab) { if (initialTab != reported) { reported = initialTab; tab = initialTab } }
    // Only the member's own choices: the first composition and the tab given by a wider window are already known.
    LaunchedEffect(tab) { if (tab != reported) { reported = tab; onTabChange(tab) } }
    val sheets = rememberMainSheets()
    var palette by remember { mutableStateOf(false) }
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val account = homeState.account
    val automationsOffered = rememberAutomationsOffered(account)
    val workflowsOffered = rememberWorkflowsOffered(account)
    // A tab the active account's server does not offer (another account was chosen) shows the first one.
    // The waits follow the app's one feed while the signed-in app is open, whatever tab shows: the badge stays live.
    LifecycleStartEffect(waits) {
        waits.start()
        onStopOrDispose { waits.stop() }
    }
    val waitsState by waits.uiState.collectAsStateWithLifecycle()
    val shownTab = if ((tab == MainTab.AUTOMATIONS && !automationsOffered) || (tab == MainTab.WORKFLOWS && !workflowsOffered) || (tab == MainTab.WAITS && !waitsState.available)) MainTab.SESSIONS else tab
    LaunchedEffect(Unit) {
        AppCommands.commands.collect { command ->
            when (command) {
                AppCommand.NEW_SESSION -> home.uiState.value.account?.takeIf { it.signedIn }?.let { sheets.newSession(it.id) }
                AppCommand.SHOW_SESSIONS -> tab = MainTab.SESSIONS
                AppCommand.SHOW_ARTIFACTS -> tab = MainTab.ARTIFACTS
                AppCommand.SHOW_ACCOUNT -> tab = MainTab.ACCOUNT
                AppCommand.COMMAND_PALETTE -> palette = !palette
                // The phone layout has no list of sessions beside the open one: only the wide layout steps through them.
                AppCommand.PREVIOUS_SESSION, AppCommand.NEXT_SESSION -> Unit
            }
        }
    }

    Column(Modifier.fillMaxSize().background(TeamCode.colors.background)) {
        // The desktop's narrow window gets the banner too: shown by platform, never by size (nothing binds it on mobile).
        UpdateBanner()
        DemoBanner(account)
        Box(Modifier.weight(1f).fillMaxWidth().underDemoBanner(account)) {
            when (shownTab) {
                MainTab.SESSIONS -> SessionsScreen(
                    onSignInAgain = onSignInAgain,
                    onOpenSession = { a, session -> onOpenSession(a.id, session.id) },
                    onNewSession = { sheets.newSession(it.id) },
                    onSwitchAccount = { sheets.switcher = true },
                    titleActions = { TransfersButton(it.id) { onOpenTransfers(it.id) } },
                    viewModel = home,
                    waitsViewModel = waits,
                    onOpenWaits = { tab = MainTab.WAITS },
                )
                MainTab.WAITS -> if (account != null && account.signedIn) key(account.id) {
                    WaitsScreen(waits, onOpenSession = onOpenSession)
                } else SignedOutTab(stringResource(Res.string.waits_title), account, onSignInAgain)
                MainTab.ARTIFACTS -> if (account != null && account.signedIn) key(account.id) {
                    val viewModel: ArtifactsViewModel = koinViewModel(key = artifactsKey(account.id)) { parametersOf(ArtifactsRoute(account.id)) }
                    ArtifactsScreen(viewModel, forSession = false, onBack = {}, onOpen = { onOpenArtifact(account.id, it.id) }, onOpenLink = onOpenLink)
                } else SignedOutTab(stringResource(Res.string.artifacts_title), account, onSignInAgain)
                MainTab.AUTOMATIONS -> if (account != null && account.signedIn) key(account.id) {
                    val viewModel: AutomationsViewModel = koinViewModel(key = automationsKey(account.id)) { parametersOf(AutomationsRoute(account.id)) }
                    AutomationsScreen(viewModel, onOpen = { onOpenAutomation(account.id, it.id) }, onCreate = { onCreateAutomation(account.id) })
                } else SignedOutTab(stringResource(Res.string.automations_title), account, onSignInAgain)
                MainTab.WORKFLOWS -> if (account != null && account.signedIn) key(account.id) {
                    val runs: WorkflowRunsViewModel = koinViewModel(key = workflowRunsKey(account.id)) { parametersOf(WorkflowsRoute(account.id)) }
                    val definitions: WorkflowsViewModel = koinViewModel(key = workflowsKey(account.id)) { parametersOf(WorkflowsRoute(account.id)) }
                    val editors: WorkflowEditorsViewModel = koinViewModel(key = workflowEditorsKey(account.id)) { parametersOf(WorkflowsRoute(account.id)) }
                    val copySuffix = stringResource(Res.string.wfe_copy_suffix)
                    WorkflowsScreen(runs, definitions, onOpenRun = { onOpenWorkflowRun(account.id, it.id) }, onOpenWorkflow = { onOpenWorkflow(account.id, it.id) },
                        onStartWorkflow = { onStartWorkflow(account.id, it.id) }, onOpenWaits = { tab = MainTab.WAITS },
                        onCreateWorkflow = { onCreateWorkflow(account.id) }, onDuplicateWorkflow = { onDuplicateWorkflow(account.id, it.id, copySuffix) }, editors = editors)
                } else SignedOutTab(stringResource(Res.string.workflows_title), account, onSignInAgain)
                MainTab.ACCOUNT -> AccountScreen(onAddAccount = onAddAccount, onSignInAgain = onSignInAgain, onSwitchAccount = { sheets.switcher = true },
                    onOpenSubscriptions = { onOpenSubscriptions(it.id) }, onOpenGitHub = { onOpenGitHub(it.id) }, onOpenMcp = { onOpenMcp(it.id) }, viewModel = settings)
            }
        }
        TabBar(shownTab, automationsOffered, workflowsOffered, waitsState.takeIf { it.available }) { tab = it }
    }

    MainSheetsHost(sheets, settings, onOpenSession, onSignInAgain, onAddAccount, onManageAccounts = { tab = MainTab.ACCOUNT })

    // A tablet or a phone with a keyboard: the same palette as the desktop, over the tabs.
    if (palette) {
        val sessions: SessionsViewModel = koinViewModel()
        // The Sessions tab keeps the shared list live; on another tab, the palette does while it is open.
        if (tab != MainTab.SESSIONS) LifecycleStartEffect(sessions) {
            sessions.start()
            onStopOrDispose { sessions.stop() }
        }
        val sessionsState by sessions.uiState.collectAsStateWithLifecycle()
        val settingsState by settings.uiState.collectAsStateWithLifecycle()
        val artifacts = account?.takeIf { it.signedIn }?.let { key(it.id) { koinViewModel<ArtifactsViewModel>(key = artifactsKey(it.id)) { parametersOf(ArtifactsRoute(it.id)) } } }
        val artifactsState = artifacts?.uiState?.collectAsStateWithLifecycle()?.value
        CommandPalette(
            paletteItems(account, settingsState.accounts, sessionsState.list?.sessions.orEmpty(), artifactsState?.artifacts.orEmpty(), PaletteActions(
                onNewSession = { sheets.newSession(it.id) }, onAllSessions = { tab = MainTab.SESSIONS }, onAllArtifacts = { tab = MainTab.ARTIFACTS },
                onTransfers = { onOpenTransfers(it.id) }, onSettings = { tab = MainTab.ACCOUNT }, onAddAccount = onAddAccount, onSwitchAccount = { settings.switchTo(it) },
                onSignInAgain = onSignInAgain,
                onOpenSession = { session -> account?.let { sessions.opened(session.id); onOpenSession(it.id, session.id) } },
                onOpenArtifact = { artifact -> account?.let { onOpenArtifact(it.id, artifact.id) } },
            )),
            onDismiss = { palette = false },
        )
    }
}

/** A tab that needs a signed-in account while the active one is signed out. */
@Composable
internal fun SignedOutTab(title: String, account: Account?, onSignInAgain: (Account) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = ContentWidth).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle(title)
            if (account != null) EmptyState(PhosphorIcons.Regular.Warning, stringResource(Res.string.sessions_signed_out_title), stringResource(Res.string.home_signed_out, hostOf(account.origin)),
                dashed = false, iconTint = TeamCode.colors.warn) {
                TcButton(stringResource(Res.string.home_sign_in_again), onClick = { onSignInAgain(account) })
            }
        }
    }
}

/**
 * Whether the active account's server offers automations (`automations.v1`): read once per account, false while it is
 * unknown. A Koin without the use case (a test shell) offers none.
 */
@Composable
internal fun rememberAutomationsOffered(account: Account?): Boolean {
    val koin = getKoin()
    val availability = remember { koin.getOrNull<AutomationsAvailabilityUseCase>() }
    val signedIn = account?.takeIf { it.signedIn }
    val offered by produceState(false, signedIn?.id, availability) {
        value = false
        value = signedIn != null && availability?.available(signedIn.id) == true
    }
    return offered
}

/**
 * Whether the active account's server offers workflows (`workflows.v1`): read once per account, false while it is
 * unknown. A Koin without the use case (a test shell) offers none.
 */
@Composable
internal fun rememberWorkflowsOffered(account: Account?): Boolean {
    val koin = getKoin()
    val availability = remember { koin.getOrNull<WorkflowsAvailabilityUseCase>() }
    val signedIn = account?.takeIf { it.signedIn }
    val offered by produceState(false, signedIn?.id, availability) {
        value = false
        value = signedIn != null && availability?.available(signedIn.id) == true
    }
    return offered
}

/** The keys of an account's runs and saved workflows in the signed-in app's store: the phone's tab and the wide layout share them. */
internal fun workflowRunsKey(accountId: String) = "workflow-runs-$accountId"
internal fun workflowsKey(accountId: String) = "workflows-$accountId"
internal fun workflowEditorsKey(accountId: String) = "workflow-editors-$accountId"

/** The key of an account's automation list in the signed-in app's store: the phone's tab and the wide layout's sidebar share it. */
internal fun automationsKey(accountId: String) = "automations-$accountId"

@Composable
private fun TabBar(selected: MainTab, automations: Boolean, workflows: Boolean, waits: WaitsUiState?, onSelect: (MainTab) -> Unit) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.Center,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = ContentWidth)) {
              // Five tabs on a narrow phone: the Automations label is shortened (a French "Automatisations" does not fit under 380 dp).
              val short = maxWidth < 380.dp
              Row(Modifier.fillMaxWidth()) {
                TabItem(MainTab.SESSIONS, PhosphorIcons.Regular.TerminalWindow, stringResource(Res.string.tab_sessions), selected, onSelect)
                if (waits != null) TabItem(MainTab.WAITS, PhosphorIcons.Regular.Bell, stringResource(Res.string.tab_waits), selected, onSelect, badge = waits)
                TabItem(MainTab.ARTIFACTS, PhosphorIcons.Regular.FileText, stringResource(Res.string.tab_artifacts), selected, onSelect)
                if (workflows) TabItem(MainTab.WORKFLOWS, PhosphorIcons.Regular.FlowArrow, stringResource(Res.string.tab_workflows), selected, onSelect)
                if (automations) TabItem(MainTab.AUTOMATIONS, PhosphorIcons.Regular.Clock, stringResource(if (short) Res.string.tab_automations_short else Res.string.tab_automations), selected, onSelect)
                TabItem(MainTab.ACCOUNT, PhosphorIcons.Regular.UserCircle, stringResource(Res.string.tab_account), selected, onSelect)
              }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(tab: MainTab, icon: ImageVector, label: String, selected: MainTab, onSelect: (MainTab) -> Unit, badge: WaitsUiState? = null) {
    val colors = TeamCode.colors
    val on = tab == selected
    val color = if (on) colors.accentText else colors.muted
    Column(
        Modifier.weight(1f).heightIn(min = 48.dp).selectable(on, role = Role.Tab) { onSelect(tab) }.testTag("tab-${tab.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            // The open waits, live while another tab shows.
            if (badge != null && badge.openCount > 0) WaitsBadge(badge.openCount, badge.moreOpen, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp).testTag("tab-badge-waits"))
        }
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
