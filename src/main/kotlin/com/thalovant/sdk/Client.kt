package com.thalovant.sdk

import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Data-plane client for talking to a Thalovant hub with a client identity.
 *
 * 0.1.0 supports the WSS transport only; requesting `https` or `mqtt` throws
 * [ThalovantUnsupportedProtocolException].
 */
public class ThalovantClient(
    public val identity: ThalovantIdentity,
    transport: HiveMindRuntimeTransport? = null,
    protocol: HubProtocol? = null,
    private val replySettleMs: Long = 250,
    private val emptyReplyWaitMs: Long = 5000,
    userAgent: String = DEFAULT_USER_AGENT,
) {
    private val transport: HiveMindRuntimeTransport =
        transport ?: transportForProtocol(identity, protocol ?: defaultRuntimeProtocol(identity), userAgent)

    @Volatile
    private var connected = false

    public suspend fun connect(timeoutMs: Long = 6000) {
        if (connected) {
            return
        }
        transport.connect(timeoutMs)
        connected = true
    }

    public suspend fun close() {
        transport.disconnect()
        connected = false
    }

    /**
     * Registers a bus event listener. When [sessionId] or [requestId] are given,
     * events carrying a different correlation id are filtered out (events without
     * one still pass, matching the other SDKs).
     */
    public fun on(
        eventName: String,
        sessionId: String? = null,
        requestId: String? = null,
        handler: (ThalovantEvent) -> Unit,
    ): ThalovantSubscription = transport.addBusListener { event ->
        if (event.name != eventName) return@addBusListener
        if (sessionId != null && event.sessionId != null && event.sessionId != sessionId) return@addBusListener
        if (requestId != null && event.requestId != null && event.requestId != requestId) return@addBusListener
        handler(event)
    }

    /** Emits a raw bus event to the hub. */
    public suspend fun emit(
        eventType: String,
        data: JsonObject = EMPTY_JSON_OBJECT,
        context: JsonObject = EMPTY_JSON_OBJECT,
    ) {
        connect()
        transport.emitBus(eventType, data, context)
    }

    /** Sends a fire-and-forget utterance with fresh correlation ids. */
    public suspend fun sendUtterance(
        text: String,
        lang: String = "en-us",
        sessionId: String? = null,
        requestId: String? = null,
        context: JsonObject = EMPTY_JSON_OBJECT,
    ) {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "sendUtterance() requires a non-empty text prompt." }
        emit(
            ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE,
            utterancePayload(prompt, lang),
            contextWithCorrelation(
                context,
                sessionId = sessionId ?: newSessionId(),
                siteId = identity.siteId,
                lang = lang,
                requestId = requestId ?: newRequestId(),
            ),
        )
    }

    /**
     * Sends an utterance and aggregates the correlated `speak` replies, mirroring
     * the Node SDK `ask()` semantics: replies are matched by request id, the call
     * waits for `ovos.utterance.handled` or a first reply, optionally waits
     * [emptyReplyWaitMs] for a late reply, then settles [replySettleMs] to catch
     * trailing fragments.
     */
    public suspend fun ask(
        text: String,
        timeoutMs: Long = 12000,
        lang: String = "en-us",
        sessionId: String? = null,
        requestId: String? = null,
        context: JsonObject = EMPTY_JSON_OBJECT,
        replySettleMs: Long? = null,
        emptyReplyWaitMs: Long? = null,
    ): ThalovantReply {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "ask() requires a non-empty text prompt." }
        val effectiveRequestId = requestId ?: newRequestId()
        val effectiveSessionId = sessionId ?: newSessionId()
        val fullContext = contextWithCorrelation(
            context,
            sessionId = effectiveSessionId,
            siteId = identity.siteId,
            lang = lang,
            requestId = effectiveRequestId,
        )
        connect()

        val lock = Any()
        val fragments = mutableListOf<String>()
        val events = mutableListOf<ThalovantEvent>()
        var failureEvent: ThalovantEvent? = null
        val handled = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Unit>()
        val whitespace = Regex("\\s+")

        val subscription = transport.addBusListener { event ->
            // Replies are correlated strictly by request id, like the Node SDK ask().
            if (event.requestId != effectiveRequestId) return@addBusListener
            when (event.name) {
                ThalovantEvents.SPEAK, ThalovantEvents.OVOS_UTTERANCE_SPEAK -> synchronized(lock) {
                    val normalized = event.text.trim().replace(whitespace, " ")
                    if (normalized.isNotEmpty() && fragments.lastOrNull() != normalized) {
                        fragments.add(normalized)
                        firstReply.complete(Unit)
                    }
                    events.add(event)
                }
                ThalovantEvents.UTTERANCE_HANDLED -> {
                    synchronized(lock) { events.add(event) }
                    handled.complete(Unit)
                }
                ThalovantEvents.INTENT_FAILURE -> synchronized(lock) { events.add(event) }
                ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT -> {
                    synchronized(lock) {
                        failureEvent = event
                        events.add(event)
                    }
                    handled.complete(Unit)
                }
            }
        }
        try {
            val raced = withTimeoutOrNull(timeoutMs) {
                transport.emitBus(ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE, utterancePayload(prompt, lang), fullContext)
                select {
                    handled.onAwait {}
                    firstReply.onAwait {}
                }
            }
            if (raced == null) {
                throw ThalovantTimeoutException("Hub did not finish handling the utterance within ${timeoutMs}ms.")
            }
            val effectiveEmptyWait = emptyReplyWaitMs ?: this.emptyReplyWaitMs
            if (synchronized(lock) { failureEvent == null && fragments.isEmpty() } && effectiveEmptyWait > 0) {
                withTimeoutOrNull(effectiveEmptyWait) { firstReply.await() }
            }
            val effectiveSettle = replySettleMs ?: this.replySettleMs
            if (effectiveSettle > 0) {
                delay(effectiveSettle)
            }
            synchronized(lock) {
                val failure = failureEvent
                if (failure == null && fragments.isEmpty()) {
                    throw ThalovantTimeoutException(
                        "Hub handled the utterance but did not emit a speak reply within ${effectiveEmptyWait}ms.",
                    )
                }
                if (failure != null && fragments.isEmpty()) {
                    throw ThalovantRuntimeException(failure.text.ifEmpty { "Hub reported ${failure.name}." })
                }
                return ThalovantReply(
                    text = fragments.joinToString(" "),
                    utterances = fragments.toList(),
                    handled = failure == null,
                    ok = failure == null,
                    sessionId = effectiveSessionId,
                    requestId = effectiveRequestId,
                    events = events.toList(),
                    failureEvent = failure,
                )
            }
        } finally {
            subscription.close()
        }
    }

    public companion object {
        public fun fromIdentityFile(path: Path, protocol: HubProtocol? = null): ThalovantClient =
            ThalovantClient(ThalovantIdentity.fromFile(path), protocol = protocol)

        public fun fromIdentityFile(path: String, protocol: HubProtocol? = null): ThalovantClient =
            ThalovantClient(ThalovantIdentity.fromFile(path), protocol = protocol)
    }
}

