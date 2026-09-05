package com.thalovant.sdk

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What a hub can be asked: the intent inventory, over the client's own session.
 *
 * The hub runtime keeps an intent manifest (OVOS-INTENT-4 section 10): every
 * intent a skill registered, per language, and on request the registration
 * itself, which for a template intent carries the sentences from the skill's
 * locale files, slots and all -- `what is the weather in {location}`. This file
 * asks that manifest and shapes the answer, so a satellite, an installer or an
 * agent shows a person what they can say without a control-plane token.
 *
 * Two queries, correlated by `context.request_id` like every other request:
 *
 * - `ovos.intent.list` `{"lang": <tag>}` -> `ovos.intent.list.response`
 *   `{"ok", "intents": [{skill_id, intent_name, lang, method, enabled,
 *   session_id}]}`. `method` is `template` (sample sentences) or `keyword`
 *   (keyword sets). A runtime may attach each entry's `definition` when asked
 *   with `include_definitions`; when it does not, the client describes each
 *   intent individually.
 * - `ovos.intent.describe` `{"skill_id", "intent_name", "lang"}` ->
 *   `ovos.intent.describe.response` `{"ok", "definitions": [{method,
 *   definition}]}` or `{"ok": false, "error"}`.
 *
 * A hub whose connection may not publish a type answers `hive.policy.denied`
 * naming it; that becomes [ThalovantPolicyDeniedException] at once rather than
 * a timeout. The engines' own manifests (`intent.service.adapt.manifest.get`
 * and `intent.service.padatious.manifest.get`, names only, no language) are the
 * fallback for a hub allowed for those alone.
 */

/** Language the intent calls use when the caller names none. */
internal const val DEFAULT_INTENT_LANG: String = "en-us"

/** Deadline the intent calls use when the caller sets none. */
public const val DEFAULT_INTENT_TIMEOUT_MS: Long = 5000

/**
 * How many describes may be in flight at once.
 *
 * A hub with 69 intents in two languages is 138 requests and, with every reply
 * delivered twice, 276 inbound events; an SDK whose reply queue is bounded (the
 * Rust bus channel holds 64) drops replies past its capacity and the inventory
 * comes back missing sentences. Batching also spares the hub a burst it never
 * asked for.
 */
public const val DESCRIBE_BATCH: Int = 32

private val ENGINE_BY_METHOD: Map<String, String> = mapOf("template" to "padatious", "keyword" to "adapt")

private fun engineForMethod(method: String): String = ENGINE_BY_METHOD[method] ?: method.ifEmpty { "unknown" }

/** `fr-fr` and `fr_FR` are the same language tag. */
public fun sameLanguage(a: String, b: String): Boolean = foldLanguage(a) == foldLanguage(b)

private fun foldLanguage(tag: String): String = tag.trim().lowercase().replace('_', '-')

/** How a [HubIntentInventory] was read. */
public enum class HubIntentSource(public val wireName: String) {
    /** The intent manifest (`ovos.intent.list` / `ovos.intent.describe`): sentences per language. */
    INTENT_MANIFEST("intent-manifest"),

    /** The engines' own manifests: names only, the fallback for a refused `ovos.intent.list`. */
    ENGINE_MANIFESTS("engine-manifests"),
    ;

