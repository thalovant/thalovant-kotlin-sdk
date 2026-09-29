package com.thalovant.sdk

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The Home Assistant link, against the four shared vector files.
 *
 * `device-login`, `connection-kinds` and `connection-admission` are HTTP
 * exchanges: each case's answers are served by a loopback API, in order, and
 * every request this SDK sends through OkHttp is checked against the one the
 * case expects -- method, path, body or body subset, `If-Match`,
 * `Authorization`. `home-link` holds the reply routing, and the answer rules,
 * which run through [ThalovantClient.answerHomeRequest] and so through the
 * client's own reply path. What this SDK produced for each case is recorded
 * before it is compared, shaped exactly as the Python reference's
 * `tests/test_home_link_vectors.py` shapes it.
 */
class HomeLinkVectorsTest {

    private fun vectors(name: String): JsonObject =
        ThalovantJson.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private val device = vectors("device-login-vectors.json")
    private val kinds = vectors("connection-kinds-vectors.json")
    private val admission = vectors("connection-admission-vectors.json")
    private val home = vectors("home-link-vectors.json")

    private fun JsonObject.cases(): List<JsonObject> = getValue("cases").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Serves a case's exchanges in order and checks each request against its own. */
    private class ScriptedApi(private val exchanges: List<JsonObject>) : AutoCloseable {
        private val server = MockWebServer()
        val sent = CopyOnWriteArrayList<String>()
        val mismatches = CopyOnWriteArrayList<String>()

