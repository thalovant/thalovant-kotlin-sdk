package com.thalovant.sdk
import kotlin.test.*
import kotlinx.serialization.json.*
class ReplyClaimsTest {
    @Test fun sharedReplyClaimVectors() {
        val raw = javaClass.getResourceAsStream("/thalovant/reply-claim-vectors.json")!!.bufferedReader().use { it.readText() }
        for (element in Json.parseToJsonElement(raw).jsonObject["cases"]!!.jsonArray) {
            val row = element.jsonObject
            val handled = row["handled"]!!.jsonPrimitive.boolean
            val failed = row["failed"]!!.jsonPrimitive.boolean
            val reply = ThalovantReply("reply", emptyList(), handled, handled && !failed, null, null,
                row["contexts"]!!.jsonArray.map { ThalovantEvent("speak", buildJsonObject {}, it.jsonObject) },
                if (failed) ThalovantEvent("failure", buildJsonObject {}, buildJsonObject {}) else null)
            val expected = row["expected"]!!.jsonObject
            assertEquals(expected["pipeline_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, reply.pipelineIds, row["name"].toString())
            assertEquals(expected["skill_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, reply.skillIds, row["name"].toString())
            assertEquals(expected["claimed"]!!.jsonPrimitive.boolean, reply.claimed, row["name"].toString())
        }
    }
}
