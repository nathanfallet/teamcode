package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowDraftRepository
import me.nathanfallet.teamcode.companion.domain.repositories.WorkflowRepository
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.DeleteWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowCatalogUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowDefinitionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowSettingsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.SaveWorkflowEditorsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.SaveWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowChanges
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsWorkflowDraftRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowEditRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowEditorsViewModel
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.CancelWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.FollowWorkflowRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.GetWorkflowUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadOpenWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.LoadWorkflowsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.StartWorkflowRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowAccess
import me.nathanfallet.teamcode.companion.domain.usecases.workflows.WorkflowsAvailabilityUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteWorkflowRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowRunRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowStartRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.WorkflowsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowRunsViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowStartViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WorkflowsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Workflows (`workflows.v1`): the member's runs, kept live by the app's one feed, and the saved workflows to start. */
val workflowsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's calls in memory.
    single { RemoteWorkflowRepository(get()) }
    single<WorkflowRepository> { get<RemoteWorkflowRepository>() }
    single { WorkflowsAvailabilityUseCase(get(), get()) }
    single { WorkflowAccess(get(), get(), get(), get()) }
    single { LoadWorkflowRunsUseCase(get()) }
    single { GetWorkflowRunUseCase(get()) }
    single { CancelWorkflowRunUseCase(get()) }
    single { LoadWorkflowsUseCase(get()) }
    single { GetWorkflowUseCase(get()) }
    single { StartWorkflowRunUseCase(get()) }
    single { LoadOpenWaitsUseCase(get(), get(), get()) }
    single { FollowWorkflowRunsUseCase(get(), get(), get()) }
    viewModel { (route: WorkflowsRoute) -> WorkflowRunsViewModel(route, get(), get(), get(), get(), get(), get()) }
    single { LoadWorkflowCatalogUseCase(get()) }
    single { LoadWorkflowDefinitionUseCase(get()) }
    single { LoadWorkflowSettingsUseCase(get()) }
    single { SaveWorkflowEditorsUseCase(get()) }
    single { WorkflowChanges() }
    single { SaveWorkflowUseCase(get(), get()) }
    single { DeleteWorkflowUseCase(get(), get()) }
    single<WorkflowDraftRepository> { SettingsWorkflowDraftRepository(get()) }
    viewModel { (route: WorkflowsRoute) -> WorkflowsViewModel(route, get(), get(), get()) }
    viewModel { (route: WorkflowsRoute) -> WorkflowEditorsViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: WorkflowEditRoute) -> WorkflowEditViewModel(route, get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (route: WorkflowRoute) -> WorkflowViewModel(route, get(), get(), get(), get(), get(), get()) }
    viewModel { (route: WorkflowStartRoute) -> WorkflowStartViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: WorkflowRunRoute) -> WorkflowRunViewModel(route, get(), get(), get(), get(), get()) }
}
