package com.thalovant.sdk

import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Handle returned by listener registrations; close it to unsubscribe. */
public class ThalovantSubscription internal constructor(private val closeFn: () -> Unit) : AutoCloseable {
    override fun close(): Unit = closeFn()
    public fun unsubscribe(): Unit = close()
}

/** Minimal runtime transport contract used by [ThalovantClient]. */
public interface HiveMindRuntimeTransport {
    public val connected: Boolean
    public val handshakeComplete: Boolean
    public suspend fun connect(timeoutMs: Long = 6000)
    public suspend fun disconnect()
    public suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject)
    public fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription
}

/** HiveMind v3 WSS transport. Only an authenticated Noise session accepts bus traffic. */
public class HiveMindWssTransport(
    public val identity: ThalovantIdentity,
    public val userAgent: String = DEFAULT_USER_AGENT,
    httpClient: OkHttpClient? = null,
    private val noiseStore: HiveMindNoiseStore = HiveMindNoiseStore(),
) : HiveMindRuntimeTransport {
    private val client: OkHttpClient = httpClient ?: defaultClient
    private val listeners = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
    private var socket: WebSocket? = null
    private var serverHello: JsonObject? = null
    private var noiseHandshake: NoiseHandshake? = null
    private var noiseSession: NoiseSession? = null
    private val sendLock = Any()
    private val connectMutex = Mutex()
    private var generation = 0L
    private var cachedPsk: Pair<String, ByteArray>? = null

    private fun resetSession() {
        connected = false
        handshakeComplete = false
        serverHello = null
        noiseHandshake = null
        noiseSession = null
    }

    @Volatile
    override var connected: Boolean = false
        private set

    @Volatile
    override var handshakeComplete: Boolean = false
        private set

    @Volatile
    public var lastError: Throwable? = null
        private set

    private var opened = CompletableDeferred<Unit>()
    private var handshake = CompletableDeferred<Unit>()

    public val authorization: String
        get() = Base64.getEncoder().encodeToString("$userAgent:${identity.accessKey}".toByteArray(Charsets.UTF_8))

    public val endpoint: String
        get() {
            val raw = identity.endpointFor(HubProtocol.WSS)
                ?: throw ThalovantConnectionException("The identity does not include a WSS endpoint.")
            if (!raw.startsWith("ws://") && !raw.startsWith("wss://")) {
                throw ThalovantConnectionException("WSS endpoint must start with ws:// or wss://.")
            }
            val encoded = java.net.URLEncoder.encode(authorization, Charsets.UTF_8)
            val separator = if ("?" in raw) "&" else "?"
            return "$raw${separator}authorization=$encoded"
        }

    override suspend fun connect(timeoutMs: Long) {
        connectMutex.withLock { connectOnce(timeoutMs) }
    }

    private suspend fun connectOnce(timeoutMs: Long) {
        if (connected && handshakeComplete) {
            return
        }
        socket?.cancel()
        generation++
        resetSession()
        lastError = null
        opened = CompletableDeferred()
        handshake = CompletableDeferred()
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", userAgent)
            .build()
        val webSocket = client.newWebSocket(request, SocketListener(generation))
        socket = webSocket
        try {
            withTimeout(timeoutMs) {
                opened.await()
                handshake.await()
            }
        } catch (error: TimeoutCancellationException) {
            webSocket.cancel()
            resetSession()
            throw ThalovantConnectionException("HiveMind WSS handshake timed out.")
        } catch (error: Throwable) {
            webSocket.cancel()
            resetSession()
            throw error
        }
    }

    override suspend fun disconnect() {
        val current = socket
        socket = null
        generation++
        resetSession()
        current?.close(1000, null)
    }

    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription {
        listeners.add(listener)
        return ThalovantSubscription { listeners.remove(listener) }
    }

    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
        sendHiveMessage(
            hiveMessage(
                "bus",
                buildJsonObject {
                    put("type", eventType)
                    put("data", data)
                    put("context", context)
                },
            ),
        )
    }

    /** Sends encrypted v3 JSON. Plaintext is reserved for the internal handshake. */
    public fun sendHiveMessage(message: JsonObject, encrypt: Boolean = true) {
        require(encrypt) { "Plaintext application messages are forbidden by HiveMind v3." }
        synchronized(sendLock) {
            val session = noiseSession ?: throw ThalovantConnectionException("Noise handshake is not complete.")
            val webSocket = socket ?: throw ThalovantConnectionException("HiveMind WSS transport is not connected.")
            for (frame in session.encrypt(message.toString().toByteArray())) {
                if (!webSocket.send(ByteString.of(*frame))) {
                    val error = ThalovantConnectionException("HiveMind WSS send failed.")
                    failHandshake(error)
                    throw error
                }
            }
        }
    }

    private fun sendHandshake(noise: JsonObject) {
        val frame = hiveMessage("shake", buildJsonObject { put("noise", noise) })
        check(socket?.send(frame.toString()) == true) { "Noise handshake send failed." }
    }

    private fun handleRawMessage(raw: String, authenticated: Boolean = false) {
        check(authenticated || noiseSession == null) { "Plaintext frame received after Noise negotiation." }
        val parsed = ThalovantJson.parseToJsonElement(raw).asObjectOrNull()
            ?: throw ThalovantConnectionException("Invalid HiveMind JSON frame.")
        val msgType = parsed.optionalString("msg_type") ?: error("Missing HiveMind message type.")
        val payload = parsed["payload"].asObjectOrNull() ?: EMPTY_JSON_OBJECT
        when (msgType) {
            "hello" -> if (!authenticated) {
                check(serverHello == null && noiseHandshake == null) { "Duplicate server HELLO." }
                check(!payload.optionalString("node_id").isNullOrBlank()) { "Server HELLO is missing node_id." }
                serverHello = payload
            }
            "handshake", "shake" -> {
                check(!authenticated) { "Unexpected encrypted handshake." }
                handleHandshake(payload)
            }
            "bus" -> {
                check(authenticated && handshakeComplete) { "Application frame received before Noise authentication." }
                val event = ThalovantEvent(
                    name = payload.optionalString("type") ?: return,
                    data = payload["data"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                    context = payload["context"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                )
                for (listener in listeners) listener(event)
            }
            else -> check(authenticated) { "Unexpected plaintext application frame." }
        }
    }

    private fun handleHandshake(payload: JsonObject) {
        val noise = payload["noise"].asObjectOrNull()
            ?: throw ThalovantConnectionException("HiveMind v3 Noise is required; legacy downgrade refused.")
        val hello = serverHello ?: error("Noise offer arrived before server HELLO.")
        val nodeId = hello.optionalString("node_id")!!
        val message = noise.optionalString("msg")
        if (message == null) {
            check(noiseHandshake == null) { "Duplicate Noise offer." }
            check((payload["max_protocol_version"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { it >= 3 } == true) {
                "Server does not advertise HiveMind v3."
            }
            fun offered(name: String): List<String> = (noise[name] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
            val pin = noiseStore.pin(nodeId)
            val pattern = if (pin != null && "KKpsk0" in offered("patterns")) "KKpsk0"
                else if ("XXpsk2" in offered("patterns")) "XXpsk2" else error("No supported Noise pattern offered.")
            val suite = Noise.suites.firstOrNull { it in offered("suites") } ?: error("No supported Noise suite offered.")
            val psk = cachedPsk?.takeIf { it.first == nodeId }?.second
                ?: Noise.derivePsk(identity.password, nodeId).also { cachedPsk = nodeId to it }
            val state = NoiseHandshake(pattern, suite, psk, Noise.prologue(hello, payload, "Noise_${pattern}_$suite"), noiseStore.staticKey(), pin)
            noiseHandshake = state
            val first = state.write("{\"binarize\":false,\"encodings\":[]}".toByteArray())
            sendHandshake(buildJsonObject { put("pattern", pattern); put("suite", suite); put("msg", Noise.hex(first)) })
        } else {
            val state = noiseHandshake ?: error("Noise message arrived before offer.")
            state.read(Noise.unhex(message))
            if (!state.finished) sendHandshake(buildJsonObject { put("msg", Noise.hex(state.write())) })
            noiseStore.verifyOrPin(nodeId, state.remoteStatic ?: error("Missing authenticated server static key."))
            noiseSession = state.session()
            noiseHandshake = null
            sendHiveMessage(helloMessage())
            handshakeComplete = true
            handshake.complete(Unit)
        }
    }

    private fun helloMessage(): JsonObject = hiveMessage(
        "hello",
        buildJsonObject {
            put("pubkey", identity.publicKey ?: "")
            put("session", buildJsonObject { put("session_id", "thalovant-kotlin-${UUID.randomUUID()}") })
            put("site_id", identity.siteId)
        },
    )

    private fun failHandshake(error: Throwable) {
        lastError = error
        resetSession()
        socket?.cancel()
        opened.completeExceptionally(error)
        handshake.completeExceptionally(error)
    }

    private inner class SocketListener(private val currentGeneration: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (currentGeneration != generation) { webSocket.cancel(); return }
            socket = webSocket
            connected = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (currentGeneration != generation) return
            try {
                handleRawMessage(text)
            } catch (error: Throwable) {
                failHandshake(error)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (currentGeneration != generation) return
            try {
                val session = noiseSession ?: error("Binary frame received before Noise authentication.")
                val frame = session.decrypt(bytes.toByteArray()) ?: return
                check(frame.second) { "Binary HiveMind payloads were not negotiated." }
                handleRawMessage(frame.first.toString(Charsets.UTF_8), authenticated = true)
            } catch (error: Throwable) { failHandshake(error) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (currentGeneration != generation) return
            failHandshake(ThalovantConnectionException("HiveMind WSS connect failed: ${t.message}", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (currentGeneration != generation) return
            if (!handshakeComplete) failHandshake(ThalovantConnectionException("HiveMind WSS closed before Noise handshake completed ($code)."))
            else resetSession()
        }
    }

    private companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}

internal fun hiveMessage(msgType: String, payload: JsonObject, metadata: JsonObject = EMPTY_JSON_OBJECT): JsonObject =
    buildJsonObject {
        put("msg_type", msgType)
        put("payload", payload)
        put("metadata", metadata)
        put("route", JsonArray(emptyList()))
        put("node", JsonNull)
        put("target_site_id", JsonNull)
        put("target_pubkey", JsonNull)
        put("source_peer", JsonNull)
    }
