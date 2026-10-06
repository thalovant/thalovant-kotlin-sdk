package com.thalovant.sdk

import kotlinx.coroutines.ensureActive

import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    public val connectionInfo: ThalovantConnectionInfo get() = ThalovantConnectionInfo(
        phase = if (handshakeComplete && connected) "ready" else if (connected) "handshake" else "idle",
    )
    public fun addHiveMessageListener(listener: (JsonObject) -> Unit): ThalovantSubscription =
        throw ThalovantRuntimeException("This transport does not support HiveMind query frames.")
    public suspend fun sendHiveFrame(message: JsonObject): Unit =
        throw ThalovantRuntimeException("This transport does not support HiveMind query frames.")
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
    private val noiseStore: HiveMindNoiseStore = HiveMindNoiseStore.forIdentity(identity),
    /**
     * How long to let a hub refuse this client after the handshake, before
     * [connect] reports the connection ready.
     *
     * **This window is the only proof of acceptance the protocol offers.** In
     * XXpsk2 this side's static key travels in the *last* handshake message,
     * so the hub judges it after there is nothing left for it to send. It
     * answers a key it accepts with silence -- there is no frame that means
     * yes -- and a contradicted TOFU pin by closing the socket.
     *
     * Without the window, [connect] returned success the instant it had
     * written that last message, and the refusal arrived milliseconds later
     * as an ordinary close on a connection the caller had already been told
     * was ready. Nothing raised, nothing to show anybody: a phone whose
     * connection had been re-paired onto it waited on its hub for ever,
     * reconnecting on every probe and being refused invisibly each time. It
     * is the difference between an error and a mystery.
     *
     * Paid once per connection and only when the hub is happy -- a refusal
     * ends the wait early. A refusal slower than this is not misreported,
     * only noticed on the next probe, which is where it was noticed before.
     * Zero restores the old behaviour.
     */
    private val acceptanceWindowMs: Long = DEFAULT_ACCEPTANCE_WINDOW_MS,
) : HiveMindRuntimeTransport {
    private val client: OkHttpClient = httpClient ?: defaultClient
    private val listeners = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
    private val hiveListeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()

    /**
     * Held on the transport and not on a connection, so a reconnect -- which
     * replaces the socket -- keeps its subscribers.
     */
    private val binaryListeners = CopyOnWriteArrayList<(ThalovantBinary) -> Unit>()
    private var connectStartedNs: Long? = null
    private var connectDurationMs: Double? = null
    private var phase = "idle"
    override val connectionInfo: ThalovantConnectionInfo get() = synchronized(sendLock) {
        ThalovantConnectionInfo(phase, connectDurationMs, if (lastError != null) "HiveMind WSS connection failed." else null)
    }
    override fun addHiveMessageListener(listener: (JsonObject) -> Unit): ThalovantSubscription {
        hiveListeners.add(listener)
        return ThalovantSubscription { hiveListeners.remove(listener) }
    }

    /** Listen for binary frames: speech a hub rendered, and files. */
    public fun addBinaryListener(listener: (ThalovantBinary) -> Unit): ThalovantSubscription {
        binaryListeners.add(listener)
        return ThalovantSubscription { binaryListeners.remove(listener) }
    }
    override suspend fun sendHiveFrame(message: JsonObject) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        // Waiting behind another frame can be cancelled: a message still
        // queued is withdrawn whole, and the link is left exactly as it was --
        // no close, no error recorded against it. Once its turn has come the
        // write is not: half a frame, or a nonce spent and never sent, would
        // break the Noise stream for every frame after it.
        sendQueue.withLock { sendHiveMessageChecked(message, true) { caller.ensureActive() } }
    }

    /** The turn of each application frame; see [sendHiveFrame]. */
    internal val sendQueue = Mutex()

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

    /**
     * Completed with the reason this connection ended, if it ended.
     *
     * Separate from [handshake] because the case that matters is a close
     * arriving *after* the handshake succeeded -- which is how a hub says it
     * did not accept this client's static key, and which [handshake] can no
     * longer report, having already been completed.
     */
    private var ended = CompletableDeferred<Throwable>()

    /**
     * Whether the hub has said anything through the authenticated session.
     *
     * A close is only read as a refusal while nothing has come back. A
     * session that has carried a frame was plainly accepted, and its later
     * close is an ordinary disconnection -- a hub restarting, a phone losing
     * its network -- which must not be reported as a credential no hub will
     * ever take.
     */
    @Volatile
    private var heardFromHub: Boolean = false

    /** The Noise pattern the current connection's handshake completed with. */
    @Volatile
    private var completedPattern: String? = null

    /** When the current connection's handshake completed, for [closeRefuses]. */
    @Volatile
    private var handshakeCompletedNs: Long? = null

    /**
     * The KK attempt just made did not authenticate, so the next attempt of
     * this connect uses XX; see [connect].
     */
    private var xxRetry: Boolean = false

    public val authorization: String
        get() = Base64.getEncoder().encodeToString("$userAgent:${identity.accessKey}".toByteArray(Charsets.UTF_8))

    public val endpoint: String
        get() {
            val given = identity.endpointFor(HubProtocol.WSS)
                ?: throw ThalovantConnectionException("The identity does not include a WSS endpoint.")
            // A URL scheme is case-insensitive, and endpointFor returns a
            // WS:// master as written; lower only the scheme.
            val raw = when {
                given.startsWith("wss://", ignoreCase = true) -> "wss://" + given.substring(6)
                given.startsWith("ws://", ignoreCase = true) -> "ws://" + given.substring(5)
                else -> throw ThalovantConnectionException("WSS endpoint must start with ws:// or wss://.")
            }
            // HttpUrl owns query encoding and fragment placement. Convert only
            // the scheme for parsing, then restore the public WebSocket URL.
            val url = ("http" + raw.substring(2)).toHttpUrlOrNull()
                ?: throw ThalovantConnectionException("Invalid WSS endpoint.")
            val builder = url.newBuilder().query(null)
            // Compare decoded names: %61uthorization is the same parameter.
            for (index in 0 until url.querySize) {
                val name = url.queryParameterName(index)
                if (name != "authorization") builder.addQueryParameter(name, url.queryParameterValue(index))
            }
            val authorized = builder.addQueryParameter("authorization", authorization).build().toString()
            return "ws" + authorized.substring(4)
        }

    /**
     * Connects and authenticates. A KK attempt that fails is followed at once,
     * inside the same connect and the same deadline, by one XX attempt.
     *
     * A KK handshake that does not authenticate -- the hub closing on its first
     * message, or its answer failing here -- means the password or the hub's
     * key is not what was pinned, and only XX can say which: a wrong password
     * is a refusal ([ThalovantIdentityException]), a changed hub key a
     * [ThalovantHubIdentityChangedException]. XX is not a downgrade: the pin is
     * still checked when it completes. The XX attempt's outcome is the connect's.
     */
    override suspend fun connect(timeoutMs: Long) {
        require(timeoutMs > 0) { "timeoutMs must be positive." }
        val ready = withTimeoutOrNull(timeoutMs) {
            connectMutex.withLock {
                try {
                    connectOnce()
                } catch (refused: ThalovantIdentityException) {
                    if (refused is ThalovantHubIdentityChangedException || !synchronized(sendLock) { xxRetry }) throw refused
                    connectOnce()
                } finally {
                    synchronized(sendLock) { xxRetry = false }
                }
            }
            true
        }
        // withTimeoutOrNull catches only this call's timeout; an enclosing
        // coroutine deadline or cancellation remains a cancellation.
        if (ready == null) throw ThalovantConnectionException("HiveMind WSS handshake timed out.")
    }

    private suspend fun connectOnce() {
        if (connected && handshakeComplete) {
            return
        }
        val attempt = synchronized(sendLock) {
            socket?.cancel()
            generation++
            resetSession()
            lastError = null
            phase = "connecting"
            connectStartedNs = System.nanoTime()
            connectDurationMs = null
            opened = CompletableDeferred()
            handshake = CompletableDeferred()
            ended = CompletableDeferred()
            heardFromHub = false
            completedPattern = null
            handshakeCompletedNs = null
            generation
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", userAgent)
            .build()
        val webSocket = synchronized(sendLock) {
            check(attempt == generation) { "HiveMind connection was cancelled." }
            client.newWebSocket(request, SocketListener(attempt)).also { socket = it }
        }
        try {
            opened.await()
            handshake.await()
            // A completed handshake is not an accepted one. See
            // [acceptanceWindowMs]: the hub forms its opinion of this side's
            // static key after the last message it will ever receive from us,
            // says nothing when it is content, and closes when it is not. So
            // the connection is called ready only once it has survived the
            // window, and a close inside the window is raised here -- where a
            // caller is still listening -- rather than landing silently on a
            // connection that has already been reported good.
            if (acceptanceWindowMs > 0) {
                withTimeoutOrNull(acceptanceWindowMs) { ended.await() }?.let { throw it }
            }
        } catch (error: Throwable) {
            synchronized(sendLock) {
                if (attempt == generation) {
                    generation++
                    socket = null
                    resetSession()
                    phase = "error"
                    lastError = error
                }
            }
            webSocket.cancel()
            throw error
        }
    }

    override suspend fun disconnect() {
        synchronized(sendLock) {
            val current = socket
            socket = null
            generation++
            resetSession()
            phase = "closed"
            val error = ThalovantConnectionException("HiveMind WSS disconnected.")
            opened.completeExceptionally(error)
            handshake.completeExceptionally(error)
            ended.complete(error)
            current?.close(1000, null)
        }
    }

    /**
     * Completes with the reason the connection current at the call ended,
     * once it has: a hub's close, a dropped socket, or [disconnect]. What a
     * session keeping the link waits on, so it notices a drop as it happens
     * rather than at its next probe.
     */
    internal fun linkEnded(): kotlinx.coroutines.Deferred<Throwable> = synchronized(sendLock) { ended }

    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription {
        listeners.add(listener)
        return ThalovantSubscription { listeners.remove(listener) }
    }

    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
        sendHiveFrame(
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
    public fun sendHiveMessage(message: JsonObject, encrypt: Boolean = true) { sendHiveMessageChecked(message, encrypt) {} }

    private fun sendHiveMessageChecked(message: JsonObject, encrypt: Boolean, checkCancellation: () -> Unit) {
        require(encrypt) { "Plaintext application messages are forbidden by HiveMind v3." }
        synchronized(sendLock) {
            checkCancellation()
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

    private fun handleRawMessage(raw: String, authenticated: Boolean = false): List<() -> Unit> {
        // Anything arriving through the authenticated session is the hub
        // speaking to a connection it kept, which settles the question a later
        // close would otherwise raise. See [SocketListener.closedAfterHandshake].
        if (authenticated) heardFromHub = true
        val deliveries = mutableListOf<() -> Unit>()
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
                    name = payload.optionalString("type") ?: return emptyList(),
                    data = payload["data"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                    context = payload["context"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                )
                for (listener in listeners.toList()) deliveries.add { listener(event) }
            }
            else -> check(authenticated) { "Unexpected plaintext application frame." }
        }
        if (authenticated && handshakeComplete) for (listener in hiveListeners.toList()) deliveries.add { listener(parsed) }
        return deliveries
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
            val pattern = if (pin != null && !xxRetry && "KKpsk0" in offered("patterns")) "KKpsk0"
                else if ("XXpsk2" in offered("patterns")) "XXpsk2" else error("No supported Noise pattern offered.")
            val suite = Noise.suites.firstOrNull { it in offered("suites") } ?: error("No supported Noise suite offered.")
            val psk = cachedPsk?.takeIf { it.first == nodeId }?.second
                ?: Noise.derivePsk(identity.password, nodeId).also { cachedPsk = nodeId to it }
            // Only KK is sealed to the pinned key. XX learns the hub's key and
            // meets the pin once it has completed, below, so a hub that is not
            // the pinned one is told apart from a password that is wrong.
            val state = NoiseHandshake(pattern, suite, psk, Noise.prologue(hello, payload, "Noise_${pattern}_$suite"),
                noiseStore.staticKey(), pin.takeIf { pattern == "KKpsk0" })
            noiseHandshake = state
            val first = state.write("{\"binarize\":false,\"encodings\":[]}".toByteArray())
            sendHandshake(buildJsonObject { put("pattern", pattern); put("suite", suite); put("msg", Noise.hex(first)) })
        } else {
            val state = noiseHandshake ?: error("Noise message arrived before offer.")
            try {
                state.read(Noise.unhex(message))
            } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
                // The hub's answer does not authenticate under the key this
                // password derives: the hub turned the credentials away (or
                // the negotiation was tampered with, which retrying will not
                // fix either). After KK it may equally be a hub whose key
                // changed, so the next attempt uses XX, which can tell.
                if (state.pattern == "KKpsk0") xxRetry = true
                throw ThalovantIdentityException(
                    "The hub did not accept this client's password. Pair this client with the hub again.",
                )
            }
            // The hub's key meets the pin before this side answers with its
            // own: XX learns that key in the hub's message, and a hub that is
            // not the pinned one gets nothing more from this client.
            noiseStore.verifyOrPin(nodeId, state.remoteStatic ?: error("Missing authenticated server static key."))
            if (!state.finished) sendHandshake(buildJsonObject { put("msg", Noise.hex(state.write())) })
            noiseSession = state.session()
            completedPattern = state.pattern
            noiseHandshake = null
            sendHiveMessage(helloMessage())
            handshakeComplete = true
            handshakeCompletedNs = System.nanoTime()
            phase = "ready"
            connectDurationMs = connectStartedNs?.let { (System.nanoTime() - it) / 1_000_000.0 }
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
        phase = "error"
        resetSession()
        socket?.cancel()
        opened.completeExceptionally(error)
        handshake.completeExceptionally(error)
        ended.complete(error)
    }

    private inner class SocketListener(private val currentGeneration: Long) : WebSocketListener() {
        private fun receive(action: () -> List<() -> Unit>) {
            val deliveries = synchronized(sendLock) {
                if (currentGeneration != generation) return@synchronized emptyList()
                try { action() } catch (error: Throwable) { failHandshake(error); emptyList() }
            }
            // Application callbacks cannot hold the cipher/send lock or turn
            // an authenticated session into a transport failure.
            for (deliver in deliveries) runCatching { deliver() }
        }
        override fun onOpen(webSocket: WebSocket, response: Response) = receive {
            socket = webSocket
            connected = true
            phase = "handshake"
            opened.complete(Unit)
            emptyList()
        }
        override fun onMessage(webSocket: WebSocket, text: String) = receive { handleRawMessage(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive {
            val session = noiseSession ?: error("Binary frame received before Noise authentication.")
            val decryptedFrame = session.decrypt(bytes.toByteArray())
            // Anything that decrypts under the new session's keys is the hub
            // speaking to a connection it kept -- JSON or WIRE-1 binary, one
            // chunk of a larger message, its own encrypted HELLO -- so a later
            // close is its trouble, not a refusal. A decryption that fails
            // throws before this, and is no frame from the hub.
            heardFromHub = true
            val frame = decryptedFrame ?: return@receive emptyList()
            check(frame.second) { "Binary HiveMind payloads were not negotiated." }
            val decrypted = frame.first
            // Text first, because that is what almost every frame is; a
            // WIRE-1 frame is not valid JSON and falls through to the codec.
            // Reading it as UTF-8 regardless is what silently lost a hub's
            // rendered speech: the bytes are a clip, not a document.
            val text = runCatching { decrypted.toString(Charsets.UTF_8) }.getOrNull()
            if (text != null && text.trimStart().startsWith("{")) {
                return@receive handleRawMessage(text, authenticated = true)
            }
            val decoded = runCatching { HiveWire.decode(decrypted) }.getOrNull()
                ?: return@receive handleRawMessage(text ?: "", authenticated = true)
            val binary = decoded.binary
            if (binary != null) {
                return@receive binaryListeners.toList().map { listener -> { listener(binary) } }
            }
            handleRawMessage(decoded.text ?: "", authenticated = true)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = receive {
            // OkHttp/interceptor failures can contain the authorized request
            // URL. Never retain that message or cause in public diagnostics.
            val error = when (val status = response?.code) {
                // The upgrade itself refused: the hub, or the gateway in front
                // of it, read the access key and would not have it.
                401, 403 -> ThalovantIdentityException(
                    "The hub refused this client's credentials (HTTP $status). Pair this client with the hub again.",
                )
                else -> ThalovantConnectionException("HiveMind WSS connection failed.")
            }
            ended.complete(error)
            failHandshake(error)
            emptyList()
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = receive {
            if (!handshakeComplete) {
                val error = closedBeforeHandshake(code)
                ended.complete(error)
                failHandshake(error)
            } else {
                ended.complete(closedAfterHandshake(code))
                resetSession()
                phase = "closed"
            }
            emptyList()
        }

        /**
         * What a close means once the handshake has already succeeded.
         *
         * Succeeding is not the same as being accepted. This side's static
         * key travels in the last handshake message, so the hub forms its
         * opinion of it after there is nothing left to answer: silence means
         * yes, a close means no.
         *
         * A connection that has since carried a frame was accepted, and its
         * close is an ordinary disconnection. Nothing came back and the socket
         * closed: the hub read this client's key and would not have it. That
         * asks the same thing of a person as a refused access key -- pair
         * again, which is the only thing that gives the hub a key it will
         * keep -- so it is reported as the same kind of failure.
         *
         * Only a close the way a hub refuses ([REFUSAL_CLOSE_CODES]) is read
         * so; an internal error or a restart right after the handshake is an
         * ordinary failure, as the reference reads it.
         */
        private fun closedAfterHandshake(code: Int): ThalovantException {
            val afterMs = handshakeCompletedNs?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0
            // OkHttp hands the code over with the close itself, so it is never late here.
            val refused = closeRefuses(code, closedAfterHandshakeMs = afterMs, codeLateMs = 0, afterAuthenticatedFrame = heardFromHub)
            return when {
                !refused -> ThalovantConnectionException("HiveMind WSS closed ($code).")
                // hivemind-core pins the first static key a connection presents
                // and aborts as soon as an XX handshake shows it another. After
                // KK the hub could only have completed with the key it pinned,
                // so the same close there is a plain refusal.
                completedPattern == "XXpsk2" -> ThalovantClientKeyRejectedException(
                    keyFolder = noiseStore.directory.toString(),
                    otherKeyFolder = noiseStore.otherFolder?.toString(),
                )
                else -> ThalovantIdentityException(
                    "The hub closed this connection without accepting it. Pair this client with the hub again.",
                )
            }
        }

        /**
         * The close code says whose problem this is, so it decides the type.
         *
         * A hub that refuses an access key closes during the handshake --
         * with no status for a key it does not know and after a Noise abort,
         * with 1008 for a malformed authorization, with 1000 from hubs that
         * close politely -- at any step of it, between its HELLO and its offer
         * included. Reporting that as a connection failure sends somebody to
         * check their network for a credential the hub has already read and
         * rejected -- the one thing their network cannot fix. The identity type
         * already exists and already means "the hub would not accept this
         * client", so a refusal raises that instead.
         *
         * A close during a KK attempt is also what a hub does when it cannot
         * read a KK first message, because the password changed or its own
         * key did; the next attempt uses XX, which can tell.
         *
         * The server's own `reason` is never echoed: it is remote text, and the
         * code carries everything a caller should branch on.
         */
        private fun closedBeforeHandshake(code: Int): ThalovantException {
            if (!closeRefuses(code)) {
                return ThalovantConnectionException("HiveMind WSS closed before Noise handshake completed ($code).")
            }
            if (noiseHandshake?.pattern == "KKpsk0") xxRetry = true
            return ThalovantIdentityException(
                "The hub refused this client's credentials. Pair this client with the hub again.",
            )
        }
    }

    private companion object {

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * The close codes (RFC 6455) a hub turns credentials away with: no status at
 * all (1005) for an access key it does not know and after a Noise abort, 1008
 * for a malformed authorization, and 1000 from hubs that close politely --
 * hivemind-core's abort path calls `disconnect()`, whose default it is. Any
 * other code -- 1001 going away, 1011 an internal error, 1013 try again later,
 * a socket that ended with no close frame (1006) -- is the hub's trouble or
 * the network's, not a verdict on the credentials, and telling somebody to
 * pair again over it would throw away a good one.
 */
internal val REFUSAL_CLOSE_CODES: Set<Int> = setOf(1000, 1005, 1008)

/** How long after the handshake a close is still the hub's answer to it; [DEFAULT_ACCEPTANCE_WINDOW_MS]. */
internal const val REFUSAL_SETTLE_MS: Long = DEFAULT_ACCEPTANCE_WINDOW_MS

/**
 * How late a transport may learn a close's code and still have it count. Some
 * (URLSession) report that a socket closed before they report how; OkHttp
 * hands the code over with the close.
 */
internal const val CLOSE_CODE_GRACE_MS: Long = 250

/**
 * Whether a close is the hub refusing the credentials rather than a drop.
 *
 * [code] is the RFC 6455 close code, null when the socket ended without one.
 * [closedAfterHandshakeMs] is when the close happened, counted from the end of
 * the handshake, or null for a close during it: the close's own time decides,
 * not when the transport reported it. [codeLateMs] is how long after the close
 * the transport learnt the code. [afterAuthenticatedFrame] says whether the hub
 * had sent anything that decrypted under the new session's keys: a hub refuses
 * a key before it writes a single transport frame, so a close after one is the
 * hub's trouble. `link-keeping-vectors.json` holds every SDK to this rule.
 */
internal fun closeRefuses(
    code: Int?,
    closedAfterHandshakeMs: Long? = null,
    codeLateMs: Long = 0,
    afterAuthenticatedFrame: Boolean = false,
): Boolean {
    if (afterAuthenticatedFrame) return false
    if (code == null || code !in REFUSAL_CLOSE_CODES || codeLateMs > CLOSE_CODE_GRACE_MS) return false
    return closedAfterHandshakeMs == null || closedAfterHandshakeMs <= REFUSAL_SETTLE_MS
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
