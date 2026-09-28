package com.thalovant.sdk

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** What the Home Assistant control-plane calls do beyond the shared vectors. */
class HomeLinkControlPlaneTest {
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

    private fun api(token: String? = "synthetic-token") = ThalovantControlPlane(server.url("/").toString(), accessToken = token)

    private fun answer(status: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    private val hub = buildJsonObject {
        put("id", "hub-1")
        put("domain", "kitchen.thalovant.io")
        put("wss_enabled", true)
    }

    @Test
    fun `a started sign-in and a token never print their secrets, and round-trip`() {
        val grant = DeviceAuthorization.fromJson(
            ThalovantJson.parseToJsonElement(
                """{"device_code":"dc-secret-1","user_code":"WDJB-MJHT","verification_uri":"https://thalovant.com/activate","interval":2.5,"expires_in":600}""",
            ).jsonObject,
        )
        assertFalse("dc-secret-1" in grant.toString())
        assertEquals(2_500, grant.intervalMillis)
        assertEquals(600_000, grant.expiresInMillis)
        val again = DeviceAuthorization.fromJson(grant.asJson())
        assertEquals(grant.deviceCode, again.deviceCode)
        assertEquals(grant.intervalMillis, again.intervalMillis)
        assertEquals(grant.expiresInMillis, again.expiresInMillis)

        val token = ApiToken.fromJson(buildJsonObject { put("access_token", "tvt-secret-2"); put("token_id", "t-1") })
        assertFalse("tvt-secret-2" in token.toString())
        assertEquals("bearer", token.tokenType)
        assertEquals("tvt-secret-2", ApiToken.fromJson(token.asJson()).accessToken)
        assertFailsWith<ThalovantApiException> { ApiToken.fromJson(buildJsonObject { put("token_type", "bearer") }) }
    }

    @Test
    fun `loginWithBrowser keeps the token's id, so it can revoke itself`(): Unit = runBlocking {
        answer(200, """{"device_code":"dc-1","user_code":"U","verification_uri":"https://thalovant.com/activate","interval":0}""")
        answer(200, """{"access_token":"tvt-1","token_id":"tok-1"}""")
        val plane = api(token = null)
        plane.loginWithBrowser(DeviceLoginOptions(openBrowser = false, prompt = {}))
        assertEquals("tok-1", plane.tokenId)
        server.takeRequest(); server.takeRequest()
        answer(204)
        plane.revokeApiToken()
        val revoke = server.takeRequest()
        assertEquals("DELETE", revoke.method)
        assertEquals("/v1/auth/api-tokens/tok-1", revoke.requestUrl?.encodedPath)
        assertEquals("Bearer tvt-1", revoke.getHeader("Authorization"))
        assertNull(plane.accessToken)
        assertNull(plane.tokenId)
        // Revoked already: again is not an error, and nothing is sent.
        plane.revokeApiToken()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a revoke that finishes after a new sign-in forgets only the token it revoked`(): Unit = runBlocking {
        // The DELETE is answered late; a password sign-in lands in between.
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS))
        answer(200, """{"access_token":"session-token","token_type":"bearer"}""")
        val plane = api()
        plane.tokenId = "old-token"
        val revoking = launch(kotlinx.coroutines.Dispatchers.IO) { plane.revokeApiToken() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest() }
        plane.login("me@example.com", "correct horse battery")
        revoking.join()
        assertEquals("session-token", plane.accessToken)
    }

    @Test
    fun `revoking another token keeps the one in use`(): Unit = runBlocking {
        answer(204)
        val plane = api()
        plane.tokenId = "mine"
        plane.revokeApiToken("theirs/../x")
        assertEquals("/v1/auth/api-tokens/theirs%2F..%2Fx", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("synthetic-token", plane.accessToken)
        assertEquals("mine", plane.tokenId)
    }

    @Test
    fun `denied and expired carry the API's answer, and stay the types they were`(): Unit = runBlocking {
        answer(400, """{"error":"access_denied"}""")
        val denied = assertFailsWith<ThalovantDeviceLoginDeniedException> { api(null).pollDeviceLogin("dc-1") }
        assertEquals(400, denied.statusCode)
        assertTrue("access_denied" in denied.body.orEmpty())
        assertIs<ThalovantException>(denied)
        answer(400, """{"error":"expired_token"}""")
        val expired = assertFailsWith<ThalovantDeviceLoginExpiredException> { api(null).pollDeviceLogin("dc-1") }
        assertEquals(400, expired.statusCode)
        // Built the old way, they still read the old way.
        assertNull(ThalovantDeviceLoginDeniedException("no").statusCode)
    }

    @Test
    fun `every call's refusals come typed, and all are still API exceptions`(): Unit = runBlocking {
        answer(401, """{"detail":"Could not validate credentials"}""")
        val auth = assertFailsWith<ThalovantApiException> { api().getHub("hub-1") }
        assertIs<ThalovantAuthException>(auth)
        answer(423, """{"detail":"Account locked"}""")
        assertIs<ThalovantAuthException>(assertFailsWith<ThalovantApiException> { api().listHubs() })
        answer(403, """{"detail":"Free plan allows up to 1 API token.","code":"plan_limit","resource":"api_token","limit":1,"used":1}""")
        val plan = assertFailsWith<ThalovantPlanException> { api().listClients() }
        assertEquals(1, plan.problem!!["limit"]!!.jsonPrimitive.content.toInt())
        answer(409, """{"detail":{"code":"home_assistant_already_linked","existing_client_id":"c-9"}}""")
        val linked = assertFailsWith<ThalovantAlreadyLinkedException> { api().createClient(buildJsonObject {}) }
        assertEquals("c-9", linked.clientId)
        answer(403, """{"detail":"Only an administrator may do that"}""")
        val other = assertFailsWith<ThalovantApiException> { api().getHub("hub-1") }
        assertEquals(ThalovantApiException::class, other::class)
    }

    @Test
    fun `a connection created without a kind is left as it always was`(): Unit = runBlocking {
        answer(201, """{"id":"c-1","etag":"e-1","spec":{"version":"1"},"initial_identify":{"access_key":"a","password":"p","site_id":"s","default_master":"wss://kitchen.thalovant.io"}}""")
        val result = api().createClientIdentity(hub, CreateClientIdentityOptions(name = "Kitchen"))
        val body = ThalovantJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("connection_type" in body.getValue("spec").jsonObject)
        assertEquals("c-1", result.clientId)
        assertNull(result.connectionType)
        assertNull(result.operation)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `every sign-in sets the token id, so a revoke never reaches another token`(): Unit = runBlocking {
        answer(200, """{"access_token":"device-token","token_id":"device-1"}""")
        val plane = api(null)
        plane.pollDeviceLogin("dc-1")
        assertEquals("device-1", plane.tokenId)
        // A password sign-in answers with no id: the device token's is not kept.
        answer(200, """{"access_token":"session-token","token_type":"bearer"}""")
        plane.login("me@example.com", "correct horse battery")
        assertEquals("session-token", plane.accessToken)
        assertNull(plane.tokenId)
        assertFailsWith<ThalovantApiException> { plane.revokeApiToken() }
        // A native sign-in mints an API token and says which.
        answer(200, """{"access_token":"native-token","token_id":"native-1"}""")
        plane.completeNativeSignIn("code", "verifier", "thalovant-android", "com.thalovant.app:/callback")
        assertEquals("native-1", plane.tokenId)
    }

    @Test
    fun `revoking the token in use twice is not an error, and another token's 401 still is`(): Unit = runBlocking {
        answer(401, """{"detail":"Could not validate credentials"}""")
        val plane = api()
        plane.tokenId = "mine"
        plane.revokeApiToken()
        assertNull(plane.accessToken)
        assertNull(plane.tokenId)
        // Revoked and forgotten: again sends nothing and succeeds, until the next sign-in.
        plane.revokeApiToken()
        assertEquals(1, server.requestCount)
        answer(401, """{"detail":"Could not validate credentials"}""")
        val other = api().also { it.tokenId = "mine" }
        assertFailsWith<ThalovantAuthException> { other.revokeApiToken("theirs") }
        assertEquals("synthetic-token", other.accessToken)
        assertEquals("mine", other.tokenId)
    }

    @Test
    fun `a 422 about another field is not an unsupported kind, even when it echoes the kind back`(): Unit = runBlocking {
        answer(422, """{"detail":[{"type":"string_too_long","loc":["body","name"],"msg":"String should have at most 64 characters","input":{"name":"x","spec":{"connection_type":"home_assistant"}}}]}""")
        val other = assertFailsWith<ThalovantApiException> {
            api().createClientIdentity(hub, CreateClientIdentityOptions(name = "HA", connectionType = CONNECTION_TYPE_HOME_ASSISTANT))
        }
        assertEquals(ThalovantApiException::class, other::class)
        answer(422, """{"detail":[{"type":"literal_error","loc":["body","spec","connection_type"],"msg":"Input should be 'voice_satellite'","input":"home_assistant"}]}""")
        assertFailsWith<ThalovantUnsupportedConnectionTypeException> {
            api().createClientIdentity(hub, CreateClientIdentityOptions(name = "HA", connectionType = CONNECTION_TYPE_HOME_ASSISTANT))
        }
    }

    @Test
    fun `a rate limit while waiting is ridden out for as long as it says`(): Unit = runBlocking {
        answer(429, """{"detail":{"code":"rate_limited","retry_after_seconds":0.3}}""")
        answer(200, """{"id":"op-1","status":"ready"}""")
        val operation = OperationResource(
            id = "op-1", kind = "client.sync", aggregateType = "client", status = OperationStatus.REQUESTED,
            createdAt = "2026-09-27T10:00:00Z", updatedAt = "2026-09-27T10:00:00Z",
        )
        val started = System.nanoTime()
        api().waitForAdmission(operation, timeoutMs = 5_000, pollIntervalMs = 5)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(2, server.requestCount)
        assertTrue(tookMs >= 300, "waited $tookMs ms")
    }

    @Test
    fun `no read of an admission runs past its deadline`(): Unit = runBlocking {
        // The API accepts the read and never answers it.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val operation = OperationResource(
            id = "op-1", kind = "client.sync", aggregateType = "client", status = OperationStatus.REQUESTED,
            createdAt = "2026-09-27T10:00:00Z", updatedAt = "2026-09-27T10:00:00Z",
        )
        val started = System.nanoTime()
        val timedOut = assertFailsWith<ThalovantAdmissionTimeoutException> {
            api().waitForAdmission(operation, timeoutMs = 300, pollIntervalMs = 10)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 2_000, "waited $tookMs ms")
        assertTrue(timedOut.message.orEmpty().endsWith("it may still admit it later."))
    }

    @Test
    fun `an origin is scheme, host and port, with the default port spelled out`(): Unit = runBlocking {
        fun operation(link: String) = OperationResource(
            id = "op-1", kind = "client.sync", aggregateType = "client", status = OperationStatus.REQUESTED,
            createdAt = "2026-09-27T10:00:00Z", updatedAt = "2026-09-27T10:00:00Z", links = mapOf("self" to link),
        )
        val plane = ThalovantControlPlane("https://api.thalovant.invalid", accessToken = "synthetic-token")
        // The same origin spelled with its default port is followed: the read is
        // attempted, and fails only because nothing answers there.
        assertFailsWith<java.io.IOException> {
            plane.waitForAdmission(operation("https://api.thalovant.invalid:443/v1/operations/op-1"), timeoutMs = 5_000)
        }
        for (other in listOf("http://api.thalovant.invalid:443/v1/operations/op-1", "https://api.thalovant.invalid:8443/v1/operations/op-1")) {
            val refused = assertFailsWith<ThalovantApiException> { plane.waitForAdmission(operation(other), timeoutMs = 5_000) }
            assertTrue("outside" in refused.message.orEmpty(), other)
        }
    }

    @Test
    fun `an empty scope list asks for the API's default, in both sign-ins`(): Unit = runBlocking {
        answer(200, """{"device_code":"dc-1","user_code":"U","verification_uri":"https://thalovant.com/activate","interval":0}""")
        api(null).beginDeviceLogin(scopes = emptyList())
        assertEquals("{}", server.takeRequest().body.readUtf8())
        answer(200, """{"device_code":"dc-1","user_code":"U","verification_uri":"https://thalovant.com/activate","interval":0}""")
        answer(200, """{"access_token":"tvt-1"}""")
        api(null).loginWithBrowser(DeviceLoginOptions(scopes = emptyList(), openBrowser = false, prompt = {}))
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a 429 says how long to wait, from its body or its headers`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("""{"detail":{"retry_after_seconds":3}}"""))
        assertEquals(3.0, assertFailsWith<ThalovantApiException> { api().getHub("h") }.retryAfterSeconds)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("Too Many Requests"))
        assertEquals(7.0, assertFailsWith<ThalovantApiException> { api().getHub("h") }.retryAfterSeconds)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("RateLimit-Reset", "12").setBody("Too Many Requests"))
        assertEquals(12.0, assertFailsWith<ThalovantApiException> { api().getHub("h") }.retryAfterSeconds)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(assertFailsWith<ThalovantApiException> { api().getHub("h") }.retryAfterSeconds)
    }

    @Test
    fun `a failed clean-up is said, and the refusal is still unsupported`(): Unit = runBlocking {
        answer(201, """{"id":"c-1","etag":"e-1","spec":{"version":"1"}}""")
        answer(500, """{"detail":"boom"}""")
        val refused = assertFailsWith<ThalovantUnsupportedConnectionTypeException> {
            api().createClientIdentity(hub, CreateClientIdentityOptions(name = "HA", connectionType = CONNECTION_TYPE_HOME_ASSISTANT))
        }
        assertTrue("remove it in the dashboard" in refused.message.orEmpty())
        assertNull(refused.statusCode)
    }

    @Test
    fun `a delete retries a changed client once, not twice`(): Unit = runBlocking {
        answer(412, """{"detail":"ETag mismatch"}""")
        answer(200, """{"id":"c-1","etag":"e-2"}""")
        answer(412, """{"detail":"ETag mismatch"}""")
        val failure = assertFailsWith<ThalovantApiException> { api().deleteClient("c-1", "e-1") }
        assertEquals(412, failure.statusCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `waiting on a created connection follows the operation the API answered with`(): Unit = runBlocking {
        answer(201, """{"id":"c-1","etag":"e-1","spec":{"version":"1","connection_type":"home_assistant"},"operation":{"id":"op/1","status":"requested","links":{"self":"/v1/operations/op%2F1"}}}""")
        answer(200, """{"id":"op/1","status":"reconciling"}""")
        answer(200, """{"id":"op/1","status":"ready"}""")
        val plane = api()
        val result = plane.createClientIdentity(hub, CreateClientIdentityOptions(name = "HA", connectionType = CONNECTION_TYPE_HOME_ASSISTANT))
        // A status this SDK does not know is not a reason to give up waiting.
        assertNull(result.operation)
        plane.waitForAdmission(result, timeoutMs = 5_000, pollIntervalMs = 5)
        server.takeRequest()
        assertEquals("/v1/operations/op%2F1", server.takeRequest().requestUrl?.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a refusal while waiting is the API's, not the hub's`(): Unit = runBlocking {
        answer(401, """{"detail":"Could not validate credentials"}""")
        val operation = OperationResource(
            id = "op-1",
            kind = "client.sync",
            aggregateType = "client",
            status = OperationStatus.REQUESTED,
            createdAt = "2026-09-27T10:00:00Z",
            updatedAt = "2026-09-27T10:00:00Z",
            // The API's own origin, spelled in full: fetched.
            links = mapOf("self" to server.url("/v1/operations/op-1").toString()),
        )
        // A token revoked mid-wait: sign in again, rather than "the hub could not admit".
        assertFailsWith<ThalovantAuthException> {
            api().waitForAdmission(operation, timeoutMs = 5_000, pollIntervalMs = 5)
        }
        assertEquals(1, server.requestCount)
    }
}
