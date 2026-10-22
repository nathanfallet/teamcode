package me.nathanfallet.teamcode.companion.domain.services

fun interface Clock {
    fun nowMillis(): Long
}
