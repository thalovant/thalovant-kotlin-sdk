package com.thalovant.sdk

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val WEATHER = "thalovant-skill-weather.thalovant"
private const val SHADOW = "thalovant-skill-custos-shadow.thalovant"

/**
 * What the hub registered: per language, per intent, the sentences. Weather
 * speaks both languages; the shadow skill only English.
 */
private val REGISTRATIONS: Map<String, Map<Pair<String, String>, List<String>>> = linkedMapOf(
    "en-us" to linkedMapOf(
        (WEATHER to "current.weather") to listOf(
            "what is the weather",
            "what is the weather in {location}",
            "how is it outside",
        ),
        (SHADOW to "custos.incidents") to listOf("are there incidents", "any incidents"),
    ),
    "fr-fr" to linkedMapOf(
        (WEATHER to "current.weather") to listOf(
            "quel temps fait-il",
            "quelle est la météo à {location}",
            "quelle est la météo",
        ),
    ),
)
private val ALLOWED = listOf("recognizer_loop:utterance", "speak")

private fun jsonOf(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/** `intent.000` .. `intent.068`, the shape a hub with many registrations has. */
private fun intentName(index: Int): String = "intent.%03d".format(index)

/** One skill with [count] template intents in English, each with one sentence. */
private fun manyIntents(count: Int): Map<String, Map<Pair<String, String>, List<String>>> = mapOf(
    "en-us" to (0 until count).associate { (WEATHER to intentName(it)) to listOf("sentence $it") },
)

internal data class Emitted(val type: String, val data: JsonObject, val context: JsonObject)

/**
 * A hub session that behaves like the one observed on 2026-09-05: answers the
 * manifest (or refuses it) and delivers every reply [repeats] times.
 */
internal open class FakeHubTransport(
    private val registrations: Map<String, Map<Pair<String, String>, List<String>>> = REGISTRATIONS,
    private val refuse: Set<String> = emptySet(),
    private val silent: Set<String> = emptySet(),
    private val definitionsInList: Boolean = false,
    private val echoRequestId: Boolean = true,
    private val repeats: Int = 2,
    private val foreignFirst: Boolean = false,
    private val fallbackDelayMs: Long = 0,
    private val connectDelayMs: Long = 0,
    private val fallbackPayload: JsonObject = buildJsonObject { put("fallbacks", JsonArray(emptyList())) },
) : HiveMindRuntimeTransport {
    @Volatile
    final override var connected: Boolean = false
        private set

    override val handshakeComplete: Boolean get() = connected

    val emitted: MutableList<Emitted> = CopyOnWriteArrayList()
    private val listeners = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()

    /** How many subscriptions were opened: two per request window. */
    val subscriptions: AtomicInteger = AtomicInteger()

    override suspend fun connect(timeoutMs: Long) {
        if (connectDelayMs > 0) kotlinx.coroutines.delay(connectDelayMs)
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription {
        subscriptions.incrementAndGet()
        listeners.add(listener)
        return ThalovantSubscription { listeners.remove(listener) }
    }

    fun emittedOf(type: String): List<Emitted> = emitted.filter { it.type == type }

    protected fun deliver(name: String, data: JsonObject, context: JsonObject) {
        // A hub that does not echo the request id still echoes the language.
        val replyContext = if (echoRequestId) {
            context
        } else {
            buildJsonObject { context["lang"]?.let { put("lang", it) } }
        }
        repeat(repeats) {
            for (listener in listeners) {
                listener(ThalovantEvent(name, data, replyContext))
            }
        }
    }

    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
        emitted.add(Emitted(eventType, data, context))
        if (eventType == ThalovantEvents.FALLBACK_LIST && fallbackDelayMs > 0) kotlinx.coroutines.delay(fallbackDelayMs)
        if (foreignFirst) {
            val foreign = buildJsonObject { put("request_id", "foreign-request") }
            denyOf(eventType, foreign)
            if (eventType == ThalovantEvents.INTENT_DESCRIBE) deliver(
                ThalovantEvents.INTENT_DESCRIBE_RESPONSE,
                buildJsonObject { put("definitions", JsonArray(listOf(buildJsonObject {
                    put("method", "template"); put("definition", definition(
                        data["skill_id"]!!.jsonPrimitive.content to data["intent_name"]!!.jsonPrimitive.content,
                        data["lang"]!!.jsonPrimitive.content, listOf("foreign phrase")))
                }))) }, foreign)
        }
        if (eventType in refuse) {
            denyOf(eventType, context)
            return
        }
        if (eventType in silent) return
        val lang = data["lang"]?.jsonPrimitive?.content ?: ""
        when (eventType) {
            ThalovantEvents.FALLBACK_LIST -> deliver(ThalovantEvents.FALLBACK_LIST_RESPONSE, fallbackPayload, context)
            ThalovantEvents.INTENT_LIST -> {
                val rows = registrations[lang].orEmpty().map { (key, samples) ->
                    buildJsonObject {
                        put("skill_id", key.first)
                        put("intent_name", key.second)
                        // The runtime standardises what it stores: fr-fr is answered as fr-FR.
                        put("lang", if (lang == "fr-fr") "fr-FR" else lang)
                        put("method", "template")
                        put("enabled", true)
                        put("session_id", "default")
                        if (definitionsInList && data["include_definitions"]?.jsonPrimitive?.content == "true") {
                            put("definition", definition(key, lang, samples))
                        }
                    }
                }
                deliver(
                    ThalovantEvents.INTENT_LIST_RESPONSE,
                    buildJsonObject {
                        put("ok", true)
                        put("intents", JsonArray(rows))
                    },
                    context,
                )
            }
            ThalovantEvents.INTENT_DESCRIBE -> {
                val key = data["skill_id"]!!.jsonPrimitive.content to data["intent_name"]!!.jsonPrimitive.content
                val samples = registrations[lang]?.get(key)
                val payload = if (samples == null) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "unknown intent")
                    }
                } else {
                    buildJsonObject {
                        put("ok", true)
                        put(
                            "definitions",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("method", "template")
                                        put("definition", definition(key, lang, samples))
                                    },
                                ),
                            ),
                        )
                    }
                }
                deliver(ThalovantEvents.INTENT_DESCRIBE_RESPONSE, payload, context)
            }
            ThalovantEvents.ADAPT_MANIFEST_GET -> deliver(
                ThalovantEvents.ADAPT_MANIFEST,
                buildJsonObject { put("intents", JsonArray(emptyList())) },
                context,
            )
            ThalovantEvents.PADATIOUS_MANIFEST_GET -> {
                val names = registrations.values
                    .flatMap { perLanguage -> perLanguage.keys.map { (skill, name) -> "$skill:$name" } }
                    .toSortedSet()
                deliver(
                    ThalovantEvents.PADATIOUS_MANIFEST,
                    buildJsonObject { put("intents", JsonArray(names.map { JsonPrimitive(it) })) },
                    context,
                )
            }
        }
    }

    /** `hive.policy.denied` as the hub sends it for a type this connection may not publish. */
    protected fun denyOf(eventType: String, context: JsonObject) {
        deliver(
            ThalovantEvents.POLICY_DENIED,
            buildJsonObject {
                put("denied_type", eventType)
                put("code", "acl_disallowed_type")
                put("reason", "$eventType not in allowed_types")
                put(
                    "data",
                    buildJsonObject {
                        put("msg_type", eventType)
                        put("allowed", JsonArray(ALLOWED.map { JsonPrimitive(it) }))
                    },
                )
            },
            context,
        )
    }

    private fun definition(key: Pair<String, String>, lang: String, samples: List<String>): JsonObject =
        buildJsonObject {
            put("skill_id", key.first)
            put("intent_name", key.second)
            put("lang", lang)
            put("samples", JsonArray(samples.map { JsonPrimitive(it) }))
            put("blacklist", JsonArray(emptyList()))
            put("slot_blacklist", buildJsonObject {})
        }
}

