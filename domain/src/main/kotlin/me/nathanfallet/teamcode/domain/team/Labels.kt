package me.nathanfallet.teamcode.domain.team

/** The label model shared by cloud sessions and local runs: non-secret, filterable `key=value` pairs. */
object LabelRules {
    const val MAX_LABELS = 16
    const val MAX_VALUE_LENGTH = 256
    private val key = Regex("[a-z0-9]([a-z0-9._/-]{0,61}[a-z0-9])?")

    fun isValid(labels: Map<String, String>) = labels.size <= MAX_LABELS &&
        labels.all { (name, value) -> key.matches(name) && value.length <= MAX_VALUE_LENGTH && value.none(Char::isISOControl) }

    fun validate(labels: Map<String, String>) {
        if (!isValid(labels))
            throw TeamFailure("invalid_label", 400, "Use at most 16 labels with lowercase keys of 1–63 characters and values of at most 256 characters.")
    }

    fun validateFilter(labels: Map<String, String>) {
        if (labels.size > MAX_LABELS) throw TeamFailure("invalid_label", 400, "Filter on at most 16 labels.")
    }

    /** Parses repeated `key=value` query parameters; the value may itself contain `=`. */
    fun parseFilter(values: List<String>): Map<String, String> = values.associate { value ->
        val (name, label) = value.split('=', limit = 2).takeIf { it.size == 2 } ?: throw TeamFailure("invalid_label", 400, "Filter labels as key=value.")
        name to label
    }.also(::validateFilter)
}
