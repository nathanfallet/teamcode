package me.nathanfallet.teamcode.companion.di

import me.nathanfallet.teamcode.companion.demo.demoModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration

/** All app modules, given the platform module (credential store, HTTP engine, preferences, platform details). */
fun appModules(platformModule: Module): List<Module> =
    listOf(infrastructureModule, domainModule, presentationModule, transferModule, subscriptionModule, githubModule, mcpModule, automationsModule, waitsModule, workflowsModule, demoModule, platformModule)

/** Starts Koin once at launch; each entry point (Android application, iOS view controller, desktop main) calls it. */
fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(appModules(platformModule))
    }
