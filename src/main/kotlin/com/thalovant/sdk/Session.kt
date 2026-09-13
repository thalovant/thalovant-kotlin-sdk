package com.thalovant.sdk

import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

public data class HubSessionPolicy(public val retrySeconds: Double = 10.0,
    public val retryCeilingSeconds: Double = 120.0, public val probeSeconds: Double = 60.0,
    public val probeDownSeconds: Double = 5.0) {
    init { require(listOf(retrySeconds,retryCeilingSeconds,probeSeconds,probeDownSeconds).all { it.isFinite() && it > 0 } && retryCeilingSeconds >= retrySeconds) }
    public fun nextWait(current: Double): Double = minOf(current*2,retryCeilingSeconds)
}
public fun alive(client: ThalovantClient?): Boolean = client != null && client.transport.connectionInfo.phase !in setOf("closed","error")
/** Owns one hub connection. Call probe at probeDelay intervals from the host.
 * A failed admitted call is never replayed; Ask may trigger an action.
 */
public class HubSession(private val connect: suspend () -> ThalovantClient,
    public val policy: HubSessionPolicy = HubSessionPolicy(),
    private val clock: () -> Double = { System.nanoTime()/1_000_000_000.0 },
    warm: Boolean = true) {
    private val lock = Any()
    private val busy = Mutex()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var client: ThalovantClient? = null
    private var retired: ThalovantClient? = null
    private var closed = false
    private var warming: Job? = null
    private class Listener(val name: String,val handler: (ThalovantEvent)->Unit,var bound: ThalovantSubscription? = null)
    private val listeners = linkedSetOf<Listener>()
    @Volatile public var retryAt: Double = 0.0; private set
    @Volatile public var retryWait: Double = policy.retrySeconds; private set
    public val held: Boolean get() = synchronized(lock) { client != null }
    init { if (warm) warm() }
    public fun probeDelay(): Double = if (held) policy.probeSeconds else policy.probeDownSeconds
    public fun on(eventName: String,handler: (ThalovantEvent)->Unit): ThalovantSubscription = synchronized(lock) {
        check(!closed) { "Hub session is closed" }
        val listener=Listener(eventName,handler,client?.on(eventName,handler=handler));listeners.add(listener)
        ThalovantSubscription { synchronized(lock) { listener.bound?.close(); listeners.remove(listener) }; Unit }
    }
    private suspend fun cleanup() { retired?.let { it.close(); retired=null } }
    private suspend fun drop() {
        synchronized(lock) {
            if (client != null) { retired=client; client=null }
            for (listener in listeners) { listener.bound?.close(); listener.bound=null }
        }
        cleanup()
    }
    private suspend fun ensure(): ThalovantClient {
        synchronized(lock) { check(!closed) { "Hub session is closed" }; client?.let { return it } }
        cleanup()
        var fresh: ThalovantClient? = null
        try {
            fresh=connect()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!closed) { "Hub session is closed" }
                for (listener in listeners) listener.bound=fresh.on(listener.name,handler=listener.handler)
                client=fresh;retryAt=0.0;retryWait=policy.retrySeconds
            }
            return fresh
        } catch (error: Exception) {
            synchronized(lock) { retryAt=clock()+retryWait;retryWait=policy.nextWait(retryWait);retired=fresh }
            withContext(NonCancellable) { drop() }
            throw error
        }
    }
    public fun warm(): Job? = synchronized(lock) {
        if (closed || clock()<retryAt) return@synchronized null
        if (warming?.isActive == true) return@synchronized warming
        scope.launch { try { busy.withLock { ensure() } } catch(error: CancellationException) { throw error } catch (_: Exception) { /* Backoff records the failure. */ } }.also { warming=it }
    }
    public suspend fun probe() {
        if (!busy.tryLock()) return
        try {
            val current=synchronized(lock) { if(closed)return;client }
            if(current != null && !alive(current)) drop()
        } finally { busy.unlock() }
        if(!held)warm()
    }
    private suspend fun <T> call(block: suspend (ThalovantClient)->T): T = busy.withLock {
        val current=synchronized(lock) { client }
        if(current != null && !alive(current))drop()
        val connected=ensure()
        try { block(connected) }
        catch(error: ThalovantRuntimeException) { throw error }
        catch(error: Exception) { withContext(NonCancellable) { drop() };throw error }
    }
    public suspend fun ask(text: String,timeoutMs: Long=12000,lang: String="en-us",sessionId: String?=null,
        requestId: String?=null,context: JsonObject=EMPTY_JSON_OBJECT,replySettleMs: Long?=null,emptyReplyWaitMs: Long?=null): ThalovantReply =
        call { it.ask(text,timeoutMs,lang,sessionId,requestId,context,replySettleMs,emptyReplyWaitMs) }
    public suspend fun emit(eventType: String,data: JsonObject=EMPTY_JSON_OBJECT,context: JsonObject=EMPTY_JSON_OBJECT): Unit = call { it.emit(eventType,data,context) }
    /** Terminal close waits for admitted work before retiring the connection. */
    public suspend fun close() {
        synchronized(lock) { closed=true }
        try { busy.withLock { drop();synchronized(lock) { listeners.clear() } } }
        finally { scope.cancel() }
    }
}
public fun hubHostname(master: String?): String = try {
    val text=master?.trim().orEmpty()
    if(text.isEmpty()) "" else URI(if("://" in text)text else "wss://$text").host.orEmpty()
} catch (_: Exception) { "" }
public data class OriginAttempt(public val host: String,public val connectTimeoutMs: Long,
    public val handshakeSeconds: Double?=null,public val address: String?=null)
/** Build must use a transport-local dial override preserving host and TLS/SNI,
 * and retire a failed attempt before throwing. No process-global resolver edits.
 */
public class OriginPreference(public val address: String,public val handshakeSeconds: Double=1.5,
    public val cooldownSeconds: Double=300.0,private val clock: ()->Double={System.nanoTime()/1_000_000_000.0}) {
    @Volatile private var quietUntil=0.0
    init { require(handshakeSeconds.isFinite() && handshakeSeconds>0 && cooldownSeconds.isFinite() && cooldownSeconds>0) }
    public val coolingDown: Boolean get()=clock()<quietUntil
    public suspend fun <T> connect(options: OriginAttempt,build: suspend (OriginAttempt)->T): T {
        currentCoroutineContext().ensureActive()
        if(address.isNotBlank() && options.host.isNotBlank() && !coolingDown) {
            try { return build(options.copy(address=address,handshakeSeconds=handshakeSeconds)).also { quietUntil=0.0 } }
            catch(error: CancellationException) { throw error }
            catch(_: Exception) { currentCoroutineContext().ensureActive(); quietUntil=clock()+cooldownSeconds }
        }
        return build(options.copy(address=null))
    }
}
