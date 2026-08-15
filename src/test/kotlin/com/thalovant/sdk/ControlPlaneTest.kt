package com.thalovant.sdk

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class ControlPlaneTest {
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

    private fun api(accessToken: String? = null): ThalovantControlPlane =
        ThalovantControlPlane(server.url("/api").toString(), accessToken = accessToken)

    private fun enqueueJson(status: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun bodyJson(request: RecordedRequest): JsonObject =
        ThalovantJson.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `uses public API default and normalizes v1 roots`() {
        assertEquals("https://api.thalovant.com/", ThalovantControlPlane().apiUrl)
        assertEquals(
            "https://api.thalovant.com/",
            ThalovantControlPlane("https://api.thalovant.com/v1").apiUrl,
        )
        assertEquals(
            "https://dash.example.com/api/",
            ThalovantControlPlane("https://dash.example.com/api/v1").apiUrl,
        )
    }

    @Test
    fun `login sends MFA codes only when provided`() = runBlocking {
        repeat(3) { enqueueJson(200, """{"access_token":"token","expires_in":3600}""") }
        val api = api()

        api.login("ada@example.com", "secret")
        api.login("ada@example.com", "secret", otpCode = "123456")
        api.login("ada@example.com", "secret", recoveryCode = "abcd-efgh", scope = "admin")

        val first = server.takeRequest()
        assertEquals("/api/v1/auth/token", first.path)
        assertEquals(DEFAULT_USER_AGENT, first.getHeader("User-Agent"))
        assertNull(first.getHeader("Authorization"))
        val firstBody = bodyJson(first)
        assertEquals("ada@example.com", firstBody["email"]?.jsonPrimitive?.content)
        assertEquals("secret", firstBody["password"]?.jsonPrimitive?.content)
        assertFalse("otp_code" in firstBody)
        assertFalse("recovery_code" in firstBody)
        assertFalse("scope" in firstBody)

        val secondBody = bodyJson(server.takeRequest())
        assertEquals("123456", secondBody["otp_code"]?.jsonPrimitive?.content)
        assertFalse("recovery_code" in secondBody)

        val thirdBody = bodyJson(server.takeRequest())
        assertEquals("abcd-efgh", thirdBody["recovery_code"]?.jsonPrimitive?.content)
        assertEquals("admin", thirdBody["scope"]?.jsonPrimitive?.content)
        assertFalse("otp_code" in thirdBody)

        assertEquals("token", api.accessToken)
    }

    @Test
    fun `api errors carry status code and body`() = runBlocking {
        enqueueJson(401, """{"code":"mfa_required"}""")
        val error = assertFailsWith<ThalovantApiException> {
            api().login("ada@example.com", "secret")
        }
        assertEquals(401, error.statusCode)
        assertEquals("""{"code":"mfa_required"}""", error.body)
        assertTrue("HTTP 401" in error.message.orEmpty())
    }

    @Test
    fun `lists public hubs without auth`() = runBlocking {
        enqueueJson(
            200,
            """{"data":[{"id":"hub-public","slug":"joke-garden","title":"Joke Garden"}],"meta":{"count":1,"next":null},"links":{"next":null}}""",
        )
        enqueueJson(200, """{"id":"hub-public","slug":"joke-garden","title":"Joke Garden"}""")
        val api = api(accessToken = "token")

        val page = api.listPublicHubs(limit = 12)
        val hub = api.getPublicHub("joke-garden")

        val listRequest = server.takeRequest()
        assertEquals("/api/v1/public/hubs", listRequest.requestUrl?.encodedPath)
        assertEquals("12", listRequest.requestUrl?.queryParameter("limit"))
        assertNull(listRequest.getHeader("Authorization"))
        val getRequest = server.takeRequest()
        assertEquals("/api/v1/public/hubs/joke-garden", getRequest.requestUrl?.encodedPath)
        assertNull(getRequest.getHeader("Authorization"))
        assertEquals("Joke Garden", hub["title"]?.jsonPrimitive?.content)
        assertTrue(page["data"] is kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun `lists hubs with bearer token`() = runBlocking {
        enqueueJson(200, """{"data":[],"meta":{"count":0,"next":null},"links":{"next":null}}""")
        api(accessToken = "token").listHubs(limit = 50, ownerId = "owner-1")

        val request = server.takeRequest()
        assertEquals("/api/v1/hubs", request.requestUrl?.encodedPath)
        assertEquals("50", request.requestUrl?.queryParameter("limit"))
        assertEquals("owner-1", request.requestUrl?.queryParameter("owner_id"))
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }

    @Test
    fun `gets a typed durable operation`() = runBlocking {
        enqueueJson(
            200,
            """
            {
              "id": "operation-1",
              "kind": "gitops.commit",
              "aggregate_type": "gitops",
              "aggregate_id": null,
              "status": "committed",
              "details": {"git_commit_created": true},
              "git_commit_sha": "abc123",
              "error_code": null,
              "error_message": null,
              "created_at": "2026-07-11T00:00:00Z",
              "updated_at": "2026-07-11T00:00:01Z",
              "committed_at": "2026-07-11T00:00:01Z",
              "applied_at": null,
              "ready_at": null,
              "terminal_at": null,
              "links": {"self": "/v1/operations/operation-1"}
            }
            """.trimIndent(),
        )

        val operation = api(accessToken = "token").getOperation("operation-1")

        val request = server.takeRequest()
        assertEquals("/api/v1/operations/operation-1", request.requestUrl?.encodedPath)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("operation-1", operation.id)
        assertEquals(OperationStatus.COMMITTED, operation.status)
        assertEquals("gitops", operation.aggregateType)
        assertNull(operation.aggregateId)
        assertEquals("abc123", operation.gitCommitSha)
        assertEquals(true, operation.details["git_commit_created"]?.jsonPrimitive?.boolean)
        assertEquals("2026-07-11T00:00:00Z", operation.createdAt)
        assertEquals("2026-07-11T00:00:01Z", operation.committedAt)
        assertNull(operation.terminalAt)
        assertEquals("/v1/operations/operation-1", operation.links["self"])
    }

    @Test
    fun `manages memory items with snake_case parameters`() = runBlocking {
        enqueueJson(200, """{"data":[{"id":"memory-1","content":"Use UTC."}],"meta":{"count":1,"next":null},"links":{"next":null}}""")
        enqueueJson(200, """{"total":1,"by_scope":{"workspace":1},"by_kind":{"preference":1},"expired":0,"deleted":0}""")
        enqueueJson(201, """{"id":"memory-1","scope":"workspace","kind":"preference","content":"Use UTC."}""")
        enqueueJson(200, """{"id":"memory-1","scope":"workspace","kind":"preference","content":"Use UTC."}""")
        enqueueJson(200, """{"id":"memory-1","scope":"workspace","kind":"preference","content":"Use America/Toronto."}""")
        server.enqueue(MockResponse().setResponseCode(204))
        val api = api(accessToken = "token")

        api.listMemoryItems(
            MemoryListOptions(
                scope = MemoryScope.WORKSPACE,
                kind = MemoryKind.PREFERENCE,
                ownerId = "owner-1",
                hubId = "hub-1",
                query = "timezone",
                includeDeleted = true,
                includeExpired = true,
                limit = 25,
                offset = 50,
            ),
        )
        val summary = api.getMemorySummary(ownerId = "owner-1")
        val created = api.createMemoryItem(
            MemoryCreatePayload(
                scope = MemoryScope.WORKSPACE,
                kind = MemoryKind.PREFERENCE,
                content = "Use UTC.",
                ownerId = "owner-1",
                hubId = "hub-1",
                consentScope = "daily_desk_memory",
                retentionPolicy = "user_controlled",
            ),
        )
        api.getMemoryItem("memory-1")
        val updated = api.updateMemoryItem(
            "memory-1",
            MemoryUpdatePayload(content = "Use America/Toronto.", clearExpiresAt = true),
        )
        api.deleteMemoryItem("memory-1")

        val list = server.takeRequest()
        assertEquals("/api/v1/memory", list.requestUrl?.encodedPath)
        assertEquals("workspace", list.requestUrl?.queryParameter("scope"))
        assertEquals("preference", list.requestUrl?.queryParameter("kind"))
        assertEquals("owner-1", list.requestUrl?.queryParameter("owner_id"))
        assertEquals("hub-1", list.requestUrl?.queryParameter("hub_id"))
        assertEquals("timezone", list.requestUrl?.queryParameter("q"))
        assertEquals("true", list.requestUrl?.queryParameter("include_deleted"))
        assertEquals("true", list.requestUrl?.queryParameter("include_expired"))
        assertEquals("25", list.requestUrl?.queryParameter("limit"))
        assertEquals("50", list.requestUrl?.queryParameter("offset"))

        val summaryRequest = server.takeRequest()
        assertEquals("/api/v1/memory/summary", summaryRequest.requestUrl?.encodedPath)
        assertEquals("owner-1", summaryRequest.requestUrl?.queryParameter("owner_id"))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        val createBody = bodyJson(create)
        assertEquals("workspace", createBody["scope"]?.jsonPrimitive?.content)
        assertEquals("preference", createBody["kind"]?.jsonPrimitive?.content)
        assertEquals("Use UTC.", createBody["content"]?.jsonPrimitive?.content)
        assertEquals("owner-1", createBody["owner_id"]?.jsonPrimitive?.content)
        assertEquals("hub-1", createBody["hub_id"]?.jsonPrimitive?.content)
        assertEquals("daily_desk_memory", createBody["consent_scope"]?.jsonPrimitive?.content)
        assertEquals("user_controlled", createBody["retention_policy"]?.jsonPrimitive?.content)

        assertEquals("GET", server.takeRequest().method)

        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        val updateBody = bodyJson(update)
        assertEquals("Use America/Toronto.", updateBody["content"]?.jsonPrimitive?.content)
        assertEquals(true, updateBody["clear_expires_at"]?.jsonPrimitive?.boolean)

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/memory/memory-1", delete.requestUrl?.encodedPath)

        assertEquals(1, summary["total"]?.jsonPrimitive?.content?.toInt())
        assertEquals("memory-1", created["id"]?.jsonPrimitive?.content)
        assertEquals("Use America/Toronto.", updated["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetches admin analytics overview with all filters`() = runBlocking {
        enqueueJson(200, """{"meta":{"scope":"admin"},"totals":{"utterances":7}}""")

        val overview = api(accessToken = "token").getAnalyticsOverview(
            AnalyticsOverviewOptions(
                admin = true,
                range = "30d",
                bucket = "1d",
                ownerId = "owner-1",
                hubId = "hub-1",
                clientId = "client-1",
                country = "CA",
                message = "speak",
                utterance = "hello",
                intent = "DailyDeskIntent",
                timeStart = "2026-05-03T20:00:00Z",
                timeEnd = "2026-05-03T21:00:00Z",
                weekday = 6,
                hour = 0,
            ),
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/admin/analytics/overview", request.requestUrl?.encodedPath)
        assertEquals("30d", request.requestUrl?.queryParameter("range"))
        assertEquals("1d", request.requestUrl?.queryParameter("bucket"))
        assertEquals("owner-1", request.requestUrl?.queryParameter("owner_id"))
        assertEquals("hub-1", request.requestUrl?.queryParameter("hub_id"))
        assertEquals("client-1", request.requestUrl?.queryParameter("client_id"))
        assertEquals("CA", request.requestUrl?.queryParameter("country"))
        assertEquals("speak", request.requestUrl?.queryParameter("message"))
        assertEquals("hello", request.requestUrl?.queryParameter("utterance"))
        assertEquals("DailyDeskIntent", request.requestUrl?.queryParameter("intent"))
        assertEquals("2026-05-03T20:00:00Z", request.requestUrl?.queryParameter("time_start"))
        assertEquals("2026-05-03T21:00:00Z", request.requestUrl?.queryParameter("time_end"))
        assertEquals("6", request.requestUrl?.queryParameter("weekday"))
        assertEquals("0", request.requestUrl?.queryParameter("hour"))
        assertEquals("7", overview["totals"]?.jsonObject?.get("utterances")?.jsonPrimitive?.content)
    }

    @Test
    fun `analytics overview omits owner_id without admin`() = runBlocking {
        enqueueJson(200, """{"totals":{"utterances":0}}""")

        api(accessToken = "token").getAnalyticsOverview(
            AnalyticsOverviewOptions(range = "7d", ownerId = "owner-1"),
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/analytics/overview", request.requestUrl?.encodedPath)
        assertEquals("7d", request.requestUrl?.queryParameter("range"))
        assertNull(request.requestUrl?.queryParameter("owner_id"))
    }

    @Test
    fun `createClientIdentity keeps generated secrets local`() = runBlocking {
        enqueueJson(
            200,
            """
            {
              "id": "hub-1",
              "name": "joke-garden",
              "domain": "jokes.thalovant.io",
              "spec": {
                "protocols": {
                  "wss": {"enabled": true},
                  "http": {"enabled": true},
                  "mqtt": {"enabled": false}
                }
              }
            }
            """.trimIndent(),
        )
        enqueueJson(
            201,
            """{"id":"client-1","name":"kiosk","hub_id":"hub-1","spec":{"version":"1","apiKeyRef":{"name":"secret","key":"apiKey"}}}""",
        )
        val api = api(accessToken = "token")

        val result = api.createClientIdentity(
            "hub-1",
            CreateClientIdentityOptions(name = "kiosk", idempotencyKey = "idem-1"),
        )

        val hubRequest = server.takeRequest()
        assertEquals("/api/v1/hubs/hub-1", hubRequest.requestUrl?.encodedPath)
        assertEquals("Bearer token", hubRequest.getHeader("Authorization"))

        val clientRequest = server.takeRequest()
        assertEquals("/api/v1/clients", clientRequest.requestUrl?.encodedPath)
        assertEquals("idem-1", clientRequest.getHeader("Idempotency-Key"))
        val payload = bodyJson(clientRequest)
        assertEquals("hub-1", payload["hub_id"]?.jsonPrimitive?.content)
        assertEquals("kiosk", payload["name"]?.jsonPrimitive?.content)
        assertEquals(true, payload["active"]?.jsonPrimitive?.boolean)
        val spec = payload["spec"]?.jsonObject
        assertNotNull(spec)
        assertEquals("1", spec["version"]?.jsonPrimitive?.content)
        assertTrue(spec["apiKey"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())
        assertTrue(spec["password"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())
        assertTrue(spec["cryptoKey"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())
        assertEquals("kiosk", spec["siteId"]?.jsonPrimitive?.content)

        assertEquals("kiosk", result.identity.siteId)
        assertTrue(result.identity.accessKey.isNotEmpty())
        assertTrue(result.identity.password.isNotEmpty())
        assertEquals("https://jokes.thalovant.io", result.identity.endpointFor(HubProtocol.HTTPS))
        assertEquals(HubProtocol.WSS, result.selectedProtocol)
        assertEquals(
            SelectedHubEndpoint(HubProtocol.WSS, "wss://jokes.thalovant.io"),
            api.requireRuntimeProtocol(result),
        )
        val redacted = result.asJson()["identity"]?.jsonObject
        assertNotNull(redacted)
        assertFalse("access_key" in redacted)
        val withSecrets = result.asJson(includeSecrets = true)["identity"]?.jsonObject
        assertNotNull(withSecrets)
        assertTrue("access_key" in withSecrets)
    }

    @Test
    fun `createClientIdentity generates an Idempotency-Key when absent and honors active`() = runBlocking {
        enqueueJson(200, """{"id":"hub-1","name":"hub","domain":"jokes.thalovant.io"}""")
        enqueueJson(201, """{"id":"client-1","name":"kiosk","hub_id":"hub-1","spec":{}}""")

        api(accessToken = "token").createClientIdentity(
            "hub-1",
            CreateClientIdentityOptions(name = "kiosk", active = false),
        )

        server.takeRequest()
        val clientRequest = server.takeRequest()
        val idempotencyKey = clientRequest.getHeader("Idempotency-Key")
        assertNotNull(idempotencyKey)
        assertTrue(idempotencyKey.isNotEmpty())
        assertEquals(false, bodyJson(clientRequest)["active"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `createClientIdentity preserves API returned initial_identify`() = runBlocking {
        enqueueJson(
            200,
            """
            {
              "id": "hub-mqtt",
              "name": "mqtt-hub",
              "domain": "mqtt.thalovant.io",
              "data_plane_endpoints": {
                "https": "https://mqtt.thalovant.io",
                "wss": "wss://mqtt.thalovant.io",
                "mqtt": "mqtts://broker.thalovant.io:8883"
              },
              "spec": {
                "protocols": {
                  "wss": {"enabled": true},
                  "http": {"enabled": true},
                  "mqtt": {"enabled": true}
                }
              }
            }
            """.trimIndent(),
        )
        enqueueJson(
            201,
            """
            {
              "id": "client-mqtt",
              "name": "kiosk",
              "hub_id": "hub-mqtt",
              "spec": {"version": "1"},
              "initial_identify": {
                "access_key": "server-access",
                "password": "server-password",
                "crypto_key": "server-crypto",
                "site_id": "server-site",
                "default_port": 443,
                "default_master": "wss://mqtt.thalovant.io",
                "mqtt": {
                  "endpoint": "mqtts://broker.thalovant.io:8883",
                  "username": "server-access",
                  "password": "broker-password",
                  "topic_prefix": "hivemind/hub-mqtt/server-access",
                  "tls": true
                }
              }
            }
            """.trimIndent(),
        )
        val api = api(accessToken = "token")

        val result = api.createClientIdentity("hub-mqtt", CreateClientIdentityOptions(name = "kiosk"))

        assertEquals("server-access", result.identity.accessKey)
        assertEquals("server-site", result.identity.siteId)
        val mqtt = result.identity.mqtt
        assertNotNull(mqtt)
        assertEquals("mqtts://broker.thalovant.io:8883", mqtt.endpoint)
        assertEquals("broker-password", mqtt.password)
        assertTrue(mqtt.tls)
        assertEquals("mqtts://broker.thalovant.io:8883", result.identity.endpointFor(HubProtocol.MQTT))
        assertEquals(
            SelectedHubEndpoint(HubProtocol.MQTT, "mqtts://broker.thalovant.io:8883"),
            api.requireRuntimeProtocol(result, HubProtocol.MQTT),
        )
    }

    @Test
    fun `loginWithBrowser polls until the token is issued and stores it`() = runBlocking {
        enqueueJson(200, DEVICE_GRANT)
        enqueueJson(400, """{"error":"authorization_pending"}""")
        enqueueJson(400, """{"error":"authorization_pending"}""")
        enqueueJson(200, DEVICE_TOKEN)
        val api = api()
        val grants = mutableListOf<JsonObject>()

        val token = api.loginWithBrowser(
            DeviceLoginOptions(
                scopes = listOf("hubs:read"),
                clientName = "kotlin-test",
                openBrowser = false,
                prompt = { grants.add(it) },
            ),
        )

        val authorize = server.takeRequest()
        assertEquals("/api/v1/auth/device/authorize", authorize.requestUrl?.encodedPath)
        assertNull(authorize.getHeader("Authorization"))
        val authorizeBody = bodyJson(authorize)
        assertEquals(
            listOf("hubs:read"),
            authorizeBody["scopes"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("kotlin-test", authorizeBody["client_name"]?.jsonPrimitive?.content)

        repeat(3) {
            val poll = server.takeRequest()
            assertEquals("/api/v1/auth/device/token", poll.requestUrl?.encodedPath)
            assertNull(poll.getHeader("Authorization"))
            assertEquals("device-code-1", bodyJson(poll)["device_code"]?.jsonPrimitive?.content)
        }

        assertEquals("device-token", api.accessToken)
        assertEquals("device-token", token["access_token"]?.jsonPrimitive?.content)
        assertEquals("token-1", token["token_id"]?.jsonPrimitive?.content)
        assertEquals(1, grants.size)
        assertEquals("WDJB-MJHT", grants.single()["user_code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `loginWithBrowser default prompt prints the verification instructions`() = runBlocking {
        enqueueJson(200, DEVICE_GRANT)
        enqueueJson(200, DEVICE_TOKEN)
        val captured = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(captured, true))
        try {
            api().loginWithBrowser(DeviceLoginOptions(openBrowser = false))
        } finally {
            System.setOut(original)
        }

        val authorizeBody = bodyJson(server.takeRequest())
        assertTrue(authorizeBody.isEmpty())
        val output = captured.toString()
        assertTrue("visit https://dash.thalovant.com/activate" in output)
        assertTrue("WDJB-MJHT" in output)
    }

    @Test
    fun `device token poll grows the interval on slow_down`() = runBlocking {
        enqueueJson(400, """{"error":"authorization_pending"}""")
        enqueueJson(400, """{"error":"slow_down"}""")
        enqueueJson(400, """{"error":"authorization_pending"}""")
        enqueueJson(200, DEVICE_TOKEN)
        val sleeps = mutableListOf<Long>()

        val token = api().pollDeviceToken(
            "device-code-1",
            intervalMillis = 5_000,
            timeoutMillis = 900_000,
            sleep = { sleeps.add(it) },
            clock = { 0L },
        )

        assertEquals(listOf(5_000L, 10_000L, 10_000L), sleeps)
        assertEquals("device-token", token["access_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun `loginWithBrowser throws a distinct exception when denied`() = runBlocking {
        enqueueJson(200, DEVICE_GRANT)
        enqueueJson(400, """{"error":"access_denied"}""")
        val api = api()

        assertFailsWith<ThalovantDeviceLoginDeniedException> {
            api.loginWithBrowser(DeviceLoginOptions(openBrowser = false, prompt = {}))
        }
        assertNull(api.accessToken)
    }

    @Test
    fun `loginWithBrowser throws a distinct exception when the code expired`() = runBlocking {
        enqueueJson(200, DEVICE_GRANT)
        enqueueJson(400, """{"error":"expired_token"}""")
        val api = api()

        val error = assertFailsWith<ThalovantDeviceLoginExpiredException> {
            api.loginWithBrowser(DeviceLoginOptions(openBrowser = false, prompt = {}))
        }
        assertTrue("again" in error.message.orEmpty())
        assertNull(api.accessToken)
    }

    @Test
    fun `device token poll rethrows unexpected API errors`() = runBlocking {
        enqueueJson(500, """{"detail":"boom"}""")

        val error = assertFailsWith<ThalovantApiException> {
            api().pollDeviceToken("device-code-1", intervalMillis = 0, timeoutMillis = 900_000)
        }
        assertEquals(500, error.statusCode)
    }

    @Test
    fun `device token poll times out`() = runBlocking {
        repeat(3) { enqueueJson(400, """{"error":"authorization_pending"}""") }
        var now = 0L

        assertFailsWith<ThalovantTimeoutException> {
            api().pollDeviceToken(
                "device-code-1",
                intervalMillis = 5_000,
                timeoutMillis = 10_000,
                sleep = { now += it },
                clock = { now },
            )
        }
        assertEquals(3, server.requestCount)
        assertEquals(10_000L, now)
    }

    @Test
    fun `createHub sends an Idempotency-Key and a snake_case body`() = runBlocking {
        enqueueJson(201, """{"id":"hub-1","name":"joke-garden","etag":"etag-1"}""")

        val hub = api(accessToken = "token").createHub(
            HubCreatePayload(
                name = "joke-garden",
                spec = ThalovantJson.parseToJsonElement("""{"protocols":{"wss":{"enabled":true}}}""").jsonObject,
                ownerId = "owner-1",
                runtimeGroupId = "rg-1",
                capacityProfile = "standard",
                visibility = "private",
            ),
            idempotencyKey = "create-hub-1",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/hubs", request.requestUrl?.encodedPath)
        assertEquals("create-hub-1", request.getHeader("Idempotency-Key"))
        assertNull(request.getHeader("If-Match"))
        val body = bodyJson(request)
        assertEquals("joke-garden", body["name"]?.jsonPrimitive?.content)
        assertEquals("owner-1", body["owner_id"]?.jsonPrimitive?.content)
        assertEquals("rg-1", body["runtime_group_id"]?.jsonPrimitive?.content)
        assertEquals("standard", body["capacity_profile"]?.jsonPrimitive?.content)
        assertEquals("private", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(
            true,
            body["spec"]?.jsonObject?.get("protocols")?.jsonObject
                ?.get("wss")?.jsonObject?.get("enabled")?.jsonPrimitive?.boolean,
        )
        // Unset options stay off the wire so the server applies its own defaults.
        assertFalse("slug" in body)
        assertFalse("namespace" in body)
        assertFalse("domain" in body)
        assertFalse("active" in body)
        assertEquals("hub-1", hub["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `createHub generates an Idempotency-Key when none is given`() = runBlocking {
        enqueueJson(201, """{"id":"hub-1"}""")

        api(accessToken = "token").createHub(HubCreatePayload(name = "joke-garden", spec = JsonObject(emptyMap())))

        val key = server.takeRequest().getHeader("Idempotency-Key")
        assertNotNull(key)
        assertTrue(key.isNotEmpty())
    }

    @Test
    fun `updateHub and deleteHub send If-Match`() = runBlocking {
        enqueueJson(200, """{"id":"hub-1","active":false,"etag":"etag-2"}""")
        server.enqueue(MockResponse().setResponseCode(204))
        val api = api(accessToken = "token")

        val updated = api.updateHub("hub-1", HubUpdatePayload(active = false, isLocked = false), etag = "etag-1")
        api.deleteHub("hub-1", etag = "etag-2")

        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/hubs/hub-1", patch.requestUrl?.encodedPath)
        assertEquals("etag-1", patch.getHeader("If-Match"))
        val patchBody = bodyJson(patch)
        assertEquals(false, patchBody["active"]?.jsonPrimitive?.boolean)
        assertEquals(false, patchBody["is_locked"]?.jsonPrimitive?.boolean)
        assertFalse("name" in patchBody)

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/hubs/hub-1", delete.requestUrl?.encodedPath)
        assertEquals("etag-2", delete.getHeader("If-Match"))
        assertEquals("etag-2", updated["etag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `hub etag mismatch surfaces as HTTP 412`() = runBlocking {
        enqueueJson(412, """{"detail":"ETag mismatch"}""")

        val error = assertFailsWith<ThalovantApiException> {
            api(accessToken = "token").updateHub("hub-1", HubUpdatePayload(active = false), etag = "stale")
        }
        assertEquals(412, error.statusCode)
        assertTrue("ETag mismatch" in error.body.orEmpty())
    }

    @Test
    fun `releaseHub sends only the options given`() = runBlocking {
        enqueueJson(200, """{"id":"hub-1"}""")
        enqueueJson(200, """{"id":"hub-1"}""")
        val api = api(accessToken = "token")

        api.releaseHub("hub-1", ReleaseOptions(channel = "stable"))
        api.releaseHub(
            "hub-1",
            ReleaseOptions(
                channel = "stable",
                mode = "custom",
                version = "1.4.0",
                images = mapOf("listener" to "ghcr.io/thalovant/listener:1.4.0"),
                reason = "pin the kiosk fleet",
            ),
        )

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/v1/hubs/hub-1/release", first.requestUrl?.encodedPath)
        val firstBody = bodyJson(first)
        assertEquals("stable", firstBody["channel"]?.jsonPrimitive?.content)
        assertEquals(1, firstBody.size)

        val secondBody = bodyJson(server.takeRequest())
        assertEquals("custom", secondBody["mode"]?.jsonPrimitive?.content)
        assertEquals("1.4.0", secondBody["version"]?.jsonPrimitive?.content)
        assertEquals("pin the kiosk fleet", secondBody["reason"]?.jsonPrimitive?.content)
        assertEquals(
            "ghcr.io/thalovant/listener:1.4.0",
            secondBody["images"]?.jsonObject?.get("listener")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `sets and clears a hub rating without If-Match`() = runBlocking {
        enqueueJson(200, """{"id":"hub-1","viewer_rating":5}""")
        enqueueJson(200, """{"id":"hub-1","viewer_rating":null}""")
        val api = api(accessToken = "token")

        val rated = api.setHubRating("hub-1", 5)
        val cleared = api.clearHubRating("hub-1")

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/hubs/hub-1/rating", put.requestUrl?.encodedPath)
        assertEquals(5, bodyJson(put)["rating"]?.jsonPrimitive?.content?.toInt())
        assertNull(put.getHeader("If-Match"))
        assertNull(put.getHeader("Idempotency-Key"))

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/hubs/hub-1/rating", delete.requestUrl?.encodedPath)
        assertEquals(5, rated["viewer_rating"]?.jsonPrimitive?.content?.toInt())
        // Clearing a rating answers 200 with the hub, not 204.
        assertEquals("hub-1", cleared["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reads hub runtime capabilities`() = runBlocking {
        enqueueJson(
            200,
            """{"hub_id":"hub-1","source":"ovos-runtime","skills":[{"id":"skill-weather"}],"counts":{"total_intents":3}}""",
        )

        val capabilities = api(accessToken = "token").getHubRuntimeCapabilities("hub-1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/hubs/hub-1/runtime-capabilities", request.requestUrl?.encodedPath)
        assertEquals(3, capabilities["counts"]?.jsonObject?.get("total_intents")?.jsonPrimitive?.content?.toInt())
        assertEquals("ovos-runtime", capabilities["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `hub runtime capabilities surface HTTP 409 when nothing reports`() = runBlocking {
        enqueueJson(409, """{"detail":"Live skills and intents are not available for this hub yet."}""")

        val error = assertFailsWith<ThalovantApiException> {
            api(accessToken = "token").getHubRuntimeCapabilities("hub-1")
        }
        assertEquals(409, error.statusCode)
    }

    @Test
    fun `manages runtime groups without If-Match or idempotency headers`() = runBlocking {
        enqueueJson(200, """{"data":[{"id":"rg-1","name":"kiosks"}]}""")
        enqueueJson(201, """{"id":"rg-1","name":"kiosks"}""")
        enqueueJson(200, """{"id":"rg-1","name":"kiosks"}""")
        enqueueJson(200, """{"id":"rg-1","status":"pending"}""")
        enqueueJson(200, """{"id":"rg-1"}""")
        server.enqueue(MockResponse().setResponseCode(204))
        val api = api(accessToken = "token")

        val page = api.listRuntimeGroups(ownerId = "owner-1")
        val created = api.createRuntimeGroup(
            RuntimeGroupCreatePayload(
                name = "kiosks",
                description = "Lobby kiosks",
                environment = "prod",
                ownerId = "owner-1",
                cloneFromDefault = true,
            ),
        )
        val fetched = api.getRuntimeGroup("rg-1")
        val updated = api.updateRuntimeGroup(
            "rg-1",
            RuntimeGroupUpdatePayload(
                name = "kiosks-eu",
                spec = ThalovantJson.parseToJsonElement("""{"replicas":2}""").jsonObject,
            ),
        )
        api.releaseRuntimeGroup("rg-1", ReleaseOptions(channel = "stable"))
        api.deleteRuntimeGroup("rg-1")

        val list = server.takeRequest()
        assertEquals("/api/v1/runtime-groups", list.requestUrl?.encodedPath)
        assertEquals("owner-1", list.requestUrl?.queryParameter("owner_id"))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        val createBody = bodyJson(create)
        assertEquals("kiosks", createBody["name"]?.jsonPrimitive?.content)
        assertEquals("Lobby kiosks", createBody["description"]?.jsonPrimitive?.content)
        assertEquals("prod", createBody["environment"]?.jsonPrimitive?.content)
        assertEquals("owner-1", createBody["owner_id"]?.jsonPrimitive?.content)
        assertEquals(true, createBody["clone_from_default"]?.jsonPrimitive?.boolean)
        // No runtime-group route reads either header.
        assertNull(create.getHeader("Idempotency-Key"))

        assertEquals("GET", server.takeRequest().method)

        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/runtime-groups/rg-1", patch.requestUrl?.encodedPath)
        assertNull(patch.getHeader("If-Match"))
        val patchBody = bodyJson(patch)
        assertEquals("kiosks-eu", patchBody["name"]?.jsonPrimitive?.content)
        assertEquals(2, patchBody["spec"]?.jsonObject?.get("replicas")?.jsonPrimitive?.content?.toInt())
        assertFalse("description" in patchBody)

        val release = server.takeRequest()
        assertEquals("/api/v1/runtime-groups/rg-1/release", release.requestUrl?.encodedPath)
        assertEquals("stable", bodyJson(release)["channel"]?.jsonPrimitive?.content)

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/runtime-groups/rg-1", delete.requestUrl?.encodedPath)
        assertNull(delete.getHeader("If-Match"))

        assertEquals("rg-1", page["data"]?.jsonArray?.first()?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("rg-1", created["id"]?.jsonPrimitive?.content)
        assertEquals("kiosks", fetched["name"]?.jsonPrimitive?.content)
        assertEquals("pending", updated["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `listRuntimeGroups omits a blank owner_id`() = runBlocking {
        enqueueJson(200, """{"data":[]}""")

        api(accessToken = "token").listRuntimeGroups()

        assertNull(server.takeRequest().requestUrl?.queryParameter("owner_id"))
    }

    @Test
    fun `reads and merges runtime group config`() = runBlocking {
        enqueueJson(200, """{"runtime_group_id":"rg-1","config":{"lang":"en-us"},"personas":{}}""")
        enqueueJson(200, """{"runtime_group_id":"rg-1","config":{"lang":"en-us"},"personas":{"default":"friendly"}}""")
        enqueueJson(200, """{"runtime_group_id":"rg-1","config":{"lang":"fr-fr"},"personas":{}}""")
        val api = api(accessToken = "token")

        val current = api.getRuntimeGroupConfig("rg-1")
        val merged = api.updateRuntimeGroupConfig(
            "rg-1",
            ThalovantJson.parseToJsonElement("""{"lang":"en-us"}""").jsonObject,
            personas = ThalovantJson.parseToJsonElement("""{"default":"friendly"}""").jsonObject,
        )
        api.updateRuntimeGroupConfig("rg-1", ThalovantJson.parseToJsonElement("""{"lang":"fr-fr"}""").jsonObject)

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/v1/runtime-groups/rg-1/config", get.requestUrl?.encodedPath)

        val withPersonas = server.takeRequest()
        assertEquals("PATCH", withPersonas.method)
        assertEquals("/api/v1/runtime-groups/rg-1/config", withPersonas.requestUrl?.encodedPath)
        val withPersonasBody = bodyJson(withPersonas)
        assertEquals("en-us", withPersonasBody["config"]?.jsonObject?.get("lang")?.jsonPrimitive?.content)
        assertEquals("friendly", withPersonasBody["personas"]?.jsonObject?.get("default")?.jsonPrimitive?.content)

        // personas is replaced only when given, so it must stay off the wire.
        val withoutPersonas = bodyJson(server.takeRequest())
        assertEquals("fr-fr", withoutPersonas["config"]?.jsonObject?.get("lang")?.jsonPrimitive?.content)
        assertFalse("personas" in withoutPersonas)

        assertEquals("en-us", current["config"]?.jsonObject?.get("lang")?.jsonPrimitive?.content)
        assertEquals("friendly", merged["personas"]?.jsonObject?.get("default")?.jsonPrimitive?.content)
    }

    @Test
    fun `installs and uninstalls a runtime group skill`() = runBlocking {
        enqueueJson(200, """{"skill_id":"skill-weather","active":true}""")
        server.enqueue(MockResponse().setResponseCode(204))
        val api = api(accessToken = "token")

        val installed = api.installRuntimeGroupSkill("rg-1", "skill-weather")
        api.uninstallRuntimeGroupSkill("rg-1", "skill-weather")

        val install = server.takeRequest()
        assertEquals("POST", install.method)
        assertEquals("/api/v1/runtime-groups/rg-1/skills", install.requestUrl?.encodedPath)
        val body = bodyJson(install)
        assertEquals("skill-weather", body["skill_id"]?.jsonPrimitive?.content)
        assertEquals("catalog", body["source_type"]?.jsonPrimitive?.content)
        assertEquals(true, body["active"]?.jsonPrimitive?.boolean)
        assertEquals(3, body.size)

        val uninstall = server.takeRequest()
        assertEquals("DELETE", uninstall.method)
        assertEquals("/api/v1/runtime-groups/rg-1/skills/skill-weather", uninstall.requestUrl?.encodedPath)
        assertEquals("skill-weather", installed["skill_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `installs a git skill with every option`() = runBlocking {
        enqueueJson(200, """{"skill_id":"skill-lobby"}""")

        api(accessToken = "token").installRuntimeGroupSkill(
            "rg-1",
            "skill-lobby",
            InstallSkillOptions(
                marketplaceSkillId = "marketplace-1",
                sourceType = "git",
                sourceRef = "https://github.com/acme/skill-lobby",
                versionPin = "v1.2.0",
                active = false,
            ),
        )

        val body = bodyJson(server.takeRequest())
        assertEquals("skill-lobby", body["skill_id"]?.jsonPrimitive?.content)
        assertEquals("git", body["source_type"]?.jsonPrimitive?.content)
        assertEquals(false, body["active"]?.jsonPrimitive?.boolean)
        assertEquals("marketplace-1", body["marketplace_skill_id"]?.jsonPrimitive?.content)
        assertEquals("https://github.com/acme/skill-lobby", body["source_ref"]?.jsonPrimitive?.content)
        assertEquals("v1.2.0", body["version_pin"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uninstall percent-encodes the skill id path segment`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        api(accessToken = "token").uninstallRuntimeGroupSkill("rg-1", "acme/skill-lobby")

        // The slash must not split into an extra path segment.
        val request = server.takeRequest()
        assertEquals(
            "/api/v1/runtime-groups/rg-1/skills/acme%2Fskill-lobby",
            request.requestUrl?.encodedPath,
        )
        assertEquals("acme/skill-lobby", request.requestUrl?.pathSegments?.last())
    }

    @Test
    fun `lists marketplace skills and omits false and blank params`() = runBlocking {
        enqueueJson(200, """{"data":[{"skill_id":"skill-weather","access_tier":"free"}]}""")
        enqueueJson(200, """{"data":[]}""")
        val api = api(accessToken = "token")

        val catalog = api.listMarketplaceSkills()
        api.listMarketplaceSkills(
            MarketplaceSkillListOptions(ownerId = "owner-1", includeInactive = true, forceRefresh = true),
        )

        val bare = server.takeRequest()
        assertEquals("/api/v1/marketplace/skills", bare.requestUrl?.encodedPath)
        assertEquals(0, bare.requestUrl?.querySize)

        val filtered = server.takeRequest()
        assertEquals("owner-1", filtered.requestUrl?.queryParameter("owner_id"))
        assertEquals("true", filtered.requestUrl?.queryParameter("include_inactive"))
        assertEquals("true", filtered.requestUrl?.queryParameter("force_refresh"))
        assertEquals(
            "skill-weather",
            catalog["data"]?.jsonArray?.first()?.jsonObject?.get("skill_id")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `lists runtime group marketplace and inventory with distinct refresh params`() = runBlocking {
        enqueueJson(200, """{"runtime_group_id":"rg-1","source":"runtime-group-cache","data":[]}""")
        enqueueJson(200, """{"runtime_group_id":"rg-1","source":"ovos-runtime-operator","data":[]}""")
        enqueueJson(200, """{"runtime_group_id":"rg-1","source":"ovos-runtime-operator-pending","data":[]}""")
        enqueueJson(200, """{"runtime_group_id":"rg-1","source":"ovos-runtime-operator","data":[]}""")
        val api = api(accessToken = "token")

        api.listRuntimeGroupMarketplace("rg-1")
        api.listRuntimeGroupMarketplace("rg-1", refreshInventory = true)
        val pending = api.listRuntimeGroupInventory("rg-1")
        api.listRuntimeGroupInventory("rg-1", refresh = true)

        val marketplace = server.takeRequest()
        assertEquals("/api/v1/runtime-groups/rg-1/marketplace", marketplace.requestUrl?.encodedPath)
        assertEquals(0, marketplace.requestUrl?.querySize)
        assertEquals("true", server.takeRequest().requestUrl?.queryParameter("refresh_inventory"))

        val inventory = server.takeRequest()
        assertEquals("/api/v1/runtime-groups/rg-1/inventory", inventory.requestUrl?.encodedPath)
        assertEquals(0, inventory.requestUrl?.querySize)
        // The inventory route spells the flag `refresh`, not `refresh_inventory`.
        val refreshed = server.takeRequest()
        assertEquals("true", refreshed.requestUrl?.queryParameter("refresh"))
        assertNull(refreshed.requestUrl?.queryParameter("refresh_inventory"))

        // No connected runtime is an empty 200, not a 409.
        assertEquals("ovos-runtime-operator-pending", pending["source"]?.jsonPrimitive?.content)
        assertEquals(0, pending["data"]?.jsonArray?.size)
    }

    @Test
    fun `provisioning surfaces the paid-plan gate as HTTP 402`() = runBlocking {
        enqueueJson(402, """{"detail":"API access requires a paid plan."}""")
        enqueueJson(402, """{"detail":"API access requires a paid plan."}""")
        val api = api(accessToken = "token")

        val hubError = assertFailsWith<ThalovantApiException> {
            api.createHub(HubCreatePayload(name = "joke-garden", spec = JsonObject(emptyMap())))
        }
        assertEquals(402, hubError.statusCode)
        assertTrue("paid plan" in hubError.body.orEmpty())

        val groupError = assertFailsWith<ThalovantApiException> {
            api.createRuntimeGroup(RuntimeGroupCreatePayload(name = "kiosks"))
        }
        assertEquals(402, groupError.statusCode)
    }

    @Test
    fun `provisioning surfaces the scope gate as HTTP 403`() = runBlocking {
        enqueueJson(403, """{"detail":"Insufficient scopes"}""")
        enqueueJson(403, """{"detail":"Insufficient scopes"}""")
        val api = api(accessToken = "token")

        val installError = assertFailsWith<ThalovantApiException> {
            api.installRuntimeGroupSkill("rg-1", "skill-weather")
        }
        assertEquals(403, installError.statusCode)
        assertTrue("Insufficient scopes" in installError.body.orEmpty())

        val deleteError = assertFailsWith<ThalovantApiException> {
            api.deleteHub("hub-1", etag = "etag-2")
        }
        assertEquals(403, deleteError.statusCode)
    }

    @Test
    fun `sends the SDK user agent on every request`() = runBlocking {
        enqueueJson(200, """{"id":"hub-1","name":"hub"}""")
        api(accessToken = "token").getHub("hub-1")

        // Asserted against SDK_VERSION rather than a hardcoded literal: a
        // hardcoded expectation only fails when the user agent is bumped and
        // the test is not, so it cannot catch a release that bumps neither.
        assertEquals("ThalovantKotlinSDK/$SDK_VERSION", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `default user agent is derived from the SDK version`() {
        assertEquals("ThalovantKotlinSDK/$SDK_VERSION", DEFAULT_USER_AGENT)
    }
}

private val DEVICE_GRANT = """
    {
      "device_code": "device-code-1",
      "user_code": "WDJB-MJHT",
      "verification_uri": "https://dash.thalovant.com/activate",
      "verification_uri_complete": "https://dash.thalovant.com/activate?user_code=WDJB-MJHT",
      "expires_in": 900,
      "interval": 0
    }
""".trimIndent()

private val DEVICE_TOKEN = """
    {
      "access_token": "device-token",
      "token_type": "bearer",
      "scopes": ["hubs:read", "clients:write"],
      "expires_at": "2027-08-13T00:00:00Z",
      "token_id": "token-1"
    }
""".trimIndent()
