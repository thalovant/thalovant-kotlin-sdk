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
 * An in-process hub that speaks HiveMind v3: the HELLO, the Noise XXpsk2 and
 * KKpsk0 handshakes, and encrypted bus frames both ways -- so a test drives
 * the SDK's real WSS transport end to end, the way a hub would.
 *
 * Every connection is counted in [attempts], and every pattern a client chose
 * in [patternsChosen]. Like hivemind-core, the hub pins a client's static key
 * on first contact, offers KK once it has one (and [offerKk] allows), and
 * closes -- with [refusalCode], since OkHttp will not send a close without a
 * status -- on a handshake message that does not authenticate or a client key
 * that is not the pinned one. [password] and [serverKey] can change under a
 * client, as a hub's do. [refuseNext] makes the next connections complete the
 * handshake and then close, which is how a hub refuses a client it has not
 * admitted; [upgradeStatus] answers the upgrade itself with an HTTP status.
 */
internal class FakeHub : AutoCloseable {
    private val server = MockWebServer()
    /** The hub's Noise static key; replace it to play a hub that was rebuilt. */
    @Volatile
    var serverKey: ByteArray = ByteArray(32) { (it + 7).toByte() }

    /** The password the hub holds for the client; change it to play one changed in the dashboard. */
    @Volatile
    var password: String = PASSWORD

    /** Whether the hub offers KK to a client it has pinned. */
    @Volatile
    var offerKk: Boolean = true

    /** The client's static key, pinned on first contact. */
    @Volatile
    private var clientPin: ByteArray? = null

    /** The pattern each client chose, in order, `KKpsk0` or `XXpsk2`. */
    val patternsChosen = CopyOnWriteArrayList<String>()

    private val psks = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val hello = buildJsonObject { put("node_id", "fake-hub"); put("pubkey", "test"); put("peer", "fake-hub") }
    private fun offer(kk: Boolean) = ThalovantJson.parseToJsonElement(
        """{"max_protocol_version":3,"binarize":true,"encodings":["JSON-HEX"],"ciphers":["AES-GCM"],""" +
            """"noise":{"patterns":[${if (kk) "\"KKpsk0\"," else ""}"XXpsk2"],"suites":["25519_ChaChaPoly_SHA256","25519_AESGCM_SHA256"]}}""",
    ).jsonObject

    private fun psk(): ByteArray = password.let { secret -> psks.getOrPut(secret) { Noise.derivePsk(secret, "fake-hub") } }
    val stateDir: Path = Files.createTempDirectory("thalovant-fake-hub")

    /** Connections the hub has seen, refused ones included. */
    val attempts = AtomicInteger()

    /** How many of the next connections to refuse right after the handshake. */
    val refuseNext = AtomicInteger()

    @Volatile
    var refusalCode: Int = 1000

    @Volatile
    var upgradeStatus: Int? = null

    /** Send one encrypted frame before a [refuseNext] close: a hub that has spoken accepted the key. */
    @Volatile
    var speakBeforeClosing: Boolean = false

    /** Make that frame a WIRE-1 binary one instead of JSON. */
    @Volatile
    var speakBinary: ByteArray? = null

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

        /** Sends a WIRE-1 binary frame through the session, as a hub renders speech. */
        fun sendBinary(frame: ByteArray) {
            synchronized(this) {
                for (chunk in noise.encrypt(frame)) socket.send(ByteString.of(*chunk))
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
        private val offered = offer(offerKk && clientPin != null)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hiveMessage("hello", hello).toString())
            webSocket.send(hiveMessage("shake", offered).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val params = ThalovantJson.parseToJsonElement(text).jsonObject["payload"]!!.jsonObject["noise"]!!.jsonObject
            if (exchange == null) {
                val pattern = params["pattern"]!!.jsonPrimitive.content
                val suite = params["suite"]!!.jsonPrimitive.content
                patternsChosen.add(pattern)
                exchange = NoiseHandshake(
                    pattern, suite, psk(), Noise.prologue(hello, offered, "Noise_${pattern}_$suite"), serverKey,
                    pinnedRemote = clientPin.takeIf { pattern == "KKpsk0" }, initiator = false,
                )
            }
            val state = exchange!!
            try {
                state.read(Noise.unhex(params["msg"]!!.jsonPrimitive.content))
            } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
                // Not sealed with this password, or not to this hub's key: the
                // hub cannot read it, and says so the only way it does.
                webSocket.close(refusalCode, null)
                return
            }
            if (!state.finished) {
                webSocket.send(hiveMessage("shake", buildJsonObject { put("noise", buildJsonObject { put("msg", Noise.hex(state.write())) }) }).toString())
            }
            if (state.finished) {
                val key = state.remoteStatic!!
                val pinned = clientPin
                if (pinned != null && !pinned.contentEquals(key)) {
                    webSocket.close(refusalCode, null)
                    return
                }
                clientPin = key
                session = Session(webSocket, state.session()).also { if (!refuse) sessions.add(it) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The client's first encrypted frame is its HELLO. A refusing hub
            // has read the client's static key by now and hangs up.
            if (refuse) {
                if (speakBeforeClosing) {
                    val binary = speakBinary
                    if (binary != null) session?.sendBinary(binary) else session?.sendBus("hub.ready")
                }
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

    fun identity(password: String = PASSWORD): ThalovantIdentity = ThalovantIdentity(
        buildJsonObject {
            put("access_key", "access")
            put("password", password)
            put("site_id", "ha")
            put("default_master", "ws://${server.hostName}:${server.port}")
        },
    )

    /** A client for this hub, with its own pin store, connected; closed again when it cannot connect. */
    suspend fun connectedClient(password: String = PASSWORD, stateDir: Path = this.stateDir): ThalovantClient {
        val client = ThalovantClient(identity(password), protocol = HubProtocol.WSS, replySettleMs = 10, noiseStore = HiveMindNoiseStore(stateDir))
        try {
            client.connect(10_000)
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
        return client
    }

    override fun close() {
        for (session in sessions) runCatching { session.socket.close(1000, null) }
        server.shutdown()
        stateDir.toFile().deleteRecursively()
    }

    companion object {
        const val PASSWORD = "fake-hub-password"
    }
}
