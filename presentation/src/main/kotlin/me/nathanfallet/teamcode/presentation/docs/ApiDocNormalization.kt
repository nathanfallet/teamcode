package me.nathanfallet.teamcode.presentation.docs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val SCHEMA_REF_PREFIX = "#/components/schemas/"

/**
 * One schema per type. Ktor puts the nullability of a type's first use into its component, then gives a use with the
 * other nullability a numbered copy (`GitHubLinkView` and a nullable `GitHubLinkView2`). This keeps one component per
 * type, never nullable, under its own name, and writes a nullable use as `anyOf: [$ref, {type: null}]`.
 */
internal fun JsonObject.withSingleSchemaPerType(): JsonObject {
    val components = this["components"] as? JsonObject ?: return this
    val schemas = components["schemas"] as? JsonObject ?: return this
    val nullable = schemas.filterValues { (it as? JsonObject)?.isNullableObject() == true }.keys
    val twins = schemas.keys.mapNotNull { name ->
        val base = name.trimEnd { it.isDigit() }
        if (base != name && base in schemas && schemas.isNullabilityTwin(name, base)) name to base else null
    }.toMap()
    val kept = JsonObject(schemas.filterKeys { it !in twins }.mapValues { (_, schema) -> (schema as? JsonObject)?.withoutNull() ?: schema })
    val document = JsonObject(this + ("components" to JsonObject(components + ("schemas" to kept))))
    return document.rewriteReferences(twins, nullable) as JsonObject
}

private fun JsonObject.isNullabilityTwin(twin: String, base: String): Boolean {
    val copy = this[twin] as? JsonObject
    val original = this[base] as? JsonObject
    return copy != null && original != null && copy.withoutNull().minus("title") == original.withoutNull().minus("title")
}

private fun JsonObject.isNullableObject(): Boolean {
    val type = this["type"] as? JsonArray ?: return false
    val names = type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return "null" in names && "object" in names
}

private fun JsonObject.withoutNull(): JsonObject {
    val type = this["type"] as? JsonArray ?: return this
    val kept = type.filter { (it as? JsonPrimitive)?.contentOrNull != "null" }
    return JsonObject(this + ("type" to (kept.singleOrNull() ?: JsonArray(kept))))
}

/** Each `$ref` points to the kept component, as an `anyOf` with `null` where it pointed to a nullable one. */
private fun JsonElement.rewriteReferences(twins: Map<String, String>, nullable: Set<String>): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { it.rewriteReferences(twins, nullable) })
    is JsonObject -> {
        val target = (this["\$ref"] as? JsonPrimitive)?.contentOrNull?.removePrefix(SCHEMA_REF_PREFIX)
        if (target != null && keys == setOf("\$ref")) {
            val reference = JsonObject(mapOf("\$ref" to JsonPrimitive(SCHEMA_REF_PREFIX + (twins[target] ?: target))))
            if (target in nullable) JsonObject(mapOf("anyOf" to JsonArray(listOf(reference, JsonObject(mapOf("type" to JsonPrimitive("null")))))))
            else reference
        } else {
            JsonObject(mapValues { (_, value) -> value.rewriteReferences(twins, nullable) })
        }
    }
    else -> this
}

/**
 * Each operation without the query parameters it does not describe. Ktor copies the query properties of a parent
 * `@Resource` onto its nested resources (`member`, `all`, `before` of `/runs` onto `/runs/{id}/end`), which the route
 * ignores; every query parameter a route reads has a description in the catalog.
 */
internal fun JsonObject.withoutUndescribedQueryParameters(): JsonObject {
    val paths = this["paths"] as? JsonObject ?: return this
    val cleaned = paths.mapValues { (_, item) ->
        if (item !is JsonObject) item else JsonObject(item.mapValues { (_, operation) ->
            val parameters = (operation as? JsonObject)?.get("parameters") as? JsonArray ?: return@mapValues operation
            val kept = parameters.filterNot { parameter ->
                parameter is JsonObject && (parameter["in"] as? JsonPrimitive)?.contentOrNull == "query" && parameter["description"] == null
            }
            JsonObject(if (kept.isEmpty()) operation - "parameters" else operation + ("parameters" to JsonArray(kept)))
        })
    }
    return JsonObject(this + ("paths" to JsonObject(cleaned)))
}
