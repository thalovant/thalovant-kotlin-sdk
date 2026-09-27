package com.thalovant.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a control-plane error carries, against the shared vectors.
 *
 * `api-error-vectors.json` is the Python reference's, vendored unchanged and
 * pinned by the parity contract's `api-errors` capability. The API answers a
 * refusal with a Problem+JSON body whose structured fields say what to do next
 * -- the images a caller may pin instead, the plan's numbers -- and a message
 * cut at 200 characters is not where anybody can read them. Each case is served
 * by a real loopback HTTP peer and read back through [ThalovantControlPlane.getHub],
 * so what is recorded is what a caller gets.
 */
class ApiErrorVectorsTest {

    private fun vectors(name: String): JsonObject =
        ThalovantJson.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private val cases = vectors("api-error-vectors.json")["cases"]!!.jsonArray.map { it.jsonObject }

    /**
     * A loopback API that answers one request with [response], byte for byte,
     * and the error [call] (by default `getHub`) throws for it.
     */
    private fun served(
        response: MockResponse,
        accessToken: String = "synthetic-token",
        call: suspend (ThalovantControlPlane) -> Unit = { it.getHub("hub-1") },
    ): ThalovantApiException {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try {
            val api = ThalovantControlPlane(server.url("/").toString(), accessToken = accessToken)
            return runBlocking { assertFailsWith<ThalovantApiException> { call(api) } }
        } finally {
            server.shutdown()
        }
    }

    private fun refusal(response: JsonObject): ThalovantApiException = served(
        MockResponse()
            .setResponseCode(response["status"]!!.jsonPrimitive.int)
            .setHeader("Content-Type", response["content_type"]!!.jsonPrimitive.content)
            .setBody(response["body"]!!.jsonPrimitive.content),
    )

    @Test
    fun `an API error carries what its vector names`() {
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val error = refusal(case["response"]!!.jsonObject)
            val produced = buildJsonObject {
                put("status", error.statusCode)
                put("code", error.errorCode)
                put("detail", error.detail)
                put("problem", error.problem ?: JsonNull)
            }
            // Recorded before the assert: the record is what this SDK produced,
            // not a restatement of what the vector says it should have.
            ConformanceRecord.record("api-error-vectors.json", name, produced)
            assertEquals(case["expect"]!!.jsonObject, produced, name)
            // The body is still the text the API sent, whole.
            assertEquals(case["response"]!!.jsonObject["body"]!!.jsonPrimitive.content, error.body, name)
            for (echoed in case["message_excludes"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
                // Everything a log line can show of this error: its message,
                // its string form, and the stack trace printed with it.
                assertFalse(echoed in error.message.orEmpty(), name)
                assertFalse(echoed in error.localizedMessage.orEmpty(), name)
                assertFalse(echoed in error.toString(), name)
                assertFalse(echoed in error.stackTraceToString(), name)
            }
        }
    }

