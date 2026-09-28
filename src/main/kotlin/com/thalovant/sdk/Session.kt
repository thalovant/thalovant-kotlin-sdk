package com.thalovant.sdk

import java.net.URI
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * How a [HubSession] waits, in seconds: the retry ladder and the probe cadence.
 *
 * The numbers are the appliance's. A hub link drops a few times a day and its
 * refusals clear in about a minute, so the ladder starts at ten seconds and
 * stops at two minutes; the probe comes round every minute while a link is
 * held and every five seconds while none is.
 *
 * [refusalGraceSeconds] is how long [HubSession.run] keeps trying through
 * refusals before it gives up: a connection just created is refused until its
 * hub has admitted it, about ninety seconds, so a refusal is only final once
 * it has lasted this long.
 */
public data class HubSessionPolicy(public val retrySeconds: Double = 10.0,
    public val retryCeilingSeconds: Double = 120.0, public val probeSeconds: Double = 60.0,
    public val probeDownSeconds: Double = 5.0, public val refusalGraceSeconds: Double = 600.0) {
    init { require(listOf(retrySeconds,retryCeilingSeconds,probeSeconds,probeDownSeconds,refusalGraceSeconds).all { it.isFinite() && it > 0 } && retryCeilingSeconds >= retrySeconds) }
    public fun nextWait(current: Double): Double = minOf(current*2,retryCeilingSeconds)
}
/** What happened to one attempt at keeping a link up; see [LinkSupervisor]. */
public enum class LinkOutcome(public val wireName: String) {
    /** The link came up. */
    UP("up"),

    /** An established link went down. */
    DROPPED("dropped"),

    /** The hub or the network could not be reached. */
    FAILED("failed"),

    /** The hub turned the credentials away ([ThalovantIdentityException]). */
    REFUSED("refused"),

    /** The hub's Noise key is not the pinned one ([ThalovantHubIdentityChangedException]). */
    KEY_CHANGED("key_changed"),
}

/** What a [LinkSupervisor] says to do next. */
public enum class LinkAction(public val wireName: String) {
    /** The link is up: keep it, and probe it. */
    HOLD("hold"),

    /** Dial again after [LinkDecision.waitSeconds]. */
    RETRY("retry"),

    /** Stop: retrying cannot help. [LinkDecision.reason] says why. */
    GIVE_UP("give_up"),
}

/** One decision of a [LinkSupervisor]: [action], after [waitSeconds], for [reason] when giving up. */
public data class LinkDecision(
    public val action: LinkAction,
    public val waitSeconds: Double = 0.0,
    public val reason: LinkOutcome? = null,
)

/**
 * How a long-lived link is kept up, as a pure function of what happened and when.
 *
 * [HubSession.run] asks it after every attempt, and a host that drives
 * [HubSession.connect] on its own schedule can ask it too. Every SDK keeps the
 * same rules (`link-keeping-vectors.json`):
 *
 * - [LinkOutcome.UP]: hold, and start the ladder and the refusal clock afresh;
 * - [LinkOutcome.DROPPED]: dial again at once;
 * - [LinkOutcome.FAILED]: wait the ladder's step -- [HubSessionPolicy.retrySeconds],
 *   doubling to [HubSessionPolicy.retryCeilingSeconds] -- and stop counting refusals;
 * - [LinkOutcome.REFUSED]: a new connection is refused until its hub admits
 *   it, so wait the ladder's step as for a failure, until refusals have lasted
 *   [HubSessionPolicy.refusalGraceSeconds] since the first of them; then give up;
 * - [LinkOutcome.KEY_CHANGED]: give up at once; retrying cannot change a key.
 *
 * Not thread-safe: one supervisor follows one link.
 */
public class LinkSupervisor(public val policy: HubSessionPolicy = HubSessionPolicy()) {
    private var wait = policy.retrySeconds
    private var refusedSince: Double? = null

