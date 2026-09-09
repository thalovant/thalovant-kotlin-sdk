package com.thalovant.sdk

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import okio.ByteString
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val CRYPTO_KEY = "0123456789abcdef"

class TransportTest {
    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<String>()
    private val serverSocket = AtomicReference<WebSocket>()
    private lateinit var stateDir: java.nio.file.Path
    private lateinit var serverSession: NoiseSession
    private val serverKey = ByteArray(32) { (it + 1).toByte() }
    private val helloPayload = buildJsonObject { put("node_id", "kotlin-test-hub"); put("pubkey", "test"); put("peer", "peer") }
    private val offer = ThalovantJson.parseToJsonElement("""{"max_protocol_version":3,"binarize":true,"encodings":["JSON-HEX"],"ciphers":["AES-GCM"],"noise":{"patterns":["XXpsk2"],"suites":["25519_ChaChaPoly_SHA256","25519_AESGCM_SHA256"]}}""").jsonObject
    private val psk by lazy { Noise.derivePsk("secret", "kotlin-test-hub") }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        stateDir = Files.createTempDirectory("kotlin-noise-test")
        received.clear()
        serverSocket.set(null)
    }

    @AfterTest
    fun tearDown() {
        serverSocket.get()?.close(1000, null)
        server.shutdown()
        stateDir.toFile().deleteRecursively()
    }

    private fun startHub(hold: java.util.concurrent.CountDownLatch? = null) {
        repeat(2) { server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    private var exchange: NoiseHandshake? = null
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        check(hold?.await(5, TimeUnit.SECONDS) != false)
                        webSocket.send(hiveMessage("hello", helloPayload).toString())
                        webSocket.send(hiveMessage("shake", offer).toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val params = ThalovantJson.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject["noise"]!!.jsonObject
                        if (exchange == null) {
                            val pattern = params["pattern"]!!.jsonPrimitive.content
                            val suite = params["suite"]!!.jsonPrimitive.content
                            exchange = NoiseHandshake(pattern, suite, psk, Noise.prologue(helloPayload, offer, "Noise_${pattern}_$suite"), serverKey, initiator = false)
                        }
                        val state = exchange!!
                        state.read(Noise.unhex(params["msg"]!!.jsonPrimitive.content))
                        if (!state.finished) webSocket.send(hiveMessage("shake", buildJsonObject { put("noise", buildJsonObject { put("msg", Noise.hex(state.write())) }) }).toString())
                        if (state.finished) serverSession = state.session()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val decoded = serverSession.decrypt(bytes.toByteArray()) ?: return
                        check(decoded.second)
                        received.add(decoded.first.toString(Charsets.UTF_8))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        ) }
        server.start()
    }

    private fun identity(): ThalovantIdentity = ThalovantIdentity(
        buildJsonObject {
            put("access_key", "access")
            put("password", "secret")
            put("crypto_key", CRYPTO_KEY)
            put("site_id", "site")
            put("default_master", "ws://${server.hostName}:${server.port}")
        },
    )

    private fun awaitMessage(): String {
        val message = received.poll(5, TimeUnit.SECONDS)
        assertNotNull(message, "expected a message from the SDK within 5s")
        return message
    }

    private fun sendBus(type: String, data: JsonObject, context: JsonObject) {
        val socket = serverSocket.get()
        assertNotNull(socket)
        val message = hiveMessage("bus", buildJsonObject { put("type", type); put("data", data); put("context", context) })
        for (frame in serverSession.encrypt(message.toString().toByteArray())) socket.send(ByteString.of(*frame))
    }

    @Test
    fun `authenticated callbacks release send lock and isolate listener failures`() = runBlocking {
        startHub()
        val transport = HiveMindWssTransport(identity(), noiseStore = HiveMindNoiseStore(stateDir))
        val observed = LinkedBlockingQueue<Boolean>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        transport.addBusListener { throw IllegalStateException("Application callback failed") }
        transport.addHiveMessageListener { throw IllegalStateException("Application frame callback failed") }
        transport.addHiveMessageListener {
            val result = executor.submit<Boolean> {
                runBlocking { transport.emitBus("callback.reply", EMPTY_JSON_OBJECT, EMPTY_JSON_OBJECT) }; true
            }
            observed.add(runCatching { result.get(2, TimeUnit.SECONDS) }.getOrDefault(false))
        }
        try {
            transport.connect()
            awaitMessage() // authenticated client hello
            sendBus("fixture", EMPTY_JSON_OBJECT, EMPTY_JSON_OBJECT)
            assertEquals(true, observed.poll(5, TimeUnit.SECONDS), "A callback must allow another thread to send")
            assertTrue(transport.connected && transport.handshakeComplete)
            assertEquals("callback.reply", ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        } finally { transport.disconnect(); executor.shutdownNow() }
    }

    @Test
    fun `queued connect deadline and cancellation never cancel the active socket`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        startHub(release)
        val transport = HiveMindWssTransport(identity(), noiseStore = HiveMindNoiseStore(stateDir))
        val owner = async(Dispatchers.Default) { transport.connect(5000) }
        try {
            kotlinx.coroutines.withTimeout(2000) { while (serverSocket.get() == null) kotlinx.coroutines.delay(5) }
            val elapsed = measureTimeMillis {
                assertFailsWith<ThalovantConnectionException> { transport.connect(50) }
            }
            assertTrue(elapsed < 1000, "queued deadline must include admission wait")
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                kotlinx.coroutines.withTimeout(50) { transport.connect(5000) }
            }
            val cancelled = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { transport.connect(5000) }
            cancelled.cancel()
            assertFailsWith<kotlinx.coroutines.CancellationException> { cancelled.await() }
            assertTrue(!owner.isCompleted)
            release.countDown()
            owner.await()
            assertTrue(transport.connected && transport.handshakeComplete)
            assertEquals(1, server.requestCount)
        } finally { release.countDown(); transport.disconnect(); owner.cancel() }
    }

    @Test
    fun `connect performs Noise authentication and sends an encrypted hello`() = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 10)
        client.connect(5000)
        try {
            val hello = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            assertEquals("hello", hello["msg_type"]?.jsonPrimitive?.content)
            val payload = hello["payload"]?.jsonObject
            assertNotNull(payload)
            assertEquals("", payload["pubkey"]?.jsonPrimitive?.content)
            assertEquals("site", payload["site_id"]?.jsonPrimitive?.content)
            val sessionId = payload["session"]?.jsonObject?.get("session_id")?.jsonPrimitive?.content
            assertNotNull(sessionId)
            assertTrue(sessionId.startsWith("thalovant-kotlin-"))
            assertEquals(JsonArray(emptyList()), hello["route"])
            assertEquals(JsonNull, hello["node"])
            assertEquals(JsonNull, hello["target_site_id"])
            assertEquals(JsonNull, hello["target_pubkey"])
            assertEquals(JsonNull, hello["source_peer"])

            // The upgrade request authenticates with base64("<userAgent>:<accessKey>").
            val upgrade = server.takeRequest()
            val expected = Base64.getEncoder()
                .encodeToString("$DEFAULT_USER_AGENT:access".toByteArray())
            assertEquals(expected, upgrade.requestUrl?.queryParameter("authorization"))
            assertEquals(DEFAULT_USER_AGENT, upgrade.getHeader("User-Agent"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `ask sends an encrypted recognizer utterance and aggregates speak replies`(): Unit = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 250)
        client.connect(5000)
        try {
            awaitMessage() // hello

            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what time is it?", sessionId = "sess-1", requestId = "req-1") }
            }

            // awaitMessage decrypts the peer's authenticated binary Noise frame.
            val bus = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            assertEquals("bus", bus["msg_type"]?.jsonPrimitive?.content)
            val payload = bus["payload"]?.jsonObject
            assertNotNull(payload)
            assertEquals("recognizer_loop:utterance", payload["type"]?.jsonPrimitive?.content)
            val data = payload["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(
                listOf("what time is it?"),
                data["utterances"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals("en-us", data["lang"]?.jsonPrimitive?.content)
            val context = payload["context"]?.jsonObject
            assertNotNull(context)
            assertEquals("req-1", context["request_id"]?.jsonPrimitive?.content)
            assertEquals("req-1", context["thalovant_request_id"]?.jsonPrimitive?.content)
            val session = context["session"]?.jsonObject
            assertNotNull(session)
            assertEquals("sess-1", session["session_id"]?.jsonPrimitive?.content)
            assertEquals("site", session["site_id"]?.jsonPrimitive?.content)
            assertEquals("en-us", session["lang"]?.jsonPrimitive?.content)
            assertEquals("req-1", session["request_id"]?.jsonPrimitive?.content)

            // The hub replies over the bus with the same context correlation.
            sendBus("speak", buildJsonObject { put("utterance", "It is noon.") }, context)
            sendBus("ovos.utterance.handled", EMPTY_JSON_OBJECT, context)

            val result = reply.await().getOrThrow()
            assertEquals("It is noon.", result.text)
            assertTrue(result.ok)
            assertTrue(result.handled)
            assertEquals("req-1", result.requestId)
            assertEquals("sess-1", result.sessionId)
            assertEquals(listOf("speak", "ovos.utterance.handled"), result.events.map { it.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun `ask ignores replies without matching correlation`(): Unit = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 250)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", sessionId = "sess-1", requestId = "req-1") }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val bus = ThalovantJson.parseToJsonElement(
                envelope.toString(),
            ).jsonObject
            val context = bus["payload"]?.jsonObject?.get("context")?.jsonObject
            assertNotNull(context)

            sendBus(
                "speak",
                buildJsonObject { put("utterance", "Wrong session") },
                buildJsonObject { put("request_id", "other") },
            )
            sendBus("speak", buildJsonObject { put("utterance", "Missing context") }, EMPTY_JSON_OBJECT)
            sendBus("speak", buildJsonObject { put("utterance", "Right reply") }, context)
            sendBus("ovos.utterance.handled", EMPTY_JSON_OBJECT, context)

            val result = reply.await().getOrThrow()
            assertEquals("Right reply", result.text)
            assertEquals(2, result.events.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun `ask surfaces hive failures as runtime errors`(): Unit = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 0)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", requestId = "req-1") }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val context = ThalovantJson.parseToJsonElement(
                envelope.toString(),
            ).jsonObject["payload"]?.jsonObject?.get("context")?.jsonObject
            assertNotNull(context)

            sendBus(
                "hive.query.timeout",
                buildJsonObject { put("utterance", "No answer before HiveMind timed out") },
                context,
            )

            val error = reply.await().exceptionOrNull()
            assertIs<ThalovantRuntimeException>(error)
            assertTrue("No answer before HiveMind timed out" in error.message.orEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `intent miss with no fallback surfaces the failure after the empty-reply wait`(): Unit =
        assertIntentMissSurfacesWithoutFallback("ovos.intent.unmatched")

    @Test
    fun `legacy intent miss with no fallback surfaces the failure`(): Unit =
        assertIntentMissSurfacesWithoutFallback("complete_intent_failure")

    /**
     * An intent miss is a SOFT failure: it must not terminate the loop before the
     * empty-reply wait (a fallback skill may still answer). With no fallback, the
     * miss is surfaced as a [ThalovantRuntimeException] once the (here short)
     * empty-reply wait elapses -- bounded by emptyReplyWait, not the full timeout.
     */
    private fun assertIntentMissSurfacesWithoutFallback(eventName: String) = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 0)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", requestId = "req-1", emptyReplyWaitMs = 200) }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val context = ThalovantJson.parseToJsonElement(
                envelope.toString(),
            ).jsonObject["payload"]?.jsonObject?.get("context")?.jsonObject
            assertNotNull(context)

            sendBus(eventName, buildJsonObject { put("utterance", "Sorry, I did not understand.") }, context)

            val result = reply.await()
            val error = result.exceptionOrNull()
            assertIs<ThalovantRuntimeException>(error, "an unrecovered intent miss must surface as a failure")
            assertTrue("Sorry, I did not understand." in error.message.orEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `intent miss is recovered by a fallback reply`() = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), noiseStore = HiveMindNoiseStore(stateDir), protocol = HubProtocol.WSS, replySettleMs = 0)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", requestId = "req-1", emptyReplyWaitMs = 5000) }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val context = ThalovantJson.parseToJsonElement(
                envelope.toString(),
            ).jsonObject["payload"]?.jsonObject?.get("context")?.jsonObject
            assertNotNull(context)

            // Intent miss, then a fallback skill answers.
            sendBus("ovos.intent.unmatched", buildJsonObject { put("utterance", "no match") }, context)
            sendBus("speak", buildJsonObject { put("utterance", "Here is a fallback answer.") }, context)
            sendBus("ovos.utterance.handled", buildJsonObject { }, context)

            val result = reply.await()
            val value = result.getOrThrow()
            assertTrue(value.ok, "a fallback reply recovers the turn")
            assertEquals("Here is a fallback answer.", value.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `same client reconnect clears cipher counters and preserves static identity`() = runBlocking {
        startHub()
        val store = HiveMindNoiseStore(stateDir)
        val client = ThalovantClient(identity(), noiseStore = store)
        client.connect(5000)
        awaitMessage()
        val originalKey = store.staticKey()
        client.close()
        client.connect(5000)
        try {
            assertEquals("hello", ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject["msg_type"]!!.jsonPrimitive.content)
            assertTrue(originalKey.contentEquals(store.staticKey()))
            client.emit("test.reconnect")
            assertEquals("bus", ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject["msg_type"]!!.jsonPrimitive.content)
        } finally { client.close() }
    }

    @Test
    fun `legacy offers cannot mark transport ready`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket.set(webSocket)
                webSocket.send("""{"msg_type":"shake","payload":{"preshared_key":true}}""")
            }
        }))
        server.start()
        val transport = HiveMindWssTransport(identity(), noiseStore = HiveMindNoiseStore(stateDir))
        assertFailsWith<ThalovantConnectionException> { transport.connect(5000) }
        assertTrue(!transport.connected && !transport.handshakeComplete)
        transport.disconnect()
    }

    @Test
    fun `client rejects unsupported protocols in this release`() {
        server.start()
        val identity = identity()
        assertFailsWith<ThalovantUnsupportedProtocolException> {
            ThalovantClient(identity, protocol = HubProtocol.MQTT)
        }
        assertFailsWith<ThalovantUnsupportedProtocolException> {
            ThalovantClient(identity, protocol = HubProtocol.HTTPS)
        }
    }

    @Test
    fun `client requires a WSS endpoint`() {
        server.start()
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("access_key", "access")
                put("password", "secret")
                put("site_id", "site")
                put("default_master", "https://hub.example.com")
            },
        )
        assertFailsWith<ThalovantUnsupportedProtocolException> { ThalovantClient(identity) }
    }
}
