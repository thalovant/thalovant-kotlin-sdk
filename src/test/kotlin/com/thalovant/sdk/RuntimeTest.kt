package com.thalovant.sdk

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

private class RuntimeFake : HiveMindRuntimeTransport {
    override var connected = false
    override val handshakeComplete get() = connected
    val bus = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
    val frames = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    val emitted = mutableListOf<ThalovantEvent>()
    val sent = mutableListOf<JsonObject>()
    var queryAnswer: ((JsonObject) -> Unit)? = null
    var queryAction: (suspend () -> Unit)? = null
    var connectAction: (suspend () -> Unit)? = null
    var emitAction: (suspend () -> Unit)? = null
    var busAnswer: ((JsonObject) -> Unit)? = null
    override suspend fun connect(timeoutMs: Long) { connectAction?.invoke(); connected = true }
    override suspend fun disconnect() { connected = false }
    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription {
        bus.add(listener); return ThalovantSubscription { bus.remove(listener) }
    }
    override fun addHiveMessageListener(listener: (JsonObject) -> Unit): ThalovantSubscription {
        frames.add(listener); return ThalovantSubscription { frames.remove(listener) }
    }
    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) { emitted.add(ThalovantEvent(eventType, data, context)); emitAction?.invoke(); busAnswer?.invoke(context) }
    override suspend fun sendHiveFrame(message: JsonObject) { sent.add(message); queryAction?.invoke(); queryAnswer?.invoke(message) }
    fun deliver(event: ThalovantEvent) { bus.forEach { it(event) } }
    fun reply(id: String, name: String, text: String = "", cascade: Boolean = false, session: String? = null) {
        val payload = hiveMessage("bus", buildJsonObject {
            put("type", name); put("data", buildJsonObject { put("utterance", text) }); put("context", contextWithCorrelation(EMPTY_JSON_OBJECT, sessionId = session))
        })
        val frame = hiveMessage(if (cascade) "cascade" else "query", payload, buildJsonObject { put("query_id", id) })
        frames.forEach { it(frame) }
    }
}

class RuntimeTest {
    private fun client(fake: RuntimeFake) = ThalovantClient(ThalovantIdentity(ThalovantJson.parseToJsonElement(
        """{"access_key":"access","password":"password","crypto_key":"0123456789abcdef","site_id":"site","default_master":"wss://hub.example"}"""
    ).jsonObject), transport = fake, replySettleMs = 0, emptyReplyWaitMs = 0)