private fun defaultRuntimeProtocol(identity: ThalovantIdentity): HubProtocol {
    if (identity.supportsProtocol(HubProtocol.WSS) && identity.endpointFor(HubProtocol.WSS) != null) {
        return HubProtocol.WSS
    }
    throw ThalovantUnsupportedProtocolException(
        "The identity does not include a usable WSS endpoint. " +
            "thalovant-kotlin-sdk 0.1.0 supports only the WSS data plane.",
    )
}

private fun transportForProtocol(
    identity: ThalovantIdentity,
    protocol: HubProtocol,
    userAgent: String,
): HiveMindRuntimeTransport = when (protocol) {
    HubProtocol.WSS -> {
        if (identity.endpointFor(HubProtocol.WSS) == null) {
            throw ThalovantUnsupportedProtocolException(
                "WSS is enabled, but the identity does not include a WSS endpoint.",
            )
        }
        HiveMindWssTransport(identity, userAgent)
    }
    HubProtocol.HTTPS -> throw ThalovantUnsupportedProtocolException(
        "The HTTPS long-poll transport is not supported by thalovant-kotlin-sdk 0.1.0. Use wss.",
    )
    HubProtocol.MQTT -> throw ThalovantUnsupportedProtocolException(
        "The MQTT transport is not supported by thalovant-kotlin-sdk 0.1.0. Use wss.",
    )
}
