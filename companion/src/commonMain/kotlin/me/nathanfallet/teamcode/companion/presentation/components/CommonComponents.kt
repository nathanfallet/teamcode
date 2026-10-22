package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.action_back
import org.jetbrains.compose.resources.stringResource

/** A screen with a top bar and a centered, scrollable column, readable on a phone and on a wide window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(PhosphorIcons.Regular.CaretLeft, contentDescription = stringResource(Res.string.action_back), modifier = Modifier.size(22.dp))
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("error"))
}

@Composable
fun BusyRow(busy: Boolean) {
    if (busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}
