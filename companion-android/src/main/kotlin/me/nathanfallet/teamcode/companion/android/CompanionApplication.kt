package me.nathanfallet.teamcode.companion.android

import android.app.Application
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(platformModule(applicationContext))
    }
}