class IntentsTest {
    private fun identity(): ThalovantIdentity = ThalovantIdentity(
        buildJsonObject {
            put("access_key", "key")
            put("password", "password")
            put("crypto_key", "0123456789abcdef")
            put("site_id", "site")
            put("default_master", "ws://hub.local:5679")
        },
    )

    private fun client(transport: FakeHubTransport): ThalovantClient =
        ThalovantClient(identity(), transport = transport, replySettleMs = 0)

    @Test
    fun `the inventory carries the sentences per language`() = runBlocking {
        val hub = FakeHubTransport()
        val inventory = client(hub).intents(listOf("en-us", "fr-fr"))

        assertEquals(HubIntentSource.INTENT_MANIFEST, inventory.source)
        assertTrue(inventory.denied.isEmpty())
        assertEquals(listOf("en-us", "fr-fr"), inventory.languages)
        assertEquals(listOf(SHADOW, WEATHER), inventory.skills.map { it.skillId })
        val weather = inventory.skills[1].intents[0]
        assertEquals("$WEATHER:current.weather", weather.id)
        assertEquals("padatious", weather.engine)
        assertTrue(weather.enabled)
        assertEquals(
            listOf("quel temps fait-il", "quelle est la météo à {location}", "quelle est la météo"),
            weather.phrasesFor("fr-FR"),
        )
        assertEquals(listOf("en-us", "fr-fr"), inventory.skills[1].languages)
        val shadow = inventory.skills[0]
        assertEquals(listOf("en-us"), shadow.languages, "the hub said the skill has no French")
        assertEquals(emptyList(), shadow.intents[0].phrasesFor("fr-fr"))
        assertTrue(inventory.hasPhrases)
        assertEquals(2, inventory.intents.size)
    }

    @Test
    fun `examples prefer whole sentences and respect the limit`() = runBlocking {
        val weather = client(FakeHubTransport()).intents(listOf("en-us")).skills[1].intents[0]
        assertEquals(listOf("how is it outside", "what is the weather"), weather.examples("en-us", 2))
        assertEquals(weather.phrasesFor("en-us"), weather.examples("en-us", 0))
        assertEquals(listOf("how is it outside"), weather.examples(limit = 1))
        assertEquals(emptyList(), weather.examples("fr-fr"))
    }

