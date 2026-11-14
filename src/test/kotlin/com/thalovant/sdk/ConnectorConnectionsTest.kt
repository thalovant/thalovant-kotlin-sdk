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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Home bridges (`connection_type: connector`) and the refusals that came with
 * them (thalovant-api #521). Each test also holds for an API that does not
 * have them yet. Mirrors the Python reference's `test_connector_connections.py`.
 */
class ConnectorConnectionsTest {
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

    private fun api() = ThalovantControlPlane(server.url("/").toString(), accessToken = "synthetic-token")

    private val hub = buildJsonObject {
        put("id", "hub-1")
        put("name", "Home")
        put("domain", "home.example.com")
        put("wss_enabled", true)
        put("http_enabled", true)
    }

    private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        headers.forEach { (name, value) -> response.setHeader(name, value) }
        server.enqueue(response)
    }

    private fun created(kind: String, extra: String = "", identity: Boolean = true): String {
        val identify = if (identity) {
            """,
            "initial_identify": {"password": "platform-password", "access_key": "platform-key", "site_id": "bridge",
              "default_port": 443, "default_master": "wss://home.example.com"}"""
        } else {
            ""
        }
        return """{"id": "client-1", "name": "Bridge", "hub_id": "hub-1", "etag": "e1",
            "spec": {"version": "1", "connection_type": "$kind"}$identify$extra}"""
    }

    private fun problem(status: Int, code: String, extra: String = "") =
        answer(status, """{"detail": "refused", "code": "$code"$extra}""")

    private fun create(
        kind: String? = "connector",
        spec: JsonObject? = null,
        key: String? = null,
        ownerId: String? = null,
    ) = runBlocking {
        api().createClientIdentity(
            hub,
            CreateClientIdentityOptions(name = "Bridge", connectionType = kind, spec = spec, idempotencyKey = key, ownerId = ownerId),
        )
    }

    @Test
    fun `the kind constants are the wire values`() {
        assertEquals("connector", CONNECTION_TYPE_CONNECTOR)
        assertEquals("home_assistant", CONNECTION_TYPE_HOME_ASSISTANT)
    }

    @Test
    fun `a bridge is created with its connector list and the platforms credentials`() {
        val serves = """{"devices": [{"id": "d1", "name": "Kitchen", "connection_type": "voice_satellite", "active": true}],
            "count": 1, "warning": null}"""
        answer(201, created("connector", ""","serves": $serves"""))
        val spec = buildJsonObject { put("connectors", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("music_assistant")) }) }
        val result = create(spec = spec)

        val sent = bodyOf(server.takeRequest()).getValue("spec").jsonObject
        assertEquals("connector", sent["connection_type"]?.jsonPrimitive?.content)
        assertEquals("music_assistant", sent["connectors"]?.jsonArray?.single()?.jsonPrimitive?.content)
        // The answer's credentials are the connection's; the ones minted locally are not.
        assertEquals("platform-key", result.identity.accessKey)
        assertEquals("platform-password", result.identity.password)
        assertEquals("connector", result.connectionType)
        assertFalse(result.replayed)
        assertEquals(ThalovantJson.parseToJsonElement(serves).jsonObject, result.serves)
        assertNull(result.servesWarning)
    }

    @Test
    fun `a link that serves nobody says so`() {
        answer(
            201,
            created("home_assistant", ""","serves": {"devices": [], "count": 0, "warning": "No device of your account is on this hub."}"""),
        )
        val result = create(kind = "home_assistant")
        assertEquals("No device of your account is on this hub.", result.servesWarning)
    }

    @Test
    fun `an api without serves still works`() {
        answer(201, created("connector"))
        val result = create()
        assertNull(result.serves)
        assertNull(result.servesWarning)
    }

    @Test
    fun `a replayed create by header never hands back an identity the sdk made up`() {
        answer(201, created("connector", identity = false), "Idempotent-Replayed" to "true")
        assertReplay()
    }

    @Test
    fun `a replayed create by marker never hands back an identity the sdk made up`() {
        answer(
            201,
            created(
                "connector",
                ""","replay": {"code": "idempotent_replay", "credentials_included": false, "detail": "x"}""",
                identity = false,
            ),
        )
        assertReplay()
    }

    private fun assertReplay() {
        val caught = assertFailsWith<ThalovantCredentialsNotReplayedException> { create(key = "k1") }
        assertEquals("client-1", caught.clientId)
        assertFalse("initial_identify" in caught.client)
        assertEquals("k1", server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `a replay header with credentials still never returns an identity`() {
        answer(201, created("connector"), "Idempotent-Replayed" to "TRUE")
        val caught = assertFailsWith<ThalovantCredentialsNotReplayedException> { create() }
        assertFalse("initial_identify" in caught.client)
    }

    @Test
    fun `an answer without credentials that is not a replay keeps the old behaviour`() {
        answer(201, created("connector", identity = false))
        assertTrue(create().identity.accessKey.isNotEmpty())
    }

    @Test
    fun `a link on another accounts hub names the reason`() {
        problem(422, "connection_type_unavailable", ""","reason": "hub_operated_by_another_account"""")
        val caught = assertFailsWith<ThalovantConnectionTypeUnavailableException> { create(kind = "home_assistant") }
        assertEquals("hub_operated_by_another_account", caught.reason)
        assertEquals(ConnectionProblemCode.CONNECTION_TYPE_UNAVAILABLE, caught.errorCode)
        assertIs<ThalovantUnsupportedConnectionTypeException>(caught)
        assertEquals(1, server.requestCount, "nothing to delete: the API made nothing")
    }

    @Test
    fun `an unknown or ambiguous kind is unsupported`() {
        for (code in listOf("connection_type_unknown", "connection_type_ambiguous")) {
            problem(422, code, ""","known": ["voice_satellite", "connector"]""")
            val caught = assertFailsWith<ThalovantUnsupportedConnectionTypeException> { create(kind = "plex") }
            assertEquals(code, caught.errorCode)
        }
    }

    @Test
    fun `a refused connector list says why and what is known`() {
        for (reason in listOf("duplicate", "own_kind", "unknown_or_malformed")) {
            problem(422, "connectors_invalid", ""","reason": "$reason", "known": ["music_assistant"]""")
            val caught = assertFailsWith<ThalovantConnectorsInvalidException> { create() }
            assertEquals(reason, caught.reason)
            assertEquals(listOf("music_assistant"), caught.known)
        }
    }

    @Test
    fun `an owner only refusal names the link`() {
        for (code in listOf(
            "device_owner_only_beside_home_link",
            "setup_session_owner_only_beside_home_link",
            "device_identity_owner_only_beside_home_link",
        )) {
            problem(403, code, ""","link_client_id": "client-9"""")
            val caught = assertFailsWith<ThalovantOwnerOnlyBesideLinkException> { create(kind = null, ownerId = "someone") }
            assertEquals("client-9", caught.linkClientId)
            assertEquals(403, caught.statusCode)
        }
    }

    @Test
    fun `the other new codes are typed`() {
        problem(409, "hub_scale_out_breaks_links", ""","links": 2""")
        assertEquals(2, assertFailsWith<ThalovantHubScaleOutBreaksLinksException> { create(kind = null) }.links)
        problem(409, "idempotency_key_spent")
        assertEquals(
            "idempotency_key_spent",
            assertFailsWith<ThalovantIdempotencyKeySpentException> { create(kind = null) }.errorCode,
        )
        problem(422, "skill_config_private_hub_only", ""","settings": ["url", "token"]""")
        assertEquals(listOf("url", "token"), assertFailsWith<ThalovantPrivateHubOnlyException> { create(kind = null) }.settings)
    }

    @Test
    fun `a structured refusal inside detail is read too`() {
        answer(422, """{"detail": {"code": "connectors_invalid", "reason": "duplicate", "known": ["music_assistant"]}}""")
        val caught = assertFailsWith<ThalovantConnectorsInvalidException> { create() }
        assertEquals("duplicate", caught.reason)
        assertEquals(listOf("music_assistant"), caught.known)
    }

    @Test
    fun `an unlisted code stays a plain api error`() {
        problem(409, "something_new")
        val caught = assertFailsWith<ThalovantApiException> { create(kind = null) }
        assertEquals(ThalovantApiException::class, caught::class)
        assertEquals("something_new", caught.errorCode)
    }

    private fun bodyOf(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        ThalovantJson.parseToJsonElement(request.body.readUtf8()).jsonObject
}
