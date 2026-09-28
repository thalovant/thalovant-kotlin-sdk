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
 * -- innermost first, so a group inside an optional part goes with it. Linear
 * in the pattern, which comes from a hub: one pass for each kind of bracket,
 * and a group's chosen branch is linked into the one around it, never copied,
 * so a long branch nested deep is not rewritten once per level.
 */
public fun speakable(pattern: String, slots: Map<String, String> = emptyMap()): String {
    var text = chooseBranches(dropOptional(pattern))
    text = slotPattern.replace(text) { match -> slots[match.groupValues[1]] ?: match.groupValues[1].replace('_', ' ') }
    return text.replace(Regex("\\s{2,}"), " ").trim(' ', ',')
}

/**
 * Removes every balanced `[` ... `]` pair and what it holds, innermost first,
 * in one pass. An opening never closed stays in the text, with what followed
 * it, and so does a closing that closes nothing -- what taking the innermost
 * pair out until none is left does.
 */
private fun dropOptional(text: String): String {
    val frames = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    for (char in text) {
        when {
            char == '[' -> frames.add(StringBuilder())
            char == ']' && frames.size > 1 -> frames.removeAt(frames.size - 1)
            else -> frames[frames.size - 1].append(char)
        }
    }
    val out = StringBuilder(frames[0])
    for (index in 1 until frames.size) out.append('[').append(frames[index])
    return out.toString()
}

/**
 * One alternative of a `(a|b|c)` group, as parts: runs of text and the
 * branches of the groups it held that were kept. A kept branch is trimmed and
 * not empty, so it begins and ends with a character that is not white space.
 */
private class Branch {
    val parts = ArrayList<Any>()
    var real = false

    fun append(char: Char) {
        (parts.lastOrNull() as? StringBuilder ?: StringBuilder().also { parts.add(it) }).append(char)
        if (!char.isWhitespace()) real = true
    }

    fun append(kept: Branch) {
        parts.add(kept)
        real = true
    }

    /** Takes white space off both ends, as `String.trim` would of its text. */
    fun trimmed(): Branch {
        (parts.firstOrNull() as? StringBuilder)?.let { first ->
            val start = first.indexOfFirst { !it.isWhitespace() }
            if (start < 0) parts.removeAt(0) else first.delete(0, start)
        }
        (parts.lastOrNull() as? StringBuilder)?.let { last ->
            val end = last.indexOfLast { !it.isWhitespace() }
            if (end < 0) parts.removeAt(parts.size - 1) else last.setLength(end + 1)
        }
        return this
    }
}

/**
 * Replaces every balanced `(` ... `)` group by its first real branch, or by
 * nothing when an empty branch makes it optional with at most one real branch
 * beside it, innermost first, in one pass. Whether a branch is real is known
 * as it is read, and a kept branch goes into the group around it as a part,
 * so no character is visited again at each level. An opening never closed
 * stays in the text, with its branches joined by `|` as they were written.
 */
private fun chooseBranches(text: String): String {
    val frames = ArrayList<ArrayList<Branch>>().apply { add(arrayListOf(Branch())) }
    for (char in text) {
        when {
            char == '(' -> frames.add(arrayListOf(Branch()))
            char == ')' && frames.size > 1 -> {
                val options = frames.removeAt(frames.size - 1)
                val real = options.count { it.real }
                val kept = if (real < options.size && real <= 1) null else options.firstOrNull { it.real }
                if (kept != null) frames[frames.size - 1].last().append(kept.trimmed())
            }
            char == '|' && frames.size > 1 -> frames[frames.size - 1].add(Branch())
            else -> frames[frames.size - 1].last().append(char)
        }
    }
    val out = StringBuilder()
    frames.forEachIndexed { depth, options ->
        if (depth > 0) out.append('(')
        options.forEachIndexed { index, option ->
            if (index > 0) out.append('|')
            write(option, out)
        }
    }
    return out.toString()
}

/** Writes a branch's text, the branches it holds included, without recursion: nesting can be deep. */
private fun write(branch: Branch, out: StringBuilder) {
    val stack = ArrayDeque<Iterator<Any>>().apply { addLast(branch.parts.iterator()) }
    while (stack.isNotEmpty()) {
        val parts = stack.last()
        if (!parts.hasNext()) { stack.removeLast(); continue }
        when (val part = parts.next()) {
            is StringBuilder -> out.append(part)
            is Branch -> stack.addLast(part.parts.iterator())
        }
    }
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