    /** The decision after [outcome], observed at [nowSeconds] on any monotonic clock. */
    public fun after(outcome: LinkOutcome, nowSeconds: Double): LinkDecision {
        when (outcome) {
            LinkOutcome.UP -> {
                wait = policy.retrySeconds
                refusedSince = null
                return LinkDecision(LinkAction.HOLD)
            }
            LinkOutcome.DROPPED -> return LinkDecision(LinkAction.RETRY, 0.0)
            LinkOutcome.KEY_CHANGED -> return LinkDecision(LinkAction.GIVE_UP, reason = LinkOutcome.KEY_CHANGED)
            LinkOutcome.REFUSED -> {
                val since = refusedSince ?: nowSeconds.also { refusedSince = it }
                if (nowSeconds - since >= policy.refusalGraceSeconds) {
                    return LinkDecision(LinkAction.GIVE_UP, reason = LinkOutcome.REFUSED)
                }
            }
            LinkOutcome.FAILED -> refusedSince = null
        }
        val step = wait
        wait = policy.nextWait(wait)
        return LinkDecision(LinkAction.RETRY, step)
    }
}

public fun alive(client: ThalovantClient?): Boolean = client != null && client.transport.connected && client.transport.handshakeComplete
/**
 * Owns one hub connection, kept by policy rather than by luck.
 *
 * Either call [probe] at [probeDelay] intervals from the host, or let [run]
 * keep the link: it reconnects on the [HubSessionPolicy] ladder, notices a
 * dropped link as it drops, and treats refusals as "not admitted yet" for
 * [HubSessionPolicy.refusalGraceSeconds]. [connected] says whether the link is
 * up, as a [StateFlow]. Subscriptions made with [on] follow every client the
 * session builds.
 *
 * A failed admitted call is never replayed; Ask may trigger an action. Calls
 * take turns, except [reply]: an answer to something the hub asked goes out
 * on the live link at once, even while an [ask] is waiting for its own reply.
 */
