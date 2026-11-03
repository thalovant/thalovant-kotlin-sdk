package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * Review threads left open on merged pull requests (#35, #41, #46) that still
 * held on `main`, each pinned so that undoing its fix fails here.
 */
class CodeRabbitFollowupsTest {
    private val identity = ThalovantIdentity(
        Json.parseToJsonElement(
            """{"access_key":"access","password":"password","site_id":"site","default_master":"wss://hub.example"}""",
        ).jsonObject,
    )

    // -- #41: the JVM constructor from before the acceptance window ----------

    @Test
    fun `the transport constructor from before the acceptance window still links`() {
        val type = HiveMindWssTransport::class.java
        // What Java calls, and what a Java class compiled against 0.7.12 names.
        val fourArguments = type.getConstructor(
            ThalovantIdentity::class.java, String::class.java, OkHttpClient::class.java, HiveMindNoiseStore::class.java,
        )
        assertFalse(fourArguments.isSynthetic, "a synthetic constructor is invisible to Java source")
        // What a Kotlin caller compiled against 0.7.12 calls when it leaves
        // arguments out: the defaults bridge of that same signature.
        type.getDeclaredConstructor(
            ThalovantIdentity::class.java, String::class.java, OkHttpClient::class.java, HiveMindNoiseStore::class.java,
            Int::class.javaPrimitiveType, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
        // And it waits the window everybody else gets.
        val store = HiveMindNoiseStore(Files.createTempDirectory("noise-compat"))
        val transport = fourArguments.newInstance(identity, DEFAULT_USER_AGENT, null, store)
        val window = type.getDeclaredField("acceptanceWindowMs").also { it.isAccessible = true }
        assertEquals(DEFAULT_ACCEPTANCE_WINDOW_MS, window.getLong(transport))
        // The five-argument form is still the one that sets it.
        val five = type.getConstructor(
            ThalovantIdentity::class.java, String::class.java, OkHttpClient::class.java, HiveMindNoiseStore::class.java,
            Long::class.javaPrimitiveType,
        ).newInstance(identity, DEFAULT_USER_AGENT, null, store, 0L)
        assertEquals(0L, window.getLong(five))
    }

    // -- #46: a link made by any call clears the refusal clock ---------------

    @Test
    fun `a link an emit brings up gives a later refusal a fresh grace`(): Unit = runBlocking {
        var now = 0.0
        val session = HubSession(
            connect = { ThalovantClient(identity, transport = UpTransport(), replySettleMs = 0, emptyReplyWaitMs = 0).also { it.connect() } },
            policy = HubSessionPolicy(retrySeconds = 0.01, retryCeilingSeconds = 0.02, refusalGraceSeconds = 10.0),
            clock = { now },
            warm = false,
        )
        try {
            // run() saw the hub refuse at 0 s: the connection was not admitted yet.
            synchronized(session.supervisor) { session.supervisor.after(LinkOutcome.REFUSED, now) }
            // An emit, not connect(), is what brings the link up a moment later.
            now = 1.0
            session.emit("hello")
            assertTrue(session.held)
            // Long after, the link drops and the next dial is refused. That
            // refusal starts its own grace; it is not the 0 s one, long expired.
            now = 1_000.0
            val decision = synchronized(session.supervisor) { session.supervisor.after(LinkOutcome.REFUSED, now) }
            assertEquals(LinkAction.RETRY, decision.action, "a refusal after a link that came up was judged on the old grace")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a refusal run() saw is not recorded over a link another call brought up meanwhile`(): Unit = runBlocking {
        var now = 0.0
        var attempts = 0
        val session = HubSession(
            connect = {
                attempts++
                if (attempts == 1) throw ThalovantIdentityException("not admitted yet")
                ThalovantClient(identity, transport = UpTransport(), replySettleMs = 0, emptyReplyWaitMs = 0).also { it.connect() }
            },
            policy = HubSessionPolicy(retrySeconds = 0.01, retryCeilingSeconds = 0.02, refusalGraceSeconds = 10.0),
            clock = { now },
            warm = false,
        )
        // run()'s first attempt is refused. Before it records that, an emit
        // takes the session it just let go of and brings the link up.
        session.beforeFailedAttemptDecision = {
            session.beforeFailedAttemptDecision = null
            session.emit("hello")
        }
        val running = launch { session.run() }
        try {
            withTimeout(5_000) { while (!session.held) delay(5) }
            assertEquals(2, attempts)
            // Much later the link drops and the next dial is refused: a new
            // refusal, with a grace of its own, not the one from before the link.
            now = 1_000.0
            val decision = synchronized(session.supervisor) { session.supervisor.after(LinkOutcome.REFUSED, now) }
            assertEquals(LinkAction.RETRY, decision.action, "a stale refusal was recorded after the link came up")
        } finally {
            session.close()
            running.join()
        }
    }

    // -- #35: publication without links never replaces a published key ------

    @Test
    fun `without links a publisher holding the lock is waited for and its key is the one used`() {
        val dir = Files.createTempDirectory("noise-publish-lock")
        try {
            val theirs = "ab".repeat(32)
            // Another process takes the publication lock and publishes while
            // holding it, the way a store in that process would.
            val holder = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("noise.test.classpath"),
                "com.thalovant.sdk.NoisePublishLockHolder", dir.toString(), "1500", theirs,
            ).redirectErrorStream(true).start()
            try {
                val reader = holder.inputStream.bufferedReader()
                assertEquals("locked", reader.readLine(), "the lock holder did not start")
                val store = HiveMindNoiseStore(dir).also { it.hardLinks = false }
                val ours = store.staticKey()
                assertTrue(holder.waitFor(20, TimeUnit.SECONDS), "the lock holder did not finish")
                assertEquals(0, holder.exitValue(), reader.readText())
                // Ours is theirs: this store waited, found their key and took it,
                // rather than publishing its own a moment before theirs replaced it.
                assertEquals(theirs, Noise.hex(ours))
                assertContentEquals(ours, HiveMindNoiseStore(dir).staticKey())
            } finally {
                holder.destroyForcibly()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `without links a key is still published and read back`() {
        val dir = Files.createTempDirectory("noise-no-links")
        try {
            val store = HiveMindNoiseStore(dir).also { it.hardLinks = false }
            val key = store.staticKey()
            assertEquals(64L, Files.size(dir.resolve("noise-static.key")))
            assertContentEquals(key, HiveMindNoiseStore(dir).also { it.hardLinks = false }.staticKey())
            // The pin path publishes the same way and still refuses a second key.
            store.verifyOrPin("node-a", ByteArray(32) { 1 })
            assertTrue(runCatching { store.verifyOrPin("node-a", ByteArray(32) { 2 }) }.isFailure)
            assertContentEquals(ByteArray(32) { 1 }, store.pin("node-a"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** A transport that is up once connected and swallows what it is sent. */
private class UpTransport : HiveMindRuntimeTransport {
    override var connected = false
    override val handshakeComplete get() = connected
    override suspend fun connect(timeoutMs: Long) { connected = true }
    override suspend fun disconnect() { connected = false }
    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription = ThalovantSubscription { }
    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {}
}

/**
 * Another process publishing a key while it holds the publication lock:
 * prints `locked`, waits, writes the key, then lets go.
 */
internal object NoisePublishLockHolder {
    @JvmStatic fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        java.nio.channels.FileChannel.open(
            dir.resolve(HiveMindNoiseStore.PUBLISH_LOCK),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            val held = channel.lock()
            println("locked")
            System.out.flush()
            Thread.sleep(args[1].toLong())
            val key = dir.resolve("noise-static.key")
            Files.write(key, args[2].toByteArray())
            try {
                Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) { }
            held.release()
        }
    }
}
