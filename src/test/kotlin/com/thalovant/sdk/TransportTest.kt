package com.thalovant.sdk

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        received.clear()
        serverSocket.set(null)
    }

    @AfterTest
    fun tearDown() {
        serverSocket.get()?.close(1000, null)
        server.shutdown()
    }

    private fun startHub() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        webSocket.send(
                            """{"msg_type":"handshake","payload":{"preshared_key":true},"metadata":{}}""",
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
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
        socket.send(
            hiveMessage(
                "bus",
                buildJsonObject {
                    put("type", type)
                    put("data", data)
                    put("context", context)
                },
            ).toString(),
        )
    }

    @Test
    fun `connect performs the preshared-key handshake with an unencrypted hello`() = runBlocking {
        startHub()
        val client = ThalovantClient(identity(), protocol = HubProtocol.WSS, replySettleMs = 10)
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
        val client = ThalovantClient(identity(), protocol = HubProtocol.WSS, replySettleMs = 250)
        client.connect(5000)
        try {
            awaitMessage() // hello

            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what time is it?", sessionId = "sess-1", requestId = "req-1") }
            }

            // Post-handshake traffic is an AES-128-GCM {ciphertext,tag,nonce} envelope.
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            assertTrue("ciphertext" in envelope, "expected the bus message to be encrypted")
            val bus = ThalovantJson.parseToJsonElement(
                HiveMindCrypto.decryptFromJson(CRYPTO_KEY, envelope),
            ).jsonObject
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
        val client = ThalovantClient(identity(), protocol = HubProtocol.WSS, replySettleMs = 250)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", sessionId = "sess-1", requestId = "req-1") }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val bus = ThalovantJson.parseToJsonElement(
                HiveMindCrypto.decryptFromJson(CRYPTO_KEY, envelope),
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
        val client = ThalovantClient(identity(), protocol = HubProtocol.WSS, replySettleMs = 0)
        client.connect(5000)
        try {
            awaitMessage() // hello
            val reply = async(Dispatchers.Default) {
                runCatching { client.ask("what is up?", requestId = "req-1") }
            }
            val envelope = ThalovantJson.parseToJsonElement(awaitMessage()).jsonObject
            val context = ThalovantJson.parseToJsonElement(
                HiveMindCrypto.decryptFromJson(CRYPTO_KEY, envelope),
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
