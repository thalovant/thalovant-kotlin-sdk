package com.thalovant.sdk

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Well-known hub bus event names. */
public object ThalovantEvents {
    public const val RECOGNIZER_LOOP_UTTERANCE: String = "recognizer_loop:utterance"
    public const val SPEAK: String = "speak"
    public const val OVOS_UTTERANCE_SPEAK: String = "ovos.utterance.speak"
    public const val UTTERANCE_HANDLED: String = "ovos.utterance.handled"
    public const val INTENT_FAILURE: String = "complete_intent_failure"
    public const val POLICY_DENIED: String = "hive.policy.denied"
    public const val QUERY_TIMEOUT: String = "hive.query.timeout"

    public val FAILURE_EVENTS: Set<String> = setOf(INTENT_FAILURE, POLICY_DENIED, QUERY_TIMEOUT)
}

/** A bus event received from the hub. */
public class ThalovantEvent(
    public val name: String,
    public val data: JsonObject = EMPTY_JSON_OBJECT,
    public val context: JsonObject = EMPTY_JSON_OBJECT,
) {
    public val text: String
        get() = optionalStringRaw(data["utterance"]) ?: optionalStringRaw(data["text"])
            ?: utterances.firstOrNull() ?: ""

    public val utterances: List<String>
        get() = when (val raw = data["utterances"]) {
            is JsonPrimitive -> listOfNotNull(optionalStringRaw(raw))
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            else -> listOfNotNull(optionalStringRaw(data["utterance"]))
        }

    public val sessionId: String? get() = sessionIdFromContext(context)

    public val requestId: String? get() = requestIdFromContext(context) ?: requestIdFromMapping(data)

    public val isFailure: Boolean get() = name in ThalovantEvents.FAILURE_EVENTS
}

/** Aggregated reply returned by [ThalovantClient.ask]. */
public class ThalovantReply(
    public val text: String,
    public val utterances: List<String>,
    public val handled: Boolean,
    public val ok: Boolean,
    public val sessionId: String?,
    public val requestId: String?,
    public val events: List<ThalovantEvent>,
    public val failureEvent: ThalovantEvent?,
)

public fun newSessionId(): String = "thalovant-session-" + UUID.randomUUID().toString().replace("-", "")

public fun newRequestId(): String = "thalovant-request-" + UUID.randomUUID().toString().replace("-", "")

internal fun utterancePayload(text: String, lang: String): JsonObject = buildJsonObject {
    put("utterances", JsonArray(listOf(JsonPrimitive(text))))
    put("lang", lang)
}

/**
 * Stamps correlation info onto a bus event context: `session.session_id`,
 * `session.site_id`, `session.lang`, and the request id under `request_id`,
 * `thalovant_request_id`, and `session.request_id`.
 */
internal fun contextWithCorrelation(
    context: JsonObject,
    sessionId: String? = null,
    siteId: String? = null,
    lang: String? = null,
    requestId: String? = null,
): JsonObject {
    val session = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(
        context["session"].asObjectOrNull() ?: emptyMap(),
    )
    if (sessionId != null) session["session_id"] = JsonPrimitive(sessionId)
    if (siteId != null && "site_id" !in session) session["site_id"] = JsonPrimitive(siteId)
    if (lang != null && "lang" !in session) session["lang"] = JsonPrimitive(lang)
    val next = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(context)
    if (requestId != null) {
        next["request_id"] = JsonPrimitive(requestId)
        next["thalovant_request_id"] = JsonPrimitive(requestId)
        session["request_id"] = JsonPrimitive(requestId)
    }
    if (session.isNotEmpty()) {
        next["session"] = JsonObject(session)
    }
    return JsonObject(next)
}

internal fun sessionIdFromContext(context: JsonObject?): String? {
    val session = context?.get("session").asObjectOrNull()
    return optionalStringRaw(session?.get("session_id")) ?: optionalStringRaw(context?.get("session_id"))
}

internal fun requestIdFromContext(context: JsonObject?): String? =
    requestIdFromMapping(context) ?: requestIdFromMapping(context?.get("session").asObjectOrNull())

internal fun requestIdFromMapping(mapping: JsonObject?): String? {
    if (mapping == null) return null
    return optionalStringRaw(mapping["request_id"])
        ?: optionalStringRaw(mapping["thalovant_request_id"])
        ?: optionalStringRaw(mapping["correlation_id"])
}

/** Like [optionalString] but without trimming/empty coercion for event text. */
private fun optionalStringRaw(element: kotlinx.serialization.json.JsonElement?): String? {
    if (element == null || element is kotlinx.serialization.json.JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content
}
