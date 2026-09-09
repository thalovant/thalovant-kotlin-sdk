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
 * Supports the HiveMind v3 Noise WSS transport only; requesting `https` or `mqtt` throws
 * [ThalovantUnsupportedProtocolException].
 */
public class ThalovantClient(
    public val identity: ThalovantIdentity,
    transport: HiveMindRuntimeTransport? = null,
    protocol: HubProtocol? = null,
    private val replySettleMs: Long = 250,
    private val emptyReplyWaitMs: Long = 5000,
    userAgent: String = DEFAULT_USER_AGENT,
    noiseStore: HiveMindNoiseStore = HiveMindNoiseStore(),
) {
    private val transport: HiveMindRuntimeTransport =
        transport ?: transportForProtocol(identity, protocol ?: defaultRuntimeProtocol(identity), userAgent, noiseStore)

    @Volatile
    private var connected = false

    public suspend fun connect(timeoutMs: Long = 6000) {
        if (connected && transport.connected && transport.handshakeComplete) {
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
        // The request id decides when both sides carry one: a hub does not echo a
        // client-declared session id, it substitutes its own (observed live on
        // 2026-09-03), so comparing session ids rejected replies the request id
        // had already identified as ours and ask() timed out.
        if (requestId != null && event.requestId != null) {
            if (event.requestId != requestId) return@addBusListener
        } else if (sessionId != null && event.sessionId != null && event.sessionId != sessionId) {
            return@addBusListener
        }
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
        // An intent miss is a soft failure: it ends phase 1 but leaves failureEvent
        // unset so the empty-reply wait still runs and a fallback reply can win.
        var softFailureEvent: ThalovantEvent? = null
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
                ThalovantEvents.INTENT_FAILURE, ThalovantEvents.INTENT_UNMATCHED -> {
                    // Soft failure: end phase 1, but keep failureEvent null so the
                    // empty-reply wait still runs and a fallback reply can take over.
                    synchronized(lock) {
                        softFailureEvent = event
                        events.add(event)
                    }
                    handled.complete(Unit)
                }
                ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT -> {
                    // Hard failure: terminal, no fallback wait.
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
                // A soft intent-miss becomes the surfaced failure only if no reply
                // (not even a fallback) arrived; a reply means a fallback recovered.
                val failure = failureEvent ?: if (fragments.isEmpty()) softFailureEvent else null
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

    /**
     * Everything the hub can be asked, per language, grouped by skill.
     *
     * Read from the runtime's intent manifest over this session, so no
     * control-plane credential is involved. Each intent carries the sentences
     * a person says to reach it, as the skill wrote them, `{slot}` placeholders
     * included. An empty [languages] means `en-us`.
     *
     * Throws [ThalovantPolicyDeniedException] when the hub refuses the query
     * and [IntentInventoryOptions.fallback] is off; with it on (the default), a
     * hub allowed for only the engines' manifests yields intent names with
     * [HubIntentInventory.source] set to [HubIntentSource.ENGINE_MANIFESTS].
     * Throws [ThalovantTimeoutException] when the hub does not answer within
     * [IntentInventoryOptions.timeoutMs].
     */
    public suspend fun intents(
        languages: List<String> = listOf(DEFAULT_INTENT_LANG),
        options: IntentInventoryOptions = IntentInventoryOptions(),
    ): HubIntentInventory = intentInventory(languages.ifEmpty { listOf(DEFAULT_INTENT_LANG) }, options)

    /** The hub's intent manifest for one language, one row per registration (`ovos.intent.list`). */
    public suspend fun listIntents(
        lang: String = DEFAULT_INTENT_LANG,
        options: ListIntentsOptions = ListIntentsOptions(),
    ): List<IntentRegistration> = listIntentRegistrations(lang, options)

    /**
     * The registrations behind one intent in one language, sentences included
     * (`ovos.intent.describe`). Empty when the hub does not know the registration.
     */
    public suspend fun describeIntent(
        skillId: String,
        intentName: String,
        lang: String = DEFAULT_INTENT_LANG,
        options: DescribeIntentOptions = DescribeIntentOptions(),
    ): List<IntentDefinition> = describeIntentRegistrations(skillId, intentName, lang, options)

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
    noiseStore: HiveMindNoiseStore,
): HiveMindRuntimeTransport = when (protocol) {
    HubProtocol.WSS -> {
        if (identity.endpointFor(HubProtocol.WSS) == null) {
            throw ThalovantUnsupportedProtocolException(
                "WSS is enabled, but the identity does not include a WSS endpoint.",
            )
        }
        HiveMindWssTransport(identity, userAgent, noiseStore = noiseStore)
    }
    HubProtocol.HTTPS -> throw ThalovantUnsupportedProtocolException(
        "The HTTPS long-poll transport is not supported by thalovant-kotlin-sdk 0.1.0. Use wss.",
    )
    HubProtocol.MQTT -> throw ThalovantUnsupportedProtocolException(
        "The MQTT transport is not supported by thalovant-kotlin-sdk 0.1.0. Use wss.",
    )
}
