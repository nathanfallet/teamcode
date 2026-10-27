package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Flask
import com.adamglin.phosphoricons.regular.GithubLogo
import me.nathanfallet.teamcode.companion.demo.DemoContent
import me.nathanfallet.teamcode.companion.demo.isDemo
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode
import me.nathanfallet.teamcode.companion.resources.Res
import me.nathanfallet.teamcode.companion.resources.demo_banner_detail
import me.nathanfallet.teamcode.companion.resources.demo_banner_title
import me.nathanfallet.teamcode.companion.resources.demo_github
import org.jetbrains.compose.resources.stringResource

/**
 * "You are trying the demo" at the top of the main screen, under the update banner, with a link to the project on
 * GitHub (star it, deploy a server). Only for the demo account: an account on a real server never shows it.
 */
@Composable
fun DemoBanner(account: Account?) {
    if (account?.isDemo != true) return
    val colors = TeamCode.colors
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().background(colors.panel).testTag("demo-banner")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PhosphorIcons.Regular.Flask, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.demo_banner_title), color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(Res.string.demo_banner_detail), color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            TcButton(stringResource(Res.string.demo_github), onClick = { openRepository(browser) }, small = true, fill = false, icon = PhosphorIcons.Regular.GithubLogo,
                modifier = Modifier.testTag("demo-github"))
        }
        HorizontalDivider(color = colors.line)
    }
}

/** The project's page in the browser; a device without one leaves the app as it is. */
internal fun openRepository(browser: androidx.compose.ui.platform.UriHandler) {
    runCatching { browser.openUri(DemoContent.REPOSITORY_URL) }
}