    @Test
    fun `the message may be shortened but the detail never is`() {
        val case = cases.first {
            it["expect"]!!.jsonObject["code"]?.jsonPrimitive?.contentOrNull == "platform_image_required"
        }
        val error = refusal(case["response"]!!.jsonObject)
        val detail = assertNotNull(error.detail)
        // The display line carries the API's sentence, cut at 200 characters
        // and without the code -- not the Problem+JSON title it once showed.
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("Thalovant API request failed with HTTP 403: Only an administrator"), message)
        assertEquals("Thalovant API request failed with HTTP 403: " + detail.take(200), message)
        // The sentence the API wrote is whole, and every list it sent is there.
        assertEquals(case["expect"]!!.jsonObject["detail"]!!.jsonPrimitive.content, detail)
        assertTrue(detail.length > 200)
        assertEquals("platform_image_required", error.errorCode)
        val problem = assertNotNull(error.problem)
        assertEquals(
            listOf("ghcr.io/thalovant/ovos-core:2026.09.2", "ghcr.io/thalovant/ovos-core:2026.09.3-alpha.1"),
            problem["allowed_images"]!!.jsonObject["core"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "ghcr.io/thalovant/ovos-core",
            problem["allowed_repositories"]!!.jsonObject["core"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "docker.io/example/ovos-messagebus:custom",
            problem["refused_images"]!!.jsonObject["bus"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the message never repeats a credential this call sent, and the error keeps what the API said`() {
        val token = "synthetic-token-7c1d"
        val secret = "sk-GENERATED-SECRET-1"
        fun refused(body: String) = served(
            MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(body),
            accessToken = token,
            // "kiosks" is shorter than any credential, so it may be repeated.
            call = { it.createRuntimeGroup(RuntimeGroupCreatePayload(name = "kiosks", description = secret)) },
        )

        val sentence = "Group kiosks was not created: $secret was refused for $token."
        val said = refused(ThalovantJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("detail", sentence) }))
        assertEquals(
            "Thalovant API request failed with HTTP 409: Group kiosks was not created: [redacted] was refused for [redacted].",
            said.message,
        )
        // Only the message is redacted: the error keeps exactly what was sent.
        assertEquals(sentence, said.detail)
        assertEquals(sentence, said.problem!!["detail"]!!.jsonPrimitive.content)
        assertTrue(secret in said.body.orEmpty())

        // The fallback fields are redacted the same way.
        val titled = refused("""{"title":"Refused $secret"}""")
        assertEquals("Thalovant API request failed with HTTP 409: Refused [redacted]", titled.message)

        // Redacted before the cut, so a credential across the 200th character
        // cannot leave its first half behind.
        val long = refused("""{"detail":"${"x".repeat(195)}$secret"}""")
        assertFalse(secret.take(5) in long.message.orEmpty(), long.message)
        assertTrue(long.message.orEmpty().endsWith("x".repeat(195) + "[reda"), long.message)
    }

    @Test
    fun `an error built the old way still reads the old way`() {
        val local = ThalovantApiException("Missing Thalovant API access token.")
        assertEquals("Missing Thalovant API access token.", local.message)
        assertNull(local.statusCode)
        assertNull(local.body)
        assertNull(local.problem)
        assertNull(local.errorCode)
        assertNull(local.detail)

        val conflict = ThalovantApiException("conflict", statusCode = 412)
        assertEquals(412, conflict.statusCode)
        assertNull(conflict.problem)
        assertNull(conflict.errorCode)
        assertNull(conflict.detail)
    }

    @Test
    fun `a body passed to the constructor gives the same fields the HTTP path does`() {
        val error = ThalovantApiException(
            "refused",
            statusCode = 403,
            body = """{"detail":"Free plan allows up to 1 connection.","code":"plan_limit","limit":1}""",
        )
        assertEquals("refused", error.message)
        assertEquals("plan_limit", error.errorCode)
        assertEquals("Free plan allows up to 1 connection.", error.detail)
        assertEquals(1, assertNotNull(error.problem)["limit"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an error body is read as UTF-8 whatever the Content-Type claims`() {
        // JSON is UTF-8; a declared charset that says otherwise must not
        // re-spell the API's sentence.
        val declared = served(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json; charset=iso-8859-1")
                .setBody("""{"detail":"skill-météo est déjà installée.","code":"skill_version_already_installed"}"""),
        )
        assertEquals("skill-météo est déjà installée.", declared.detail)
        // A byte order mark is dropped, as it always was.
        val marked = served(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("﻿{\"detail\":\"Hub not found\"}"),
        )
        assertEquals("{\"detail\":\"Hub not found\"}", marked.body)
        assertEquals("Hub not found", marked.detail)
    }

    @Test
    fun `the vectors cover every shape the rules name`() {
        // A vendored copy that quietly lost its non-JSON or its nested case would still pass.
        val expects = cases.map { it["expect"]!!.jsonObject }
        fun text(expect: JsonObject, key: String): String? = expect[key]?.jsonPrimitive?.contentOrNull
        assertTrue(expects.any { it["problem"] is JsonNull })
        assertTrue(expects.any { text(it, "code") != null && text(it, "detail") == null })
        assertTrue(expects.any { text(it, "detail") != null && text(it, "code") == null })
        assertTrue(expects.any { (it["problem"] as? JsonObject)?.get("detail") is JsonObject })
        assertTrue(expects.any { (it["problem"] as? JsonObject)?.get("detail") is JsonArray })
        assertTrue(expects.any { (text(it, "detail")?.length ?: 0) > 256 }, "no detail longer than any SDK's message limit")
        assertTrue(expects.any { text(it, "detail")?.contains('\n') == true })
        assertTrue(cases.any { it["message_excludes"] != null })
    }
}
