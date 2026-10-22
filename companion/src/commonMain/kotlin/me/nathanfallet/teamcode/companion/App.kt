package me.nathanfallet.teamcode.companion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.nathanfallet.teamcode.companion.presentation.navigation.TeamCodeNavHost
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App() {
    val root: RootViewModel = koinViewModel()
    val theme by root.theme.collectAsStateWithLifecycle()
    TeamCodeTheme(theme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TeamCodeNavHost(root)
        }
    }
}
