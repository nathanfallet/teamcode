package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddServerViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LoginViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val presentationModule = module {
    viewModelOf(::RootViewModel)
    viewModelOf(::AddServerViewModel)
    viewModel { (route: LoginRoute) -> LoginViewModel(route, get(), get(), get(), get()) }
    viewModelOf(::HomeViewModel)
    viewModelOf(::SettingsViewModel)
}
