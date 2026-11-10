package me.nathanfallet.teamcode.companion.android

import android.app.Application
import me.nathanfallet.teamcode.companion.di.initKoin
import me.nathanfallet.teamcode.companion.di.platformModule
import me.nathanfallet.teamcode.companion.di.startPush
import me.nathanfallet.teamcode.companion.domain.services.PushPlatform
import me.nathanfallet.teamcode.companion.infrastructure.push.AndroidPushPlatform
import org.koin.dsl.bind
import org.koin.dsl.module

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Push only in a build that carries the Firebase project; elsewhere no PushPlatform, and nothing registers.
        val push = if (PushNotifications.configured) module { single { AndroidPushPlatform(get()) } bind PushPlatform::class } else null
        val koin = initKoin(platformModule(applicationContext)) { push?.let { modules(it) } }.koin
        if (push == null) return
        PushNotifications.start(this, koin.get())
        startPush(koin)
    }
}
