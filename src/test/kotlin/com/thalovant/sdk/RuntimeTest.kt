package com.thalovant.sdk

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*

private class RuntimeFake : HiveMindRuntimeTransport {
    override var connected = false
    override val handshakeComplete get() = connected
    val bus = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
    val frames = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    val emitted = mutableListOf<ThalovantEvent>()
    val sent = mutableListOf<JsonObject>()
    var queryAnswer: ((JsonObject) -> Unit)? = null
    override suspend fun connect(timeoutMs: Long) { connected = true }
    override suspend fun disconnect() { connected = false }
    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription {
        bus.add(listener); return ThalovantSubscription { bus.remove(listener) }
    }
    override fun addHiveMessageListener(listener: (JsonObject) -> Unit): ThalovantSubscription {
        frames.add(listener); return ThalovantSubscription { frames.remove(listener) }
    }
    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) { emitted.add(ThalovantEvent(eventType, data, context)) }
    override suspend fun sendHiveFrame(message: JsonObject) { sent.add(message); queryAnswer?.invoke(message) }
    fun deliver(event: ThalovantEvent) { bus.forEach { it(event) } }
    fun reply(id: String, name: String, text: String = "", cascade: Boolean = false) {
        val payload = hiveMessage("bus", buildJsonObject {
            put("type", name); put("data", buildJsonObject { put("utterance", text) }); put("context", EMPTY_JSON_OBJECT)
        })
        val frame = hiveMessage(if (cascade) "cascade" else "query", payload, buildJsonObject { put("query_id", id) })
        frames.forEach { it(frame) }
    }
}

class RuntimeTest {
    private fun client(fake: RuntimeFake) = ThalovantClient(ThalovantIdentity(ThalovantJson.parseToJsonElement(
        """{"access_key":"access","password":"password","crypto_key":"0123456789abcdef","site_id":"site","default_master":"wss://hub.example"}"""
    ).jsonObject), transport = fake, replySettleMs = 0, emptyReplyWaitMs = 0)

    @Test fun `query rejects unrelated ids and aggregates nested cascade replies`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        fake.queryAnswer = {
            fake.reply("another", "speak", "wrong")
            fake.reply("q", "speak", " first   part ")
            fake.reply("q", "speak", "first part")
            fake.reply("q", "ovos.utterance.speak", "second", cascade = true)
            fake.reply("q", "hive.query.complete", cascade = true)
        }
        val reply = sdk.query("hello", requestId = "r", queryId = "q", sessionId = "s")
        assertEquals("first part second", reply.text)
        assertTrue(reply.ok)
        assertEquals("s", reply.sessionId); assertEquals("r", reply.requestId)
        val request = fake.sent.single()
        assertEquals("query", request["msg_type"]!!.jsonPrimitive.content)
        assertEquals("r", request["payload"]!!.jsonObject["payload"]!!.jsonObject["context"]!!.jsonObject["request_id"]!!.jsonPrimitive.content)
        assertTrue(fake.frames.isEmpty())
    }

    @Test fun `query failure silence cancellation and disconnect clean subscriptions`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        fake.queryAnswer = { fake.reply("q", ThalovantEvents.POLICY_DENIED) }
        assertFailsWith<ThalovantRuntimeException> { sdk.query("test", queryId = "q") }
        assertTrue(fake.frames.isEmpty())
        fake.queryAnswer = null
        assertFailsWith<ThalovantTimeoutException> { sdk.query("test", timeoutMs = 20) }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sdk.query("test") }
        pending.cancelAndJoin(); assertTrue(fake.frames.isEmpty())
        val lost = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sdk.query("test") }.exceptionOrNull() }
        fake.disconnect()
        assertIs<ThalovantConnectionException>(withTimeout(1000) { lost.await() })
        assertTrue(fake.frames.isEmpty())
    }

    @Test fun `event wait filters with request id precedence and removes listeners`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            sdk.waitForEvent("speak", sessionId = "mine", requestId = "r") { it.text == "yes" }
        }
        fun event(request: String, text: String) = ThalovantEvent("speak", buildJsonObject { put("utterance", text) },
            contextWithCorrelation(EMPTY_JSON_OBJECT, "hub-rewritten", requestId = request))
        fake.deliver(event("wrong", "yes")); fake.deliver(event("r", "no")); assertFalse(pending.isCompleted)
        fake.deliver(event("r", "yes")); assertEquals("yes", pending.await().text)
        assertTrue(fake.bus.isEmpty())
        assertFailsWith<ThalovantTimeoutException> { sdk.waitForEvent("never", timeoutMs = 20) }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { sdk.waitForEvent("never") }
        cancelled.cancelAndJoin(); assertTrue(fake.bus.isEmpty())
    }

    @Test fun `bounded event stream stops on count timeout cancellation and loss`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sdk.listen("speak", maxEvents = 2).toList() }
        fake.deliver(ThalovantEvent("other")); repeat(2) { fake.deliver(ThalovantEvent("speak")) }
        assertEquals(2, pending.await().size); assertTrue(fake.bus.isEmpty())
        assertTrue(sdk.listen("never", timeoutMs = 20).toList().isEmpty()); assertTrue(fake.bus.isEmpty())
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { sdk.listen("never").toList() }
        cancelled.cancelAndJoin(); assertTrue(fake.bus.isEmpty())
        val lost = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sdk.listen("never").toList() }.exceptionOrNull() }
        fake.disconnect(); assertIs<ThalovantConnectionException>(withTimeout(1000) { lost.await() })
        assertTrue(fake.bus.isEmpty())
    }

    @Test fun `conversation shares session while action and code preserve structured input`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        val original = buildJsonObject { put("input", buildJsonObject { put("custom", "keep") }); put("nested", buildJsonObject { put("a", true) }) }
        val conversation = sdk.conversation("stable", "fr-fr", original)
        conversation.sendAction(" launch ", "Go")
        conversation.sendCode(" 001-09 ", label = "Ticket")
        val (action, code) = fake.emitted
        assertEquals("stable", action.sessionId); assertEquals(action.sessionId, code.sessionId)
        assertNotEquals(action.requestId, code.requestId)
        assertEquals("launch", action.utterances.single())
        assertEquals("keep", action.context["input"]!!.jsonObject["custom"]!!.jsonPrimitive.content)
        assertEquals("action", action.context["input"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("001-09", code.utterances.single())
        assertTrue(code.data["input"]!!.jsonObject["exact"]!!.jsonPrimitive.boolean)
        assertEquals("fr-fr", code.data["lang"]!!.jsonPrimitive.content)
        assertNull(original["input"]!!.jsonObject["kind"])
        assertFailsWith<IllegalArgumentException> { sdk.sendAction(" ") }
        assertFailsWith<IllegalArgumentException> { sdk.sendCode(" ") }
        assertTrue(sdk.healthcheck().ok); assertTrue(sdk.doctor().ok)
        assertEquals("ready", sdk.connectWithInfo().phase)
    }
}
