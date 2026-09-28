package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString

/**
 * An in-process hub that speaks HiveMind v3: the HELLO, the Noise XXpsk2
 * handshake, and encrypted bus frames both ways -- so a test drives the SDK's
 * real WSS transport end to end, the way a hub would.
 *
 * Every connection is counted in [attempts]. [refuseNext] makes the next
 * connections complete the handshake and then close with [refusalCode], which
 * is how a hub refuses a client it has not admitted; [upgradeStatus] answers
 * the upgrade itself with an HTTP status instead.
 */
internal class FakeHub : AutoCloseable {
    private val server = MockWebServer()
    private val serverKey = ByteArray(32) { (it + 7).toByte() }
    private val hello = buildJsonObject { put("node_id", "fake-hub"); put("pubkey", "test"); put("peer", "fake-hub") }
    private val offer = ThalovantJson.parseToJsonElement(
        """{"max_protocol_version":3,"binarize":true,"encodings":["JSON-HEX"],"ciphers":["AES-GCM"],"noise":{"patterns":["XXpsk2"],"suites":["25519_ChaChaPoly_SHA256","25519_AESGCM_SHA256"]}}""",
    ).jsonObject
    private val psk by lazy { Noise.derivePsk(PASSWORD, "fake-hub") }
    val stateDir: Path = Files.createTempDirectory("thalovant-fake-hub")

    /** Connections the hub has seen, refused ones included. */
    val attempts = AtomicInteger()

    /** How many of the next connections to refuse right after the handshake. */
    val refuseNext = AtomicInteger()

    @Volatile
    var refusalCode: Int = 1000

    @Volatile
    var upgradeStatus: Int? = null

    /** Sessions that completed the handshake and were not refused, newest last. */
    val sessions = CopyOnWriteArrayList<Session>()

    inner class Session internal constructor(val socket: WebSocket, private val noise: NoiseSession) {
        /** Every frame the client sent through the session, decrypted. */
        val received = LinkedBlockingQueue<JsonObject>()

        internal fun receive(bytes: ByteArray) {
            val decoded = noise.decrypt(bytes) ?: return
            received.add(ThalovantJson.parseToJsonElement(decoded.first.toString(Charsets.UTF_8)).jsonObject)
        }

        fun sendBus(type: String, data: JsonObject = EMPTY_JSON_OBJECT, context: JsonObject = EMPTY_JSON_OBJECT) {
            val message = hiveMessage("bus", buildJsonObject { put("type", type); put("data", data); put("context", context) })
            synchronized(this) {
                for (frame in noise.encrypt(message.toString().toByteArray())) socket.send(ByteString.of(*frame))
            }
        }

        /** The next bus frame the client sent of [type], waiting up to [timeoutMs]. */
        fun awaitBus(type: String, timeoutMs: Long = 5_000): JsonObject? {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                val frame = received.poll(left, TimeUnit.MILLISECONDS) ?: return null
                val payload = frame["payload"] as? JsonObject ?: continue
                if (frame["msg_type"]?.jsonPrimitive?.content == "bus" && payload["type"]?.jsonPrimitive?.content == type) {
                    return payload
                }
            }
        }
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                attempts.incrementAndGet()
                upgradeStatus?.let { return MockResponse().setResponseCode(it) }
                val refuse = refuseNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
                return MockResponse().withWebSocketUpgrade(connection(refuse))
            }
        }
        server.start()
    }

    private fun connection(refuse: Boolean) = object : WebSocketListener() {
        private var exchange: NoiseHandshake? = null
        private var session: Session? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hiveMessage("hello", hello).toString())
            webSocket.send(hiveMessage("shake", offer).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val params = ThalovantJson.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject["noise"]!!.jsonObject
            if (exchange == null) {
                val pattern = params["pattern"]!!.jsonPrimitive.content
                val suite = params["suite"]!!.jsonPrimitive.content
                exchange = NoiseHandshake(pattern, suite, psk, Noise.prologue(hello, offer, "Noise_${pattern}_$suite"), serverKey, initiator = false)
            }
            val state = exchange!!
            state.read(Noise.unhex(params["msg"]!!.jsonPrimitive.content))
            if (!state.finished) {
                webSocket.send(hiveMessage("shake", buildJsonObject { put("noise", buildJsonObject { put("msg", Noise.hex(state.write())) }) }).toString())
            }
            if (state.finished && !refuse) session = Session(webSocket, state.session()).also { sessions.add(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The client's first encrypted frame is its HELLO. A refusing hub
            // has read the client's static key by now and hangs up.
            if (refuse) {
                webSocket.close(refusalCode, null)
                return
            }
            session?.receive(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    /** Closes every live session from the hub's side, as a hub restart does. */
    fun dropAll(code: Int = 1001) {
        for (session in sessions) session.socket.close(code, "going away")
        sessions.clear()
    }

    fun identity(): ThalovantIdentity = ThalovantIdentity(
        buildJsonObject {
            put("access_key", "access")
            put("password", PASSWORD)
            put("site_id", "ha")
            put("default_master", "ws://${server.hostName}:${server.port}")
        },
    )

    /** A client for this hub, with its own pin store, connected. */
    suspend fun connectedClient(): ThalovantClient =
        ThalovantClient(identity(), protocol = HubProtocol.WSS, replySettleMs = 10, noiseStore = HiveMindNoiseStore(stateDir))
            .also { it.connect(5_000) }

    override fun close() {
        for (session in sessions) runCatching { session.socket.close(1000, null) }
        server.shutdown()
        stateDir.toFile().deleteRecursively()
    }

    private companion object {
        const val PASSWORD = "fake-hub-password"
    }
}
