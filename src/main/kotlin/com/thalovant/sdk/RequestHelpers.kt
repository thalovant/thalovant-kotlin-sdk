package com.thalovant.sdk

import kotlinx.serialization.json.*

/** Build the request-level location read by OVOS skills. City is required. */
public fun buildLocation(
    city: String = "", region: String = "", country: String = "",
    latitude: Number? = null, longitude: Number? = null, timezone: String = "",
): JsonObject? {
    if (city.isBlank()) return null
    return buildJsonObject {
        put("city", city.trim())
        if (region.isNotBlank()) put("region", region.trim())
        if (country.isNotBlank()) put("country_code", country.trim().uppercase(java.util.Locale.ROOT))
        if (timezone.isNotBlank()) put("timezone", buildJsonObject { put("code", timezone.trim()) })
        val lat = latitude?.toDouble(); val lon = longitude?.toDouble()
        if (lat != null && lon != null && (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0)
            put("coordinate", buildJsonObject { put("latitude", lat); put("longitude", lon) })
    }
}

public fun requestContext(
    context: JsonObject = EMPTY_JSON_OBJECT, sttLang: String? = null,
    pipeline: List<String>? = null, location: JsonObject? = null,
): JsonObject? {
    val result = context.toMutableMap()
    val stages = pipeline.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (stages.isNotEmpty()) result["session"] = JsonObject((context["session"].asObjectOrNull() ?: EMPTY_JSON_OBJECT) + ("pipeline" to JsonArray(stages.map(::JsonPrimitive))))
    if (!sttLang.isNullOrBlank()) result["stt_lang"] = JsonPrimitive(sttLang.trim())
    if (!location.isNullOrEmpty()) result["location"] = JsonObject(location.toMap())
    return result.takeIf { it.isNotEmpty() }?.let(::JsonObject)
}

/** Request hints with the same deadline and cancellation behavior as ask. */
public suspend fun ThalovantClient.askWithHints(
    text: String, sttLang: String? = null, pipeline: List<String>? = null, location: JsonObject? = null,
    timeoutMs: Long = 12000, lang: String = "en-us", sessionId: String? = null,
    requestId: String? = null, context: JsonObject = EMPTY_JSON_OBJECT,
    replySettleMs: Long? = null, emptyReplyWaitMs: Long? = null,
): ThalovantReply = ask(text, timeoutMs, lang, sessionId, requestId,
    requestContext(context, sttLang, pipeline, location) ?: EMPTY_JSON_OBJECT, replySettleMs, emptyReplyWaitMs)

private val slotPattern = Regex("\\{([a-z_][a-z0-9_]*)\\}")

/**
 * One illustrative sentence, without inventing slot values.
 *
 * `[optional]` parts go, `(a|b)` groups keep their first branch -- or nothing,
 * when an empty branch makes the group optional with at most one real branch
 * -- innermost first, so a group inside an optional part goes with it. One
 * pass each, with a stack: patterns come from hubs, and taking the innermost
 * pair out and starting again cost a full pass per level of nesting.
 */
public fun speakable(pattern: String, slots: Map<String, String> = emptyMap()): String {
    var text = resolveNested(resolveNested(pattern, '[', ']') { "" }, '(', ')') { inside ->
        val options = inside.split('|').map { it.trim() }
        val real = options.filter { it.isNotEmpty() }
        if (real.size < options.size && real.size <= 1) "" else real.firstOrNull().orEmpty()
    }
    text = slotPattern.replace(text) { match -> slots[match.groupValues[1]] ?: match.groupValues[1].replace('_', ' ') }
    return text.replace(Regex("\\s{2,}"), " ").trim(' ', ',')
}

/**
 * Replaces every balanced [opening] ... [closing] pair by [resolve] of its
 * inside, innermost first, in one pass: a pair is resolved when its closing
 * character arrives, with the pairs it held already resolved. An opening never
 * closed stays in the text, and so does a closing that closes nothing -- what
 * substituting the innermost pair until none is left does.
 */
private fun resolveNested(text: String, opening: Char, closing: Char, resolve: (String) -> String): String {
    val frames = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    for (char in text) {
        when {
            char == opening -> frames.add(StringBuilder())
            char == closing && frames.size > 1 -> {
                val inside = frames.removeAt(frames.size - 1).toString()
                frames[frames.size - 1].append(resolve(inside))
            }
            else -> frames[frames.size - 1].append(char)
        }
    }
    val out = StringBuilder(frames[0])
    for (index in 1 until frames.size) out.append(opening).append(frames[index])
    return out.toString()
}

public const val MAX_AUDIO_CLIP_BYTES: Int = 4 * 1024 * 1024
public const val MAX_REPLY_MEDIA_BYTES: Int = 16 * 1024 * 1024

internal class ReplyMediaBudget {
    var dropped: Int = 0; private set
    private var chars = 0
    private val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonObject, Boolean>())
    fun accept(event: ThalovantEvent): Boolean {
        if (!event.isAudio) return true
        if (seen.contains(event.data)) return false
        val encoded = (event.data["binary_data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (encoded == null || encoded.length > MAX_AUDIO_CLIP_BYTES * 2 || chars + encoded.length > MAX_REPLY_MEDIA_BYTES * 2) {
            dropped++; return false
        }
        seen.add(event.data); chars += encoded.length; return true
    }
}