public class HubSession(connect: suspend () -> ThalovantClient,
    public val policy: HubSessionPolicy = HubSessionPolicy(),
    private val clock: () -> Double = { System.nanoTime()/1_000_000_000.0 },
    warm: Boolean = true) {
    /** Builds and connects a client; `connect` itself is the one-attempt call below. */
    private val build: suspend () -> ThalovantClient = connect
    private val lock = Any()
    private val busy = Mutex()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var client: ThalovantClient? = null
    private var retired: ThalovantClient? = null
    private var closed = false
    private var warming: Job? = null
    private class Listener(val name: String,val handler: (ThalovantEvent)->Unit,var bound: ThalovantSubscription? = null)
    private val listeners = linkedSetOf<Listener>()
    private val closing = CompletableDeferred<Unit>()
    private val linkUp = MutableStateFlow(false)
    private val supervisor = LinkSupervisor(policy)

    /**
     * Whether a client is held and its link came up, as it changes: `true`
     * once a connection is made, `false` once one is dropped or the session
     * closes. What an app shows as "connected".
     */
    public val connected: StateFlow<Boolean> = linkUp.asStateFlow()
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
    // Failed disconnects can retain a live transport: retry cleanup before replacement.
    private suspend fun cleanup() { retired?.let { it.close(); retired=null } }
    private suspend fun drop() {
        synchronized(lock) {
            if (client != null) { retired=client; client=null }
            for (listener in listeners) { listener.bound?.close(); listener.bound=null }
        }
        linkUp.value = false
        cleanup()
    }
    private suspend fun ensure(): ThalovantClient {
        synchronized(lock) { check(!closed) { "Hub session is closed" }; client?.let { return it } }
        cleanup()
        var fresh: ThalovantClient? = null
        try {
            fresh=build()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!closed) { "Hub session is closed" }
                for (listener in listeners) listener.bound=fresh.on(listener.name,handler=listener.handler)
                client=fresh;retryAt=0.0;retryWait=policy.retrySeconds
            }
            linkUp.value = true
            return fresh
        } catch (error: Exception) {
            synchronized(lock) { retryAt=clock()+retryWait;retryWait=policy.nextWait(retryWait);retired=fresh }
            cleanupAfterFailure(error)
            throw error
        }
    }
    private suspend fun cleanupAfterFailure(original: Exception) {
        try { withContext(NonCancellable) { drop() } }
        catch (cleanup: Exception) { if (cleanup !== original) original.addSuppressed(cleanup) }
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
        catch(error: Exception) { cleanupAfterFailure(error);throw error }
    }
    public suspend fun ask(text: String,timeoutMs: Long=12000,lang: String="en-us",sessionId: String?=null,
        requestId: String?=null,context: JsonObject=EMPTY_JSON_OBJECT,replySettleMs: Long?=null,emptyReplyWaitMs: Long?=null): ThalovantReply =
        call { it.ask(text,timeoutMs,lang,sessionId,requestId,context,replySettleMs,emptyReplyWaitMs) }
    public suspend fun emit(eventType: String,data: JsonObject=EMPTY_JSON_OBJECT,context: JsonObject=EMPTY_JSON_OBJECT): Unit = call { it.emit(eventType,data,context) }

    /**
     * Answers a message the hub sent, back along the route it came
     * ([ThalovantClient.reply]).
     *
     * Never waits behind a running [ask]. An ask holds the session for as long
     * as it waits for its answer -- seconds -- and a hub that asked this client
     * something waits for its reply with a deadline of its own: a Home
     * Assistant request is timed out after ten seconds. So a reply goes out on
     * the live link at once. Only when no link is up does it connect first,
     * taking its turn like any other call.
     *
     * Withdrawing a reply -- cancelling it while it still waits, as the home
     * link does when the hub's bound passes -- is not a failure of the link:
     * it is not sent, and the connection is left exactly as it was. A
     * connection this reply had to make is the session's, made on the
     * session's own scope, and stays up whether the reply is withdrawn or not.
     */
    public suspend fun reply(event: ThalovantEvent,msgType: String,data: JsonObject=EMPTY_JSON_OBJECT,context: JsonObject=EMPTY_JSON_OBJECT) {
        val live = synchronized(lock) { check(!closed) { "Hub session is closed" }; client }
        val target = if (live != null && alive(live)) live else scope.async {
            busy.withLock {
                val current = synchronized(lock) { client }
                if (current != null && !alive(current)) drop()
                ensure()
            }
        }.await()
        target.reply(event, msgType, data, context)
    }

    /**
     * Makes one attempt now: returns with a live link, or throws why there is none.
     *
     * [ThalovantIdentityException] when the hub turned the credentials away --
     * a connection it has not admitted yet, or one that was deleted -- and
     * [ThalovantConnectionException] or [ThalovantTimeoutException] for
     * everything else. A link already up is kept; this does not dial again.
     * Not held back by the retry ladder: a host that asks gets an attempt.
     */
    public suspend fun connect() {
        busy.withLock {
            val current = synchronized(lock) { check(!closed) { "Hub session is closed" }; client }
            if (current != null) {
                if (alive(current)) return
                log.fine { "hub link: dropped" }
                drop()
            }
            log.fine { "hub link: connecting" }
            ensure()
            synchronized(supervisor) { supervisor.after(LinkOutcome.UP, clock()) }
            log.fine { "hub link: up" }
        }
    }

    /**
     * Stays connected until [close], by policy; returns once the session is closed.
     *
     * A link [connect] already opened is the one kept; it is not dialled
     * again. While a link is held this watches it, notices a drop as it
     * happens rather than at the next probe, and dials again at once. After a
     * failed attempt it waits [HubSessionPolicy.retrySeconds], doubling up to
     * [HubSessionPolicy.retryCeilingSeconds]. [LinkSupervisor] holds these
     * rules, and every SDK keeps them.
     *
     * A hub refusing the credentials ([ThalovantIdentityException]) is retried
     * like any other failure until the refusals have lasted
     * [HubSessionPolicy.refusalGraceSeconds] -- a connection just created is
     * refused until its hub admits it -- and then that refusal is thrown. A
     * hub whose key no longer matches the pinned one
     * ([ThalovantHubIdentityChangedException]) is thrown at once: waiting does
     * not change a key. Anything that is not a connection failure is thrown
     * as it happens.
     *
     * Every attempt, drop and recovery is logged at `FINE` on the
     * `com.thalovant.sdk.session` logger and nowhere louder; what deserves more
     * is for the application to say.
     */
    public suspend fun run() {
        while (!isClosed()) {
            val current = synchronized(lock) { client }
            if (current != null && alive(current)) {
                awaitDropOrProbe(current)
                if (isClosed()) return
                if (!alive(current)) {
                    log.fine { "hub link: dropped" }
                    busy.withLock { if (synchronized(lock) { client } === current) drop() }
                    decide(LinkOutcome.DROPPED)
                }
                continue
            }
            val decision = try {
                connect()
                continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: ThalovantHubIdentityChangedException) {
                if (isClosed()) return
                log.fine { "hub link: the hub's key changed (${changed.message})" }
                decide(LinkOutcome.KEY_CHANGED)
                throw changed
            } catch (refusal: ThalovantIdentityException) {
                if (isClosed()) return
                log.fine { "hub link: refused (${refusal.message})" }
                decide(LinkOutcome.REFUSED).also { if (it.action == LinkAction.GIVE_UP) throw refusal }
            } catch (failure: Exception) {
                if (isClosed()) return
                if (failure !is ThalovantConnectionException && failure !is ThalovantTimeoutException &&
                    failure !is java.io.IOException) throw failure
                log.fine { "hub link: attempt failed (${failure.message})" }
                decide(LinkOutcome.FAILED)
            }
            log.fine { "hub link: next attempt in ${decision.waitSeconds}s" }
            if (decision.waitSeconds > 0) withTimeoutOrNull(millis(decision.waitSeconds)) { closing.await() }
        }
    }

    private fun decide(outcome: LinkOutcome): LinkDecision = synchronized(supervisor) { supervisor.after(outcome, clock()) }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    /** Returns when [current]'s link ends, the probe interval passes, or the session closes. */
    private suspend fun awaitDropOrProbe(current: ThalovantClient) {
        val ended = (current.transport as? HiveMindWssTransport)?.linkEnded()
        withTimeoutOrNull(millis(policy.probeSeconds)) {
            if (ended != null) {
                select<Unit> {
                    closing.onAwait {}
                    ended.onAwait {}
                }
            } else {
                // A transport of the caller's own says nothing when it drops.
                while (!closing.isCompleted && alive(current)) delay(LINK_POLL_MS)
            }
        }
    }

    /** Terminal close waits for admitted work before retiring the connection. */
    public suspend fun close() {
        synchronized(lock) { closed=true }
        closing.complete(Unit)
        try { busy.withLock { drop();synchronized(lock) { listeners.clear() } } }
        finally { scope.cancel() }
    }

    private companion object {
        val log: Logger = Logger.getLogger("com.thalovant.sdk.session")

        /** How often [awaitDropOrProbe] looks at a transport that cannot say it dropped. */
        const val LINK_POLL_MS: Long = 250

        fun millis(seconds: Double): Long = (seconds * 1_000).toLong().coerceAtLeast(1)
    }
}

