package me.nathanfallet.teamcode.companion.infrastructure.system

import kotlin.time.Clock as KotlinClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator

object SystemClock : Clock {
    override fun nowMillis(): Long = KotlinClock.System.now().toEpochMilliseconds()
}

object RandomIdGenerator : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): String = Uuid.random().toString()
}
