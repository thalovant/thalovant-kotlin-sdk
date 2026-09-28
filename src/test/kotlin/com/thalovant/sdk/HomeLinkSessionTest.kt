package com.thalovant.sdk

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Home Assistant link kept by a [HubSession]: answered over a real hub
 * connection, never behind a running turn, and kept through drops and
 * refusals by policy.
 */
class HomeLinkSessionTest {

    private val fast = HubSessionPolicy(
        retrySeconds = 0.05, retryCeilingSeconds = 0.2, probeSeconds = 0.05,
        probeDownSeconds = 0.05, refusalGraceSeconds = 0.4,
    )

    private suspend fun eventually(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) { while (!predicate()) delay(10) }
    }

    private val request = buildJsonObject {
        put("request_id", "r1")
        put("utterance", "turn off the kitchen light")
        put("lang", "en-US")
    }

    private val route = buildJsonObject {
        put("source", "thalovant-skill-home")
        put("destination", JsonArray(listOf(JsonPrimitive("ha-peer"))))
        put("session", buildJsonObject { put("session_id", "kitchen") })
    }

    @Test
    fun `home requests are answered back along their route over a real hub connection`(): Unit = runBlocking {
        FakeHub().use { hub ->
            val session = HubSession(connect = { hub.connectedClient() }, policy = fast, warm = false)
            val heard = CopyOnWriteArrayList<HomeRequest>()
            val link = launch(Dispatchers.Default) {
                session.answerHomeRequests { request ->
                    heard += request
                    HomeAnswer(speech = "<speak>Turned off the kitchen light.</speak>", responseType = ThalovantHome.ACTION_DONE)
                }
            }
            try {
                session.connect()
                assertTrue(session.connected.value)
                eventually { hub.sessions.isNotEmpty() }
                val peer = hub.sessions.single()
                peer.sendBus(ThalovantHome.REQUEST, request, route)
                val answer = withContext(Dispatchers.IO) { peer.awaitBus(ThalovantHome.RESPONSE) }
                assertNotNull(answer, "the hub never got its answer")
                assertEquals(listOf("turn off the kitchen light"), heard.map { it.utterance })
                assertEquals(
                    buildJsonObject {
                        put("request_id", "r1")
                        put("speech", "Turned off the kitchen light.")
                        put("response_type", "action_done")
                        put("continue_conversation", false)
                    },
                    answer["data"],
                )
                val context = answer.getValue("context").jsonObject
                assertEquals("thalovant-skill-home", context["destination"]?.jsonPrimitive?.content)
                assertEquals("ha-peer", context["source"]?.jsonPrimitive?.content)
                assertEquals("kitchen", context["session"]?.jsonObject?.get("session_id")?.jsonPrimitive?.content)
            } finally {
                link.cancelAndJoin()
                session.close()
            }
            assertFalse(session.connected.value)
        }
    }

    @Test
    fun `a home request is answered while an ask is still waiting for its own reply`(): Unit = runBlocking {
        // The trap this guards: HubSession holds its lock for as long as an ask
        // waits -- seconds -- and the hub times a home request out after ten.
        FakeHub().use { hub ->
            val session = HubSession(connect = { hub.connectedClient() }, policy = fast, warm = false)
            val link = launch(Dispatchers.Default) {
                session.answerHomeRequests { HomeAnswer(speech = "Done.") }
            }
            try {
                session.connect()
                eventually { hub.sessions.isNotEmpty() }
                val peer = hub.sessions.single()
                // The hub never answers this ask; it stays in flight.
                val ask = async(Dispatchers.Default) { runCatching { session.ask("what time is it", timeoutMs = 20_000) } }
                assertNotNull(withContext(Dispatchers.IO) { peer.awaitBus(ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE) })
                val started = System.nanoTime()
                peer.sendBus(ThalovantHome.REQUEST, request, route)
                val answer = withContext(Dispatchers.IO) { peer.awaitBus(ThalovantHome.RESPONSE, timeoutMs = 3_000) }
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertNotNull(answer, "the answer waited behind the ask")
                assertTrue(ask.isActive, "the ask must still be waiting when the answer goes out")
                assertTrue(tookMs < 3_000, "answered in $tookMs ms")
                ask.cancel()
            } finally {
                link.cancelAndJoin()
                session.close()
            }
        }
    }

    @Test
    fun `run keeps the link, notices a drop as it happens, and subscriptions follow`(): Unit = runBlocking {
        FakeHub().use { hub ->
            val session = HubSession(
                connect = { hub.connectedClient() },
                policy = fast.copy(probeSeconds = 60.0),
                warm = false,
            )
            val seen = CopyOnWriteArrayList<String>()
            val subscription = session.on("hub.says") { seen += it.data["word"]!!.jsonPrimitive.content }
            val runner = launch(Dispatchers.Default) { session.run() }
            try {
                eventually { session.connected.value && hub.sessions.isNotEmpty() }
                hub.sessions.single().sendBus("hub.says", buildJsonObject { put("word", "one") })
                eventually { seen == listOf("one") }
                hub.dropAll()
                // A 60 s probe would not see this in time: the drop itself wakes run().
                eventually(3_000) { hub.attempts.get() == 2 && session.connected.value && hub.sessions.isNotEmpty() }
                hub.sessions.single().sendBus("hub.says", buildJsonObject { put("word", "two") })
                eventually { seen == listOf("one", "two") }
                subscription.close()
                hub.sessions.single().sendBus("hub.says", buildJsonObject { put("word", "three") })
                delay(100)
                assertEquals(listOf("one", "two"), seen.toList())
            } finally {
                session.close()
                withTimeout(2_000) { runner.join() }
            }
        }
    }

    @Test
    fun `a hub that closes right after the handshake is a refusal, and one that clears connects`(): Unit = runBlocking {
        FakeHub().use { hub ->
            hub.refuseNext.set(2)
            val session = HubSession(connect = { hub.connectedClient() }, policy = fast.copy(refusalGraceSeconds = 30.0), warm = false)
            try {
                assertFailsWith<ThalovantIdentityException> { session.connect() }
                assertFalse(session.held)
                val runner = launch(Dispatchers.Default) { session.run() }
                eventually { session.connected.value }
                assertEquals(3, hub.attempts.get())
                session.close()
                withTimeout(2_000) { runner.join() }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `a close with another code right after the handshake is a drop, not a refusal`(): Unit = runBlocking {
        FakeHub().use { hub ->
            hub.refuseNext.set(1)
            hub.refusalCode = 1011
            val session = HubSession(connect = { hub.connectedClient() }, policy = fast, warm = false)
            try {
                // A connection failure, which ThalovantIdentityException is not: retried, never "pair again".
                val failure = assertFailsWith<ThalovantConnectionException> { session.connect() }
                assertTrue("1011" in failure.message.orEmpty())
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `an upgrade refused with 403 is a refusal of the credentials`(): Unit = runBlocking {
        FakeHub().use { hub ->
            hub.upgradeStatus = 403
            val failure = assertFailsWith<ThalovantIdentityException> { hub.connectedClient() }
            assertTrue("HTTP 403" in failure.message.orEmpty())
            hub.upgradeStatus = 502
            assertFailsWith<ThalovantConnectionException> { hub.connectedClient() }
        }
    }

    // -- the policy, driven without a network --------------------------------

    @Test
    fun `run retries refusals through the grace, then throws the refusal`(): Unit = runBlocking {
        var now = 0.0
        var attempts = 0
        val session = HubSession(
            connect = { attempts++; now += 0.1; throw ThalovantIdentityException("not admitted yet") },
            policy = HubSessionPolicy(retrySeconds = 0.001, retryCeilingSeconds = 0.002, refusalGraceSeconds = 0.35),
            clock = { now },
            warm = false,
        )
        val refusal = assertFailsWith<ThalovantIdentityException> { withTimeout(5_000) { session.run() } }
        assertEquals("not admitted yet", refusal.message)
        // Refused from 0.1 s on; the attempt at 0.5 s is the first past 0.35 s of it.
        assertEquals(5, attempts)
        session.close()
    }

    @Test
    fun `a connection failure between refusals restarts the grace`(): Unit = runBlocking {
        var now = 0.0
        var attempts = 0
        val session = HubSession(
            connect = {
                attempts++
                now += 0.1
                if (attempts == 3) throw ThalovantConnectionException("offline")
                throw ThalovantIdentityException("not admitted yet")
            },
            policy = HubSessionPolicy(retrySeconds = 0.001, retryCeilingSeconds = 0.002, refusalGraceSeconds = 0.35),
            clock = { now },
            warm = false,
        )
        assertFailsWith<ThalovantIdentityException> { withTimeout(5_000) { session.run() } }
        // Refused at 0.1 and 0.2, offline at 0.3, refused again from 0.4 until 0.8.
        assertEquals(8, attempts)
        session.close()
    }

    @Test
    fun `a hub whose key changed is thrown at once, and so is anything that is not a connection failure`(): Unit = runBlocking {
        var attempts = 0
        val changed = HubSession(connect = { attempts++; throw ThalovantHubIdentityChangedException("rebuilt") }, policy = fast, warm = false)
        assertFailsWith<ThalovantHubIdentityChangedException> { withTimeout(5_000) { changed.run() } }
        assertEquals(1, attempts)
        changed.close()
        val broken = HubSession(connect = { throw IllegalArgumentException("identity file is missing access_key") }, policy = fast, warm = false)
        assertFailsWith<IllegalArgumentException> { withTimeout(5_000) { broken.run() } }
        broken.close()
    }

    @Test
    fun `close stops run and a closed session refuses a reply`(): Unit = runBlocking {
        val session = HubSession(connect = { throw ThalovantConnectionException("offline") }, policy = fast, warm = false)
        val runner = launch(Dispatchers.Default) { session.run() }
        delay(100)
        session.close()
        withTimeout(2_000) { runner.join() }
        assertFailsWith<IllegalStateException> { session.reply(ThalovantEvent(ThalovantHome.REQUEST), ThalovantHome.RESPONSE) }
    }

    @Test
    fun `the link state follows the connection`(): Unit = runBlocking {
        val outbox = Outbox()
        val session = HubSession(connect = { outbox.client().also { it.connect() } }, policy = fast, warm = false)
        assertFalse(session.connected.value)
        session.connect()
        assertTrue(session.connected.value)
        outbox.offline = true
        outbox.connected = false
        session.probe()
        withTimeout(2_000) { session.connected.first { !it } }
        session.close()
    }

    @Test
    fun `a handler that ignores cancellation neither holds the answer back nor the call`(): Unit = runBlocking {
        val outbox = Outbox()
        val client = outbox.client()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val started = System.nanoTime()
        // The call returns at the deadline, not when the handler chooses to stop.
        val sent = withTimeout(2_000) {
            client.answerHomeRequest(ThalovantEvent(ThalovantHome.REQUEST, request), timeoutMs = 100) {
                try {
                    // Blocks its thread and never looks at cancellation.
                    withContext(Dispatchers.IO) { while (!release.isCompleted) Thread.sleep(5) }
                } finally {
                    finished.complete(Unit)
                }
                HomeAnswer(speech = "too late")
            }
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 1_500, "answered after $tookMs ms")
        assertFalse(finished.isCompleted, "the handler was still running")
        assertEquals(ThalovantHome.TIMEOUT, sent?.get("error_code")?.jsonPrimitive?.content)
        assertEquals(ThalovantHome.TIMEOUT, outbox.emitted.single().second["error_code"]?.jsonPrimitive?.content)
        release.complete(Unit)
        withTimeout(2_000) { finished.await() }
        delay(50)
        assertEquals(1, outbox.emitted.size, "its late answer is dropped")
    }

    @Test
    fun `a reply withdrawn while queued is not sent and leaves the link up`(): Unit = runBlocking {
        FakeHub().use { hub ->
            val client = hub.connectedClient()
            try {
                eventually { hub.sessions.isNotEmpty() }
                val peer = hub.sessions.single()
                val transport = client.transport as HiveMindWssTransport
                // Another frame is being written.
                transport.sendQueue.lock()
                val sent = client.answerHomeRequest(
                    ThalovantEvent(ThalovantHome.REQUEST, request, route), hubTimeoutMs = 200,
                ) { HomeAnswer(speech = "Done.") }
                assertEquals(null, sent, "withdrawn at the bound")
                transport.sendQueue.unlock()
                // Never sent late, and the same link carries the next frame.
                client.emit("still.there")
                val next = withContext(Dispatchers.IO) { peer.awaitBus("still.there") }
                assertNotNull(next)
                assertTrue(alive(client))
                assertEquals(1, hub.attempts.get(), "the same link all along")
                assertTrue(peer.received.none { it["payload"]?.jsonObject?.get("type")?.jsonPrimitive?.content == ThalovantHome.RESPONSE })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `a connection a withdrawn reply had to make stays the session's`(): Unit = runBlocking {
        val outbox = Outbox()
        var builds = 0
        val session = HubSession(
            connect = { builds++; delay(300); outbox.client().also { it.connect() } },
            policy = fast,
            warm = false,
        )
        try {
            val sent = session.answerHomeRequest(ThalovantEvent(ThalovantHome.REQUEST, request), hubTimeoutMs = 100) {
                HomeAnswer(speech = "Done.")
            }
            assertEquals(null, sent)
            // The connect it started finishes on the session and is kept.
            withTimeout(2_000) { session.connected.first { it } }
            assertEquals(1, builds)
            assertTrue(outbox.emitted.isEmpty(), "nothing sent late")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a served request is answered within the hub's bound from its arrival, or not at all`(): Unit = runBlocking {
        val outbox = Outbox()
        val client = outbox.client()
        val delivered = CompletableDeferred<(ThalovantEvent) -> Unit>()
        val link = launch(Dispatchers.Default) {
            serveHomeRequests(
                subscribe = { listener -> delivered.complete(listener); ThalovantSubscription {} },
                timeoutMs = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
                hubTimeoutMs = 300,
                handler = { request -> if (request.requestId == "slow") delay(200); HomeAnswer(speech = "Done.") },
            ) { event, payload ->
                // A transport stuck behind other frames for the slow one.
                if (payload["request_id"]?.jsonPrimitive?.content == "slow") delay(200)
                client.reply(event, ThalovantHome.RESPONSE, payload)
            }
        }
        try {
            val deliver = withTimeout(2_000) { delivered.await() }
            deliver(ThalovantEvent(ThalovantHome.REQUEST, buildJsonObject { put("request_id", "slow") }))
            deliver(ThalovantEvent(ThalovantHome.REQUEST, buildJsonObject { put("request_id", "quick") }))
            eventually { outbox.emitted.isNotEmpty() }
            delay(500)
            // 200 ms of handler and 200 ms of sending do not fit in 300: withdrawn, never sent late.
            assertEquals(listOf("quick"), outbox.emitted.map { it.second["request_id"]?.jsonPrimitive?.content })
        } finally {
            link.cancelAndJoin()
        }
    }

    @Test
    fun `a handler that cancels itself is a failure to handle, and a caller's cancellation is not answered`(): Unit = runBlocking {
        val outbox = Outbox()
        val client = outbox.client()
        val payload = client.answerHomeRequest(ThalovantEvent(ThalovantHome.REQUEST, request)) {
            throw kotlinx.coroutines.CancellationException("gave up")
        }
        assertEquals(ThalovantHome.FAILED_TO_HANDLE, payload?.get("error_code")?.jsonPrimitive?.content)
        outbox.emitted.clear()
        val answering = launch { client.answerHomeRequest(ThalovantEvent(ThalovantHome.REQUEST, request)) { delay(60_000); null } }
        delay(50)
        answering.cancelAndJoin()
        assertTrue(outbox.emitted.isEmpty())
    }

    @Test
    fun `a handler still at TODO is answered, and the link answers the next request`(): Unit = runBlocking {
        val outbox = Outbox()
        val client = outbox.client()
        val delivered = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
        var calls = 0
        val link = launch(Dispatchers.Default) {
            serveHomeRequests(
                subscribe = { listener -> delivered += listener; ThalovantSubscription { delivered.remove(listener) } },
                timeoutMs = 1_000,
                handler = { if (++calls == 1) TODO("not wired to Assist yet") else HomeAnswer(speech = "Done.") },
            ) { event, payload -> client.reply(event, ThalovantHome.RESPONSE, payload) }
        }
        try {
            eventually { delivered.isNotEmpty() }
            delivered.single()(ThalovantEvent(ThalovantHome.REQUEST, request))
            eventually { outbox.emitted.size == 1 }
            assertEquals(ThalovantHome.FAILED_TO_HANDLE, outbox.emitted[0].second["error_code"]?.jsonPrimitive?.content)
            delivered.single()(ThalovantEvent(ThalovantHome.REQUEST, request))
            eventually { outbox.emitted.size == 2 }
            assertEquals("Done.", outbox.emitted[1].second["speech"]?.jsonPrimitive?.content)
        } finally {
            link.cancelAndJoin()
        }
        assertTrue(delivered.isEmpty(), "cancelling the link unsubscribes it")
    }

    @Test
    fun `a null answer is unknown and conversation ids are echoed or replaced`() {
        val asked = HomeRequest("r9", "is it raining", "en-US", conversationId = "conv-1")
        val unknown = homeResponse(asked, null)
        assertEquals("error", unknown["response_type"]?.jsonPrimitive?.content)
        assertEquals("unknown", unknown["error_code"]?.jsonPrimitive?.content)
        assertEquals("conv-1", unknown["conversation_id"]?.jsonPrimitive?.content)
        val replaced = homeResponse(asked, HomeAnswer(speech = "No.", responseType = "query_answer", conversationId = "conv-2"))
        assertEquals("conv-2", replaced["conversation_id"]?.jsonPrimitive?.content)
        // An error code only ever rides with an error.
        val done = homeResponse(asked, HomeAnswer(speech = "Done.", errorCode = "timeout"))
        assertFalse("error_code" in done)
    }

    @Test
    fun `speech keeps to the portable rules beyond the vectors`() {
        assertEquals("It is 21 degrees & rising.", plainSpeech("<speak>It is 21&nbsp;degrees\n  &amp; rising.</speak>"))
        // Only the portable set: a named reference HTML alone knows stays as written.
        assertEquals("Il fait 21 &deg;C \u00E0 Paris", plainSpeech("Il fait 21&#160;&deg;C &#224; Paris"))
        assertEquals("fish &amp chips", plainSpeech("fish &amp chips"))
        // Numeric references are code points, never Windows-1252.
        assertEquals("\u0080 is \u0080", plainSpeech("&#x80; is &#128;"))
        assertEquals("\uD83D\uDE00", plainSpeech("&#128512;"))
        assertEquals("", plainSpeech(null))
        assertEquals("", plainSpeech(" <break time=\"1s\"/> "))
        // A long attribute is scanned, not recursed through.
        assertEquals("Ding", plainSpeech("<audio src='" + "a".repeat(200_000) + "'>Ding</audio>"))
        // The documented rule, not a pattern's: a tag runs from `<` and a letter to
        // the next `>` outside quotes, whatever lies between.
        assertEquals("x", plainSpeech("<a,b>x"))
        assertEquals("c", plainSpeech("<a <b>c"))
        assertEquals("xz", plainSpeech("x<a \"q>\" y>z"))
        assertEquals("<a \"unclosed>t", plainSpeech("<a \"unclosed>t"))
        // Linear on text built to make a scan per `<`, or a backtracking pattern, crawl.
        val started = System.nanoTime()
        for (evil in listOf("<a" + " ".repeat(200_000), "<a".repeat(100_000), "<!--".repeat(50_000), "<?".repeat(100_000), "<a '".repeat(50_000))) {
            plainSpeech(evil)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "not linear")
        // U+001C..U+001F are not White_Space: kept, at the ends too.
        assertEquals("\u001Ca\u001Cb\u001F", plainSpeech(" \u001Ca\u001Cb\u001F\u3000"))
    }

    @Test
    fun `a reply turns the route round and keeps everything else`(): Unit = runBlocking {
        val outbox = Outbox()
        val client = outbox.client()
        val event = ThalovantEvent("mycroft.volume.get", context = route)
        client.reply(event, "mycroft.volume.get.response", buildJsonObject { put("percent", 0.4) }, buildJsonObject { put("extra", 1) })
        val (type, data, context) = outbox.emitted.single()
        assertEquals("mycroft.volume.get.response", type)
        assertEquals(0.4, data["percent"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("ha-peer", context["source"]?.jsonPrimitive?.content)
        assertEquals("thalovant-skill-home", context["destination"]?.jsonPrimitive?.content)
        assertEquals("1", context["extra"]?.jsonPrimitive?.content)
        assertIs<JsonObject>(context["session"])
        assertFailsWith<IllegalArgumentException> { client.reply(event, " ") }
    }

    /** A transport that keeps what the client sent. */
    private class Outbox : HiveMindRuntimeTransport {
        @Volatile override var connected: Boolean = false
        override val handshakeComplete: Boolean get() = connected
        @Volatile var offline: Boolean = false
        val emitted = CopyOnWriteArrayList<Triple<String, JsonObject, JsonObject>>()
        override suspend fun connect(timeoutMs: Long) {
            if (offline) throw ThalovantConnectionException("offline")
            connected = true
        }
        override suspend fun disconnect() { connected = false }
        override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
            emitted += Triple(eventType, data, context)
        }
        override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription = ThalovantSubscription {}

        fun client(): ThalovantClient = ThalovantClient(
            ThalovantIdentity(
                buildJsonObject {
                    put("access_key", "access")
                    put("password", "password")
                    put("site_id", "ha")
                    put("default_master", "wss://hub.example")
                },
            ),
            transport = this,
        )
    }
}
