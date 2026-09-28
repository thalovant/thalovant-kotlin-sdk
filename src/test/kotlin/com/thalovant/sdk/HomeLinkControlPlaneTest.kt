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
        // Nothing left to revoke, and nothing is sent.
        assertFailsWith<ThalovantApiException> { plane.revokeApiToken() }
        assertEquals(3, server.requestCount)
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