    @Test
    fun `every registration is described at once and repeats are dropped`() = runBlocking {
        val hub = FakeHubTransport(repeats = 3)
        val inventory = client(hub).intents(listOf("en-us", "fr-fr"))

        val describes = hub.emittedOf(ThalovantEvents.INTENT_DESCRIBE).map {
            Triple(
                it.data["skill_id"]?.jsonPrimitive?.content,
                it.data["intent_name"]?.jsonPrimitive?.content,
                it.data["lang"]?.jsonPrimitive?.content,
            )
        }
        assertEquals(3, describes.size)
        assertEquals(3, describes.toSet().size)
        assertEquals(2, inventory.intents.size)
        val queries = hub.emitted.filter {
            it.type == ThalovantEvents.INTENT_LIST || it.type == ThalovantEvents.INTENT_DESCRIBE
        }
        assertEquals(5, queries.size)
        for (query in queries) {
            val requestId = query.context["request_id"]?.jsonPrimitive?.content
            assertNotNull(requestId, "every query is correlated by request id")
            assertTrue(requestId.startsWith("thalovant-request-"))
            assertEquals(query.data["lang"], query.context["lang"], "the language rides in the context too")
        }
        // The list is asked once per language, with definitions requested.
        val listed = hub.emittedOf(ThalovantEvents.INTENT_LIST)
        assertEquals(listOf("en-us", "fr-fr"), listed.map { it.data["lang"]?.jsonPrimitive?.content })
        assertEquals(JsonPrimitive(true), listed[0].data["include_definitions"])
    }

    @Test
    fun `definitions attached to the listing skip the describes`() = runBlocking {
        val hub = FakeHubTransport(definitionsInList = true)
        val inventory = client(hub).intents(listOf("fr-fr"))
        assertTrue(hub.emittedOf(ThalovantEvents.INTENT_DESCRIBE).isEmpty())
        assertEquals("quel temps fait-il", inventory.intents[0].phrasesFor("fr-fr")[0])
        assertEquals(
            buildJsonObject {
                put("lang", "fr-fr")
                put("include_definitions", true)
            },
            hub.emitted[0].data,
        )
    }

    @Test
    fun `describe off lists names and engines without sentences`() = runBlocking {
        val hub = FakeHubTransport()
        val inventory = client(hub).intents(listOf("en-us"), IntentInventoryOptions(describe = false))
        assertTrue(hub.emittedOf(ThalovantEvents.INTENT_DESCRIBE).isEmpty())
        assertNull(hub.emitted[0].data["include_definitions"])
        assertEquals(2, inventory.intents.size)
        assertFalse(inventory.hasPhrases)
        assertEquals(listOf("en-us"), inventory.intents[0].languages)
    }

    @Test
    fun `a refusal is an error naming the type not a timeout`() = runBlocking {
        val hub = FakeHubTransport(refuse = setOf(ThalovantEvents.INTENT_LIST))
        val error = assertFailsWith<ThalovantPolicyDeniedException> {
            client(hub).intents(listOf("en-us"), IntentInventoryOptions(fallback = false, timeoutMs = 5000))
        }
        assertIs<ThalovantRuntimeException>(error)
        assertEquals("ovos.intent.list", error.deniedType)
        assertEquals("acl_disallowed_type", error.code)
        assertEquals("ovos.intent.list not in allowed_types", error.reason)
        assertEquals(ALLOWED, error.allowed)
        assertTrue("ovos.intent.list" in error.message.orEmpty())
        assertTrue("connection" in error.message.orEmpty())
        assertTrue(hub.emittedOf(ThalovantEvents.ADAPT_MANIFEST_GET).isEmpty(), "no fallback was asked for")
    }

    @Test
    fun `the fallback lists names and says what was refused`() = runBlocking {
        val hub = FakeHubTransport(refuse = setOf(ThalovantEvents.INTENT_LIST))
        val inventory = client(hub).intents(listOf("en-us", "fr-fr"))

        assertEquals(HubIntentSource.ENGINE_MANIFESTS, inventory.source)
        assertEquals(listOf("ovos.intent.list"), inventory.denied)
        assertEquals(listOf("en-us", "fr-fr"), inventory.languages)
        assertFalse(inventory.hasPhrases)
        assertEquals(
            listOf("$SHADOW:custos.incidents", "$WEATHER:current.weather"),
            inventory.intents.map { it.id },
        )
        assertEquals("padatious", inventory.intents[0].engine)
        assertTrue(inventory.intents[0].languages.isEmpty())
        // Names carry no language, so the engines are asked once, not per language.
        assertEquals(1, hub.emittedOf(ThalovantEvents.PADATIOUS_MANIFEST_GET).size)
        assertEquals(1, hub.emittedOf(ThalovantEvents.ADAPT_MANIFEST_GET).size)
    }

