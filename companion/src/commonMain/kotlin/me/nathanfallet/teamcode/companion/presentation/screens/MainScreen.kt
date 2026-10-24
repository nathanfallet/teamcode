package me.nathanfallet.teamcode.companion.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
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
import me.nathanfallet.teamcode.companion.presentation.components.hostOf
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LocalSessionViewModel
import me.nathanfallet.teamcode.companion.domain.services.LocalHost
import org.koin.compose.getKoin
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.resources.Res
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

enum class MainTab { SESSIONS, ARTIFACTS, ACCOUNT }

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
    val sheets = MainSheets(
        rememberSaveable { mutableStateOf<String?>(null) }, rememberSaveable { mutableStateOf<String?>(null) },
        rememberSaveable { mutableStateOf<String?>(null) }, rememberSaveable { mutableStateOf<String?>(null) },
        rememberSaveable { mutableStateOf(false) }, localHost,
    )
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
    home: HomeViewModel = koinViewModel(),
    settings: SettingsViewModel = koinViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.SESSIONS) }
    val sheets = rememberMainSheets()
    val homeState by home.uiState.collectAsStateWithLifecycle()
    val account = homeState.account
    LaunchedEffect(Unit) {
        AppCommands.commands.collect { command ->
            when (command) {
                AppCommand.NEW_SESSION -> home.uiState.value.account?.takeIf { it.signedIn }?.let { sheets.newSession(it.id) }
                AppCommand.SHOW_SESSIONS -> tab = MainTab.SESSIONS
                AppCommand.SHOW_ARTIFACTS -> tab = MainTab.ARTIFACTS
                AppCommand.SHOW_ACCOUNT -> tab = MainTab.ACCOUNT
            }
        }
    }

    Column(Modifier.fillMaxSize().background(TeamCode.colors.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MainTab.SESSIONS -> SessionsScreen(
                    onSignInAgain = onSignInAgain,
                    onOpenSession = { a, session -> onOpenSession(a.id, session.id) },
                    onNewSession = { sheets.newSession(it.id) },
                    onSwitchAccount = { sheets.switcher = true },
                    titleActions = { TransfersButton(it.id) { onOpenTransfers(it.id) } },
                    viewModel = home,
                )
                MainTab.ARTIFACTS -> if (account != null && account.signedIn) key(account.id) {
                    val viewModel: ArtifactsViewModel = koinViewModel(key = "artifacts-${account.id}") { parametersOf(ArtifactsRoute(account.id)) }
                    ArtifactsScreen(viewModel, forSession = false, onBack = {}, onOpen = { onOpenArtifact(account.id, it.id) }, onOpenLink = onOpenLink)
                } else SignedOutTab(stringResource(Res.string.artifacts_title), account, onSignInAgain)
                MainTab.ACCOUNT -> AccountScreen(onAddAccount = onAddAccount, onSignInAgain = onSignInAgain, onSwitchAccount = { sheets.switcher = true },
                    onOpenSubscriptions = { onOpenSubscriptions(it.id) }, viewModel = settings)
            }
        }
        TabBar(tab) { tab = it }
    }

    MainSheetsHost(sheets, settings, onOpenSession, onSignInAgain, onAddAccount, onManageAccounts = { tab = MainTab.ACCOUNT })
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

@Composable
private fun TabBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    val colors = TeamCode.colors
    Column(Modifier.fillMaxWidth().background(colors.line).padding(top = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().background(colors.panelAlt).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.widthIn(max = ContentWidth)) {
                TabItem(MainTab.SESSIONS, PhosphorIcons.Regular.TerminalWindow, stringResource(Res.string.tab_sessions), selected, onSelect)
                TabItem(MainTab.ARTIFACTS, PhosphorIcons.Regular.FileText, stringResource(Res.string.tab_artifacts), selected, onSelect)
                TabItem(MainTab.ACCOUNT, PhosphorIcons.Regular.UserCircle, stringResource(Res.string.tab_account), selected, onSelect)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(tab: MainTab, icon: ImageVector, label: String, selected: MainTab, onSelect: (MainTab) -> Unit) {
    val colors = TeamCode.colors
    val on = tab == selected
    val color = if (on) colors.accentText else colors.muted
    Column(
        Modifier.weight(1f).heightIn(min = 48.dp).selectable(on, role = Role.Tab) { onSelect(tab) }.testTag("tab-${tab.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
