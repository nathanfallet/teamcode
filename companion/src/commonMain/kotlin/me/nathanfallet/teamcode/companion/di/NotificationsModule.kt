package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.domain.repositories.NotificationPreferencesRepository
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.LoadNotificationPreferencesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.NotificationPreferencesAvailabilityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SendTestPushUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.notifications.SetNotificationPreferenceUseCase
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteNotificationPreferencesRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.NotificationPreferencesRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NotificationPreferencesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The member's notification switches and the test push (`notifications.push.v1`). */
val notificationsModule = module {
    single<NotificationPreferencesRepository> { RemoteNotificationPreferencesRepository(get()) }
    single { NotificationPreferencesAvailabilityUseCase(get(), get()) }
    single { LoadNotificationPreferencesUseCase(get(), get(), get(), get()) }
    single { SetNotificationPreferenceUseCase(get(), get()) }
    single { SendTestPushUseCase(get(), get()) }
    viewModel { (route: NotificationPreferencesRoute) -> NotificationPreferencesViewModel(route, get(), get(), get()) }
}