    @Test
    fun `a hub refusing everything raises even with the fallback`() = runBlocking {
        val hub = FakeHubTransport(
            refuse = setOf(ThalovantEvents.INTENT_LIST, ThalovantEvents.ADAPT_MANIFEST_GET),
        )
        val error = assertFailsWith<ThalovantPolicyDeniedException> { client(hub).intents(listOf("en-us")) }
        assertEquals("intent.service.adapt.manifest.get", error.deniedType)
    }

    @Test
    fun `a refused describe is the policy error too`() = runBlocking {
        val hub = FakeHubTransport(refuse = setOf(ThalovantEvents.INTENT_DESCRIBE))
        val error = assertFailsWith<ThalovantPolicyDeniedException> { client(hub).intents(listOf("en-us")) }
        assertEquals("ovos.intent.describe", error.deniedType)
    }

    @Test
    fun `a silent listing falls back without implying an explicit policy denial`() = runBlocking {
        val hub = FakeHubTransport(silent = setOf(ThalovantEvents.INTENT_LIST))
        val result = client(hub).intents(listOf("en-us"), IntentInventoryOptions(timeoutMs = 30))
        assertEquals(HubIntentSource.ENGINE_MANIFESTS, result.source)
        assertEquals(listOf(ThalovantEvents.INTENT_LIST), result.denied)
        assertFalse(result.hasPhrases)
        assertTrue(result.fallbacksKnown)
        assertFalse(result.mayAnswer("fr-fr"))
    }

    @Test
    fun `silent listing strict mode and silent engines retain timeout errors`() = runBlocking {
        for ((silent, fallback, expected) in listOf(
            Triple(setOf(ThalovantEvents.INTENT_LIST), false, ThalovantEvents.INTENT_LIST),
            Triple(setOf(ThalovantEvents.INTENT_LIST, ThalovantEvents.ADAPT_MANIFEST_GET), true, ThalovantEvents.ADAPT_MANIFEST_GET),
        )) {
            val error = assertFailsWith<ThalovantTimeoutException> {
                client(FakeHubTransport(silent = silent)).intents(listOf("en-us"), IntentInventoryOptions(timeoutMs = 30, fallback = fallback))
            }
            assertTrue(expected in error.message.orEmpty())
        }
    }

    @Test
    fun `fallback discovery distinguishes unknown from known empty`() = runBlocking {
        for (hub in listOf(
            FakeHubTransport(refuse = setOf(ThalovantEvents.FALLBACK_LIST)),
            FakeHubTransport(silent = setOf(ThalovantEvents.FALLBACK_LIST)),
            FakeHubTransport(fallbackPayload = buildJsonObject { put("fallbacks", "invalid") }),
            FakeHubTransport(fallbackPayload = buildJsonObject { put("ok", false); put("fallbacks", JsonArray(emptyList())) }),
        )) {
            val inventory = client(hub).intents(listOf("fr-fr"), IntentInventoryOptions(timeoutMs = 30))
            assertFalse(inventory.fallbacksKnown)
            assertTrue(inventory.mayAnswer("de-de"))
            assertEquals(JsonPrimitive(false), inventory.asJson()["fallbacks_known"])
        }
        val inventory = client(FakeHubTransport()).intents(listOf("fr-fr"))
        assertTrue(inventory.fallbacksKnown)
        assertTrue(inventory.mayAnswer("fr-FR"))
        assertFalse(inventory.mayAnswer("de-de"))
        assertFalse(inventory.copy(skills = inventory.skills.map { skill ->
            skill.copy(intents = skill.intents.map { it.copy(enabled = false) })
        }).mayAnswer("fr-fr"))
    }

    @Test
    fun `foreign correlated denials and matching describe content never satisfy our request`() = runBlocking {
        val inventory = client(FakeHubTransport(foreignFirst = true)).intents(listOf("en-us", "fr-fr"))
        assertTrue(inventory.hasPhrases)
        assertTrue(inventory.intents.flatMap { it.phrases.values.flatten() }.none { it == "foreign phrase" })
        assertTrue(inventory.fallbacksKnown)
    }

