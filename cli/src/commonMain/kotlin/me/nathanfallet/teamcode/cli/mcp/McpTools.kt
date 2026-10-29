package me.nathanfallet.teamcode.cli.mcp

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.proxy.RELAY_SIGN_IN_REFUSED
import me.nathanfallet.teamcode.client.TeamCodeException

/** What the stdio MCP servers TeamCode runs beside an agent share: JSON-RPC answers, argument readers and error texts. */
internal object McpTools {
    fun result(id: JsonElement, value: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
    fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) }
    }
    fun text(text: String, isError: Boolean = false) = buildJsonObject {
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        if (isError) put("isError", true)
    }

    /**
     * Never an exception chain: only our own messages and the server's error code and text. [hint] adds a server-specific
     * sentence for a TeamCode error code (what to do about it), or null.
     */
    fun describe(error: Exception, hint: (TeamCodeException) -> String? = { null }): String = when (error) {
        is CliUserException -> error.message
        is TeamCodeException -> if (error.code == RELAY_SIGN_IN_REFUSED)
            "TeamCode refused the sign-in ($RELAY_SIGN_IN_REFUSED): run `teamcode login` on this computer; in a cloud session, the execution has ended. Retrying does not help."
        else "TeamCode refused the call (${error.code ?: "http_${error.statusCode}"}): ${error.message}" +
            (hint(error)?.let { " $it" } ?: "") +
            (if (error.statusCode == 502 || error.statusCode == 503 || error.statusCode == 429) " Retry in a moment." else "")
        is kotlinx.serialization.SerializationException -> "unexpected_answer: TeamCode answered something this version cannot read. Update teamcode."
        // The typed client's own argument checks ("Invalid member."): the call never left, fix the argument.
        is IllegalArgumentException -> "invalid_argument: ${error.message ?: "an argument is not valid"} Fix the argument; retrying does not help."
        else -> "server_unreachable: the TeamCode server could not be reached. Retry in a moment."
    }

    /** A string argument, absent when missing, null or blank; any other type is refused rather than read as absent. */
    fun JsonObject.string(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content.takeIf { it.isNotBlank() } else throw CliUserException("$name must be a string.")
        else -> throw CliUserException("$name must be a string.")
    }
    /** Absent or null is null; a whole number, also written as a string or as `3.0`, is that number; anything else is refused. */
    fun JsonObject.int(name: String): Int? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
        return primitive?.content?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.takeIf { d -> d % 1.0 == 0.0 && d in -1e9..1e9 }?.toInt() }
            ?: throw CliUserException("$name must be a whole number.")
    }
    /** Absent or null is null; `true`/`false`, also written as strings, are themselves; anything else is refused. */
    fun JsonObject.boolean(name: String): Boolean? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return when ((value as? JsonPrimitive)?.content?.trim()?.lowercase()) { "true" -> true; "false" -> false; else -> throw CliUserException("$name must be true or false.") }
    }

    /** A tool's input schema: an object with [required] properties and no others. */
    fun schema(required: List<String>, properties: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object"); putJsonObject("properties", properties)
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }
    fun JsonObjectBuilder.text(name: String, description: String) = putJsonObject(name) { put("type", "string"); put("description", description) }
    fun JsonObjectBuilder.number(name: String, description: String) = putJsonObject(name) { put("type", "integer"); put("description", description) }
}
