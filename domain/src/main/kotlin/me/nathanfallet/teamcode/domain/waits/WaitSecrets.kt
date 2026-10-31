package me.nathanfallet.teamcode.domain.waits

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps the values of secret fields apart from everything a wait shows (docs/waits.md#secrets). An answer is split in two: the
 * values that are stored in the wait, where a secret field reads [PROVIDED] or [NOT_PROVIDED] and never its value, and the
 * [WaitSecret]s the store seals for the session. Nothing else (the answer view, the wake message, the feed, the audit) ever sees
 * the value.
 */
object WaitSecrets {
    const val PROVIDED = "provided"
    const val NOT_PROVIDED = "not provided"

    class Split(val values: Map<String, JsonElement>, val secrets: List<WaitSecret>)

    /** [values] already checked by [WaitRules.checkAnswer]. [at] is the answer's instant, from which the secrets' lifetime runs. */
    fun split(fields: List<WaitField>, values: Map<String, JsonElement>, at: Long): Split {
        val secretFields = fields.filter { it.type == FieldType.SECRET }
        if (secretFields.isEmpty()) return Split(values, emptyList())
        val kept = LinkedHashMap(values)
        val secrets = mutableListOf<WaitSecret>()
        for (field in secretFields) {
            val value = values[field.id]
            if (WaitRules.present(value)) {
                secrets += WaitSecret(WaitRules.secretVariable(field.id), field.id, (value as JsonPrimitive).content, field.oneTime, at + WaitRules.SECRET_LIFETIME_MILLIS)
                kept[field.id] = JsonPrimitive(PROVIDED)
            } else kept[field.id] = JsonPrimitive(NOT_PROVIDED)
        }
        return Split(kept, secrets)
    }

    /** Whether the stored answer says the secret field [id] was provided. */
    fun provided(answer: WaitAnswer?, id: String) = (answer?.values?.get(id) as? JsonPrimitive)?.content == PROVIDED
}
