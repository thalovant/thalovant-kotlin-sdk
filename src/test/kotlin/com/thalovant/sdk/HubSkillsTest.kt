package com.thalovant.sdk

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HubSkillsTest {
    private fun reply(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val accepted = """{"operation_id":"op-1","state":"installing","skill":"s/1"}"""

    @Test fun `routes preserve shared runtime data and resume without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = ThalovantControlPlane(server.url("/").toString(), accessToken = "token")
            server.enqueue(reply("""{"data":[]}"""))
            server.enqueue(reply("""{"data":[{"kind":"event","actor_email":null}]}"""))
            server.enqueue(reply(accepted, 202)); server.enqueue(reply("""{"status":"ready"}"""))
            server.enqueue(reply(accepted, 202)); server.enqueue(reply(accepted, 202))
            api.listHubSkills("h/1")
            val history = api.listHubSkillHistory("h/1", 200)
            assertEquals(JsonNull, history["data"]!!.jsonArray[0].jsonObject["actor_email"])
            val result = api.installHubSkill("h/1", "s/1", "1.2.0")
            assertEquals("installed", api.waitForHubSkillOperation(result)["state"]!!.jsonPrimitive.content)
            assertEquals("installing", result["state"]!!.jsonPrimitive.content)
            api.updateHubSkill("h/1", "s/1", "1.2.0"); api.removeHubSkill("h/1", "s/1")
            val requests = (1..6).map { server.takeRequest() }
            assertEquals(listOf("GET", "GET", "POST", "GET", "PATCH", "DELETE"), requests.map { it.method })
            assertEquals("/v1/hubs/h%2F1/skills", requests[0].path)
            assertEquals("/v1/hubs/h%2F1/skills/history?limit=200", requests[1].path)
            assertEquals("/v1/hubs/h%2F1/skills/s%2F1", requests[4].path)
            assertEquals("1.2.0", Json.parseToJsonElement(requests[2].body.readUtf8()).jsonObject["version"]!!.jsonPrimitive.content)
        }
    }
    @Test fun `polling failures preserve ID and never repeat write`() = runBlocking {
        for (status in listOf("failed", "timed_out", "applied", "http-error")) {
            MockWebServer().use { server ->
                server.start(); val api = ThalovantControlPlane(server.url("/").toString(), accessToken = "token")
                server.enqueue(reply(accepted, 202))
                server.enqueue(if (status == "http-error") reply("""{"detail":"private-data"}""", 503) else reply("""{"status":"$status"}"""))
                val error = try { api.installHubSkill("h", "s", options = HubSkillWaitOptions(true, 100, 1000)); fail("expected error") } catch (e: ThalovantException) { e }
                assertTrue(error.message!!.contains("op-1")); assertFalse(error.message!!.contains("private-data"))
                assertEquals(2, server.requestCount)
            }
        }
    }
    @Test fun `invalid limits and wait options do not send`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = ThalovantControlPlane(server.url("/").toString(), accessToken = "token")
            assertFailsWith<IllegalArgumentException> { api.listHubSkillHistory("h", 0) }
            assertFailsWith<IllegalArgumentException> { api.listHubSkillHistory("h", 201) }
            assertFailsWith<IllegalArgumentException> { api.installHubSkill("h", "s", options = HubSkillWaitOptions(timeoutMs = 0)) }
            assertEquals(0, server.requestCount)
        }
    }
}