        @Volatile
        var index = 0
            private set

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@ScriptedApi) {
                    answer(request)
                }
            }
            server.start()
        }

        val url: String get() = server.url("/").toString()
        val host: String get() = server.hostName
        val port: Int get() = server.port

        private fun answer(request: RecordedRequest): MockResponse {
            val ifMatch = request.getHeader("If-Match")
            val path = request.requestUrl!!.encodedPath
            sent += "${request.method} $path" + (ifMatch?.let { " If-Match=$it" } ?: "")
            if (index >= exchanges.size) {
                mismatches += "unexpected ${request.method} $path"
                return MockResponse().setResponseCode(599).setBody("{}")
            }
            val exchange = exchanges[index]
            if ((exchange["repeat"] as? JsonPrimitive)?.booleanOrNull != true) index++
            val expected = exchange.getValue("request").jsonObject
            val method = expected.getValue("method").jsonPrimitive.content
            val expectedPath = expected.getValue("path").jsonPrimitive.content
            if (request.method != method || path != expectedPath) mismatches += "${request.method} $path != $method $expectedPath"
            val raw = request.body.readUtf8()
            val body = if (raw.isEmpty()) null else ThalovantJson.parseToJsonElement(raw)
            expected["json"]?.let { if (body != it) mismatches += "body $body != $it" }
            expected["json_subset"]?.let { if (!contains(body, it)) mismatches += "body $body lacks $it" }
            expected["if_match"]?.let { if (ifMatch != it.jsonPrimitive.content) mismatches += "If-Match $ifMatch != $it" }
            expected["authorization"]?.let {
                if (request.getHeader("Authorization") != it.jsonPrimitive.content) mismatches += "wrong Authorization header"
            }
            val response = exchange.getValue("response").jsonObject
            val text = response.getValue("body").jsonPrimitive.content
            return MockResponse().setResponseCode(response.getValue("status").jsonPrimitive.int).apply {
                for ((header, value) in response["headers"]?.jsonObject.orEmpty()) setHeader(header, value.jsonPrimitive.content)
                if (text.isNotEmpty()) setHeader("Content-Type", response.getValue("content_type").jsonPrimitive.content)
                setBody(text)
            }
        }

        private fun contains(value: JsonElement?, subset: JsonElement): Boolean = when (subset) {
            is JsonObject -> value is JsonObject && subset.all { (key, item) -> key in value && contains(value[key], item) }
            else -> value == subset
        }

        override fun close() = server.shutdown()
    }

    /** A whole number spelled as one, as the reference's `_number()` spells it. */
    private fun number(value: Double): JsonPrimitive =
        if (value == floor(value) && value.isFinite()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    /** Neither the device code nor any credential ever reaches what a log line can show. */
    private fun excluded(error: Throwable, spec: JsonObject) {
        for (text in spec["message_excludes"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
            assertFalse(text in error.message.orEmpty(), text)
            assertFalse(text in error.toString(), text)
            assertFalse(text in error.stackTraceToString(), text)
        }
    }

    private fun apiFields(error: ThalovantApiException): JsonObject = buildJsonObject {
        put("status", error.statusCode)
        put("code", error.errorCode)
        put("detail", error.detail)
    }

    // -- device login ---------------------------------------------------------

    /** A failure, with the api-errors fields when the API answered one. */
    private fun deviceError(error: ThalovantApiException): JsonObject = buildJsonObject {
        put("outcome", "error")
        put("status", error.statusCode)
        if (error.statusCode != null) {
            put("code", error.errorCode)
            put("detail", error.detail)
        }
    }

    private suspend fun pollOnce(plane: ThalovantControlPlane, authorization: DeviceAuthorization): JsonObject {
        val token = try {
            plane.pollDeviceLogin(authorization)
        } catch (pending: ThalovantDeviceLoginPendingException) {
            return buildJsonObject {
                put("outcome", "pending")
                put("interval", number(pending.intervalMillis / 1_000.0))
            }
        } catch (error: ThalovantDeviceLoginExpiredException) {
            excluded(error, device)
            return buildJsonObject { put("outcome", "expired"); put("status", error.statusCode) }
        } catch (error: ThalovantDeviceLoginDeniedException) {
            excluded(error, device)
            return buildJsonObject { put("outcome", "denied"); put("status", error.statusCode) }
        } catch (error: ThalovantApiException) {
            excluded(error, device)
            return deviceError(error)
        }
        assertEquals(token.accessToken, plane.accessToken)
        assertEquals(token.tokenId, plane.tokenId)
        // A plain class: printing a token never prints the credential.
        assertFalse(token.accessToken in token.toString())
        return buildJsonObject {
            put("outcome", "approved")
            put("token_type", token.tokenType)
            put("scopes", JsonArray(token.scopes.map(::JsonPrimitive)))
            put("expires_at", token.expiresAt)
            put("token_id", token.tokenId)
        }
    }

    private fun deviceCase(case: JsonObject): Pair<JsonArray, ScriptedApi> = runBlocking {
        val call = case.getValue("call").jsonObject
        val api = ScriptedApi(case.getValue("exchanges").jsonArray.map { it.jsonObject })
        api.use {
            // Only the approver's read is signed in; a device signing in has no token yet.
            val plane = if (call.text("op") == "describe") ThalovantControlPlane(api.url, accessToken = "synthetic-token")
            else ThalovantControlPlane(api.url)
            val produced = mutableListOf<JsonElement>()
            when (call.text("op")) {
                "describe" -> try {
                    val request = plane.describeDeviceLogin(call.text("user_code")!!)
                    produced += buildJsonObject {
                        put("outcome", "described")
                        put("scopes", JsonArray(request.scopes.map(::JsonPrimitive)))
                        put("client_name", request.clientName)
                        put("client_id", request.clientId)
                        put("client_verified", request.clientVerified)
                        put("device_name", request.deviceName)
                    }
                } catch (error: ThalovantApiException) {
                    produced += deviceError(error)
                }
                "begin" -> try {
                    val grant = plane.beginDeviceLogin(
                        scopes = call["scopes"]?.jsonArray?.map { it.jsonPrimitive.content },
                        clientName = call.text("client_name"),
                        clientId = call.text("client_id"),
                    )
                    assertFalse(grant.deviceCode in grant.toString())
                    produced += buildJsonObject {
                        put("outcome", "started")
                        put("user_code", grant.userCode)
                        put("verification_uri", grant.verificationUri)
                        put("verification_uri_complete", grant.verificationUriComplete)
                        put("interval", number(grant.intervalMillis / 1_000.0))
                        put("expires_in", number(grant.expiresInMillis / 1_000.0))
                    }
                } catch (error: ThalovantApiException) {
                    excluded(error, device)
                    produced += deviceError(error)
                }
                else -> {
                    val held = call.getValue("authorization").jsonObject
                    val authorization = DeviceAuthorization(
                        deviceCode = held.getValue("device_code").jsonPrimitive.content,
                        userCode = "",
                        verificationUri = "https://x",
                        intervalMillis = (held.getValue("interval").jsonPrimitive.double * 1_000).toLong(),
                    )
                    repeat(call["times"]?.jsonPrimitive?.int ?: 1) { produced += pollOnce(plane, authorization) }
                    if (call.text("op") == "revoke") {
                        plane.revokeApiToken()
                        assertNull(plane.accessToken)
                        assertNull(plane.tokenId)
                        produced.clear()
                        produced += buildJsonObject { put("outcome", "revoked") }
                        // Idempotent: revoking again sends nothing and succeeds.
                        val sent = api.sent.size
                        plane.revokeApiToken()
                        assertEquals(sent, api.sent.size)
                    }
                }
            }
            JsonArray(produced) to api
        }
    }

    @Test
    fun `device login runs every shared vector`() {
        for (case in device.cases()) {
            val name = case.text("name")!!
            val (produced, api) = deviceCase(case)
            ConformanceRecord.record("device-login-vectors.json", name, produced)
            assertTrue(api.mismatches.isEmpty(), "$name: ${api.mismatches}")
            assertEquals(case.getValue("exchanges").jsonArray.size, api.index, "$name: not every exchange was used")
            assertEquals(case.getValue("expect"), produced, name)
        }
    }

    // -- connection kinds -----------------------------------------------------

    private fun kindOutcome(error: ThalovantApiException): String = when (error) {
        is ThalovantPlanException -> "plan"
        is ThalovantAlreadyLinkedException -> "already_linked"
        is ThalovantAuthException -> "auth"
        else -> "error"
    }

    private fun kindsCase(case: JsonObject): Pair<JsonObject, ScriptedApi> = runBlocking {
        val call = case.getValue("call").jsonObject
        val api = ScriptedApi(case.getValue("exchanges").jsonArray.map { it.jsonObject })
        api.use {
            val plane = ThalovantControlPlane(api.url, accessToken = "synthetic-token")
            val produced = LinkedHashMap<String, JsonElement>()
            if (call.text("op") == "create") {
                try {
                    val result = plane.createClientIdentity(
                        call.getValue("hub").jsonObject,
                        CreateClientIdentityOptions(
                            name = call.text("name")!!,
                            connectionType = call.text("connection_type"),
                        ),
                    )
                    produced["outcome"] = JsonPrimitive("created")
                    produced["client_id"] = JsonPrimitive(result.clientId)
                    produced["connection_type"] = JsonPrimitive(result.connectionType)
                    produced["operation_id"] = JsonPrimitive(result.operation?.id)
                } catch (error: ThalovantUnsupportedConnectionTypeException) {
                    excluded(error, kinds)
                    produced["outcome"] = JsonPrimitive("unsupported")
                    if (error.statusCode != null) {
                        produced.putAll(apiFields(error))
                    } else {
                        produced["deleted"] = JsonPrimitive(api.sent.any { it.startsWith("DELETE ") })
                    }
                } catch (error: ThalovantApiException) {
                    excluded(error, kinds)
                    produced["outcome"] = JsonPrimitive(kindOutcome(error))
                    produced.putAll(apiFields(error))
                    if (error is ThalovantAlreadyLinkedException) produced["client_id"] = JsonPrimitive(error.clientId)
                }
            } else {
                try {
                    plane.deleteClient(call.text("client_id")!!, call.text("etag"))
                    produced["outcome"] = JsonPrimitive("deleted")
                } catch (error: ThalovantApiException) {
                    produced["outcome"] = JsonPrimitive(kindOutcome(error))
                    produced.putAll(apiFields(error))
                }
            }
            produced["requests"] = JsonArray(api.sent.map(::JsonPrimitive))
            JsonObject(produced) to api
        }
    }

    @Test
    fun `connection kinds run every shared vector`() {
        for (case in kinds.cases()) {
            val name = case.text("name")!!
            val (produced, api) = kindsCase(case)
            ConformanceRecord.record("connection-kinds-vectors.json", name, produced)
            assertTrue(api.mismatches.isEmpty(), "$name: ${api.mismatches}")
            assertEquals(case.getValue("expect"), produced, name)
        }
    }

    // -- admission ------------------------------------------------------------

    private fun admissionCase(case: JsonObject): Pair<JsonObject, ScriptedApi> = runBlocking {
        val call = case.getValue("call").jsonObject
        val api = ScriptedApi(case["exchanges"]?.jsonArray.orEmpty().map { it.jsonObject })
        api.use {
            val resetting = if (call.text("api") == "unreachable") ResettingListener() else null
            val url = resetting?.let { "http://127.0.0.1:${it.port}/" } ?: api.url
            val plane = ThalovantControlPlane(url, accessToken = "synthetic-token")
            val operation = (call["operation"] as? JsonObject)?.let {
                val placed = ThalovantJson.parseToJsonElement(
                    it.toString().replace("{api_host}", api.host).replace("{api_port}", api.port.toString()),
                )
                ThalovantJson.decodeFromJsonElement(OperationResource.serializer(), placed)
            }
            val expect = case.getValue("expect").jsonObject
            val started = System.nanoTime()
            val produced = LinkedHashMap<String, JsonElement>()
            try {
                plane.waitForAdmission(
                    operation,
                    timeoutMs = call.getValue("timeout_ms").jsonPrimitive.long,
                    pollIntervalMs = call.getValue("poll_interval_ms").jsonPrimitive.long,
                )
                produced["outcome"] = JsonPrimitive("admitted")
                produced["polls"] = JsonPrimitive(api.sent.size)
            } catch (error: ThalovantAdmissionTimeoutException) {
                // A connection error and a timeout at once: it may still be admitted.
                assertIs<ThalovantConnectionException>(error)
                assertIs<ThalovantTimeout>(error)
                assertTrue(error.message.orEmpty().endsWith("it may still admit it later."), error.message)
                produced["outcome"] = JsonPrimitive("timeout")
                if ("polls" in expect) produced["polls"] = JsonPrimitive(api.sent.size)
            } catch (error: ThalovantAdmissionFailedException) {
                produced["outcome"] = JsonPrimitive("failed")
                produced["error_code"] = JsonPrimitive(error.errorCode)
                produced["status"] = JsonPrimitive(error.statusCode)
                if (error.statusCode != null) {
                    produced["code"] = JsonPrimitive(error.code)
                    produced["detail"] = JsonPrimitive(error.detail)
                }
                produced["polls"] = JsonPrimitive(api.sent.size)
            } catch (_: java.io.IOException) {
                // The API out of reach: this SDK's own error for that, as it is.
                produced["outcome"] = JsonPrimitive("unreachable")
                produced["polls"] = JsonPrimitive(api.sent.size)
            } catch (error: ThalovantAuthException) {
                produced["outcome"] = JsonPrimitive("auth")
                produced["status"] = JsonPrimitive(error.statusCode)
                produced["polls"] = JsonPrimitive(api.sent.size)
            } catch (_: ThalovantApiException) {
                produced["outcome"] = JsonPrimitive("error")
                produced["polls"] = JsonPrimitive(api.sent.size)
            }
            resetting?.close()
            expect["waited_at_least_ms"]?.let {
                // Recorded as the bound it met, so every SDK records the same value.
                val waitedMs = (System.nanoTime() - started) / 1_000_000
                val bound = it.jsonPrimitive.long
                produced["waited_at_least_ms"] = JsonPrimitive(if (waitedMs >= bound) bound else waitedMs)
            }
            JsonObject(produced) to api
        }
    }

    @Test
    fun `admission runs every shared vector`() {
        for (case in admission.cases()) {
            val name = case.text("name")!!
            val (produced, api) = admissionCase(case)
            ConformanceRecord.record("connection-admission-vectors.json", name, produced)
            assertTrue(api.mismatches.isEmpty(), "$name: ${api.mismatches}")
            assertEquals(case.getValue("expect"), produced, name)
        }
    }

    /**
     * An API that cannot be reached, reached the same way on every platform:
     * a loopback port that accepts each connection and resets it at once. A
     * closed port is refused at once on Linux and macOS, but Windows retries
     * the SYN for about two seconds first.
     */
    private class ResettingListener : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: continue
                runCatching { connection.setSoLinger(true, 0); connection.close() } // RST
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            thread.join(2_000)
        }
    }

    // -- the home link --------------------------------------------------------

    /** A transport that keeps what the client sent, so the reply is checked as sent. */
    private class Outbox(private val sendMs: Long = 0) : HiveMindRuntimeTransport {
        override var connected: Boolean = false
        override val handshakeComplete: Boolean get() = connected
        val emitted = CopyOnWriteArrayList<Triple<String, JsonObject, JsonObject>>()
        override suspend fun connect(timeoutMs: Long) { connected = true }
        override suspend fun disconnect() { connected = false }
        override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {
            // A transport that takes sendMs to put a frame on the wire.
            if (sendMs > 0) delay(sendMs)
            emitted += Triple(eventType, data, context)
        }
        override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription = ThalovantSubscription {}
    }

    private fun handler(spec: JsonObject): suspend (HomeRequest) -> HomeAnswer? = {
        if ((spec["raises"] as? JsonPrimitive)?.boolean == true) throw IllegalStateException("the conversation agent is gone")
        spec["sleep_ms"]?.let { delay(it.jsonPrimitive.long) }
        HomeAnswer(
            speech = spec.text("speech") ?: "",
            responseType = spec.text("response_type") ?: ThalovantHome.ACTION_DONE,
            errorCode = spec.text("error_code"),
            continueConversation = (spec["continue_conversation"] as? JsonPrimitive)?.boolean ?: false,
        )
    }

    private fun client(outbox: Outbox): ThalovantClient = ThalovantClient(
        ThalovantIdentity(
            buildJsonObject {
                put("access_key", "access")
                put("password", "password")
                put("site_id", "ha")
                put("default_master", "wss://hub.example")
            },
        ),
        transport = outbox,
    )

    @Test
    fun `the home link runs every shared vector`() {
        for (case in home.cases()) {
            val name = case.text("name")!!
            val produced: JsonElement = when (case.text("kind")) {
                "reply_context" -> replyContext(case.getValue("context").jsonObject)
                "speech" -> JsonPrimitive(plainSpeech(case.text("text")))
                "deadline" -> deadlineCase(case)
                "queued" -> queuedCase(case)
                else -> {
                    val outbox = Outbox()
                    val event = homeRequest(case)
                    val timeoutMs = case["timeout_ms"]?.jsonPrimitive?.long ?: ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS
                    val payload = runBlocking {
                        client(outbox).answerHomeRequest(event, timeoutMs, handler = handler(case.getValue("handler").jsonObject))
                    }
                    assertNotNull(payload, name)
                    // Exactly one answer, the payload returned, sent back along the route.
                    assertEquals(listOf(Triple(ThalovantHome.RESPONSE, payload, replyContext(event.context))), outbox.emitted.toList(), name)
                    payload
                }
            }
            ConformanceRecord.record("home-link-vectors.json", name, produced)
            assertEquals(case.getValue("expect"), produced, name)
        }
    }

    /** A reply that waits behind another frame, over a real link to an in-process hub. */
    private fun queuedCase(case: JsonObject): JsonObject = FakeHub().use { hub ->
        runBlocking {
            val client = hub.connectedClient()
            try {
                withTimeout(5_000) { while (hub.sessions.isEmpty()) delay(10) }
                val peer = hub.sessions.single()
                val transport = client.transport as HiveMindWssTransport
                transport.sendQueue.lock() // another frame is being written ...
                val busy = launch { delay(case.millis("busy_ms")); transport.sendQueue.unlock() } // ... for busy_ms
                val event = ThalovantEvent(
                    ThalovantHome.REQUEST,
                    data = case.getValue("request").jsonObject,
                    context = buildJsonObject { put("source", "skill"); put("destination", "ha") },
                )
                val sent = client.answerHomeRequest(event, hubTimeoutMs = case.millis("hub_timeout_ms"), handler = handler(case.getValue("handler").jsonObject))
                busy.join()
                delay(200) // time enough for a withdrawn reply to go out late, if it would
                client.emit("still.there")
                // Everything the hub received, up to the message that proves the link still carries one.
                val frames = withContext(Dispatchers.IO) {
                    val seen = mutableListOf<JsonObject>()
                    while (true) {
                        val frame = peer.received.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: break
                        seen += frame
                        if (frame["payload"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "still.there") break
                    }
                    seen
                }
                fun type(frame: JsonObject) = frame["payload"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                val kept = frames.any { type(it) == "still.there" } && hub.attempts.get() == 1
                val responses = frames.filter { type(it) == ThalovantHome.RESPONSE }.map { it.getValue("payload").jsonObject["data"] }
                assertEquals(listOfNotNull(sent), responses, "never sent late, never twice")
                buildJsonObject {
                    put("replied", sent != null)
                    put("link_kept", kept)
                    if (sent != null) put("response", sent)
                }
            } finally {
                client.close()
            }
        }
    }

    private fun JsonObject.millis(key: String): Long = getValue(key).jsonPrimitive.long

    private fun homeRequest(case: JsonObject) = ThalovantEvent(
        ThalovantHome.REQUEST,
        data = case.getValue("request").jsonObject,
        context = buildJsonObject { put("source", "skill") },
    )

    /** The hub's bound, from arrival, covers the handler and the sending both; nothing is sent late. */
    private fun deadlineCase(case: JsonObject): JsonObject {
        val outbox = Outbox(sendMs = case.getValue("send_ms").jsonPrimitive.long)
        val hubTimeoutMs = case.getValue("hub_timeout_ms").jsonPrimitive.long
        val started = System.nanoTime()
        val sent = runBlocking {
            client(outbox).answerHomeRequest(
                homeRequest(case),
                timeoutMs = case.getValue("timeout_ms").jsonPrimitive.long,
                hubTimeoutMs = hubTimeoutMs,
                handler = handler(case.getValue("handler").jsonObject),
            )
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        // Never past the hub's bound, whatever the handler or the transport did.
        assertTrue(tookMs <= hubTimeoutMs + 100, "took $tookMs ms")
        return buildJsonObject {
            put("replied", sent != null)
            if (sent != null) {
                assertEquals(listOf(sent), outbox.emitted.map { it.second })
                put("response", sent)
            } else {
                assertTrue(outbox.emitted.isEmpty(), "a reply was sent after the hub gave up")
            }
        }
    }

    @Test
    fun `the recorder writes JSON byte for byte as the reference does`() {
        // Every escape the reference's json.dumps(ensure_ascii=False) makes and
        // every one it does not, keys in code-point order: the digest of
        // exactly what Python writes for this value.
        val text = "a\"b\\c/d\b\u000C\n\r\t\u0001\u001F\u007F\u2028\u2029\u00E9\uD83D\uDE00"
        val value = buildJsonObject {
            put(text, text)
            put("\uFFFF", 1)
            put("\uD83D\uDE00", 2)
            put("b", JsonArray(listOf(JsonPrimitive(true), JsonNull, JsonPrimitive(1.0))))
        }
        assertEquals("fa5644fa9e7805f9a16081e501a442fc01b3feb4c6358962cb31fc7c901f5b5e", ConformanceRecord.canonicalDigest(value))
    }

    @Test
    fun `a vector's fractions are spelled as the reference spells them`() {
        // The reference digests each vector file with Python's float repr.
        // The vectors are whole milliseconds now, but a fraction must still
        // digest as the reference's does. These are what repr() writes.
        val python = mapOf(
            "0.01" to "0.01", "0.2" to "0.2", "0.05" to "0.05", "0.02" to "0.02", "1e-05" to "1e-05",
            "1.5" to "1.5", "0.30000000000000004" to "0.30000000000000004", "123.456" to "123.456",
            "1e-07" to "1e-07", "5e-324" to "5e-324", "2.5e-05" to "2.5e-05", "0.0001" to "0.0001",
            "0.00012345" to "0.00012345", "-0.75" to "-0.75", "1234567890123456.7" to "1234567890123456.8",
            "0.1" to "0.1", "1.0" to "1", "900" to "900",
            // What the reference writes once a whole float is its int (checked
            // against Python): the exact value of the double, never its text.
            "1e20" to "100000000000000000000", "1e+20" to "100000000000000000000", "1E+2" to "100",
            "1.0000000000000000001" to "1", "-0.0" to "0", "100000000000000000000" to "100000000000000000000",
            "1e300" to "1000000000000000052504760255204420248704468581108159154915854115511802457988908195786371375080447864043704443832883878176942523235360430575644792184786706982848387200926575803737830233794788090059368953234970799945081119038967640880074652742780142494579258788820056842838115669472196386865459400540160",
        )
        for ((written, spelled) in python) assertEquals(spelled, ConformanceRecord.referenceNumber(written), written)
    }

    @Test
    fun `the contract lists match the SDK`() {
        assertEquals(home.getValue("response_types").jsonArray.map { it.jsonPrimitive.content }, ThalovantHome.RESPONSE_TYPES)
        assertEquals(home.getValue("error_codes").jsonArray.map { it.jsonPrimitive.content }, ThalovantHome.ERROR_CODES)
        assertEquals(home.text("request_type"), ThalovantHome.REQUEST)
        assertEquals(home.text("response_type"), ThalovantHome.RESPONSE)
        assertEquals(home.getValue("reply_timeout_ms").jsonPrimitive.long, ThalovantHome.REQUEST_TIMEOUT_MS)
        assertEquals(9_000L, ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS)
        assertTrue(ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS < ThalovantHome.REQUEST_TIMEOUT_MS)
        assertEquals(device.getValue("home_assistant_scopes").jsonArray.map { it.jsonPrimitive.content }, HOME_ASSISTANT_SCOPES)
        assertEquals(device.text("home_assistant_client_id"), HOME_ASSISTANT_CLIENT_ID)
        assertEquals(JsonNull, replyContext(buildJsonObject { put("source", JsonNull) })["source"])
    }
}
