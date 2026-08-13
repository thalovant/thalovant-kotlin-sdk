package com.thalovant.sdk

import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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

/**
 * HiveMind WSS transport.
 *
 * Wire protocol (mirrors the Node SDK transport):
 * - Connect to the identity WSS endpoint with `?authorization=base64("<userAgent>:<accessKey>")`.
 * - The hub sends `{"msg_type":"handshake","payload":{"preshared_key":true}}`; the
 *   client answers with an unencrypted `hello` message carrying its pubkey, a fresh
 *   session id, and the identity `site_id`, which completes the handshake.
 * - After the handshake, outbound messages are AES-128-GCM encrypted JSON envelopes
 *   (`{ciphertext,tag,nonce}` hex) keyed by the identity `crypto_key`; inbound
 *   messages may be encrypted envelopes or plaintext HiveMind messages.
 * - Bus traffic uses `{"msg_type":"bus","payload":{"type","data","context"},...}`.
 */
public class HiveMindWssTransport(
    public val identity: ThalovantIdentity,
    public val userAgent: String = DEFAULT_USER_AGENT,
    httpClient: OkHttpClient? = null,
) : HiveMindRuntimeTransport {
    private val client: OkHttpClient = httpClient ?: defaultClient
    private val listeners = CopyOnWriteArrayList<(ThalovantEvent) -> Unit>()
    private var socket: WebSocket? = null

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
        if (connected && handshakeComplete) {
            return
        }
        lastError = null
        handshakeComplete = false
        opened = CompletableDeferred()
        handshake = CompletableDeferred()
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", userAgent)
            .build()
        val webSocket = client.newWebSocket(request, SocketListener())
        socket = webSocket
        try {
            withTimeout(timeoutMs) {
                opened.await()
                handshake.await()
            }
        } catch (error: TimeoutCancellationException) {
            webSocket.cancel()
            connected = false
            throw ThalovantConnectionException("HiveMind WSS handshake timed out.")
        } catch (error: Throwable) {
            webSocket.cancel()
            connected = false
            throw error
        }
    }

    override suspend fun disconnect() {
        val current = socket
        socket = null
        current?.close(1000, null)
        connected = false
        handshakeComplete = false
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

    /** Serializes and sends a HiveMind message, encrypting after the handshake when possible. */
    public fun sendHiveMessage(message: JsonObject, encrypt: Boolean = true) {
        val webSocket = socket
        if (webSocket == null || !connected) {
            throw ThalovantConnectionException("HiveMind WSS transport is not connected.")
        }
        val serialized = message.toString()
        val cryptoKey = identity.cryptoKey
        val payload = if (encrypt && handshakeComplete && cryptoKey != null) {
            HiveMindCrypto.encryptAsJson(cryptoKey, serialized)
        } else {
            serialized
        }
        if (!webSocket.send(payload)) {
            throw ThalovantConnectionException("HiveMind WSS send failed: socket is closed.")
        }
    }

    private fun handleRawMessage(raw: String) {
        var parsed = ThalovantJson.parseToJsonElement(raw).asObjectOrNull() ?: return
        val cryptoKey = identity.cryptoKey
        if ("ciphertext" in parsed && cryptoKey != null) {
            parsed = ThalovantJson.parseToJsonElement(HiveMindCrypto.decryptFromJson(cryptoKey, parsed))
                .asObjectOrNull() ?: return
        }
        val msgType = parsed.optionalString("msg_type") ?: return
        val payload = parsed["payload"].asObjectOrNull() ?: EMPTY_JSON_OBJECT
        when (msgType) {
            "handshake", "shake" -> handleHandshake(payload)
            "bus" -> {
                val event = ThalovantEvent(
                    name = payload.optionalString("type") ?: return,
                    data = payload["data"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                    context = payload["context"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
                )
                for (listener in listeners) {
                    listener(event)
                }
            }
        }
    }

    private fun handleHandshake(payload: JsonObject) {
        if (truthy(payload["preshared_key"]) && !truthy(payload["handshake"]) && !truthy(payload["envelope"])) {
            if (HiveMindCrypto.runtimeKey(identity.cryptoKey) == null) {
                throw ThalovantConnectionException(
                    "HiveMind requested a preshared key, but identity.crypto_key is missing.",
                )
            }
            sendHiveMessage(helloMessage(), encrypt = false)
            handshakeComplete = true
            handshake.complete(Unit)
            return
        }
        throw ThalovantConnectionException(
            "Only HiveMind preshared-key WSS handshakes are supported in this release.",
        )
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
        connected = false
        opened.completeExceptionally(error)
        handshake.completeExceptionally(error)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleRawMessage(text)
            } catch (error: Throwable) {
                failHandshake(error)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failHandshake(ThalovantConnectionException("HiveMind WSS connect failed: ${t.message}", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            if (!handshakeComplete) {
                val suffix = if (reason.isNotEmpty()) ": $reason" else ""
                failHandshake(
                    ThalovantConnectionException("HiveMind WSS closed before handshake completed ($code)$suffix."),
                )
            }
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
