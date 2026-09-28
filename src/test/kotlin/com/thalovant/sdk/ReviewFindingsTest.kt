package com.thalovant.sdk

import java.nio.file.Files
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/** The 0.9.1 behaviour the shared vectors do not reach, and the review findings behind it. */
class ReviewFindingsTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun api(token: String? = null) = ThalovantControlPlane(server.url("/").toString(), accessToken = token)

    private fun answer(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    @Test
    fun `loginWithBrowser names a registered app, and leaves out none`(): Unit = runBlocking {
        answer(200, """{"device_code":"dc-1","user_code":"U","verification_uri":"https://thalovant.com/activate","interval":0}""")
        answer(200, """{"access_token":"tvt-1"}""")
        api().loginWithBrowser(DeviceLoginOptions(clientId = HOME_ASSISTANT_CLIENT_ID, openBrowser = false, prompt = {}))
        assertEquals("""{"client_id":"thalovant-home-assistant"}""", server.takeRequest().body.readUtf8())
        server.takeRequest()
        answer(200, """{"device_code":"dc-1","user_code":"U","verification_uri":"https://thalovant.com/activate","interval":0}""")
        api().beginDeviceLogin(scopes = HOME_ASSISTANT_SCOPES)
        assertFalse("client_id" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a describe's user code is one path segment, and verified needs an id`(): Unit = runBlocking {
        answer(200, """{"scopes":["hubs:read"],"client_name":"Home Assistant","client_verified":true}""")
        val request = api("t").describeDeviceLogin("WDJB/../x")
        assertEquals("/v1/auth/device/codes/WDJB%2F..%2Fx", server.takeRequest().requestUrl?.encodedPath)
        // Claimed verified with no app id: nothing vouches for it.
        assertFalse(request.clientVerified)
    }

    @Test
    fun `each device-token POST is bounded by what is left of the sign-in`(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertFailsWith<ThalovantTimeoutException> { api().pollDeviceToken("dc-1", intervalMillis = 10, timeoutMillis = 300) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 2_000, "the POST ran $tookMs ms past a 300 ms sign-in")
    }

    @Test
    fun `an operation status this SDK does not know keeps its id`(): Unit = runBlocking {
        answer(201, """{"id":"c-1","etag":"e-1","spec":{"version":"1","connection_type":"home_assistant"},"operation":{"id":"op-7","kind":"client.sync","aggregate_type":"client","status":"reconciling","created_at":"t","updated_at":"t","links":{"self":"/v1/operations/op-7"}}}""")
        val result = api("t").createClientIdentity(
            buildJsonObject { put("id", "hub-1"); put("domain", "kitchen.thalovant.io") },
            CreateClientIdentityOptions(name = "HA", connectionType = CONNECTION_TYPE_HOME_ASSISTANT),
        )
        assertNull(result.operation)
        assertEquals("op-7", result.operationId)
        assertEquals("reconciling", result.operationStatus)
        // And the wait keeps polling through it.
        server.takeRequest()
        answer(200, """{"id":"op-7","status":"reconciling"}""")
        answer(200, """{"id":"op-7","status":"ready"}""")
        api("t").waitForAdmission(result, timeoutMs = 5_000, pollIntervalMs = 5)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `speakable is one pass, and says what substituting innermost first said`() {
        val cases = mapOf(
            "mute it [for a (second|bit)]" to "mute it",
            "did i (already |)ask (about|for|to|) {thing}" to "did i ask about thing",
            "(turn|switch) [the] (light|lamp) (on|)" to "turn light",
            "a] b [c" to "a] b [c",
            "((a|b)|c) d" to "a d",
            "[a (b|c)" to "[a b",
        )
        for ((pattern, said) in cases) assertEquals(said, speakable(pattern), pattern)
        val deep = "(".repeat(16_000) + "x" + ")".repeat(16_000) + " [" .repeat(16_000) + "y" + "]".repeat(16_000)
        val started = System.nanoTime()
        assertEquals("x", speakable(deep))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "a pass per nesting level")
    }

    private fun deflate(bytes: ByteArray, whole: Boolean = true): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        if (whole) deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(65536)
        while (true) {
            val written = deflater.deflate(chunk, 0, chunk.size, if (whole) Deflater.NO_FLUSH else Deflater.SYNC_FLUSH)
            if (written == 0) break
            out.write(chunk, 0, written)
            if (whole && deflater.finished()) break
        }
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun `a compressed block is capped at 32 MiB, and a truncated one refused`() {
        assertContentEquals("hello".toByteArray(), HiveWire.inflate(deflate("hello".toByteArray())))
        val bomb = deflate(ByteArray(MAX_INFLATED + 1))
        assertTrue(bomb.size < 100_000)
        assertFailsWith<ThalovantRuntimeException> { HiveWire.inflate(bomb) }
        assertEquals(MAX_INFLATED, HiveWire.inflate(deflate(ByteArray(MAX_INFLATED))).size)
        assertFailsWith<ThalovantRuntimeException> { HiveWire.inflate(deflate("truncated".repeat(100).toByteArray(), whole = false)) }
    }

    @Test
    fun `a WIRE-1 binary frame is the hub speaking too, so a close after it is a drop`() {
        val frames = ThalovantJson.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/binary-frames.json")!!.bufferedReader().use { it.readText() },
        ).jsonObject.getValue("cases").jsonArray
        val frame = Base64.getDecoder().decode(frames.first().jsonObject.getValue("frame").jsonPrimitive.content)
        FakeHub().use { hub ->
            hub.refuseNext.set(1)
            hub.speakBeforeClosing = true
            hub.speakBinary = frame
            val failure = assertFailsWith<ThalovantException> { runBlocking { hub.connectedClient() } }
            assertFalse(failure is ThalovantIdentityException, "a hub that spoke did not refuse: $failure")
            assertIs<ThalovantConnectionException>(failure)
        }
    }

    @Test
    fun `a reply that went out counts as the link being up`(): Unit = runBlocking {
        val outbox = object : HiveMindRuntimeTransport {
            override var connected = false
            override val handshakeComplete get() = connected
            override suspend fun connect(timeoutMs: Long) { connected = true }
            override suspend fun disconnect() { connected = false }
            override suspend fun emitBus(eventType: String, data: kotlinx.serialization.json.JsonObject, context: kotlinx.serialization.json.JsonObject) {}
            override fun addBusListener(listener: (ThalovantEvent) -> Unit) = ThalovantSubscription {}
        }
        val identity = ThalovantIdentity(buildJsonObject { put("access_key", "a"); put("password", "p"); put("site_id", "s"); put("default_master", "wss://h") })
        val policy = HubSessionPolicy(retrySeconds = 1.0, retryCeilingSeconds = 8.0)
        val session = HubSession(connect = { ThalovantClient(identity, transport = outbox).also { it.connect() } }, policy = policy, warm = false)
        try {
            // Two failures move the ladder on ...
            session.supervisor.after(LinkOutcome.FAILED, 0.0)
            session.supervisor.after(LinkOutcome.FAILED, 1.0)
            session.reply(ThalovantEvent("mycroft.volume.get"), "mycroft.volume.get.response")
            // ... and a reply that went out starts it again, as a connect does.
            assertEquals(1.0, session.supervisor.after(LinkOutcome.FAILED, 2.0).waitSeconds)
        } finally {
            session.close()
        }
    }

    @Test
    fun `an identity read from a file keeps its key beside it, adopting the old one only for a hub it met`() {
        val home = Files.createTempDirectory("thalovant-home")
        val original = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            val legacy = HiveMindNoiseStore()
            val legacyKey = legacy.staticKey()
            legacy.verifyOrPin("hub-a", ByteArray(32) { 1 })
            fun identityFile(name: String): java.nio.file.Path {
                val dir = Files.createDirectories(home.resolve(name))
                val file = dir.resolve("identity.json")
                Files.writeString(file, """{"access_key":"a","password":"p","site_id":"s","default_master":"wss://h"}""")
                runCatching { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
                return file
            }
            // A hub the old key met: the key and its pins are copied, not moved.
            val met = HiveMindNoiseStore.forIdentity(ThalovantIdentity.fromFile(identityFile("met")))
            assertEquals(home.resolve("met").resolve(HiveMindNoiseStore.IDENTITY_KEY_FOLDER), met.directory)
            assertEquals(HiveMindNoiseStore.defaultDirectory(), met.otherFolder)
            assertContentEquals(ByteArray(32) { 1 }, met.pin("hub-a"))
            assertContentEquals(legacyKey, met.staticKey())
            assertContentEquals(legacyKey, legacy.staticKey(), "never moved")
            // A hub it never met: a key of its own.
            val fresh = HiveMindNoiseStore.forIdentity(ThalovantIdentity.fromFile(identityFile("fresh")))
            assertNull(fresh.pin("hub-b"))
            assertFalse(legacyKey.contentEquals(fresh.staticKey()))
            // An identity from anywhere else keeps the shared default.
            val plain = ThalovantIdentity(buildJsonObject { put("access_key", "a"); put("password", "p"); put("site_id", "s"); put("default_master", "wss://h") })
            assertEquals(HiveMindNoiseStore.defaultDirectory(), HiveMindNoiseStore.forIdentity(plain).directory)
        } finally {
            System.setProperty("user.home", original)
            home.toFile().deleteRecursively()
        }
    }
}
