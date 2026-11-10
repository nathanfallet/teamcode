package me.nathanfallet.teamcode.companion.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.demo.demoModule
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform
import me.nathanfallet.teamcode.companion.domain.usecases.push.SyncPushUseCase
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration

/** All app modules, given the platform module (credential store, HTTP engine, preferences, platform details). */
fun appModules(platformModule: Module): List<Module> =
    listOf(infrastructureModule, domainModule, presentationModule, transferModule, subscriptionModule, githubModule, mcpModule, notificationsModule, automationsModule, waitsModule, todosModule, workflowsModule, demoModule, platformModule)

/** Starts Koin once at launch; each entry point (Android application, iOS view controller, desktop main) calls it. */
fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(appModules(platformModule))
    }

/**
 * Keeps the servers of the signed-in accounts told where to push (SyncPushUseCase), on a phone whose platform module
 * binds a [PushPlatform]; nothing elsewhere. Called once after [initKoin].
 */
fun startPush(koin: Koin) {
    if (koin.getOrNull<PushPlatform>() == null) return
    val sync = koin.get<SyncPushUseCase>()
    koin.get<CoroutineScope>().launch { sync() }
}
