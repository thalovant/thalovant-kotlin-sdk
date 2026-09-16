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
    public const val AUDIO_QUEUE: String = "mycroft.audio.queue"
    public const val SPEAK: String = "speak"
    public const val OVOS_UTTERANCE_SPEAK: String = "ovos.utterance.speak"
    public const val UTTERANCE_HANDLED: String = "ovos.utterance.handled"

    /** Current OVOS name for an utterance that matched no intent. */
    public const val INTENT_UNMATCHED: String = "ovos.intent.unmatched"

    /** Legacy Mycroft name for [INTENT_UNMATCHED]; still emitted by older hubs. */
    public const val INTENT_FAILURE: String = "complete_intent_failure"
    public const val POLICY_DENIED: String = "hive.policy.denied"
    public const val QUERY_TIMEOUT: String = "hive.query.timeout"

    // The hub runtime's intent manifest (OVOS-INTENT-4 section 10) and the
    // engines' own manifests, read by [ThalovantClient.intents].

    /** Query: the intent manifest for one language, `{"lang"}` (+ `include_definitions`). */
    public const val INTENT_LIST: String = "ovos.intent.list"

    /** Reply to [INTENT_LIST]: `{ok, intents: [{skill_id, intent_name, lang, method, enabled, session_id}]}`. */
    public const val INTENT_LIST_RESPONSE: String = "ovos.intent.list.response"

    /** Query: the registrations behind one intent, `{"skill_id", "intent_name", "lang"}`. */
    public const val INTENT_DESCRIBE: String = "ovos.intent.describe"

    /** Reply to [INTENT_DESCRIBE]: `{ok, definitions: [{method, definition}]}` or `{ok: false, error}`. */
    public const val INTENT_DESCRIBE_RESPONSE: String = "ovos.intent.describe.response"

    /** Query: the Adapt engine's manifest, intent names only. */
    public const val ADAPT_MANIFEST_GET: String = "intent.service.adapt.manifest.get"

    /** Reply to [ADAPT_MANIFEST_GET]: `{intents: ["<skill_id>:<intent_name>"]}`. */
    public const val ADAPT_MANIFEST: String = "intent.service.adapt.manifest"

    /** Query: the Padatious engine's manifest, intent names only. */
    public const val PADATIOUS_MANIFEST_GET: String = "intent.service.padatious.manifest.get"

    /** Reply to [PADATIOUS_MANIFEST_GET]: `{intents: ["<skill_id>:<intent_name>"]}`. */
    public const val PADATIOUS_MANIFEST: String = "intent.service.padatious.manifest"

    /** Query and reply for skills that handle utterances outside the intent manifest. */
    public const val FALLBACK_LIST: String = "ovos.skills.fallback.list"
    public const val FALLBACK_LIST_RESPONSE: String = "ovos.skills.fallback.list.response"

    public val FAILURE_EVENTS: Set<String> =
        setOf(INTENT_UNMATCHED, INTENT_FAILURE, POLICY_DENIED, QUERY_TIMEOUT)
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

    public val lang: String? get() = listOf(data["lang"], context["lang"], context["session"].asObjectOrNull()?.get("lang"))
        .mapNotNull { optionalStringRaw(it) }.firstOrNull { it.isNotEmpty() }
    public val isAudio: Boolean get() = name == ThalovantEvents.AUDIO_QUEUE
    public val hasAudio: Boolean get() = isAudio && (data["binary_data"] as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true
    /** Decode embedded hex only; never fetch skill-provided paths or URLs. */
    public fun audioBytes(maxBytes: Int = MAX_AUDIO_CLIP_BYTES): ByteArray {
        val encoded = (data["binary_data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(isAudio && maxBytes >= 0 && !encoded.isNullOrEmpty() && encoded.length.toLong() <= maxBytes.toLong() * 2) { "Missing or oversized embedded audio." }
        // Avoid recursive regex matching on multi-megabyte valid clips.
        val bytes = ByteArray(encoded.length / 2)
        var high = -1
        var written = 0
        for (c in encoded) {
            if (c == ' ' || c.code in 9..13) {
                require(high < 0) { "Invalid embedded audio hex." }
                continue
            }
            val value = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> -1
            }
            require(value >= 0) { "Invalid embedded audio hex." }
            if (high < 0) high = value
            else { bytes[written++] = (high * 16 + value).toByte(); high = -1 }
        }
        require(high < 0) { "Invalid embedded audio hex." }
        return if (written == bytes.size) bytes else bytes.copyOf(written)
    }

    public val isFailure: Boolean get() = name in ThalovantEvents.FAILURE_EVENTS
}

/** Aggregated reply returned by [ThalovantClient.ask]. */
public class ThalovantReply @JvmOverloads constructor(
    public val text: String,
    public val utterances: List<String>,
    public val handled: Boolean,
    public val ok: Boolean,
    public val sessionId: String?,
    public val requestId: String?,
    public val events: List<ThalovantEvent>,
    public val failureEvent: ThalovantEvent?,
    public val droppedMedia: Int = 0,
) {
    public val pipelineIds: List<String> get() = contextIdentifiers("pipeline_id")
    public val skillIds: List<String> get() = contextIdentifiers("skill_id")
    /** Advisory claim status; unstamped successful legacy replies remain claimed. */
    public val claimed: Boolean get() = handled && ok && failureEvent == null &&
        pipelineIds.let { stages -> stages.isEmpty() || stages.any { !it.contains("fallback") } }
    private fun contextIdentifiers(key: String): List<String> = events.mapNotNull {
        (it.context[key] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotEmpty)
    }.distinct()
    public val lang: String? get() = events.firstNotNullOfOrNull { it.lang?.takeIf(String::isNotEmpty) }
    public val hasAudio: Boolean get() = events.any { it.isAudio }
    public val mediaEvents: List<ThalovantEvent> get() = events.filter { it.isAudio || it.name in listOf(ThalovantEvents.SPEAK, ThalovantEvents.OVOS_UTTERANCE_SPEAK) }
}

/**
 * A binary frame a hub sent this client.
 *
 * This is how a hub answers `speak:synth`: it renders the utterance and sends
 * the audio back rather than text, so a client with no synthesiser of its own
 * can still speak. Files arrive the same way.
 *
 * It is *not* tied to a request. A binary frame carries no request id -- only
 * the metadata below -- so it cannot be correlated to one `ask` and is
 * delivered by subscription instead. Match it on [utterance] if a turn needs
 * to claim it.
 */
public data class ThalovantBinary(
    /**
     * `tts_audio`, `file`, or `binary:<n>` for a wire type this SDK does not
     * name yet -- unknown is still delivered, never dropped.
     */
    public val kind: String,
    public val data: ByteArray,
    public val metadata: JsonObject,
) {
    /** What was spoken, for rendered speech. The hub sends it beside the sound. */
    public val utterance: String? get() = text("utterance")

    public val lang: String? get() = text("lang")

    /**
     * The hub's own name for the bytes.
     *
     * Remote text naming a remote file: a hint, never a path to write to. A
     * caller that saves this owes it the same care as any untrusted filename.
     */
    public val fileName: String? get() = text("file_name")

    private fun text(key: String): String? =
        (metadata[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)

    // ByteArray compares by identity, which inside a data class makes two
    // equal frames unequal -- a trap worth closing where it is written.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ThalovantBinary && kind == other.kind &&
            data.contentEquals(other.data) && metadata == other.metadata)

    override fun hashCode(): Int =
        (kind.hashCode() * 31 + data.contentHashCode()) * 31 + metadata.hashCode()
}

/** The hive's own frame kinds, which a client may subscribe to. */
public object ThalovantHive {
    /** Aimed down at every child of a hub. */
    public const val BROADCAST: String = "broadcast"

    /** Walked across the whole hive, once per node. */
    public const val PROPAGATE: String = "propagate"

    /** Sent up to the parent. */
    public const val ESCALATE: String = "escalate"

    /** Addressed node to node. */
    public const val INTERCOM: String = "intercom"

    /** The mailbox peers use to find each other through NAT. */
    public const val RENDEZVOUS: String = "rendezvous"

    /**
     * Every kind `onHive` accepts.
     *
     * `query` and `cascade` are deliberately absent: they are this client's own
     * request/response traffic and `ask()` already owns them, so subscribing
     * to one would quietly compete for the same replies.
     */
    public val KINDS: List<String> = listOf(BROADCAST, PROPAGATE, ESCALATE, INTERCOM, RENDEZVOUS)
}

/**
 * Session fields a client carries from one turn of a conversation to the next.
 *
 * A hub keeps nothing for a *named* session: OVOS-SESSION-2 §2.2 makes the
 * orchestrator stateless for those, so the carrier a client sends is the whole
 * snapshot and whatever the last turn activated is discarded the moment it
 * ends. Without `converse_handlers` the converse pipeline has no skill to poll
 * and every follow-up -- "encore un", "another one" -- falls past it to the
 * fallback.
 *
 * An allow-list, not a deny-list: a hub field nobody here has considered must
 * not start replaying itself into later turns. Two groups are deliberately
 * absent -- the caller's own per-turn settings (`lang`, `pipeline`, `site_id`),
 * because a satellite decides the language per utterance from what it heard and
 * a remembered one would silently outrank it; and the live device flags
 * (`is_speaking`, `is_recording`), which describe a moment that has passed.
 */
public val CONVERSATION_SESSION_FIELDS: List<String> = listOf(
    "converse_handlers",
    "active_handlers",
    "active_skills",
    "context",
    "utterance_states",
    "response_mode",
)

/**
 * Fill the conversation fields of [session] from the hub's last reply.
 *
 * This turn's own values win: a field the caller set is never overwritten, only
 * one it left out is taken from the turn before.
 */
public fun carryConversation(previous: JsonObject?, session: JsonObject): JsonObject {
    if (previous == null || previous.isEmpty()) return session
    val carried = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(session)
    for (field in CONVERSATION_SESSION_FIELDS) {
        val value = previous[field] ?: continue
        if (field in carried) continue
        if (value is JsonArray && value.isEmpty()) continue
        if (value is JsonObject && value.isEmpty()) continue
        carried[field] = value
    }
    return JsonObject(carried)
}

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
