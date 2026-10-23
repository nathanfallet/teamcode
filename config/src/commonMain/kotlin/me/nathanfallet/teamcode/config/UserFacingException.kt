package me.nathanfallet.teamcode.config

/** A failure whose message is written by TeamCode itself (never derived from a remote or platform error), so it is safe and useful to print. */
class UserFacingException(override val message: String) : Exception(message)
