package com.thalovant.sdk

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * Browser sign-in against the shared `browser-login-vectors.json`.
 *
 * The pure sections (PKCE, the redirect, the authorization URL, the
 * configuration, the browser rule, what a callback means) are run against the
 * functions that decide them; the callbacks run through a real listener too.
 * `sign_ins` and `chooser` are run for real: a loopback API (MockWebServer)
 * serves each case's exchanges in order and checks every request the SDK sends
 * through OkHttp, and a raw socket plays the browser against the port the SDK
 * bound. What this SDK produced for each case is recorded for the conformance
 * record before it is compared, shaped exactly as the Python reference's
 * `tests/test_browser_login_vectors.py` shapes it. The SDK has one form, the
 * suspend one, so that is the one recorded.
 */
class BrowserLoginVectorsTest {

    private fun vectors(name: String): JsonObject =
        ThalovantJson.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private val browser = vectors("browser-login-vectors.json")

    private fun cases(section: String): List<JsonObject> = browser.getValue(section).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.name(): String = getValue("name").jsonPrimitive.content

    private fun record(section: String, case: JsonObject, produced: JsonElement) =
        ConformanceRecord.record("browser-login-vectors.json", "$section/${case.name()}", produced)

    /** Runs every case of a section, and fails once at the end with every case that did not match. */
    private fun section(section: String, run: (JsonObject) -> JsonElement) {
        val failures = mutableListOf<String>()
        for (case in cases(section)) {
            val produced = try {
                run(case)
            } catch (error: AssertionError) {
                failures += "${case.name()}: ${error.message}"
                continue
            }
            record(section, case, produced)
            if (produced != case.getValue("expect")) failures += "${case.name()}: produced $produced, expected ${case.getValue("expect")}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // -- the pure sections --------------------------------------------------

    @Test
    fun `pkce runs every shared vector`() = section("pkce") { case ->
        buildJsonObject { put("challenge", NativeSignIn.challengeFor(case.text("verifier")!!)) }
    }

    @Test
    fun `redirect uris run every shared vector`() = section("redirect_uris") { case ->
        try {
            buildJsonObject { put("redirect_uri", NativeSignIn.loopbackRedirectUri(case.getValue("port").jsonPrimitive.int)) }
        } catch (_: IllegalArgumentException) {
            buildJsonObject { put("error", "invalid_port") }
        }
    }

    @Test
    fun `authorization urls run every shared vector`() = section("authorization_urls") { case ->
        val given = case.getValue("input").jsonObject
        val scopes = (given["scopes"] as? JsonArray)?.map { it.jsonPrimitive.content }
        buildJsonObject {
            put(
                "url",
                NativeSignIn.browserAuthorizationUrl(
                    authorizationEndpoint = given.text("authorization_endpoint")!!,
                    redirectUri = NativeSignIn.loopbackRedirectUri(given.getValue("port").jsonPrimitive.int),
                    codeChallenge = given.text("code_challenge")!!,
                    state = given.text("state")!!,
                    scopes = scopes,
                    deviceName = given.text("device_name"),
                    clientId = given.text("client_id")!!,
                ),
            )
        }
    }

    @Test
    fun `config runs every shared vector`() = section("config") { case ->
        try {
            val config = NativeSignInConfig.fromJson(case.getValue("body"))
            buildJsonObject {
                put("usable", true)
                put("authorization_endpoint", config.authorizationEndpoint)
                put("token_endpoint", config.tokenEndpoint)
                put("sdk_client_id", config.sdkClientId)
            }
        } catch (_: IllegalArgumentException) {
            buildJsonObject { put("usable", false) }
        }
    }

    @Test
    fun `environment runs every shared vector`() = section("environment") { case ->
        val env = case.getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }
        buildJsonObject { put("browser", NativeSignIn.canOpenBrowser(env, case.text("platform")!!)) }
    }

    private fun callbackOutcome(status: Int, callback: LoopbackCallback?): JsonObject = buildJsonObject {
        put("status", status)
        when (callback?.outcome) {
            null -> put("outcome", "ignored")
            LoopbackCallback.Outcome.CODE -> {
                put("outcome", "code")
                put("code", callback.code)
            }
            LoopbackCallback.Outcome.DENIED -> put("outcome", "denied")
            LoopbackCallback.Outcome.FAILED -> {
                put("outcome", "failed")
                put("error", callback.error)
            }
        }
    }

    @Test
    fun `callbacks run every shared vector`() = section("callbacks") { case ->
        val request = case.getValue("request").jsonObject
        val (status, callback) = classifyLoopbackRequest(
            request.text("method")!!,
            request.text("target")!!.replace("{state}", SYNTHETIC_STATE),
            SYNTHETIC_STATE,
        )
        callbackOutcome(status, callback)
    }

    @Test
    fun `callbacks run every shared vector through a real listener`() {
        val failures = mutableListOf<String>()
        for (case in cases("callbacks")) {
            val listener = LoopbackListener(SYNTHETIC_STATE)
            val port = listener.start()
            try {
                val request = case.getValue("request").jsonObject
                val answer = http(
                    port,
                    request.text("method")!!,
                    request.text("target")!!.replace("{state}", SYNTHETIC_STATE),
                    beforeReading = { listener.finish(false) },
                )
                checkPageHeaders(answer)
                val produced = callbackOutcome(answer.status, runBlocking { listener.wait(0) })
                if (produced != case.getValue("expect")) failures += "${case.name()}: produced $produced"
            } finally {
                runBlocking { listener.close() }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // -- a browser, and an API, on loopback ----------------------------------

    internal class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    private fun Answer.page(): String? =
        browser.getValue("pages").jsonObject.entries.firstOrNull { (_, text) -> text.jsonPrimitive.content in body }?.key

    private fun checkPageHeaders(answer: Answer) {
        for ((name, value) in browser.getValue("page_headers").jsonObject) {
            assertEquals(value.jsonPrimitive.content, answer.headers[name], "$name in ${answer.headers}")
        }
        // Self-contained: nothing for the page to load.
        assertFalse("src=" in answer.body || "href=" in answer.body || "http" in answer.body, answer.body)
    }

    /** What the test learnt from the URL the SDK built. */
    private class Signing(url: String) {
        val endpoint: String
        val pairs: List<Pair<String, String>>
        val port: Int
        val state: String
        val challenge: String

        init {
            val uri = URI(url)
            endpoint = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
            pairs = uri.rawQuery.split('&').map { pair ->
                java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            val values = pairs.toMap()
            val redirect = URI(values.getValue("redirect_uri"))
            port = redirect.port
            state = values.getValue("state")
            challenge = values.getValue("code_challenge")
            assertTrue(STATE_ALPHABET.matches(state), "state must be at least 128 bits of base64url")
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(challenge), challenge)
            assertEquals("http://127.0.0.1:$port/callback", values["redirect_uri"])
            assertTrue(port in 1024..65535)
        }

        fun normalized(text: String): String =
            text.replace("http://127.0.0.1:$port/callback", "http://127.0.0.1:{port}/callback")
                .replace(state, "{state}").replace(challenge, "{challenge}")

        fun urlRecord(): JsonObject = buildJsonObject {
            put("endpoint", endpoint)
            put("query", JsonArray(pairs.map { (key, value) -> JsonArray(listOf(JsonPrimitive(key), JsonPrimitive(normalized(value)))) }))
        }
    }

    /** Serves a case's exchanges in order and checks each request against its own. */
    private class ScriptedApi(private val exchanges: List<JsonObject>) : AutoCloseable {
        private val server = MockWebServer()
        val sent = CopyOnWriteArrayList<String>()
        val mismatches = CopyOnWriteArrayList<String>()

        @Volatile var signing: Signing? = null
        @Volatile var exchange: JsonObject? = null
        @Volatile var verifier: String? = null
        @Volatile var index = 0
            private set

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@ScriptedApi) { answer(request) }
            }
            server.start()
        }

        val url: String get() = server.url("/").toString()

        /** The exchange body with the generated values put back as placeholders, once proven. */
        private fun tokenBody(body: JsonElement?): JsonElement? {
            val sign = signing
            if (body !is JsonObject || sign == null) return body
            if (body.keys != setOf("code", "code_verifier", "client_id", "redirect_uri")) {
                mismatches += "the exchange must carry exactly four fields, not ${body.keys.sorted()}"
            }
            val sentVerifier = (body["code_verifier"] as? JsonPrimitive)?.contentOrNull
            val clean = body.mapValues { (_, value) ->
                if (value is JsonPrimitive && value.isString) JsonPrimitive(sign.normalized(value.content)) else value
            }.toMutableMap()
            if (sentVerifier != null && UNRESERVED.matches(sentVerifier) && s256(sentVerifier) == sign.challenge) {
                clean["code_verifier"] = JsonPrimitive("{verifier}")
                verifier = sentVerifier
            } else {
                mismatches += "the code_verifier is not the one the challenge was made from"
            }
            return JsonObject(clean).also { exchange = it }
        }

        private fun answer(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            sent += "${request.method} $path"
            if (index >= exchanges.size) {
                mismatches += "unexpected ${request.method} $path"
                return MockResponse().setResponseCode(599).setBody("{}")
            }
            val exchange = exchanges[index++]
            val expected = exchange.getValue("request").jsonObject
            val method = expected.getValue("method").jsonPrimitive.content
            val expectedPath = expected.getValue("path").jsonPrimitive.content
            if (request.method != method || path != expectedPath) mismatches += "${request.method} $path != $method $expectedPath"
            val raw = request.body.readUtf8()
            var body: JsonElement? = if (raw.isEmpty()) null else ThalovantJson.parseToJsonElement(raw)
            if (expectedPath == "/v1/auth/native/token" || (body as? JsonObject)?.containsKey("code_verifier") == true) {
                body = tokenBody(body)
            }
            expected["json"]?.let { if (body != it) mismatches += "body $body != $it" }
            expected["authorization"]?.let {
                if (request.getHeader("Authorization") != it.jsonPrimitive.content) mismatches += "wrong Authorization header"
            }
            val response = exchange.getValue("response").jsonObject
            val text = response.getValue("body").jsonPrimitive.content
            return MockResponse().setResponseCode(response.getValue("status").jsonPrimitive.int).apply {
                if (text.isNotEmpty()) setHeader("Content-Type", response.getValue("content_type").jsonPrimitive.content)
                setBody(text)
            }
        }

        override fun close() = server.shutdown()
    }

    private fun result(plane: ThalovantControlPlane, outcome: Result<JsonObject>): JsonObject {
        val error = outcome.exceptionOrNull()
        return when (error) {
            null -> {
                val token = outcome.getOrThrow()
                assertEquals(token.text("access_token"), plane.accessToken)
                assertEquals(token.text("token_id"), plane.tokenId)
                buildJsonObject {
                    put("outcome", "approved")
                    put("token_type", token.getValue("token_type"))
                    put("scopes", token.getValue("scopes"))
                    put("expires_at", token.getValue("expires_at"))
                    put("token_id", token.getValue("token_id"))
                }
            }
            is ThalovantDeviceLoginDeniedException -> buildJsonObject {
                put("outcome", "denied")
                put("status", error.statusCode)
            }
            is ThalovantDeviceLoginExpiredException -> buildJsonObject {
                put("outcome", "expired")
                put("status", error.statusCode)
            }
            is ThalovantApiException -> buildJsonObject {
                put("outcome", "error")
                put("status", error.statusCode)
                put("code", error.errorCode)
                if (error.statusCode != null) put("detail", error.detail)
            }
            else -> throw error
        }
    }

    /** Neither a synthetic secret, nor the verifier, nor the state reaches anything an error shows. */
    private fun noSecrets(error: Throwable, api: ScriptedApi) {
        val hidden = browser.getValue("message_excludes").jsonArray.map { it.jsonPrimitive.content } +
            listOfNotNull(api.verifier, api.signing?.state)
        val shown = mutableListOf(error.message.orEmpty(), error.toString(), error.stackTraceToString())
        if (error is ThalovantApiException) {
            shown += listOfNotNull(error.body, error.problem?.toString(), error.detail, error.errorCode)
        }
        for (value in hidden) assertFalse(shown.any { value in it }, "a secret reached the error")
    }

    private fun signIn(
        case: JsonObject,
        start: suspend (ThalovantControlPlane, (String) -> Unit) -> JsonObject,
    ): Pair<JsonObject, ScriptedApi> = runBlocking {
        val api = ScriptedApi(case.getValue("exchanges").jsonArray.map { it.jsonObject })
        try {
            val plane = ThalovantControlPlane(api.url)
            val opened = CompletableDeferred<String>()
            val task = async(Dispatchers.IO) { runCatching { start(plane) { url -> opened.complete(url) } } }
            withTimeout(10_000) { while (!opened.isCompleted && !task.isCompleted) delay(5) }
            val produced = linkedMapOf<String, JsonElement>()
            val signing = if (opened.isCompleted) Signing(opened.await()) else null
            if (signing != null) {
                api.signing = signing
                produced["authorization_url"] = signing.urlRecord()
                produced["browser"] = JsonArray(
                    case.getValue("requests").jsonArray.map { item ->
                        val request = item.jsonObject
                        val answer = withContext(Dispatchers.IO) {
                            http(signing.port, request.text("method")!!, request.text("target")!!.replace("{state}", signing.state))
                        }
                        checkPageHeaders(answer)
                        buildJsonObject {
                            put("status", answer.status)
                            if (answer.status == 200) put("page", answer.page())
                        }
                    },
                )
            }
            val outcome = withTimeout(10_000) { task.await() }
            outcome.exceptionOrNull()?.let { noSecrets(it, api) }
            if (signing != null) produced["closed"] = JsonPrimitive(withContext(Dispatchers.IO) { refused(signing.port) })
            api.exchange?.let { produced["exchange"] = it }
            var result = result(plane, outcome)
            val then = case.getValue("call").jsonObject.text("then")
            if (then == "revoke" && result.text("outcome") == "approved") {
                plane.revokeApiToken()
                assertEquals(null, plane.accessToken)
                assertEquals(null, plane.tokenId)
                val sent = api.sent.size
                plane.revokeApiToken() // idempotent: sends nothing
                assertEquals(sent, api.sent.size)
                result = buildJsonObject { put("outcome", "revoked") }
            }
            produced["result"] = result
            assertTrue(api.mismatches.isEmpty(), api.mismatches.joinToString("; "))
            assertEquals(case.getValue("exchanges").jsonArray.size, api.index, "not every exchange was used: ${api.sent}")
            JsonObject(produced) to api
        } finally {
            api.close()
        }
    }

    private fun JsonObject.timeoutMillis(): Long? = (this["timeout_ms"] as? JsonPrimitive)?.long

    @Test
    fun `sign ins run every shared vector`() = section("sign_ins") { case ->
        val call = case.getValue("call").jsonObject
        signIn(case) { plane, handOver ->
            plane.browserLogin(
                BrowserLoginOptions(
                    scopes = (call["scopes"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    deviceName = call.text("device_name"),
                    openBrowser = false,
                    prompt = handOver,
                    timeoutMillis = call.timeoutMillis() ?: NativeSignIn.DEFAULT_BROWSER_LOGIN_TIMEOUT_MILLIS,
                ),
            )
        }.first
    }

    @Test
    fun `the chooser runs every shared vector`() = section("chooser") { case ->
        val call = case.getValue("call").jsonObject
        val env = case.getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val (produced, api) = signIn(case) { plane, handOver ->
            plane.interactiveLogin(
                InteractiveLoginOptions(
                    method = LoginMethod.fromWireName(call.text("method")!!),
                    deviceName = call.text("device_name"),
                    timeoutMillis = call.timeoutMillis(),
                    // The browser the SDK opens. Device login opens its
                    // verification page too; only the consent page is this
                    // test's to answer.
                    opener = { url -> if ("response_type=code" in url) handOver(url) },
                ),
                env,
                case.text("platform")!!,
            )
        }
        val method = if ("POST /v1/auth/device/authorize" in api.sent) "device" else "browser"
        if (method == "browser" && "browser" in produced) assertEquals(JsonPrimitive(true), produced["closed"])
        buildJsonObject {
            put("method", method)
            put("result", produced.getValue("result"))
        }
    }

    // -- what the file holds ---------------------------------------------------

    @Test
    fun `every section is run`() {
        // A section nobody runs is behaviour written down and never checked.
        val sections = setOf("pkce", "redirect_uris", "authorization_urls", "config", "environment", "callbacks", "sign_ins", "chooser")
        assertEquals(sections, browser.filterValues { value ->
            value is JsonArray && value.isNotEmpty() && (value[0] as? JsonObject)?.containsKey("name") == true
        }.keys)
        assertEquals(119, sections.sumOf { cases(it).size })
    }

    @Test
    fun `the RFC 7636 vector is the first pkce case`() {
        val first = cases("pkce").first()
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", first.text("verifier"))
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", first.getValue("expect").jsonObject.text("challenge"))
    }

    @Test
    fun `the contract constants match the SDK`() {
        assertEquals(browser.text("client_id"), NativeSignIn.SDK_CLIENT_ID)
        assertEquals(browser.text("redirect_uri"), "http://127.0.0.1:{port}${NativeSignIn.LOOPBACK_CALLBACK_PATH}")
        assertEquals(browser.getValue("default_timeout_ms").jsonPrimitive.long, NativeSignIn.DEFAULT_BROWSER_LOGIN_TIMEOUT_MILLIS)
        assertEquals(browser.getValue("device_name_max_code_points").jsonPrimitive.int, NativeSignIn.MAX_DEVICE_NAME_LENGTH)
        val pages = browser.getValue("pages").jsonObject
        assertEquals(pages.text("signed_in"), SIGNED_IN_TEXT)
        assertEquals(pages.text("not_completed"), NOT_COMPLETED_TEXT)
        assertEquals(
            browser.getValue("page_headers").jsonObject.mapValues { it.value.jsonPrimitive.content },
            LOOPBACK_PAGE_HEADERS.toMap(),
        )
        val prompt = browser.getValue("prompt").jsonObject
        val shown = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(shown, true, "UTF-8"))
        try {
            presentBrowserLogin("https://x.test/a", prompt = null, openBrowser = false, opener = null)
            presentBrowserLogin("https://x.test/b", prompt = null, openBrowser = true, opener = {})
        } finally {
            System.setOut(original)
        }
        val printed = shown.toString("UTF-8").replace("\r\n", "\n")
        assertEquals(
            prompt.text("not_opening")!!.replace("{url}", "https://x.test/a") + "\n" +
                prompt.text("opening")!!.replace("{url}", "https://x.test/b") + "\n",
            printed,
        )
        // The rules a generated verifier and state must meet.
        val verifier = browser.getValue("verifier").jsonObject
        val state = browser.getValue("state").jsonObject
        repeat(20) {
            val made = NativeSignIn.newVerifier()
            assertTrue(made.length in verifier.getValue("min_length").jsonPrimitive.int..verifier.getValue("max_length").jsonPrimitive.int)
            assertTrue(made.all { it in verifier.text("alphabet")!! })
            val tied = NativeSignIn.newState()
            assertTrue(tied.length >= state.getValue("min_length").jsonPrimitive.int)
            assertTrue(tied.all { it in state.text("alphabet")!! })
        }
        // 24 random bytes: 192 bits, over the 128 the contract asks for.
        assertTrue(24 * 8 >= state.getValue("min_random_bits").jsonPrimitive.int)
    }

    @Test
    fun `the listener binds loopback only`() {
        val listener = LoopbackListener("s".repeat(32))
        listener.start()
        try {
            assertEquals("127.0.0.1", listener.boundAddress?.hostAddress)
        } finally {
            runBlocking { listener.close() }
        }
    }

    internal companion object {
        const val SYNTHETIC_STATE = "Xx0-_yLq7m3Vw9ZtR2cKp4HdN8sFjB6a"
        val UNRESERVED = Regex("[A-Za-z0-9._~-]{43,128}")
        val STATE_ALPHABET = Regex("[A-Za-z0-9_-]{22,}")

        fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** One request on its own connection, as a browser tab sends it. */
        fun http(port: Int, method: String, target: String, beforeReading: (() -> Unit)? = null, raw: ByteArray? = null): Answer =
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                socket.soTimeout = 10_000
                val output = socket.getOutputStream()
                output.write(raw ?: "$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                if (beforeReading != null) {
                    Thread.sleep(50)
                    beforeReading()
                }
                parse(socket.getInputStream().readBytes())
            }

        fun parse(raw: ByteArray): Answer {
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val head = if (split >= 0) text.substring(0, split) else text
            val lines = head.split("\r\n")
            val status = lines[0].split(" ")[1].toInt()
            val headers = lines.drop(1).associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
            val body = if (split >= 0) String(raw.copyOfRange(split + 4, raw.size), Charsets.UTF_8) else ""
            return Answer(status, headers, body)
        }

        /** Whether a new connection to the port is refused. */
        fun refused(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
            false
        } catch (_: ConnectException) {
            true
        }
    }
}
