package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.presentation.navigation.LoginRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AddServerViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.HomeViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.LoginViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.RootViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SettingsViewModel
import me.nathanfallet.teamcode.companion.presentation.components.embedsArtifacts
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.ArtifactsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val presentationModule = module {
    viewModelOf(::RootViewModel)
    viewModelOf(::AddServerViewModel)
    viewModel { (route: LoginRoute) -> LoginViewModel(route, get(), get(), get(), get()) }
    viewModelOf(::HomeViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::SessionsViewModel)
    viewModel { (route: SessionRoute) -> SessionViewModel(route, get(), get(), get(), get(), get(), get()) }
    viewModel { (route: NewSessionRoute) -> NewSessionViewModel(route, get(), get(), get()) }
    viewModel { (route: ArtifactsRoute) -> ArtifactsViewModel(route, get()) }
    viewModel { (route: ArtifactRoute) -> ArtifactViewModel(route, get(), get(), embedsArtifacts, get()) }
}
