package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAvailabilityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferSessionUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteTransferRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.TransferRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransferViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.TransfersViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Session transfers (`cloud.transfer.v1`): the repository, its use cases and the two view models. */
val transferModule = module {
    single<TransferRepository> { RemoteTransferRepository(get()) }
    single { TransferAvailabilityUseCase(get(), get()) }
    single { LoadRecipientsUseCase(get(), get(), get(), get()) }
    single { TransferSessionUseCase(get(), get(), get(), get()) }
    single { TransferListUseCase(get(), get(), get(), get()) }
    viewModel { (route: TransferRoute) -> TransferViewModel(route, get(), get(), get(), get()) }
    viewModel { (route: TransfersRoute) -> TransfersViewModel(route, get(), get()) }
}
