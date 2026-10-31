package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.AutomationRepository
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationAccess
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationSecrets
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationsAvailabilityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.CancelAutomationRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.DeleteAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.GetAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.LoadAutomationRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.LoadAutomationsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.PreviewAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.RotateAutomationSecretUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SaveAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SetAutomationEnabledUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.TestAutomationUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAutomationRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationEditRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationTestRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationTestViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.AutomationsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Automations (`automations.v1`): cron and webhook triggers that start cloud sessions as their owner. */
val automationsModule = module {
    single { RemoteAutomationRepository(get()) }
    single<AutomationRepository> { get<RemoteAutomationRepository>() }
    single { AutomationSecrets() }
    single { AutomationsAvailabilityUseCase(get(), get()) }
    single { AutomationAccess(get(), get(), get(), get()) }
    single { LoadAutomationsUseCase(get()) }
    single { GetAutomationUseCase(get()) }
    single { SaveAutomationUseCase(get()) }
    single { SetAutomationEnabledUseCase(get()) }
    single { DeleteAutomationUseCase(get()) }
    single { RotateAutomationSecretUseCase(get()) }
    single { PreviewAutomationUseCase(get()) }
    single { TestAutomationUseCase(get()) }
    single { LoadAutomationRunsUseCase(get()) }
    single { CancelAutomationRunUseCase(get()) }
    viewModel { (route: AutomationsRoute) -> AutomationsViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: AutomationRoute) -> AutomationViewModel(route, get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (route: AutomationEditRoute) -> AutomationEditViewModel(route, get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (route: AutomationTestRoute) -> AutomationTestViewModel(route, get(), get(), get(), get(), get()) }
}
