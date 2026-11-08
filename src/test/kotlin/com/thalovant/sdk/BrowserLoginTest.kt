package com.thalovant.sdk

import com.thalovant.sdk.BrowserLoginVectorsTest.Companion.http
import com.thalovant.sdk.BrowserLoginVectorsTest.Companion.refused
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Browser sign-in beyond the shared vectors: what is printed and opened, the
 * generated secrets, the listener's own edges, and what never reaches an error.
 *
 * `BrowserLoginVectorsTest` runs the contract every SDK shares; these are the
 * parts that are this SDK's own -- its coroutines, its threads, its stdout --
 * or that are about what must *not* happen.
 */
class BrowserLoginTest {

    private val config = """{"authorization_endpoint":"https://dash.thalovant.com/authorize",""" +
        """"token_endpoint":"/v1/auth/native/token","device_authorization_endpoint":"/v1/auth/device/authorize",""" +
        """"sdk_client_id":"thalovant-sdk","loopback_redirect_uri":"http://127.0.0.1:{port}/callback",""" +
        """"code_challenge_methods_supported":["S256"]}"""
    private val token = """{"access_token":"tvpat_unit_token","token_type":"bearer","scopes":["hubs:read"],""" +
        """"expires_at":"2027-11-08T12:00:00Z","token_id":"tok-unit","ai_disclosure":"AI"}"""

