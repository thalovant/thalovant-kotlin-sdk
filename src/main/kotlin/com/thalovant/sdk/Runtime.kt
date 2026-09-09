package com.thalovant.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local authenticated transport state, without endpoint URLs or credentials. */
public data class ThalovantConnectionInfo(
    public val phase: String = "idle",
    public val connectMs: Double? = null,
    public val lastError: String? = null,
)

/** Local transport health; this does not assert that every hub skill is healthy. */
public data class ThalovantHealth(
    public val connected: Boolean,
    public val handshakeComplete: Boolean,
    public val transportAlive: Boolean,
    public val connection: ThalovantConnectionInfo,
) {
    public val ok: Boolean get() = connected && handshakeComplete && transportAlive && connection.lastError == null
}

public data class ThalovantDoctorCheck(public val name: String, public val ok: Boolean, public val detail: String)
public data class ThalovantDoctorReport(public val checks: List<ThalovantDoctorCheck>) {
    public val ok: Boolean get() = checks.all { it.ok }
}

public fun ThalovantClient.connectionInfo(): ThalovantConnectionInfo = transport.connectionInfo

public suspend fun ThalovantClient.connectWithInfo(timeoutMs: Long = 6000): ThalovantConnectionInfo {
    connect(timeoutMs)
    return connectionInfo()
}

public suspend fun ThalovantClient.healthcheck(timeoutMs: Long = 6000): ThalovantHealth {
    connect(timeoutMs)
    return ThalovantHealth(transport.connected, transport.handshakeComplete, transport.connected, connectionInfo())
}

