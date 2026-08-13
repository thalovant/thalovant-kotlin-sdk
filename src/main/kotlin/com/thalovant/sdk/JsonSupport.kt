package com.thalovant.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val ThalovantJson: Json = Json {
    ignoreUnknownKeys = true
}

internal val EMPTY_JSON_OBJECT: JsonObject = JsonObject(emptyMap())

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

/** Returns the first element present under any of [keys], even if it is JsonNull. */
internal fun JsonObject.firstElement(vararg keys: String): JsonElement? {
    for (key in keys) {
        if (containsKey(key)) {
            return this[key]
        }
    }
    return null
}

/** Mirrors the Node SDK `optional(value)`: string content of a primitive, trimmed, empty coerced to null. */
internal fun optionalString(element: JsonElement?): String? {
    if (element == null || element is JsonNull) {
        return null
    }
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.trim().ifEmpty { null }
}

internal fun JsonObject.optionalString(vararg keys: String): String? = optionalString(firstElement(*keys))

/** Mirrors the Node SDK `enabledValue(value, fallback)` for protocol flags. */
internal fun enabledValue(element: JsonElement?, fallback: Boolean): Boolean {
    when (element) {
        null, is JsonNull -> return fallback
        is JsonPrimitive -> {
            when (element.content.trim().lowercase()) {
                "1", "true", "yes", "on" -> return true
                "0", "false", "no", "off" -> return false
            }
            return fallback
        }
        is JsonObject -> return enabledValue(element["enabled"], fallback)
        else -> return fallback
    }
}

/** Positive-integer coercion with fallback, mirroring the Node SDK `numberValue`. */
internal fun positiveIntValue(element: JsonElement?, fallback: Int): Int {
    val raw = optionalString(element) ?: return fallback
    val parsed = raw.toIntOrNull() ?: return fallback
    return if (parsed > 0) parsed else fallback
}

/** Truthiness check used by the HiveMind handshake, mirroring JS truthiness for JSON values. */
internal fun truthy(element: JsonElement?): Boolean = when (element) {
    null, is JsonNull -> false
    is JsonPrimitive -> element.content.isNotEmpty() && element.content != "false" && element.content != "0"
    else -> true
}
