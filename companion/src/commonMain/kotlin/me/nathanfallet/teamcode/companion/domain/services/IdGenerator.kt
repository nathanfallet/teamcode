package me.nathanfallet.teamcode.companion.domain.services

fun interface IdGenerator {
    fun newId(): String
}