    @Test fun `duplicate live Ask IDs cannot share replies`() = duplicateLiveId(false)
    @Test fun `duplicate live Query IDs cannot share replies`() = duplicateLiveId(true)
    private fun duplicateLiveId(query: Boolean) = runBlocking {
        supervisorScope {
            val fake = RuntimeFake(); val sdk = client(fake)
            fun start(prompt: String) = async {
                if (query) sdk.query(prompt, requestId = prompt, queryId = "shared")
                else sdk.ask(prompt, requestId = "shared")
            }
            val owner = start("owner")
            withTimeout(2000) { while (if (query) fake.sent.size != 1 else fake.emitted.size != 1) yield() }
            val duplicate = start("duplicate")
            withTimeout(2000) { while (!duplicate.isCompleted && (if (query) fake.frames.size != 2 else fake.bus.size != 2)) yield() }
            if (query) { fake.reply("shared", "speak", "only-owner"); fake.reply("shared", "hive.query.complete") }
            else fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "only-owner") },
                contextWithCorrelation(EMPTY_JSON_OBJECT, requestId = "shared")))
            assertEquals("only-owner", owner.await().text)
            assertFailsWith<ThalovantRuntimeException> { duplicate.await() }
            assertEquals(1, if (query) fake.sent.size else fake.emitted.size)
        }
    }

    @Test fun `correlation reservations end with collector cancellation`() = runBlocking {
        for (query in listOf(false, true)) supervisorScope {
            val fake = RuntimeFake(); val sdk = client(fake)
            fun start() = async { if (query) sdk.query("test", queryId = "shared") else sdk.ask("test", requestId = "shared") }
            val owner = start()
            withTimeout(2000) { while (if (query) fake.sent.size != 1 else fake.emitted.size != 1) yield() }
            owner.cancelAndJoin()
            assertEquals(0, if (query) fake.frames.size else fake.bus.size)
            // The fake has no pending remote replies; ordinary callers use fresh IDs.
            val next = start()
            withTimeout(2000) { while (if (query) fake.sent.size != 2 else fake.emitted.size != 2) yield() }
            if (query) { fake.reply("shared", "speak", "next-only"); fake.reply("shared", "hive.query.complete") }
            else fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "next-only") },
                contextWithCorrelation(EMPTY_JSON_OBJECT, requestId = "shared")))
            assertEquals("next-only", next.await().text)
        }
    }

    @Test fun `Ask Query and separate clients have independent ID namespaces`() = runBlocking {
        val fake = RuntimeFake(); val other = RuntimeFake(); val sdk = client(fake); val second = client(other)
        val ask = async { sdk.ask("ask", requestId = "shared") }
        val query = async { sdk.query("query", queryId = "shared") }
        val remote = async { second.ask("other", requestId = "shared") }
        withTimeout(2000) { while (fake.emitted.size != 1 || fake.sent.size != 1 || other.emitted.size != 1) yield() }
        val context = contextWithCorrelation(EMPTY_JSON_OBJECT, requestId = "shared")
        fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "ask-only") }, context))
        fake.reply("shared", "speak", "query-only"); fake.reply("shared", "hive.query.complete")
        other.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "other-only") }, context))
        assertEquals("ask-only", ask.await().text); assertEquals("query-only", query.await().text); assertEquals("other-only", remote.await().text)
    }

    @Test fun `ask budget includes connect send empty wait and settle`() = runBlocking {
        for (phase in listOf("connect", "send", "empty", "settle", "no_speech")) {
            val fake = RuntimeFake(); val sdk = client(fake)
            if (phase == "connect") fake.connectAction = { awaitCancellation() }
            if (phase == "send") fake.emitAction = { awaitCancellation() }
            fake.busAnswer = { context -> fake.deliver(ThalovantEvent(
                if (phase in listOf("empty", "no_speech")) ThalovantEvents.UTTERANCE_HANDLED else ThalovantEvents.SPEAK,
                buildJsonObject { put("utterance", "answer") }, context)) }
            withTimeout(1000) {
                if (phase == "settle") assertEquals("answer", sdk.ask("test", timeoutMs = 250, replySettleMs = 60000).text)
                else if (phase == "no_speech") assertFailsWith<ThalovantTimeoutException> { sdk.ask("test", timeoutMs = 60000, emptyReplyWaitMs = 0, replySettleMs = 60000) }
                else assertFailsWith<ThalovantTimeoutException> { sdk.ask("test", timeoutMs = 250, emptyReplyWaitMs = 60000) }
            }
            assertTrue(fake.bus.isEmpty())
        }
    }

    @Test fun `ask freezes hard failures and ignores all subsequent speech`() = runBlocking {
        for (partial in listOf(false, true)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            fake.busAnswer = { context ->
                if (partial) fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "partial") }, context))
                fake.deliver(ThalovantEvent(ThalovantEvents.POLICY_DENIED, context = context))
                fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "ignored") }, context))
            }
            withTimeout(1000) {
                if (partial) {
                    val reply = sdk.ask("test", timeoutMs = 500, replySettleMs = 60000)
                    assertEquals("partial", reply.text); assertFalse(reply.ok); assertEquals(2, reply.events.size)
                } else assertFailsWith<ThalovantRuntimeException> { sdk.ask("test", timeoutMs = 500, replySettleMs = 60000) }
            }
            assertTrue(fake.bus.isEmpty())
        }
    }

    @Test fun `ask returns speech or hard failure while an admitted send retires`() = runBlocking {
        for (hard in listOf(false, true)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val retired = CompletableDeferred<Unit>()
            fake.emitAction = {
                val context = fake.emitted.last().context
                fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "answer") }, context))
                if (hard) {
                    fake.deliver(ThalovantEvent(ThalovantEvents.POLICY_DENIED, context = context))
                    fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "ignored") }, context))
                }
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await(); retired.complete(Unit) } }
            }
            val request = async { sdk.ask("test", timeoutMs = if (hard) 60000 else 250, replySettleMs = 60000) }
            try {
                withTimeout(1000) { entered.await() }
                val reply = withTimeout(1000) { request.await() }
                assertEquals("answer", reply.text); assertEquals(!hard, reply.ok)
                assertFalse(retired.isCompleted); assertTrue(fake.bus.isEmpty())
            } finally { release.complete(Unit); request.cancel() }
            withTimeout(1000) { retired.await() }
        }
    }

    @Test fun `runtime IO cancellation does not enter the write failure callback`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val failures = CopyOnWriteArrayList<Exception>()
        val operation = launchRuntimeIo({ failures.add(it) }) { entered.complete(Unit); awaitCancellation() }
        withTimeout(1000) { entered.await() }
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled); assertTrue(failures.isEmpty())
    }

    @Test fun `cancelled send checks cancellation after waiting for the transport lock`() = runBlocking {
        val transport = HiveMindWssTransport(ThalovantIdentity(ThalovantJson.parseToJsonElement(
            """{"access_key":"fixture","password":"fixture","site_id":"fixture","default_master":"wss://hub.invalid"}"""
        ).jsonObject))
        val field = HiveMindWssTransport::class.java.getDeclaredField("sendLock").apply { isAccessible = true }
        val lock = field.get(transport)
        val held = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val owner = Thread { synchronized(lock) { held.countDown(); release.await() } }.apply { start() }
        assertTrue(held.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val entered = CompletableDeferred<Unit>(); val failure = CompletableDeferred<Throwable>()
        val pending = launch(Dispatchers.IO) {
            entered.complete(Unit)
            try { transport.sendHiveFrame(hiveMessage("bus", EMPTY_JSON_OBJECT)) }
            catch (error: Throwable) { failure.complete(error) }
        }
        try { entered.await(); pending.cancel() }
        finally { release.countDown(); owner.join(1000) }
        // Cancellation wins before even inspecting the absent Noise session or writing a frame.
        assertIs<CancellationException>(withTimeout(1000) { failure.await() })
        pending.join()
    }

    @Test fun `ask propagates write failures during reply phases but preserves a hard terminal reply`() = runBlocking {
        for (first in listOf(ThalovantEvents.SPEAK, ThalovantEvents.UTTERANCE_HANDLED, ThalovantEvents.INTENT_UNMATCHED)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            fake.emitAction = {
                val context = fake.emitted.last().context
                fake.deliver(ThalovantEvent(first, buildJsonObject { put("utterance", "answer") }, context))
                yield()
                throw ThalovantConnectionException("Fixture write failed.")
            }
            withTimeout(1000) {
                assertFailsWith<ThalovantConnectionException> { sdk.ask("test", timeoutMs = 60000, replySettleMs = 60000, emptyReplyWaitMs = 60000) }
            }
            assertTrue(fake.bus.isEmpty())
        }
        val fake = RuntimeFake(); val sdk = client(fake)
        fake.emitAction = {
            val context = fake.emitted.last().context
            fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "partial") }, context))
            fake.deliver(ThalovantEvent(ThalovantEvents.POLICY_DENIED, context = context))
            throw ThalovantConnectionException("Late fixture write failure.")
        }
        val reply = withTimeout(1000) { sdk.ask("test", replySettleMs = 60000) }
        assertFalse(reply.ok); assertEquals("partial", reply.text)
    }

    @Test fun `query returns terminal replies or expires while an admitted send retires`() = runBlocking {
        for (ending in listOf("complete", "hard", "timeout")) {
            val fake = RuntimeFake(); val sdk = client(fake)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val retired = CompletableDeferred<Unit>()
            fake.queryAction = {
                fake.reply("q", "speak", "answer")
                if (ending == "complete") fake.reply("q", "hive.query.complete")
                if (ending == "hard") fake.reply("q", ThalovantEvents.POLICY_DENIED)
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await(); retired.complete(Unit) } }
            }
            val request = async { runCatching { sdk.query("test", timeoutMs = if (ending == "timeout") 250 else 60000, queryId = "q") } }
            try {
                withTimeout(1000) { entered.await() }
                if (ending == "timeout") withTimeout(1000) { assertFailsWith<ThalovantTimeoutException> { request.await().getOrThrow() } }
                else {
                    val reply = withTimeout(1000) { request.await().getOrThrow() }
                    assertEquals("answer", reply.text); assertEquals(ending == "complete", reply.ok)
                }
                assertFalse(retired.isCompleted); assertTrue(fake.frames.isEmpty())
            } finally { release.complete(Unit); request.cancel() }
            withTimeout(1000) { retired.await() }
        }
    }

    @Test fun `query write failure wins before completion but cannot replace a terminal reply`() = runBlocking {
        for (terminal in listOf(false, true)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            fake.queryAction = {
                fake.reply("q", "speak", "answer")
                if (terminal) fake.reply("q", "hive.query.complete")
                throw ThalovantConnectionException("Fixture query write failure.")
            }
            if (terminal) assertEquals("answer", sdk.query("test", queryId = "q").text)
            else assertFailsWith<ThalovantConnectionException> { sdk.query("test", queryId = "q") }
            assertTrue(fake.frames.isEmpty())
        }
    }

    @Test fun `ask returns the first correlated runtime session replacement`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        fake.busAnswer = { context ->
            val id = context["request_id"]!!.jsonPrimitive.content
            fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "ignored") }, contextWithCorrelation(EMPTY_JSON_OBJECT, "foreign", requestId = "other")))
            fake.deliver(ThalovantEvent(ThalovantEvents.UTTERANCE_HANDLED, context = contextWithCorrelation(EMPTY_JSON_OBJECT, "runtime-first", requestId = id)))
            fake.deliver(ThalovantEvent("speak", buildJsonObject { put("utterance", "answer") }, contextWithCorrelation(EMPTY_JSON_OBJECT, "runtime-later", requestId = id)))
        }
        val reply = sdk.ask("test", sessionId = "requested", requestId = "r")
        assertEquals("runtime-first", reply.sessionId); assertEquals("r", reply.requestId); assertEquals("answer", reply.text)
        assertEquals(listOf("runtime-first", "runtime-later"), reply.events.map { it.sessionId })
    }

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

    @Test fun `query returns the first accepted runtime session replacement`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        fake.queryAnswer = {
            fake.reply("foreign", "speak", "ignored", session = "foreign")
            fake.reply("q", ThalovantEvents.UTTERANCE_HANDLED, session = "runtime-first")
            fake.reply("q", "speak", "answer", session = "runtime-later")
            fake.reply("q", "hive.query.complete", session = "runtime-final")
        }
        val reply = sdk.query("test", sessionId = "requested", requestId = "r", queryId = "q")
        assertEquals("runtime-first", reply.sessionId); assertEquals("r", reply.requestId)
        assertEquals("answer", reply.text); assertEquals(3, reply.events.size)
    }

    @Test fun `query soft misses allow later speech while hard failures retain partial speech`() = runBlocking {
        for (miss in listOf(ThalovantEvents.INTENT_UNMATCHED, ThalovantEvents.INTENT_FAILURE)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            fake.queryAnswer = { fake.reply("q", miss); fake.reply("q", "speak", "answer"); fake.reply("q", "hive.query.complete") }
            val reply = sdk.query("test", queryId = "q")
            assertEquals("answer", reply.text); assertTrue(reply.ok); assertNull(reply.failureEvent)
            assertEquals(listOf(miss, "speak", "hive.query.complete"), reply.events.map { it.name })
            fake.queryAnswer = { fake.reply("q", miss); fake.reply("q", "hive.query.complete") }
            assertFailsWith<ThalovantRuntimeException> { sdk.query("test", queryId = "q") }
        }
        for (hard in listOf(ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT)) {
            val fake = RuntimeFake(); val sdk = client(fake)
            fake.queryAnswer = { fake.reply("q", "speak", "partial"); fake.reply("q", hard); fake.reply("q", "speak", "ignored") }
            val reply = sdk.query("test", queryId = "q")
            assertEquals("partial", reply.text); assertFalse(reply.ok); assertEquals(hard, reply.failureEvent?.name)
        }
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
        val sent = CompletableDeferred<Unit>()
        fake.queryAction = { sent.complete(Unit) }
        val lost = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sdk.query("test") }.exceptionOrNull() }
        withTimeout(1000) { sent.await() }
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

    @Test fun `stream overflow is explicit and closes its subscription`() = runBlocking {
        val fake = RuntimeFake(); val sdk = client(fake)
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val consuming = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { sdk.listen("speak").collect { entered.complete(Unit); resume.await() } }.exceptionOrNull()
        }
        fake.deliver(ThalovantEvent("speak")); entered.await()
        repeat(65) { fake.deliver(ThalovantEvent("speak")) }
        resume.complete(Unit)
        assertIs<ThalovantRuntimeException>(withTimeout(1000) { consuming.await() })
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