/**
 * Answers one `thalovant.home.request` this session received, on the live
 * link and without waiting behind a running [HubSession.ask], within
 * [hubTimeoutMs] from now; null when no reply could be sent in time. See
 * [ThalovantClient.answerHomeRequest].
 */
public suspend fun HubSession.answerHomeRequest(
    event: ThalovantEvent,
    timeoutMs: Long = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
    hubTimeoutMs: Long = ThalovantHome.REQUEST_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
): JsonObject? = answerHomeRequestWith(event, timeoutMs, handler, hubTimeoutMs) { request, payload ->
    reply(request, ThalovantHome.RESPONSE, payload)
}

/**
 * Answers every `thalovant.home.request` this session receives, on every
 * client it builds, until the calling coroutine is cancelled; it never
 * returns otherwise. This is the Home Assistant link:
 *
 * ```
 * val session = HubSession(connect = { ThalovantClient(identity).also { it.connect() } }, warm = false)
 * launch { session.run() }
 * launch {
 *     session.answerHomeRequests { request ->
 *         HomeAnswer(speech = "Turned off the kitchen light.", responseType = ThalovantHome.ACTION_DONE)
 *     }
 * }
 * ```
 *
 * Each request is answered on a coroutine of its own and within [timeoutMs];
 * the reply never waits behind a running [HubSession.ask]. See
 * [ThalovantHome] for the rules every answer keeps.
 */
public suspend fun HubSession.answerHomeRequests(
    timeoutMs: Long = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
): Nothing = serveHomeRequests(
    subscribe = { listener -> on(ThalovantHome.REQUEST, listener) },
    timeoutMs = timeoutMs,
    handler = handler,
) { request, payload -> reply(request, ThalovantHome.RESPONSE, payload) }
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