/** Connect diagnostics with status-only failures so failing URLs cannot disclose access keys. */
public suspend fun ThalovantClient.doctor(timeoutMs: Long = 6000): ThalovantDoctorReport {
    val checks = mutableListOf(ThalovantDoctorCheck("identity", true, "Client identity loaded."))
    checks += ThalovantDoctorCheck("endpoint", identity.endpointFor(HubProtocol.WSS) != null, "WSS endpoint availability.")
    try {
        checks += ThalovantDoctorCheck("connect", healthcheck(timeoutMs).ok, "Authenticated WSS transport state.")
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
      catch (_: Exception) { checks += ThalovantDoctorCheck("connect", false, "Authenticated WSS connection failed.") }
    return ThalovantDoctorReport(checks)
}

/** Wait for one matching event. Cancellation, timeout and transport loss remove the subscription. */
public suspend fun ThalovantClient.waitForEvent(
    eventName: String,
    timeoutMs: Long = 12000,
    sessionId: String? = null,
    requestId: String? = null,
    predicate: (ThalovantEvent) -> Boolean = { true },
): ThalovantEvent {
    require(timeoutMs > 0) { "timeoutMs must be positive." }
    val answer = CompletableDeferred<ThalovantEvent>()
    val subscription = on(eventName, sessionId, requestId) { event ->
        try { if (predicate(event)) answer.complete(event) }
        catch (error: Exception) { answer.completeExceptionally(error) }
    }
    try {
        return withTimeoutOrNull(timeoutMs) {
            connect(timeoutMs)
            awaitRuntime(answer)
        } ?: throw ThalovantTimeoutException("Hub did not emit $eventName within ${timeoutMs}ms.")
    } finally { subscription.close() }
}

/** Cold bounded event stream. A slow consumer receives an overflow error instead of silently losing events. */
public fun ThalovantClient.listen(
    eventName: String,
    timeoutMs: Long? = null,
    maxEvents: Int? = null,
    sessionId: String? = null,
    requestId: String? = null,
    predicate: (ThalovantEvent) -> Boolean = { true },
): Flow<ThalovantEvent> {
    require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive." }
    require(maxEvents == null || maxEvents >= 0) { "maxEvents must be non-negative." }
    return flow {
        if (maxEvents == 0) return@flow
        val queue = Channel<ThalovantEvent>(64)
        val subscription = on(eventName, sessionId, requestId) { event ->
            try {
                if (predicate(event) && queue.trySend(event).isFailure) {
                    queue.close(ThalovantRuntimeException("Event stream buffer overflow."))
                }
            } catch (error: Exception) { queue.close(error) }
        }
        try {
            withTimeoutOrNull(timeoutMs ?: Long.MAX_VALUE) {
                connect(minOf(timeoutMs ?: 6000, 6000))
                var received = 0
                while (maxEvents == null || received < maxEvents) {
                    val event = withTimeoutOrNull(100) { queue.receive() }
                    if (event != null) { emit(event); received++ }
                    else requireRuntimeConnected()
                }
            }
        } finally { subscription.close(); queue.cancel() }
    }
}

public suspend fun ThalovantClient.sendAction(
    payload: String, title: String? = null, lang: String = "en-us",
    sessionId: String? = null, requestId: String? = null, context: JsonObject = EMPTY_JSON_OBJECT,
) {
    val prompt = payload.trim()
    require(prompt.isNotEmpty()) { "sendAction() requires a non-empty payload." }
    val input = buildJsonObject { put("kind", "action"); put("title", title); put("payload", prompt) }
    sendUtterance(prompt, lang, sessionId, requestId, mergeRuntimeContext(context, buildJsonObject { put("input", input) }))
}

/** Send a code as exact text plus input metadata in both the event data and context. */
public suspend fun ThalovantClient.sendCode(
    value: String, kind: String = "code", label: String? = null, lang: String = "en-us",
    sessionId: String? = null, requestId: String? = null, context: JsonObject = EMPTY_JSON_OBJECT,
) {
    val code = value.trim()
    require(code.isNotEmpty()) { "sendCode() requires a non-empty value." }
    val input = buildJsonObject { put("kind", kind); put("label", label); put("value", code); put("exact", true) }
    val merged = mergeRuntimeContext(context, buildJsonObject { put("input", input) })
    emit(ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE,
        JsonObject(utterancePayload(code, lang) + ("input" to input)),
        contextWithCorrelation(merged, sessionId ?: newSessionId(), identity.siteId, lang, requestId ?: newRequestId()))
}

/** Direct HiveMind query/cascade exchange, strictly scoped by query id. Never automatically replayed. */
public suspend fun ThalovantClient.query(
    text: String, timeoutMs: Long = 12000, lang: String = "en-us", sessionId: String? = null,
    requestId: String? = null, queryId: String? = null, context: JsonObject = EMPTY_JSON_OBJECT,
): ThalovantReply {
    val prompt = text.trim()
    require(prompt.isNotEmpty()) { "query() requires a non-empty prompt." }
    require(timeoutMs > 0) { "timeoutMs must be positive." }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    val started = System.nanoTime()
    fun remaining(): Long = (timeoutMs - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
    val ready = CompletableDeferred<Unit>()
    val request = requestId ?: newRequestId()
    val query = queryId ?: request
    val session = sessionId ?: newSessionId()
    val fullContext = contextWithCorrelation(context, session, identity.siteId, lang, request)
    val done = CompletableDeferred<Unit>()
    val lock = Any()
    val events = mutableListOf<ThalovantEvent>()
    val fragments = mutableListOf<String>()
    var failure: ThalovantEvent? = null
    var responseSessionId: String? = null
    val whitespace = Regex("\\s+")
    val subscription = transport.addHiveMessageListener { message ->
        if (message.optionalString("msg_type") !in listOf("query", "cascade")) return@addHiveMessageListener
        val metadata = message["metadata"].asObjectOrNull() ?: return@addHiveMessageListener
        if (metadata.optionalString("query_id", "queryId") != query) return@addHiveMessageListener
        val event = queryEvent(message) ?: return@addHiveMessageListener
        synchronized(lock) {
            if (done.isCompleted) return@synchronized
            events.add(event)
            if (responseSessionId == null) responseSessionId = event.sessionId?.takeIf { it.isNotBlank() }
            when {
                event.name == "hive.query.complete" -> done.complete(Unit)
                event.name in listOf(ThalovantEvents.SPEAK, ThalovantEvents.OVOS_UTTERANCE_SPEAK) -> {
                    val fragment = event.text.trim().replace(whitespace, " ")
                    if (fragment.isNotEmpty() && fragments.lastOrNull() != fragment) fragments.add(fragment)
                }
                event.name in listOf(ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT) -> {
                    failure = event; done.complete(Unit)
                }
                event.isFailure -> failure = event
            }
        }
    }
    val operation = launchRuntimeIo(onFailure = { error ->
        synchronized(lock) { if (!done.isCompleted) done.completeExceptionally(error) }
    }) {
        val connectBudget = remaining()
        if (connectBudget <= 0) throw ThalovantTimeoutException("Query budget expired before connecting.")
        connect(connectBudget)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        ready.complete(Unit)
        val bus = hiveMessage("bus", buildJsonObject {
            put("type", ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE)
            put("data", utterancePayload(prompt, lang)); put("context", fullContext)
        })
        transport.sendHiveFrame(hiveMessage("query", bus, buildJsonObject { put("query_id", query) }))
    }
    try {
        withTimeoutOrNull(remaining()) {
            select { ready.onAwait {}; done.onAwait {} }
            awaitRuntime(done)
        } ?: throw ThalovantTimeoutException("Hub did not complete the query within ${timeoutMs}ms.")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (fragments.isEmpty()) {
                if (failure != null) throw ThalovantRuntimeException("Hub reported ${failure!!.name}.")
                throw ThalovantTimeoutException("Hub completed the query without a speak reply.")
            }
            val terminalFailure = failure?.takeIf { it.name in listOf(ThalovantEvents.POLICY_DENIED, ThalovantEvents.QUERY_TIMEOUT) }
            return ThalovantReply(fragments.joinToString(" "), fragments.toList(), terminalFailure == null, terminalFailure == null,
                responseSessionId ?: session, request, events.toList(), terminalFailure)
        }
    } finally { operation.cancel(); subscription.close() }
}

/** Recursion is bounded for untrusted nested cascade envelopes. */
private fun queryEvent(message: JsonObject): ThalovantEvent? {
    var payload = message["payload"].asObjectOrNull() ?: return null
    repeat(16) {
        val name = payload.optionalString("type")
        if (name != null) return ThalovantEvent(name,
            payload["data"].asObjectOrNull() ?: EMPTY_JSON_OBJECT,
            payload["context"].asObjectOrNull() ?: EMPTY_JSON_OBJECT)
        payload = payload["payload"].asObjectOrNull() ?: return null
    }
    return null
}

private suspend fun <T> ThalovantClient.awaitRuntime(answer: CompletableDeferred<T>): T {
    while (true) {
        if (answer.isCompleted) return answer.await()
        val value = withTimeoutOrNull(100) { answer.await() }
        if (value != null) return value
        requireRuntimeConnected()
    }
}

private fun ThalovantClient.requireRuntimeConnected() {
    if (!transport.connected || !transport.handshakeComplete) throw ThalovantConnectionException("HiveMind transport disconnected while waiting.")
}

internal fun mergeRuntimeContext(base: JsonObject, extra: JsonObject): JsonObject = JsonObject(
    (base.keys + extra.keys).associateWith { key ->
        val left = base[key]; val right = extra[key]
        if (left is JsonObject && right is JsonObject) mergeRuntimeContext(left, right) else right ?: left!!
    },
)

/** A stable session wrapper; closing it never closes the shared client transport. */
public class ThalovantConversation internal constructor(
    public val client: ThalovantClient, public val sessionId: String, public val lang: String,
    public val context: JsonObject,
) {
    public suspend fun ask(text: String, timeoutMs: Long = 12000, context: JsonObject = EMPTY_JSON_OBJECT): ThalovantReply =
        client.ask(text, timeoutMs, lang, sessionId, context = mergeRuntimeContext(this.context, context))
    public suspend fun query(text: String, timeoutMs: Long = 12000, context: JsonObject = EMPTY_JSON_OBJECT): ThalovantReply =
        client.query(text, timeoutMs, lang, sessionId, context = mergeRuntimeContext(this.context, context))
    public suspend fun sendUtterance(text: String, context: JsonObject = EMPTY_JSON_OBJECT) {
        client.sendUtterance(text, lang, sessionId, context = mergeRuntimeContext(this.context, context))
    }
    public suspend fun sendAction(payload: String, title: String? = null, context: JsonObject = EMPTY_JSON_OBJECT) {
        client.sendAction(payload, title, lang, sessionId, context = mergeRuntimeContext(this.context, context))
    }
    public suspend fun sendCode(value: String, kind: String = "code", label: String? = null, context: JsonObject = EMPTY_JSON_OBJECT) {
        client.sendCode(value, kind, label, lang, sessionId, context = mergeRuntimeContext(this.context, context))
    }
    public fun on(eventName: String, handler: (ThalovantEvent) -> Unit): ThalovantSubscription = client.on(eventName, sessionId, handler = handler)
    public suspend fun waitForEvent(eventName: String, timeoutMs: Long = 12000, predicate: (ThalovantEvent) -> Boolean = { true }): ThalovantEvent =
        client.waitForEvent(eventName, timeoutMs, sessionId, predicate = predicate)
    public fun listen(eventName: String, timeoutMs: Long? = null, maxEvents: Int? = null): Flow<ThalovantEvent> =
        client.listen(eventName, timeoutMs, maxEvents, sessionId)
    public suspend fun emit(eventType: String, data: JsonObject = EMPTY_JSON_OBJECT, context: JsonObject = EMPTY_JSON_OBJECT) {
        client.emit(eventType, data, contextWithCorrelation(mergeRuntimeContext(this.context, context), sessionId, client.identity.siteId, lang))
    }
}

public fun ThalovantClient.conversation(sessionId: String = newSessionId(), lang: String = "en-us", context: JsonObject = EMPTY_JSON_OBJECT): ThalovantConversation =
    ThalovantConversation(this, sessionId, lang, context)

/** Caller waits are bounded separately; this worker keeps physical transport ownership until it finishes. */
internal suspend fun launchRuntimeIo(onFailure: (Exception) -> Unit, block: suspend () -> Unit): kotlinx.coroutines.Job =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext().minusKey(kotlinx.coroutines.Job) + kotlinx.coroutines.Dispatchers.IO).launch {
        try { block() }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { onFailure(error) }
    }
