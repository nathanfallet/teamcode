package me.nathanfallet.teamcode.companion.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The system font (San Francisco on Apple platforms, Roboto on Android, the desktop's UI font), as on the dashboard.
private val Body = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, lineHeight = 22.sp)

val TeamCodeTypography: Typography = Typography().run {
    copy(
        headlineMedium = Body.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        headlineSmall = Body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = Body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = Body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        titleSmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = Body,
        bodyMedium = Body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
        bodySmall = Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = Body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = Body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = Body.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** Origins, identifiers and versions: what the member compares character by character. */
val MonospaceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

/** Secondary details in monospace: agent, model, repository, age. */
val MetaStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

/** Section titles: small capitals, spaced. */
val SectionStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)

/** The pill chips: monospace, tight. */
val ChipStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 12.sp, letterSpacing = 0.04.em)