    /** The config and token routes; the token route answers with [tokenAnswer], the config route with [configAnswer]. */
    private inner class Api(
        val tokenAnswer: (JsonObject) -> MockResponse = { json(200, token) },
        val configAnswer: () -> MockResponse = { json(200, config) },
    ) : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<String>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when (path) {
                        "/v1/auth/native/config" -> configAnswer()
                        "/v1/auth/native/token" -> tokenAnswer(ThalovantJson.parseToJsonElement(request.body.readUtf8()).jsonObject)
                        "/v1/auth/device/authorize" -> json(
                            200,
                            """{"device_code":"dc-unit","user_code":"WDJB-MJHT","verification_uri":"https://thalovant.com/activate",""" +
                                """"verification_uri_complete":"https://thalovant.com/activate?code=WDJB-MJHT","expires_in":900,"interval":0}""",
                        )
                        "/v1/auth/device/token" -> json(200, """{"access_token":"tvpat_device","token_type":"bearer","token_id":"dev-id"}""")
                        else -> MockResponse().setResponseCode(599)
                    }
                }
            }
            server.start()
        }

        val url: String get() = server.url("/").toString()

        override fun close() = server.shutdown()
    }

    private fun json(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private class Parts(url: String) {
        val query: Map<String, String> = URI(url).rawQuery.split('&').associate { pair ->
            java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        val port: Int = URI(query.getValue("redirect_uri")).port
        val state: String = query.getValue("state")
    }

    /** A sign-in started on its own coroutine, and the URL it showed once it shows one. */
    private class Started(val task: Deferred<Result<JsonObject>>, val url: CompletableDeferred<String>) {
        suspend fun parts(): Parts = Parts(withTimeout(5_000) { url.await() })

        /** The sign-in's answer, or what it threw; held in a Result so a failure does not cancel the test's scope. */
        suspend fun result(): JsonObject = withTimeout(10_000) { task.await() }.getOrThrow()
    }

    private fun kotlinx.coroutines.CoroutineScope.start(
        plane: ThalovantControlPlane,
        options: (handOver: (String) -> Unit) -> BrowserLoginOptions,
    ): Started {
        val url = CompletableDeferred<String>()
        val task = async(Dispatchers.IO) { runCatching { plane.browserLogin(options { url.complete(it) }) } }
        return Started(task, url)
    }

    private suspend fun get(port: Int, target: String) = withContext(Dispatchers.IO) { http(port, "GET", target) }

    private suspend fun raw(port: Int, bytes: ByteArray) = withContext(Dispatchers.IO) { http(port, "", "", raw = bytes) }

    private fun <T> printed(block: () -> T): Pair<T, String> {
        val shown = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(shown, true, "UTF-8"))
        val value = try {
            block()
        } finally {
            System.setOut(original)
        }
        return value to shown.toString("UTF-8").replace("\r\n", "\n")
    }

    // -- what the person sees -----------------------------------------------

    @Test
    fun `the URL is printed and the browser opened on a thread of its own`() {
        val opened = CopyOnWriteArrayList<Pair<String, String>>()
        val (signedIn, out) = printed {
            runBlocking {
                Api().use { api ->
                    val plane = ThalovantControlPlane(api.url)
                    val started = start(plane) { handOver ->
                        BrowserLoginOptions(deviceName = "unit", opener = { url ->
                            opened += url to Thread.currentThread().name
                            handOver(url)
                        })
                    }
                    val parts = started.parts()
                    assertEquals(200, get(parts.port, "/callback?code=c&state=${parts.state}").status)
                    started.result()
                }
            }
        }
        assertEquals("tvpat_unit_token", signedIn.getValue("access_token").jsonPrimitive.content)
        val (url, thread) = opened.single()
        assertEquals("thalovant-browser", thread)
        assertTrue("Opening your browser to sign in to Thalovant. If it does not open, visit:\n$url\n" in out, out)
    }

    @Test
    fun `without openBrowser the URL is only shown`() {
        val (_, out) = printed {
            runBlocking {
                Api().use { api ->
                    // Nobody comes back to the port, so the sign-in expires.
                    val plane = ThalovantControlPlane(api.url)
                    assertFailsWith<ThalovantDeviceLoginExpiredException> {
                        plane.browserLogin(BrowserLoginOptions(openBrowser = false, timeoutMillis = 200, opener = { error("the browser was opened") }))
                    }
                }
            }
        }
        assertTrue(out.startsWith("To sign in to Thalovant, visit:\nhttps://dash.thalovant.com/authorize?response_type=code&"), out)
    }

    @Test
    fun `a prompt takes the print's place and does not stop the browser from opening`() {
        val opened = CompletableDeferred<String>()
        val (_, out) = printed {
            runBlocking {
                Api().use { api ->
                    val plane = ThalovantControlPlane(api.url)
                    val started = start(plane) { handOver ->
                        BrowserLoginOptions(prompt = handOver, opener = { opened.complete(it) })
                    }
                    val parts = started.parts()
                    assertEquals(started.url.await(), withTimeout(5_000) { opened.await() })
                    get(parts.port, "/callback?code=c&state=${parts.state}")
                    started.result()
                }
            }
        }
        assertFalse("Thalovant" in out, out)
    }

    @Test
    fun `a browser that cannot open changes nothing`(): Unit = runBlocking {
        Api().use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver ->
                BrowserLoginOptions(prompt = handOver, opener = { throw IllegalStateException("no runnable browser") })
            }
            val parts = started.parts()
            get(parts.port, "/callback?code=c&state=${parts.state}")
            assertEquals("tok-unit", started.result().getValue("token_id").jsonPrimitive.content)
            assertEquals("tok-unit", plane.tokenId)
        }
    }

    @Test
    fun `the device name defaults to this sdk on this host`() {
        assertEquals("thalovant-kotlin-sdk on workstation", NativeSignIn.deviceNameFor("workstation"))
        assertEquals("thalovant-kotlin-sdk on workstation", NativeSignIn.deviceNameFor(" workstation\n"))
        assertEquals(120, NativeSignIn.deviceNameFor("h".repeat(300)).length)
        assertEquals("thalovant-kotlin-sdk", NativeSignIn.deviceNameFor(""))
        assertEquals("thalovant-kotlin-sdk", NativeSignIn.deviceNameFor(null))
        assertTrue(NativeSignIn.defaultDeviceName().startsWith("thalovant-kotlin-sdk"))
        // Cut by code point, never inside a surrogate pair.
        val cut = NativeSignIn.deviceNameFor("🎙".repeat(200))
        assertEquals(120, cut.codePointCount(0, cut.length))
    }

    @Test
    fun `the default device name reaches the consent page`(): Unit = runBlocking {
        Api().use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver, timeoutMillis = 200) }
            val parts = started.parts()
            val expired = assertFailsWith<ThalovantDeviceLoginExpiredException> { started.result() }
            assertTrue("within 0.2 seconds" in expired.message.orEmpty(), expired.message)
            assertNull(expired.statusCode)
            assertEquals(NativeSignIn.defaultDeviceName(), parts.query["device_name"])
            assertNull(parts.query["scope"])
        }
    }

    // -- the secrets ----------------------------------------------------------

    @Test
    fun `every verifier and state is fresh and well formed`() {
        val verifiers = List(50) { NativeSignIn.newVerifier() }.toSet()
        val states = List(50) { NativeSignIn.newState() }.toSet()
        assertEquals(50, verifiers.size)
        assertEquals(50, states.size)
        assertTrue(verifiers.all { Regex("[A-Za-z0-9._~-]{43,128}").matches(it) })
        // 24 random bytes: 192 bits, well over the 128 the contract asks for.
        assertTrue(states.all { Regex("[A-Za-z0-9_-]{32}").matches(it) })
    }

    @Test
    fun `an echoed code and verifier never reach the error`(): Unit = runBlocking {
        var sent: JsonObject? = null
        val echo: (JsonObject) -> MockResponse = { body ->
            // A validation error lists each field's input, as FastAPI's 422 does,
            // and this one puts the code in a key too.
            sent = body
            val detail = JsonArray(body.map { (key, value) ->
                buildJsonObject {
                    put("loc", JsonArray(listOf(JsonPrimitive("body"), JsonPrimitive(key))))
                    put("msg", "bad")
                    put("input", value)
                }
            })
            json(422, buildJsonObject {
                put("detail", detail)
                put("seen:${body.getValue("code").jsonPrimitive.content}", "x")
            }.toString())
        }
        Api(tokenAnswer = echo).use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver) }
            val parts = started.parts()
            val answer = get(parts.port, "/callback?code=secret-code-value&state=${parts.state}")
            assertEquals(200, answer.status)
            assertTrue("Sign-in was not completed." in answer.body)
            val error = assertFailsWith<ThalovantApiException> { started.result() }
            assertEquals(422, error.statusCode)
            assertEquals(ThalovantApiException::class, error::class)
            val shown = listOf(error.message.orEmpty(), error.toString(), error.stackTraceToString(), error.body.orEmpty(),
                error.problem.toString(), error.detail.orEmpty()).joinToString("\n")
            val body = assertNotNull(sent)
            for (secret in listOf(body.getValue("code").jsonPrimitive.content, body.getValue("code_verifier").jsonPrimitive.content, parts.state)) {
                assertFalse(secret in shown, "a secret reached the error")
            }
            assertTrue("[redacted]" in error.body.orEmpty())
            // Nothing rides along: no cause, nothing suppressed.
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertNull(plane.accessToken)
        }
    }

    @Test
    fun `scrubbing keeps the kind of error, its status and its retry hint`() {
        val original = apiException("Thalovant API request failed with HTTP 401.", 401, """{"detail":"bad nc_code_value"}""")
        original.retryAfterSeconds = 7.0
        val clean = withoutSecrets(original, listOf("nc_code_value", null, ""))
        assertIs<ThalovantAuthException>(clean)
        assertEquals(401, clean.statusCode)
        assertEquals("bad [redacted]", clean.detail)
        assertEquals(7.0, clean.retryAfterSeconds)
        // Nothing to take out: the error itself.
        assertTrue(withoutSecrets(original, listOf("absent")) === original)
        // A body that is not JSON is scrubbed as text.
        assertEquals("<p>[redacted]</p>", withoutSecrets(ThalovantApiException("x", 500, "<p>nc_code_value</p>"), listOf("nc_code_value")).body)
    }

    @Test
    fun `a cleartext API is refused before anything is asked or opened`(): Unit = runBlocking {
        val plane = ThalovantControlPlane("http://control.example.test")
        val opener: (String) -> Unit = { error("the browser was opened") }
        val first = assertFailsWith<ThalovantApiException> { plane.browserLogin(BrowserLoginOptions(opener = opener)) }
        assertTrue("cleartext" in first.message.orEmpty())
        val second = assertFailsWith<ThalovantApiException> {
            plane.interactiveLogin(InteractiveLoginOptions(method = LoginMethod.BROWSER, opener = opener))
        }
        assertTrue("cleartext" in second.message.orEmpty())
    }

    @Test
    fun `a method nobody knows is refused by name`() {
        assertEquals(LoginMethod.AUTO, LoginMethod.fromWireName("auto"))
        val error = assertFailsWith<IllegalArgumentException> { LoginMethod.fromWireName("password") }
        assertTrue("auto" in error.message.orEmpty())
    }

    @Test
    fun `the opener the chooser was given opens a device login's page too, on a thread of its own`(): Unit = runBlocking {
        Api().use { api ->
            val opened = CompletableDeferred<Pair<String, String>>()
            val plane = ThalovantControlPlane(api.url)
            val (token, _) = printed {
                runBlocking {
                    plane.interactiveLogin(
                        InteractiveLoginOptions(
                            method = LoginMethod.DEVICE,
                            deviceName = "unit",
                            opener = { opened.complete(it to Thread.currentThread().name) },
                        ),
                        emptyMap(),
                        "macos",
                    )
                }
            }
            assertEquals("dev-id", token.getValue("token_id").jsonPrimitive.content)
            val (url, thread) = withTimeout(5_000) { opened.await() }
            assertEquals("https://thalovant.com/activate?code=WDJB-MJHT", url)
            assertEquals("thalovant-browser", thread)
            // A forced device login never asks for the configuration.
            assertFalse(api.requests.any { "native" in it }, api.requests.toString())
        }
    }

    @Test
    fun `an opener that blocks does not hold up a device login`(): Unit = runBlocking {
        // A console browser can hold the call until it exits. Run inline, the
        // opener kept the device login from polling, and from starting its
        // deadline, until it returned. Bounded, so that a regression fails
        // here rather than hanging the suite.
        Api().use { api ->
            val release = java.util.concurrent.CountDownLatch(1)
            val plane = ThalovantControlPlane(api.url)
            try {
                val began = System.nanoTime()
                val (token, _) = printed {
                    runBlocking {
                        plane.interactiveLogin(
                            InteractiveLoginOptions(
                                method = LoginMethod.DEVICE,
                                deviceName = "unit",
                                opener = { release.await(3, java.util.concurrent.TimeUnit.SECONDS) },
                            ),
                            emptyMap(),
                            "macos",
                        )
                    }
                }
                val took = (System.nanoTime() - began) / 1_000_000
                assertEquals("dev-id", token.getValue("token_id").jsonPrimitive.content)
                assertTrue(took < 2_000, "the device login waited $took ms for its opener")
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `an API out of reach for the configuration means a device login, unless the browser is forced`(): Unit = runBlocking {
        // The reference reads an unreachable API as ThalovantAPIUnreachableError,
        // an API error its chooser falls back on; this SDK throws IOException.
        // The connection is dropped once the request is read, so no answer at
        // all comes back -- not an empty 200, which would be "unusable".
        val gone = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        Api(configAnswer = gone).use { api ->
            val plane = ThalovantControlPlane(api.url)
            // Forced, the failure is the caller's, as the transport error it
            // is -- which also proves this is a transport failure.
            assertFailsWith<java.io.IOException> {
                plane.interactiveLogin(
                    InteractiveLoginOptions(method = LoginMethod.BROWSER, opener = { error("the browser was opened") }),
                    mapOf("DISPLAY" to ":0"),
                    "linux",
                )
            }
            assertFalse(api.requests.any { "device" in it }, api.requests.toString())
            val opened = CopyOnWriteArrayList<String>()
            val (token, _) = printed {
                runBlocking {
                    plane.interactiveLogin(
                        InteractiveLoginOptions(deviceName = "unit", opener = { opened += it }),
                        mapOf("DISPLAY" to ":0"),
                        "linux",
                    )
                }
            }
            assertEquals("dev-id", token.getValue("token_id").jsonPrimitive.content)
            assertTrue("POST /v1/auth/device/authorize" in api.requests, api.requests.toString())
            assertFalse(opened.any { "response_type=code" in it }, "the consent page was opened")
        }
    }

    // -- the listener's own edges ----------------------------------------------

    @Test
    fun `cancelling the sign-in closes the port`(): Unit = runBlocking {
        Api().use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver) }
            val parts = started.parts()
            assertFalse(refused(parts.port))
            started.task.cancel()
            assertFailsWith<CancellationException> { started.result() }
            assertTrue(withContext(Dispatchers.IO) { refused(parts.port) })
        }
    }

    @Test
    fun `a connection that sends nothing does not hold the sign-in`(): Unit = runBlocking {
        // A browser opens connections it never uses; the sign-in must not wait on them.
        Api().use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver) }
            val parts = started.parts()
            Socket().use { idle ->
                idle.connect(InetSocketAddress("127.0.0.1", parts.port), 5_000)
                val began = System.nanoTime()
                assertEquals(200, get(parts.port, "/callback?code=c&state=${parts.state}").status)
                started.result()
                val took = (System.nanoTime() - began) / 1_000_000
                assertTrue(took < 900, "took $took ms")
            }
        }
    }

    @Test
    fun `a request that is not one is 400 and the wait goes on`(): Unit = runBlocking {
        Api().use { api ->
            val plane = ThalovantControlPlane(api.url)
            val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver) }
            val parts = started.parts()
            assertEquals(400, raw(parts.port, "hello\r\n\r\n".toByteArray()).status)
            val tooBig = "GET /callback?code=".toByteArray() + ByteArray(MAX_REQUEST_HEAD + 10) { 'a'.code.toByte() } +
                " HTTP/1.1\r\n\r\n".toByteArray()
            assertEquals(400, raw(parts.port, tooBig).status)
            assertFalse(started.task.isCompleted)
            assertEquals(200, get(parts.port, "/callback?code=c&state=${parts.state}").status)
            assertEquals("tvpat_unit_token", started.result().getValue("access_token").jsonPrimitive.content)
        }
    }

    @Test
    fun `a second answer after the first is turned away`(): Unit = runBlocking {
        val listener = LoopbackListener("the-state-of-this-sign-in")
        val port = listener.start()
        try {
            withContext(Dispatchers.IO) {
                // Both connected before the first is accepted, so the second
                // still reaches the listener after the port stopped listening.
                Socket().use { first ->
                    Socket().use { second ->
                        first.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                        second.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                        first.soTimeout = 10_000
                        second.soTimeout = 10_000
                        Thread.sleep(50)
                        val request = "GET /callback?code=c&state=the-state-of-this-sign-in HTTP/1.1\r\n\r\n".toByteArray()
                        first.getOutputStream().write(request)
                        val accepted = assertNotNull(runBlocking { listener.wait(5_000) })
                        assertEquals("c", accepted.code)
                        assertTrue(refused(port))
                        second.getOutputStream().write(request)
                        assertTrue(String(second.getInputStream().readBytes()).startsWith("HTTP/1.1 400 "))
                        listener.finish(true)
                        assertTrue("Signed in to Thalovant." in String(first.getInputStream().readBytes()))
                    }
                }
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `the wait is never cut short`(): Unit = runBlocking {
        val listener = LoopbackListener("s")
        listener.start()
        try {
            val began = System.nanoTime()
            assertNull(listener.wait(250))
            assertTrue((System.nanoTime() - began) / 1_000_000 >= 250)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `waiting on a listener that was never started is an error`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { LoopbackListener("s").wait(0) }
        Unit
    }

    @Test
    fun `a connection that closes before its answer changes nothing`(): Unit = runBlocking {
        val listener = LoopbackListener("the-state", answerTimeoutMillis = 50)
        val port = listener.start()
        try {
            withContext(Dispatchers.IO) {
                // Gone before sending a whole request head.
                Socket().use { gone ->
                    gone.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    gone.getOutputStream().write("GET /callback?code=c&st".toByteArray())
                }
            }
            assertNull(listener.wait(100))
            // Nothing was accepted, so this sign-in's own answer still is; its
            // page, never told how the sign-in ended, says it was not completed.
            val answer = get(port, "/callback?code=c&state=the-state")
            assertEquals(200, answer.status)
            assertTrue("Sign-in was not completed." in answer.body)
            assertNotNull(listener.wait(0))
        } finally {
            listener.close()
        }
    }

    @Test
    fun `the accepted callback never prints its code`() {
        val (_, callback) = classifyLoopbackRequest("GET", "/callback?code=nc_secret_code&state=s", "s")
        assertFalse("nc_secret_code" in callback.toString())
    }

    // -- what a query means ----------------------------------------------------

    @Test
    fun `a query is decoded once, as a form decodes it`() {
        assertEquals(listOf("a" to "b c", "d" to "", "e" to "f=g"), formDecode("a=b+c&d&&e=f=g"))
        assertEquals(listOf("code" to "a+b/c=%41"), formDecode("code=a%2Bb%2Fc%3D%2541"))
        // A % without two hex digits after it stays as it is.
        assertEquals(listOf("x" to "%G1%", "y" to "%4"), formDecode("x=%G1%&y=%4"))
        // Bytes that are not UTF-8 become U+FFFD; what is outside ASCII is kept.
        assertEquals(listOf("x" to "�", "y" to "été"), formDecode("x=%FF&y=%C3%A9t%C3%A9"))
        assertEquals(listOf("y" to "é"), formDecode("y=é"))
    }

    // -- the remaining edges -----------------------------------------------------

    @Test
    fun `this machine is named as the browser rule names it`() {
        assertEquals("macos", NativeSignIn.platformNameFor("Mac OS X"))
        assertEquals("windows", NativeSignIn.platformNameFor("Windows 11"))
        assertEquals("linux", NativeSignIn.platformNameFor("Linux"))
        assertEquals("freebsd", NativeSignIn.platformNameFor("FreeBSD"))
        assertEquals("sunos", NativeSignIn.platformNameFor("SunOS"))
    }

    @Test
    fun `the browser rule reads this process by default`() {
        assertEquals(NativeSignIn.canOpenBrowser(System.getenv(), NativeSignIn.platformName()), NativeSignIn.canOpenBrowser())
    }

    @Test
    fun `the browser opens with the platform's own command, never through a shell`() {
        val url = "https://dash.thalovant.com/authorize?a=1&b=2"
        assertEquals(listOf("open", url), browserCommand("macos", url))
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", url), browserCommand("windows", url))
        assertEquals(listOf("xdg-open", url), browserCommand("linux", url))
        assertEquals(listOf("xdg-open", url), browserCommand("freebsd", url))
        assertFalse(isAndroid())
    }

    @Test
    fun `an authorization endpoint no browser can open is unusable`() {
        val base = ThalovantJson.parseToJsonElement(config).jsonObject
        fun usable(endpoint: String): Boolean = runCatching {
            NativeSignInConfig.fromJson(JsonObject(base + ("authorization_endpoint" to JsonPrimitive(endpoint))))
        }.isSuccess
        for (endpoint in listOf(
            "https://dash.thalovant.com/author\u0007ize", "https://dash.thalovant.com /authorize",
            "https://dash.thalovant.com:99999/authorize", "https://[::1/authorize", "https://dash.thalovant.com:8x/authorize",
            "https://[1.2.3.4]/authorize", "https://x[::1]/authorize", "https://[::1]x/authorize", "https:///authorize",
            "ftp://dash.thalovant.com/authorize", "https://dash.thalovant.com/authorize?", "http://[::2]/authorize",
            "https://dash.thalovant.com /authorize", "",
        )) {
            assertFalse(usable(endpoint), endpoint)
        }
        for (endpoint in listOf(
            "http://[::1]:5173/authorize", "https://[2001:db8::1]/authorize", "https://[::ffff:1.2.3.4]/authorize",
            "https://dash_staging.example/authorize", "HTTPS://Dash.Thalovant.com/authorize", "http://LOCALHOST:5173/authorize",
            "https://dash.thalovant.com:/authorize", "https://dash.thalovant.com",
        )) {
            assertTrue(usable(endpoint), endpoint)
        }
    }

    @Test
    fun `a configuration that is not an object is unusable`() {
        assertFailsWith<IllegalArgumentException> { NativeSignInConfig.fromJson(JsonArray(emptyList())) }
        assertFailsWith<IllegalArgumentException> { NativeSignInConfig.fromJson(null) }
        val base = ThalovantJson.parseToJsonElement(config).jsonObject
        assertEquals("/v1/auth/device/authorize", NativeSignInConfig.fromJson(base).deviceAuthorizationEndpoint)
        for (tokenEndpoint in listOf("/v1/auth\\native", "/v1/auth native", "v1/auth/native/token", "/v1/#x")) {
            assertFailsWith<IllegalArgumentException>(tokenEndpoint) {
                NativeSignInConfig.fromJson(JsonObject(base + ("token_endpoint" to JsonPrimitive(tokenEndpoint))))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            NativeSignInConfig.fromJson(JsonObject(base + ("sdk_client_id" to JsonPrimitive("x".repeat(65)))))
        }
    }

    @Test
    fun `an answer that is not JSON is an error with no status`() {
        for (route in listOf("config", "token")) {
            val garbled = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>not json</html>")
            val error = runBlocking {
                Api(tokenAnswer = { garbled }, configAnswer = { if (route == "config") garbled else json(200, config) }).use { api ->
                    val plane = ThalovantControlPlane(api.url)
                    val started = start(plane) { handOver -> BrowserLoginOptions(openBrowser = false, prompt = handOver) }
                    if (route == "token") {
                        val parts = started.parts()
                        assertEquals(200, get(parts.port, "/callback?code=c&state=${parts.state}").status)
                    }
                    assertFailsWith<ThalovantApiException> { started.result() }
                }
            }
            assertNull(error.statusCode, route)
            assertNull(error.errorCode, route)
        }
    }

    @Test
    fun `a 404 configuration says to use a device login`(): Unit = runBlocking {
        Api(configAnswer = { json(404, """{"detail":"Not Found"}""") }).use { api ->
            val plane = ThalovantControlPlane(api.url)
            val error = assertFailsWith<ThalovantApiException> { plane.browserLogin(BrowserLoginOptions(openBrowser = false)) }
            assertEquals(404, error.statusCode)
            assertEquals("Not Found", error.detail)
            assertTrue("device login" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `a tab whose answer never comes is told the sign-in was not completed`(): Unit = runBlocking {
        val listener = LoopbackListener("the-state", answerTimeoutMillis = 50)
        val port = listener.start()
        try {
            val answer = get(port, "/callback?code=c&state=the-state")
            assertEquals(200, answer.status)
            assertNotNull(listener.wait(0))
            assertTrue("Sign-in was not completed." in answer.body)
        } finally {
            listener.close()
        }
    }
}
