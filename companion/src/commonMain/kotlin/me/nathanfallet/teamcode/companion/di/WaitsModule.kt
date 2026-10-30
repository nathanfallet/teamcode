package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteWaitRepository
import me.nathanfallet.teamcode.companion.presentation.viewmodels.WaitsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Waits (`waits.v1`): what sessions need from the member, on the Waiting for you screen and counted by its badge and the sessions banner. */
val waitsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's calls in memory.
    single { RemoteWaitRepository(get()) }
    single<WaitRepository> { get<RemoteWaitRepository>() }
    single { LoadWaitsUseCase(get(), get(), get()) }
    single { AnswerWaitUseCase(get(), get()) }
    single { FollowWaitsUseCase(get(), get(), get()) }
    viewModel { WaitsViewModel(get(), get(), get(), get()) }
}
