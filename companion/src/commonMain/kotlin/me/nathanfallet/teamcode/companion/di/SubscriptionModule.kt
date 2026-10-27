package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.SubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.CompleteSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.LoadSubscriptionAccountsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.StartSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSubscriptionAccountRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.SubscriptionAccountsRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SubscriptionAccountsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Subscription accounts: the pool of an account's server and adding one (`teamcode accounts add` in the app). */
val subscriptionModule = module {
    single { RemoteSubscriptionAccountRepository(get(), get(), get(), get()) }
    single { LoadSubscriptionAccountsUseCase(get(), get(), get()) }
    single { StartSubscriptionSignInUseCase(get(), get()) }
    single { CompleteSubscriptionSignInUseCase(get()) }
    viewModel { (route: SubscriptionAccountsRoute) -> SubscriptionAccountsViewModel(route, get(), get(), get()) }
}