    public companion object {
        public fun fromWireName(value: String): HubIntentSource? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

/** Options for [ThalovantClient.intents]. */
public data class IntentInventoryOptions(
    /** Deadline for each query; the describe batch shares one deadline. */
    public val timeoutMs: Long = DEFAULT_INTENT_TIMEOUT_MS,
    /** Fetch the sentences (`false` lists names and engines only). */
    public val describe: Boolean = true,
    /** Fall back to the engines' manifests when the hub refuses `ovos.intent.list`. */
    public val fallback: Boolean = true,
)

/** Options for [ThalovantClient.listIntents]. */
public data class ListIntentsOptions(
    public val timeoutMs: Long = DEFAULT_INTENT_TIMEOUT_MS,
    /** Ask the runtime to attach each row's `definition`; honoured by runtimes that support it. */
    public val includeDefinitions: Boolean = false,
)

/** Options for [ThalovantClient.describeIntent]. */
public data class DescribeIntentOptions(
    public val timeoutMs: Long = DEFAULT_INTENT_TIMEOUT_MS,
)

/** One row of the hub's intent manifest, from `ovos.intent.list.response`. */
public data class IntentRegistration(
    public val skillId: String,
    public val intentName: String,
    public val lang: String,
    /** `template` (sample sentences) or `keyword` (keyword sets). */
    public val method: String,
    public val enabled: Boolean = true,
    public val sessionId: String = "default",
    /** The registration itself, when the runtime attached it to the listing. */
    public val definition: JsonObject? = null,
) {
    /** `padatious` for a template intent, `adapt` for a keyword one. */
    public val engine: String get() = engineForMethod(method)

    public companion object {
        /** Parses one listing row; null when it names no skill or intent. */
        public fun from(raw: JsonObject): IntentRegistration? {
            val skillId = raw.optionalString("skill_id") ?: return null
            val intentName = raw.optionalString("intent_name") ?: return null
            return IntentRegistration(
                skillId = skillId,
                intentName = intentName,
                lang = raw.optionalString("lang") ?: "",
                method = raw.optionalString("method") ?: "",
                enabled = enabledValue(raw["enabled"], true),
                sessionId = raw.optionalString("session_id") ?: "default",
                definition = raw["definition"].asObjectOrNull(),
            )
        }
    }
}

/** A registration as the skill made it, from `ovos.intent.describe.response`. */
public data class IntentDefinition(
    public val skillId: String,
    public val intentName: String,
    public val lang: String,
    public val method: String,
    /** The sentences as the skill's locale files wrote them, `{slot}` placeholders included. */
    public val samples: List<String> = emptyList(),
    /** The whole definition object, for fields this SDK does not model. */
    public val raw: JsonObject = EMPTY_JSON_OBJECT,
) {
    public val engine: String get() = engineForMethod(method)

    public companion object {
        /** Parses one `{method, definition}` item; null when it names no skill or intent. */
        public fun from(item: JsonObject): IntentDefinition? {
            val definition = item["definition"].asObjectOrNull() ?: return null
            val skillId = definition.optionalString("skill_id") ?: return null
            val intentName = definition.optionalString("intent_name") ?: return null
            return IntentDefinition(
                skillId = skillId,
                intentName = intentName,
                lang = definition.optionalString("lang") ?: "",
                method = item.optionalString("method") ?: definition.optionalString("method") ?: "",
                samples = samplesOf(definition),
                raw = definition,
            )
        }
    }
}

/** One thing a hub can be asked, with the sentences that ask it, per language. */
public data class HubIntent(
    public val skillId: String,
    public val name: String,
    /** `padatious`, `adapt`, or the raw method for anything else. */
    public val engine: String,
    /** Sentences keyed by the language tag the inventory was asked for. */
    public val phrases: Map<String, List<String>> = emptyMap(),
    public val enabled: Boolean = true,
) {
    /** `<skill_id>:<name>`, the form the engines' manifests use. */
    public val id: String get() = "$skillId:$name"

    /** The languages this intent was listed in, in the order asked. */
    public val languages: List<String> get() = phrases.keys.toList()

    /** The sentences for one language; tags compare case-insensitively with `_`/`-` folded. */
    public fun phrasesFor(lang: String): List<String> =
        phrases.entries.firstOrNull { sameLanguage(it.key, lang) }?.value ?: emptyList()

    /**
     * A few sentences worth showing: whole ones before ones with a slot, shorter
     * first. With no [lang], the first listed language; with [limit] <= 0, all.
     */
    public fun examples(lang: String? = null, limit: Int = 2): List<String> {
        val pool = if (lang != null) phrasesFor(lang) else phrases.values.firstOrNull() ?: emptyList()
        if (limit <= 0) return pool
        return pool.sortedWith(compareBy({ '{' in it }, { it.length })).take(limit)
    }

    public fun asJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("skill_id", skillId)
        put("name", name)
        put("engine", engine)
        put("enabled", enabled)
        put(
            "phrases",
            buildJsonObject {
                for ((tag, texts) in phrases) put(tag, jsonStrings(texts))
            },
        )
    }
}

/** The intents one skill registered. */
public data class HubSkillIntents(
    public val skillId: String,
    public val intents: List<HubIntent>,
) {
    /** Every language any of the skill's intents was listed in. */
    public val languages: List<String> get() = intents.flatMap { it.phrases.keys }.distinct()

    public fun asJson(): JsonObject = buildJsonObject {
        put("skill_id", skillId)
        put("languages", jsonStrings(languages))
        put("intents", JsonArray(intents.map { it.asJson() }))
    }
}

/**
 * Everything a hub can be asked, grouped by skill.
 *
 * [source] says how it was read: [HubIntentSource.INTENT_MANIFEST] carries
 * sentences per language; [HubIntentSource.ENGINE_MANIFESTS] is the names-only
 * fallback, and [denied] then names the query the hub refused.
 */
public data class HubIntentInventory(
    /** The languages asked for, in order. */
    public val languages: List<String>,
    public val skills: List<HubSkillIntents>,
    public val source: HubIntentSource = HubIntentSource.INTENT_MANIFEST,
    public val denied: List<String> = emptyList(),
) {
    /** Every intent of every skill, in skill order. */
    public val intents: List<HubIntent> get() = skills.flatMap { it.intents }

    /** Whether any intent carries at least one sentence. */
    public val hasPhrases: Boolean get() = intents.any { intent -> intent.phrases.values.any { it.isNotEmpty() } }

    public fun asJson(): JsonObject = buildJsonObject {
        put("languages", jsonStrings(languages))
        put("source", source.wireName)
        put("denied", jsonStrings(denied))
        put("skills", JsonArray(skills.map { it.asJson() }))
    }
}

// ---------------------------------------------------------------------------
// the wire
// ---------------------------------------------------------------------------

/** One registration to describe: `(skill_id, intent_name, lang)`. */
internal data class IntentKey(val skillId: String, val intentName: String, val lang: String)

/**
 * Sends one bus query and returns its reply, matched by request id.
 *
 * A reply may arrive more than once; the first one wins and repeats are
 * dropped. A `hive.policy.denied` naming the query throws at once.
 */
internal suspend fun ThalovantClient.requestReply(
    queryType: String,
    replyType: String,
    data: JsonObject,
    lang: String?,
    timeoutMs: Long,
): ThalovantEvent {
    val requestId = newRequestId()
    val answer = CompletableDeferred<ThalovantEvent>()
    connect()
    val denials = on(ThalovantEvents.POLICY_DENIED) { event ->
        if (deniedTypeOf(event) == queryType) {
            answer.completeExceptionally(ThalovantPolicyDeniedException.fromEvent(event))
        }
    }
    val replies = on(replyType, requestId = requestId) { event -> answer.complete(event) }
    try {
        emit(queryType, data, intentQueryContext(requestId, lang))
        return withTimeoutOrNull(timeoutMs) { answer.await() }
            ?: throw ThalovantTimeoutException("Hub did not answer $queryType within ${timeoutMs}ms.")
    } finally {
        replies.close()
        denials.close()
    }
}

/** The hub's intent manifest for one language. */
internal suspend fun ThalovantClient.listIntentRegistrations(
    lang: String,
    options: ListIntentsOptions,
): List<IntentRegistration> {
    val data = buildJsonObject {
        put("lang", lang)
        if (options.includeDefinitions) put("include_definitions", true)
    }
    val event = requestReply(
        ThalovantEvents.INTENT_LIST,
        ThalovantEvents.INTENT_LIST_RESPONSE,
        data,
        lang = lang,
        timeoutMs = options.timeoutMs,
    )
    val rows = event.data["intents"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row -> row.asObjectOrNull()?.let(IntentRegistration::from) }
}

/** Every registration behind one intent in one language. */
internal suspend fun ThalovantClient.describeIntentRegistrations(
    skillId: String,
    intentName: String,
    lang: String,
    options: DescribeIntentOptions,
): List<IntentDefinition> {
    val event = requestReply(
        ThalovantEvents.INTENT_DESCRIBE,
        ThalovantEvents.INTENT_DESCRIBE_RESPONSE,
        describeData(IntentKey(skillId, intentName, lang)),
        lang = lang,
        timeoutMs = options.timeoutMs,
    )
    if (!enabledValue(event.data["ok"], true)) return emptyList()
    return definitionsOf(event.data)
}

/**
 * Describes many registrations, at most [batch] of them in flight.
 *
 * One subscription per batch, one request id per registration, replies matched
 * by that id -- or by the definition's own skill, intent and language when a
 * hub does not echo the id -- and repeats dropped. The deadline covers each
 * batch, so a hub that answers nothing fails after one batch rather than
 * holding every request open. [batch] `= 0` sends them all at once.
 */
internal suspend fun ThalovantClient.describeMany(
    wanted: List<IntentKey>,
    timeoutMs: Long,
    batch: Int = DESCRIBE_BATCH,
): Map<IntentKey, List<IntentDefinition>> {
    val keys = wanted.distinct()
    if (keys.isEmpty()) return emptyMap()
    if (batch <= 0 || keys.size <= batch) return describeWindow(keys, timeoutMs)
    val batched = LinkedHashMap<IntentKey, List<IntentDefinition>>()
    for (start in keys.indices step batch) {
        try {
            batched.putAll(describeWindow(keys.subList(start, minOf(start + batch, keys.size)), timeoutMs))
        } catch (timeout: ThalovantTimeoutException) {
            // A partial answer is an answer, across windows as within one:
            // windows are contiguous slices, so an unresponsive skill with more
            // than one window's worth of intents would otherwise turn the whole
            // inventory into a timeout while the same skill with fewer intents
            // only loses its sentences. A hub silent from the start still fails
            // at the first window, since nothing is found. Only a timeout means
            // "this window had nothing": a refusal is a policy problem and
            // reaches the caller.
            if (batched.isEmpty()) throw timeout
        }
    }
    return batched
}

/** One describe window: every key in flight together, one deadline for the lot. */
private suspend fun ThalovantClient.describeWindow(
    keys: List<IntentKey>,
    timeoutMs: Long,
): Map<IntentKey, List<IntentDefinition>> {
    val byRequest = ConcurrentHashMap<String, IntentKey>()
    val found = LinkedHashMap<IntentKey, List<IntentDefinition>>()
    val lock = Any()
    val done = CompletableDeferred<Unit>()
    connect()
    val denials = on(ThalovantEvents.POLICY_DENIED) { event ->
        if (deniedTypeOf(event) == ThalovantEvents.INTENT_DESCRIBE) {
            done.completeExceptionally(ThalovantPolicyDeniedException.fromEvent(event))
        }
    }
    val replies = on(ThalovantEvents.INTENT_DESCRIBE_RESPONSE) { event ->
        val definitions = definitionsOf(event.data)
        val key = event.requestId?.let { byRequest[it] }
            ?: definitions.firstOrNull()?.let { first ->
                // No request id came back: the definition names what it describes.
                keys.firstOrNull {
                    it.skillId == first.skillId &&
                        it.intentName == first.intentName &&
                        sameLanguage(it.lang, first.lang)
                }
            }
            ?: return@on
        synchronized(lock) {
            if (key !in found) {
                found[key] = if (enabledValue(event.data["ok"], true)) definitions else emptyList()
                if (found.size == keys.size) done.complete(Unit)
            }
        }
    }
    try {
        for (key in keys) {
            val requestId = newRequestId()
            byRequest[requestId] = key
            emit(ThalovantEvents.INTENT_DESCRIBE, describeData(key), intentQueryContext(requestId, key.lang))
        }
        if (withTimeoutOrNull(timeoutMs) { done.await() } == null) {
            // A partial answer is still an answer: the intents the hub did not
            // describe in time simply carry no sentences.
            synchronized(lock) {
                if (found.isEmpty()) {
                    throw ThalovantTimeoutException(
                        "Hub did not answer ${ThalovantEvents.INTENT_DESCRIBE} within ${timeoutMs}ms.",
                    )
                }
            }
        }
    } finally {
        replies.close()
        denials.close()
    }
    return synchronized(lock) { LinkedHashMap(found) }
}

/**
 * The engines' own manifests: `{"adapt": [names], "padatious": [names]}`.
 *
 * Names only, and the same names whatever the language asked, because an
 * intent's name is the same in every language. The fallback for a hub allowed
 * for these queries but not the intent manifest.
 */
internal suspend fun ThalovantClient.intentNames(lang: String, timeoutMs: Long): Map<String, List<String>> {
    val names = LinkedHashMap<String, List<String>>()
    val engines = listOf(
        Triple("adapt", ThalovantEvents.ADAPT_MANIFEST_GET, ThalovantEvents.ADAPT_MANIFEST),
        Triple("padatious", ThalovantEvents.PADATIOUS_MANIFEST_GET, ThalovantEvents.PADATIOUS_MANIFEST),
    )
    for ((engine, queryType, replyType) in engines) {
        val event = requestReply(
            queryType,
            replyType,
            buildJsonObject { put("lang", lang) },
            lang = lang,
            timeoutMs = timeoutMs,
        )
        names[engine] = stringsOf(event.data["intents"]).filter { it.isNotEmpty() }
    }
    return names
}

/**
 * Everything the hub can be asked, in each language, grouped by skill.
 *
 * Asks the intent manifest per language and, unless the runtime attached
 * definitions to the listing, describes every registration at once. When the
 * hub refuses `ovos.intent.list` and [IntentInventoryOptions.fallback] is on,
 * the engines' manifests give the names and the result says so.
 */
internal suspend fun ThalovantClient.intentInventory(
    languages: List<String>,
    options: IntentInventoryOptions,
): HubIntentInventory {
    // `en-us`, `en-US` and `en_us` are one language, asked once; the inventory
    // keeps the first spelling seen, in the order given.
    val asked = mutableListOf<String>()
    for (language in languages) {
        val tag = language.trim()
        if (tag.isNotEmpty() && asked.none { sameLanguage(it, tag) }) asked.add(tag)
    }
    require(asked.isNotEmpty()) { "intents() needs at least one language." }

    val listed = LinkedHashMap<String, List<IntentRegistration>>()
    try {
        for (lang in asked) {
            listed[lang] = listIntentRegistrations(
                lang,
                ListIntentsOptions(timeoutMs = options.timeoutMs, includeDefinitions = options.describe),
            )
        }
    } catch (denied: ThalovantPolicyDeniedException) {
        if (!options.fallback || denied.deniedType != ThalovantEvents.INTENT_LIST) throw denied
        return inventoryFromNames(intentNames(asked[0], options.timeoutMs), asked.toList(), denied.deniedType)
    }

    val wanted = listed.flatMap { (lang, entries) ->
        entries
            .filter { it.enabled && it.definition == null && it.method == "template" }
            .map { IntentKey(it.skillId, it.intentName, lang) }
    }
    val described =
        if (options.describe && wanted.isNotEmpty()) describeMany(wanted, options.timeoutMs) else emptyMap()

    val phrases = LinkedHashMap<Pair<String, String>, LinkedHashMap<String, List<String>>>()
    val engines = HashMap<Pair<String, String>, String>()
    val enabled = HashMap<Pair<String, String>, Boolean>()
    for ((lang, entries) in listed) {
        for (entry in entries) {
            val key = entry.skillId to entry.intentName
            engines.putIfAbsent(key, entry.engine)
            enabled[key] = (enabled[key] ?: false) || entry.enabled
            val sentences = entry.definition?.let(::samplesOf)
                ?: described[IntentKey(entry.skillId, entry.intentName, lang)]
                    ?.firstOrNull { it.samples.isNotEmpty() }?.samples
                ?: emptyList()
            // An intent registered under both engines has two rows for the
            // language; the keyword row carries no sentences and must not erase
            // the template row's, whichever order the rows arrive in.
            val perLanguage = phrases.getOrPut(key) { LinkedHashMap() }
            if (sentences.isNotEmpty() || lang !in perLanguage) perLanguage[lang] = sentences
        }
    }

    val bySkill = TreeMap<String, MutableList<HubIntent>>()
    for ((key, perLanguage) in phrases) {
        val (skillId, intentName) = key
        bySkill.getOrPut(skillId) { mutableListOf() }.add(
            HubIntent(
                skillId = skillId,
                name = intentName,
                engine = engines.getValue(key),
                phrases = LinkedHashMap(perLanguage),
                enabled = enabled.getValue(key),
            ),
        )
    }
    val skills = bySkill.map { (skillId, intents) -> HubSkillIntents(skillId, intents.sortedBy { it.name }) }
    return HubIntentInventory(
        languages = asked.toList(),
        skills = skills,
        source = HubIntentSource.INTENT_MANIFEST,
    )
}

private fun inventoryFromNames(
    names: Map<String, List<String>>,
    languages: List<String>,
    denied: String,
): HubIntentInventory {
    val bySkill = TreeMap<String, LinkedHashMap<String, HubIntent>>()
    for ((engine, entries) in names) {
        for (raw in entries) {
            val separator = raw.indexOf(':')
            var skillId = if (separator >= 0) raw.substring(0, separator) else ""
            var intentName = if (separator >= 0) raw.substring(separator + 1) else ""
            if (intentName.isEmpty()) {
                skillId = ""
                intentName = raw
            }
            // First engine to name it wins, as on the manifest path.
            bySkill.getOrPut(skillId) { LinkedHashMap() }
                .getOrPut(intentName) { HubIntent(skillId = skillId, name = intentName, engine = engine) }
        }
    }
    val skills = bySkill.map { (skillId, intents) -> HubSkillIntents(skillId, intents.values.sortedBy { it.name }) }
    return HubIntentInventory(
        languages = languages,
        skills = skills,
        source = HubIntentSource.ENGINE_MANIFESTS,
        denied = listOf(denied),
    )
}

/** `{"request_id", "lang"}` at the top level, plus the session block every other request stamps. */
private fun ThalovantClient.intentQueryContext(requestId: String, lang: String?): JsonObject =
    contextWithCorrelation(
        if (lang != null) buildJsonObject { put("lang", lang) } else EMPTY_JSON_OBJECT,
        siteId = identity.siteId,
        lang = lang,
        requestId = requestId,
    )

private fun describeData(key: IntentKey): JsonObject = buildJsonObject {
    put("skill_id", key.skillId)
    put("intent_name", key.intentName)
    put("lang", key.lang)
}

private fun deniedTypeOf(event: ThalovantEvent): String? = event.data.optionalString("denied_type")

private fun definitionsOf(data: JsonObject): List<IntentDefinition> {
    val items = data["definitions"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { item -> item.asObjectOrNull()?.let(IntentDefinition::from) }
}

private fun samplesOf(definition: JsonObject): List<String> =
    stringsOf(definition["samples"]).map { it.trim() }.filter { it.isNotEmpty() }

private fun stringsOf(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
}

private fun jsonStrings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
