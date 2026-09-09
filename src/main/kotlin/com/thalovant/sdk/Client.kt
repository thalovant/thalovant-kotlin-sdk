package com.thalovant.sdk

import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    internal val transport: HiveMindRuntimeTransport =
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
        require(timeoutMs > 0) { "timeoutMs must be positive." }
        val effectiveEmptyWait = emptyReplyWaitMs ?: this.emptyReplyWaitMs
        val effectiveSettle = replySettleMs ?: this.replySettleMs
        require(effectiveEmptyWait >= 0 && effectiveSettle >= 0) { "Reply waits must be non-negative." }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val started = System.nanoTime()
        fun remaining(): Long = (timeoutMs - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        val effectiveRequestId = requestId ?: newRequestId()
        val effectiveSessionId = sessionId ?: newSessionId()
        val fullContext = contextWithCorrelation(context, effectiveSessionId, identity.siteId, lang, effectiveRequestId)
        val lock = Any()
        val fragments = mutableListOf<String>()
        val events = mutableListOf<ThalovantEvent>()
        var failureEvent: ThalovantEvent? = null
        var softFailureEvent: ThalovantEvent? = null
        var responseSessionId: String? = null
        var firstSpeechAt: Long? = null
        var emptyStartedAt: Long? = null
        fun phaseRemaining(window: Long, since: Long?): Long = minOf(remaining(),
            (window - (since?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0)).coerceAtLeast(0))
        val handled = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val whitespace = Regex("\\s+")
        val subscription = transport.addBusListener { event ->
            if (event.requestId != effectiveRequestId) return@addBusListener
            synchronized(lock) {
                if (failureEvent != null) return@addBusListener
                when (event.name) {
                    ThalovantEvents.SPEAK, ThalovantEvents.OVOS_UTTERANCE_SPEAK -> {
                        val normalized = event.text.trim().replace(whitespace, " ")
                        if (normalized.isNotEmpty() && fragments.lastOrNull() != normalized) {
                            if (firstSpeechAt == null) firstSpeechAt = System.nanoTime()
                            fragments.add(normalized); firstReply.complete(Unit)
                        }
                        events.add(event)
                    }
                    ThalovantEvents.UTTERANCE_HANDLED -> {
                        if (emptyStartedAt == null) emptyStartedAt = System.nanoTime()
                        events.add(event); handled.complete(Unit)
                    }
                    ThalovantEvents.INTENT_FAILURE, ThalovantEvents.INTENT_UNMATCHED -> {
                        if (emptyStartedAt == null) emptyStartedAt = System.nanoTime()
                        softFailureEvent = event; events.add(event); handled.complete(Unit)
                    }
                    ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT -> {
                        failureEvent = event; events.add(event)
                        handled.complete(Unit); firstReply.complete(Unit); terminal.complete(Unit)
                    }
                    else -> return@addBusListener
                }
                if (responseSessionId == null) responseSessionId = event.sessionId?.takeIf { it.isNotBlank() }
            }
        }
        // The I/O worker retains transport ownership while cancellation retires an admitted write.
        val operation = launchRuntimeIo(onFailure = { error ->
            synchronized(lock) {
                if (fragments.isEmpty() && failureEvent == null && softFailureEvent == null) handled.completeExceptionally(error)
            }
        }) {
            val connectBudget = remaining()
            if (connectBudget <= 0) throw ThalovantTimeoutException("Request budget expired before connecting.")
            connect(connectBudget)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            transport.emitBus(ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE, utterancePayload(prompt, lang), fullContext)
        }
        try {
            val progressed = withTimeoutOrNull(remaining()) { select { handled.onAwait {}; firstReply.onAwait {} } }
            if (progressed == null && synchronized(lock) { fragments.isEmpty() && failureEvent == null && softFailureEvent == null })
                throw ThalovantTimeoutException("Hub did not finish handling the utterance within ${timeoutMs}ms.")
            if (synchronized(lock) { failureEvent == null && fragments.isEmpty() }) {
                withTimeoutOrNull(synchronized(lock) { phaseRemaining(effectiveEmptyWait, emptyStartedAt) }) { firstReply.await() }
            }
            // Settling is optional and shares the original budget. Hard failure wakes it immediately.
            if (synchronized(lock) { failureEvent == null && fragments.isNotEmpty() }) {
                withTimeoutOrNull(synchronized(lock) { phaseRemaining(effectiveSettle, firstSpeechAt) }) { terminal.await() }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            synchronized(lock) {
                val failure = failureEvent ?: if (fragments.isEmpty()) softFailureEvent else null
                if (failure == null && fragments.isEmpty()) throw ThalovantTimeoutException("Hub handled the utterance without a speak reply within the request budget.")
                if (failure != null && fragments.isEmpty()) throw ThalovantRuntimeException(failure.text.ifEmpty { "Hub reported ${failure.name}." })
                return ThalovantReply(fragments.joinToString(" "), fragments.toList(), failure == null, failure == null,
                    responseSessionId ?: effectiveSessionId, effectiveRequestId, events.toList(), failure)
            }
        } finally { operation.cancel(); subscription.close() }
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

    /** Registered fallback handlers; null means discovery was unavailable, empty means none registered. */
    public suspend fun listFallbacks(timeoutMs: Long = 5000): List<HubFallback>? = discoverFallbacks(timeoutMs)

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
            "thalovant-kotlin-sdk supports only the WSS data plane.",
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
        "The HTTPS long-poll transport is not supported by thalovant-kotlin-sdk. Use wss.",
    )
    HubProtocol.MQTT -> throw ThalovantUnsupportedProtocolException(
        "The MQTT transport is not supported by thalovant-kotlin-sdk. Use wss.",
    )
}
