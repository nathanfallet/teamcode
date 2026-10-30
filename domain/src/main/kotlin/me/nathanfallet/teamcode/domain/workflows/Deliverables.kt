package me.nathanfallet.teamcode.domain.workflows

import java.net.URI
import kotlinx.serialization.json.*

/** What an agent node must hand in, checked by the server before it accepts it (docs/workflows.md). */
object Deliverables {
    const val MAX_TEXT = 32_000
    const val MAX_JSON = 64_000
    private val artifactId = Regex("[A-Za-z0-9._:-]{1,64}")

    fun invalid(message: String) = me.nathanfallet.teamcode.domain.team.TeamFailure("invalid_deliverable", 400, message)

    /** The canonical text stored for [output]: the string itself, or the JSON of a `json` deliverable. */
    fun validate(deliverable: Deliverable, output: JsonElement): String = when (deliverable.type) {
        DeliverableType.ARTIFACT -> text(output, "an artifact id").also { if (!artifactId.matches(it)) throw invalid("Deliver the id of an artifact.") }
        DeliverableType.URL -> url(text(output, "an https address"), deliverable.domain)
        DeliverableType.TEXT -> text(output, "text").also { if (it.isBlank() || it.length > MAX_TEXT) throw invalid("Deliver 1 to $MAX_TEXT characters of text.") }
        DeliverableType.JSON -> {
            if (output is JsonPrimitive && output.isString) throw invalid("Deliver a JSON value, not a string holding JSON.")
            val encoded = output.toString()
            if (encoded.length > MAX_JSON) throw invalid("Deliver at most $MAX_JSON characters of JSON.")
            deliverable.schema?.let { check(it, output, "output") }
            encoded
        }
    }

    private fun text(output: JsonElement, what: String): String =
        (output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("Deliver $what as a string.")

    private fun url(value: String, domain: String?): String {
        if (value.length > 2_000 || value.any { it.isWhitespace() || it.isISOControl() }) throw invalid("Deliver an https address without spaces.")
        val uri = try { URI(value) } catch (_: Exception) { throw invalid("Deliver a valid https address.") }
        val host = uri.host?.lowercase()
        if (uri.scheme != "https" || host == null) throw invalid("Deliver an https address.")
        if (domain != null && host != domain && !host.endsWith(".$domain")) throw invalid("Deliver an address on $domain.")
        return value
    }

    /**
     * A small subset of JSON Schema: `type` (object, array, string, number, integer, boolean), an object's `properties` and
     * `required`, an array's `items`, and a string's `enum`. Anything else in the schema is ignored.
     */
    fun check(schema: JsonElement, value: JsonElement, path: String) {
        val rules = schema as? JsonObject ?: return
        when ((rules["type"] as? JsonPrimitive)?.contentOrNull) {
            "object" -> {
                val obj = value as? JsonObject ?: throw invalid("$path must be an object.")
                (rules["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull { it !in obj }?.let { throw invalid("$path.$it is required.") }
                (rules["properties"] as? JsonObject)?.forEach { (key, sub) -> obj[key]?.let { check(sub, it, "$path.$key") } }
            }
            "array" -> {
                val array = value as? JsonArray ?: throw invalid("$path must be a list.")
                rules["items"]?.let { items -> array.forEachIndexed { i, element -> check(items, element, "$path[$i]") } }
            }
            "string" -> {
                val string = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("$path must be a string.")
                (rules["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.let { if (string !in it) throw invalid("$path must be one of ${it.joinToString(", ")}.") }
            }
            "number" -> if ((value as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull != null } != true) throw invalid("$path must be a number.")
            "integer" -> if ((value as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } != true) throw invalid("$path must be an integer.")
            "boolean" -> if ((value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } != true) throw invalid("$path must be true or false.")
        }
    }
}
