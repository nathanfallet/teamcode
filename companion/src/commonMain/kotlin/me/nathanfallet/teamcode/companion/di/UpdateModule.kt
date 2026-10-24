package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.UpdatePreferences
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsUpdatePreferences
import me.nathanfallet.teamcode.companion.presentation.viewmodels.UpdateBannerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The update banner; included by the desktop's platform module, which provides the [me.nathanfallet.teamcode.companion.domain.services.AppUpdater]. */
val updateModule = module {
    single<UpdatePreferences> { SettingsUpdatePreferences(get()) }
    viewModel { UpdateBannerViewModel(get(), get(), get()) }
}