    @Test
    fun `fallback probe budget includes sending and preserves caller cancellation`() = runBlocking {
        val sdk = client(FakeHubTransport(fallbackDelayMs = 5000))
        val started = System.nanoTime()
        assertNull(sdk.listFallbacks(30))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000)
        assertNull(client(FakeHubTransport(connectDelayMs = 5000)).listFallbacks(30))
        val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sdk.listFallbacks(5000) }
        job.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
    }

    @Test
    fun `fallback discovery parses and sorts handlers and ignores invalid rows`() = runBlocking {
        val payload = ThalovantJson.parseToJsonElement("""{"fallbacks":[
            {"skill_id":"b","priority":20}, {"skill_id":"a","priority":20.5},
            {"skill_id":"true","priority":true}, {"skill_id":"default","priority":"42"},
            {"skill_id":"huge","priority":1e100},
            {"skill_id":""}, {"skill_id":123}, false
        ]}""").jsonObject
        val sdk = client(FakeHubTransport(fallbackPayload = payload))
        assertEquals(listOf(HubFallback("default", 0), HubFallback("true", 1), HubFallback("a", 20), HubFallback("b", 20)), sdk.listFallbacks())
        val inventory = sdk.intents(listOf("fr-fr"))
        assertTrue(inventory.fallbacksKnown)
        assertTrue(inventory.mayAnswer("de-de"))
        assertEquals(4, inventory.asJson()["fallbacks"]!!.jsonArray.size)
    }

    @Test
    fun `a describe that never comes leaves that intent without sentences`() = runBlocking {
        class HalfDeaf : FakeHubTransport() {
            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                if (eventType == ThalovantEvents.INTENT_DESCRIBE &&
                    data["skill_id"]?.jsonPrimitive?.content == SHADOW
                ) {
                    emitted.add(Emitted(eventType, data, context))
                    return
                }
                super.emitBus(eventType, data, context)
            }
        }

        val inventory = client(HalfDeaf()).intents(listOf("en-us"), IntentInventoryOptions(timeoutMs = 300))
        val byId = inventory.intents.associateBy { it.id }
        assertTrue(byId.getValue("$WEATHER:current.weather").phrasesFor("en-us").isNotEmpty())
        assertEquals(emptyList(), byId.getValue("$SHADOW:custos.incidents").phrasesFor("en-us"))
        assertEquals(listOf("en-us"), byId.getValue("$SHADOW:custos.incidents").languages)
    }

    @Test
    fun `a reply without a request id is still taken`() = runBlocking {
        // A hub that does not echo the request id is not evidence of anything:
        // the listing passes the filter, the describes match by definition.
        val hub = FakeHubTransport(echoRequestId = false, repeats = 1)
        val inventory = client(hub).intents(listOf("en-us", "fr-fr"))
        assertTrue(inventory.hasPhrases)
        val weather = inventory.intents.first { it.skillId == WEATHER }
        assertEquals("quel temps fait-il", weather.phrasesFor("fr-fr")[0])
        assertEquals("what is the weather", weather.phrasesFor("en-us")[0])
        assertEquals(
            listOf("are there incidents", "any incidents"),
            inventory.intents.first { it.skillId == SHADOW }.phrasesFor("en-us"),
        )
    }

    @Test
    fun `low level calls expose the manifest rows and definitions`() = runBlocking {
        val hub = FakeHubTransport()
        val rows = client(hub).listIntents("fr-fr")
        assertEquals(
            listOf(listOf(WEATHER, "current.weather", "padatious", "true")),
            rows.map { listOf(it.skillId, it.intentName, it.engine, it.enabled.toString()) },
        )
        assertEquals("fr-FR", rows[0].lang)
        assertTrue(sameLanguage(rows[0].lang, "fr-fr"))
        assertEquals("template", rows[0].method)
        assertEquals("default", rows[0].sessionId)
        assertNull(rows[0].definition)
        assertNull(hub.emitted.last().data["include_definitions"])

        val definitions = client(hub).describeIntent(WEATHER, "current.weather", "fr-fr")
        assertEquals(1, definitions.size)
        assertEquals("quel temps fait-il", definitions[0].samples[0])
        assertEquals("padatious", definitions[0].engine)
        assertEquals(WEATHER, definitions[0].skillId)
        assertEquals("current.weather", definitions[0].intentName)
        assertEquals("fr-fr", definitions[0].lang)
        assertEquals(JsonArray(emptyList()), definitions[0].raw["blacklist"])
        assertEquals(emptyList(), client(hub).describeIntent(SHADOW, "custos.incidents", "fr-fr"))
    }

    @Test
    fun `listIntents can ask for the definitions`() = runBlocking {
        val hub = FakeHubTransport(definitionsInList = true)
        val rows = client(hub).listIntents("en-us", ListIntentsOptions(includeDefinitions = true))
        assertEquals(JsonPrimitive(true), hub.emitted[0].data["include_definitions"])
        assertEquals(2, rows.size)
        val definition = rows[0].definition
        assertNotNull(definition)
        assertEquals("what is the weather", definition["samples"]?.jsonArray?.get(0)?.jsonPrimitive?.content)
    }

    @Test
    fun `asJson is JSON-ready and complete`() = runBlocking {
        val inventory = client(FakeHubTransport()).intents(listOf("en-us", "fr-fr"))
        val payload = inventory.asJson()
        assertEquals("intent-manifest", payload["source"]?.jsonPrimitive?.content)
        assertEquals(listOf("en-us", "fr-fr"), payload["languages"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), payload["denied"])
        val weather = payload["skills"]!!.jsonArray.map { it.jsonObject }
            .first { it["skill_id"]?.jsonPrimitive?.content == WEATHER }
        assertEquals(listOf("en-us", "fr-fr"), weather["languages"]?.jsonArray?.map { it.jsonPrimitive.content })
        val intent = weather["intents"]!!.jsonArray[0].jsonObject
        assertEquals("$WEATHER:current.weather", intent["id"]?.jsonPrimitive?.content)
        assertEquals("padatious", intent["engine"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), intent["enabled"])
        assertEquals(
            "quel temps fait-il",
            intent["phrases"]?.jsonObject?.get("fr-fr")?.jsonArray?.get(0)?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `languages default to english`() = runBlocking {
        val hub = FakeHubTransport()
        client(hub).intents()
        assertEquals("en-us", hub.emitted[0].data["lang"]?.jsonPrimitive?.content)
        hub.emitted.clear()
        client(hub).intents(emptyList())
        assertEquals("en-us", hub.emitted[0].data["lang"]?.jsonPrimitive?.content)
        hub.emitted.clear()
        client(hub).listIntents()
        assertEquals("en-us", hub.emitted[0].data["lang"]?.jsonPrimitive?.content)
        hub.emitted.clear()
        client(hub).describeIntent(WEATHER, "current.weather")
        assertEquals("en-us", hub.emitted[0].data["lang"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a blank language list is rejected and duplicates are folded`() = runBlocking {
        val hub = FakeHubTransport()
        assertFailsWith<IllegalArgumentException> { client(hub).intents(listOf("  ")) }
        val inventory = client(hub).intents(listOf("en-us", " en-us ", "fr-fr"))
        assertEquals(listOf("en-us", "fr-fr"), inventory.languages)
        assertEquals(2, hub.emittedOf(ThalovantEvents.INTENT_LIST).size)
    }

    @Test
    fun `language tags compare case-insensitively with underscores folded`() {
        assertTrue(sameLanguage("fr-fr", "fr_FR"))
        assertTrue(sameLanguage(" EN-US", "en_us "))
        assertFalse(sameLanguage("en-us", "en-gb"))
        assertEquals(HubIntentSource.ENGINE_MANIFESTS, HubIntentSource.fromWireName("engine-manifests"))
        assertNull(HubIntentSource.fromWireName("cache"))
    }

    @Test
    fun `the policy error is built from the bus event`() {
        val event = ThalovantEvent(
            ThalovantEvents.POLICY_DENIED,
            buildJsonObject {
                put("denied_type", "ovos.intent.list")
                put("code", "acl_disallowed_type")
                put("reason", "ovos.intent.list not in allowed_types")
                put(
                    "data",
                    buildJsonObject {
                        put("msg_type", "ovos.intent.list")
                        put("allowed", JsonArray(listOf(JsonPrimitive("speak"))))
                    },
                )
            },
        )
        val error = ThalovantPolicyDeniedException.fromEvent(event)
        assertEquals("ovos.intent.list", error.deniedType)
        assertEquals("acl_disallowed_type", error.code)
        assertEquals("ovos.intent.list not in allowed_types", error.reason)
        assertEquals(listOf("speak"), error.allowed)
        assertTrue(error.message.orEmpty().startsWith("The hub refused 'ovos.intent.list': ovos.intent.list not in"))

        val bare = ThalovantPolicyDeniedException.fromEvent(ThalovantEvent(ThalovantEvents.POLICY_DENIED))
        assertEquals("", bare.deniedType)
        assertEquals(emptyList(), bare.allowed)
        assertTrue("refused by the hub's policy" in bare.message.orEmpty())
    }

    @Test
    fun `rows and definitions without a skill or intent are skipped`() {
        assertNull(IntentRegistration.from(buildJsonObject { put("skill_id", WEATHER) }))
        assertNull(IntentDefinition.from(buildJsonObject { put("method", "template") }))
        val keyword = IntentRegistration.from(
            buildJsonObject {
                put("skill_id", WEATHER)
                put("intent_name", "keyword.weather")
                put("method", "keyword")
                put("enabled", false)
            },
        )
        assertNotNull(keyword)
        assertEquals("adapt", keyword.engine)
        assertFalse(keyword.enabled)
        assertEquals("", keyword.lang)
        val odd = IntentRegistration.from(
            buildJsonObject {
                put("skill_id", WEATHER)
                put("intent_name", "odd")
                put("method", "regex")
            },
        )
        assertEquals("regex", odd?.engine)
    }

    @Test
    fun `hasPhrases means at least one sentence`() = runBlocking {
        val hub = FakeHubTransport(
            registrations = mapOf("en-us" to mapOf((SHADOW to "custos.incidents") to emptyList<String>())),
        )
        val inventory = client(hub).intents(listOf("en-us"))
        assertTrue(inventory.intents.isNotEmpty())
        assertFalse(inventory.hasPhrases, "an intent with no sentence does not make an inventory with phrases")
    }

    @Test
    fun `languages are folded and deduplicated before asking`() = runBlocking {
        val hub = FakeHubTransport()
        val inventory = client(hub).intents(listOf(" en-us ", "en-US", "en_us", "fr-fr"))
        assertEquals(listOf("en-us", "fr-fr"), inventory.languages, "the first spelling seen, in the order given")
        assertEquals(
            listOf("en-us", "fr-fr"),
            hub.emittedOf(ThalovantEvents.INTENT_LIST).map { it.data["lang"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `a keyword row does not erase the template row's sentences`() = runBlocking {
        // One intent, two registrations in one language: the keyword row has no
        // samples and arrives after the template row.
        class Dual : FakeHubTransport() {
            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                if (eventType != ThalovantEvents.INTENT_LIST) {
                    super.emitBus(eventType, data, context)
                    return
                }
                emitted.add(Emitted(eventType, data, context))
                val lang = data["lang"]!!.jsonPrimitive.content
                val rows = listOf(
                    row(lang, "template", buildJsonObject { put("samples", jsonOf("what is the weather")) }),
                    row(lang, "keyword", buildJsonObject { put("required", JsonArray(emptyList())) }),
                )
                deliver(
                    ThalovantEvents.INTENT_LIST_RESPONSE,
                    buildJsonObject {
                        put("ok", true)
                        put("intents", JsonArray(rows))
                    },
                    context,
                )
            }

            private fun row(lang: String, method: String, extra: JsonObject): JsonObject = buildJsonObject {
                put("skill_id", WEATHER)
                put("intent_name", "current.weather")
                put("lang", lang)
                put("method", method)
                put("enabled", true)
                put("session_id", "default")
                put(
                    "definition",
                    buildJsonObject {
                        put("skill_id", WEATHER)
                        put("intent_name", "current.weather")
                        put("lang", lang)
                        for ((name, value) in extra) put(name, value)
                    },
                )
            }
        }

        val inventory = client(Dual()).intents(listOf("en-us"))
        assertEquals(1, inventory.intents.size)
        assertEquals(listOf("what is the weather"), inventory.intents[0].phrasesFor("en-us"))
        assertEquals("padatious", inventory.intents[0].engine, "the first row names the engine")
    }

    @Test
    fun `the fallback keeps the first engine that names an intent`() = runBlocking {
        // Adapt is asked first, so a name both engines list is adapt's.
        class BothEngines : FakeHubTransport(refuse = setOf(ThalovantEvents.INTENT_LIST)) {
            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                if (eventType != ThalovantEvents.ADAPT_MANIFEST_GET) {
                    super.emitBus(eventType, data, context)
                    return
                }
                emitted.add(Emitted(eventType, data, context))
                deliver(
                    ThalovantEvents.ADAPT_MANIFEST,
                    buildJsonObject { put("intents", jsonOf("$WEATHER:current.weather")) },
                    context,
                )
            }
        }

        val inventory = client(BothEngines()).intents(listOf("en-us"))
        assertEquals(HubIntentSource.ENGINE_MANIFESTS, inventory.source)
        assertEquals("adapt", inventory.intents.first { it.name == "current.weather" }.engine)
        assertEquals("padatious", inventory.intents.first { it.name == "custos.incidents" }.engine)
    }

    @Test
    fun `describes go out in bounded batches`() = runBlocking {
        // A hub with many intents must not put more requests in flight than a
        // bounded reply queue can hold: 69 intents is 69 describes, and every
        // reply arrives twice. They go out 32 at a time, each batch its own
        // subscription window with its own deadline.
        assertEquals(32, DESCRIBE_BATCH)
        val hub = FakeHubTransport(registrations = manyIntents(69))
        val connected = client(hub).also { it.connect() }
        val wanted = (0 until 69).map { IntentKey(WEATHER, intentName(it), "en-us") }

        assertEquals(69, connected.describeMany(wanted, timeoutMs = 5000).size)
        // Two subscriptions per window (the replies and the refusals): three windows.
        assertEquals(6, hub.subscriptions.get(), "69 describes go out in three windows of at most 32")

        val unbounded = FakeHubTransport(registrations = manyIntents(69))
        assertEquals(69, client(unbounded).describeMany(wanted, timeoutMs = 5000, batch = 0).size)
        assertEquals(2, unbounded.subscriptions.get(), "batch = 0 sends them all at once")

        val whole = client(FakeHubTransport(registrations = manyIntents(69))).intents(listOf("en-us"))
        assertEquals(69, whole.intents.size)
        assertTrue(whole.intents.all { it.phrasesFor("en-us").isNotEmpty() })
    }

    @Test
    fun `a silent window keeps what the earlier windows found`() = runBlocking {
        // Windows are contiguous slices, so a skill that stops answering can own
        // a whole window. Losing its sentences is right; losing the inventory is
        // not. Windows are 0-31, 32-63, 64-68: the first answers in full, the
        // second in part, the third not at all.
        class GoesQuiet : FakeHubTransport(registrations = manyIntents(69)) {
            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                val name = data["intent_name"]?.jsonPrimitive?.content
                if (eventType == ThalovantEvents.INTENT_DESCRIBE &&
                    name != null && name.substringAfterLast('.').toInt() >= 40
                ) {
                    emitted.add(Emitted(eventType, data, context))
                    return
                }
                super.emitBus(eventType, data, context)
            }
        }

        val inventory = client(GoesQuiet()).intents(listOf("en-us"), IntentInventoryOptions(timeoutMs = 300))
        assertEquals(69, inventory.intents.size, "every intent is still listed")
        assertEquals(40, inventory.intents.count { it.phrasesFor("en-us").isNotEmpty() })
        assertTrue(inventory.hasPhrases)
    }

    @Test
    fun `a hub silent from the first window still fails fast`() = runBlocking {
        val hub = FakeHubTransport(
            registrations = manyIntents(69),
            silent = setOf(ThalovantEvents.INTENT_DESCRIBE),
        )
        val connected = client(hub).also { it.connect() }
        assertFailsWith<ThalovantTimeoutException> {
            connected.describeMany((0 until 69).map { IntentKey(WEATHER, intentName(it), "en-us") }, 200)
        }
        assertEquals(
            32,
            hub.emittedOf(ThalovantEvents.INTENT_DESCRIBE).size,
            "it gives up after one window, not after all 69",
        )
    }

    @Test
    fun `a refusal in a later window is not swallowed as a partial answer`() = runBlocking {
        // Only a timeout means "this window had nothing". A hub that starts
        // refusing part-way is a policy problem and must reach the caller.
        class RefusesLater : FakeHubTransport(registrations = manyIntents(69)) {
            @Volatile
            var refusing: Boolean = false

            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                if (eventType == ThalovantEvents.INTENT_DESCRIBE) {
                    if (refusing) {
                        emitted.add(Emitted(eventType, data, context))
                        denyOf(eventType, context)
                        return
                    }
                    if (emittedOf(ThalovantEvents.INTENT_DESCRIBE).size >= 31) refusing = true
                }
                super.emitBus(eventType, data, context)
            }
        }

        val hub = RefusesLater()
        val connected = client(hub).also { it.connect() }
        val error = assertFailsWith<ThalovantPolicyDeniedException> {
            connected.describeMany((0 until 69).map { IntentKey(WEATHER, intentName(it), "en-us") }, 300)
        }
        assertEquals(ThalovantEvents.INTENT_DESCRIBE, error.deniedType)
    }

    @Test
    fun `a refused listing is an error not an empty hub`() = runBlocking {
        // `ok: false` on a listing means the query failed. Reporting it as no
        // intents would show a person a device that can do nothing.
        class Refuses : FakeHubTransport() {
            override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                if (eventType != ThalovantEvents.INTENT_LIST) {
                    super.emitBus(eventType, data, context)
                    return
                }
                emitted.add(Emitted(eventType, data, context))
                deliver(
                    ThalovantEvents.INTENT_LIST_RESPONSE,
                    buildJsonObject {
                        put("ok", false)
                        put("error", "manifest unavailable")
                    },
                    context,
                )
            }
        }

        val error = assertFailsWith<ThalovantRuntimeException> { client(Refuses()).intents(listOf("en-us")) }
        assertEquals("ovos.intent.list failed: manifest unavailable", error.message)
        // A listing that fails without saying why still says the query failed.
        val bare = assertFailsWith<ThalovantRuntimeException> {
            client(
                object : FakeHubTransport() {
                    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
                        if (eventType != ThalovantEvents.INTENT_LIST) {
                            super.emitBus(eventType, data, context)
                            return
                        }
                        emitted.add(Emitted(eventType, data, context))
                        deliver(
                            ThalovantEvents.INTENT_LIST_RESPONSE,
                            buildJsonObject { put("ok", false) },
                            context,
                        )
                    }
                },
            ).listIntents("en-us")
        }
        assertEquals("ovos.intent.list failed: the hub refused the listing", bare.message)
    }

    @Test
    fun `a describe that does not know the intent is not an error`() = runBlocking {
        // The other half of the rule: describe answering `ok: false` for an
        // intent it does not know is a real answer, and leaves it without
        // sentences instead of failing the call.
        val hub = FakeHubTransport()
        assertEquals(emptyList(), client(hub).describeIntent(SHADOW, "custos.incidents", "fr-fr"))
        val inventory = client(hub).intents(listOf("en-us", "fr-fr"))
        assertEquals(emptyList(), inventory.intents.first { it.skillId == SHADOW }.phrasesFor("fr-fr"))
        assertTrue(inventory.hasPhrases)
    }

    @Test
    fun `only string entries survive in the allowed list`() {
        // A number or a null in `allowed` is not a message type; stringifying
        // one would put "3" or "null" in front of an operator reading which
        // types to allow.
        val error = ThalovantPolicyDeniedException.fromEvent(
            ThalovantEvent(
                ThalovantEvents.POLICY_DENIED,
                buildJsonObject {
                    put("denied_type", "ovos.intent.list")
                    put("code", "acl_disallowed_type")
                    put(
                        "data",
                        buildJsonObject {
                            put(
                                "allowed",
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("speak"),
                                        JsonPrimitive(3),
                                        JsonNull,
                                        JsonPrimitive("recognizer_loop:utterance"),
                                    ),
                                ),
                            )
                        },
                    )
                },
            ),
        )
        assertEquals(listOf("speak", "recognizer_loop:utterance"), error.allowed)
    }
}
