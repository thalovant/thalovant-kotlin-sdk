package com.thalovant.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What an ask does when the hub refuses it, against the shared vectors.
 *
 * `refusal-vectors.json` is the Python reference's, vendored unchanged and
 * pinned by the parity contract: a refusal becomes a typed error carrying the
 * hub's code and, for a spent quota, its numbers; an unmatched intent is an
 * unanswered question; and a denial with no request id is taken only by the
 * ask that can be the one it refused.
 */
class RefusalVectorsTest {

    private fun vectors(name: String): JsonObject =
        ThalovantJson.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private val cases = vectors("refusal-vectors.json")

    private fun eventOf(case: JsonObject): ThalovantEvent {
        val wire = case["event"]!!.jsonObject
        return ThalovantEvent(
            wire["type"]!!.jsonPrimitive.content,
            wire["data"]?.jsonObject ?: JsonObject(emptyMap()),
            wire["context"]?.jsonObject ?: JsonObject(emptyMap()),
        )
    }

    @Test
    fun `every failure event becomes the error its vector names`() {
        for (case in cases["classification"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["name"]!!.jsonPrimitive.content
            val expect = case["expect"]!!.jsonObject
            val error = failureError(eventOf(case))
            if (expect["kind"]!!.jsonPrimitive.content == "unanswered") {
                assertIs<ThalovantUnansweredException>(error, name)
                continue
            }
            val refused = assertIs<ThalovantPolicyDeniedException>(error, name)
            val quota = refused.quota
            val produced: JsonElement = buildJsonObject {
                put("kind", "refused")
                put("denied_type", refused.deniedType)
                put("code", refused.code)
                put("reason", refused.reason)
                put("allowed", buildJsonArray { refused.allowed.forEach { add(JsonPrimitive(it)) } })
                put("quota", if (quota == null) JsonNull else buildJsonObject {
                    put("period", quota.period)
                    put("limit", quota.limit)
                    put("used", quota.used)
                    put("reset_after", quota.resetAfterSeconds)
                })
            }
            assertEquals(expect, produced, name)
        }
    }

    @Test
    fun `a denial is taken only by the ask it can belong to`() {
        for (case in cases["correlation"]!!.jsonArray.map { it.jsonObject }) {
            val requestId = when (val id = case["request_id"]) {
                null, JsonNull -> null
                else -> if (id.jsonPrimitive.content == "own") "req-own" else "req-other"
            }
            val taken = refusalBelongsToAsk(
                requestId = requestId,
                ownRequestId = "req-own",
                deniedType = case["denied_type"]!!.jsonPrimitive.content,
                asksInFlight = case["asks_in_flight"]!!.jsonPrimitive.int,
                queriesInFlight = case["queries_in_flight"]!!.jsonPrimitive.int,
            )
            assertEquals(case["taken"]!!.jsonPrimitive.boolean, taken, case["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the vectors cover every kind of refusal`() {
        // A copy that quietly lost its quota or its unanswered case would still pass.
        val classification = cases["classification"]!!.jsonArray.map { it.jsonObject["expect"]!!.jsonObject }
        val codes = classification.mapNotNull { (it["code"] as? JsonPrimitive)?.content }.toSet()
        assertTrue(codes.containsAll(setOf("acl_disallowed_type", "intent_quota_exceeded", "backend_unavailable")))
        assertEquals(setOf("refused", "unanswered"), classification.map { it["kind"]!!.jsonPrimitive.content }.toSet())
        assertEquals(setOf(true, false),
            (cases["correlation"] as JsonArray).map { it.jsonObject["taken"]!!.jsonPrimitive.boolean }.toSet())
    }
}
