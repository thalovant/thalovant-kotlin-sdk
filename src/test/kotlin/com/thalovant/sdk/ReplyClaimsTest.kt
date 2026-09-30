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
            val contexts = row["contexts"]!!.jsonArray
            val metas = row["metas"]?.jsonArray
            val names = row["names"]?.jsonArray
            val events = contexts.mapIndexed { index, context ->
                val meta = metas?.getOrNull(index)?.takeUnless { it is JsonNull }?.jsonObject
                val data = if (meta != null) buildJsonObject { put("meta", meta) } else buildJsonObject {}
                val name = names?.getOrNull(index)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: "speak"
                ThalovantEvent(name, data, context.jsonObject)
            }
            val reply = ThalovantReply("reply", emptyList(), handled, handled && !failed, null, null,
                events,
                if (failed) ThalovantEvent("failure", buildJsonObject {}, buildJsonObject {}) else null)
            val expected = row["expected"]!!.jsonObject
            assertEquals(expected["pipeline_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, reply.pipelineIds, row["name"].toString())
            assertEquals(expected["skill_ids"]!!.jsonArray.map { it.jsonPrimitive.content }, reply.skillIds, row["name"].toString())
            assertEquals(expected["claimed"]!!.jsonPrimitive.boolean, reply.claimed, row["name"].toString())
        }
    }

    private fun event(pipelineId: String?, skillId: String?, meta: JsonObject? = null): ThalovantEvent {
        val context = buildJsonObject {
            if (pipelineId != null) put("pipeline_id", pipelineId)
            if (skillId != null) put("skill_id", skillId)
        }
        val data = buildJsonObject {
            if (meta != null) put("meta", meta)
        }
        return ThalovantEvent("speak", data, context)
    }

    private fun reply(handled: Boolean, events: List<ThalovantEvent>, failureEvent: ThalovantEvent? = null) =
        ThalovantReply("text", emptyList(), handled, handled && failureEvent == null, null, null, events, failureEvent)

    @Test fun aSkillCanAssertAGenuineClaimFromTheFallbackTier() {
        val r = reply(true, listOf(event(
            "ovos-fallback-pipeline-plugin", "thalovant-skill-home.thalovant",
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, true) },
        )))
        assertTrue(r.claimed)
    }

    @Test fun theFleetsGenericCatchAllIsStillNotAClaim() {
        // Regression: a real fallback-tier skill_id with no assertion stays unclaimed.
        val r = reply(true, listOf(event(
            "ovos-fallback-pipeline-plugin", "thalovant-skill-custos-fallback.thalovant",
        )))
        assertFalse(r.claimed)
    }

    @Test fun theAssertionDoesNotAffectANonFallbackReply() {
        val r = reply(true, listOf(event(
            "ovos-padatious-pipeline-plugin", "thalovant-skill-weather.thalovant",
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, true) },
        )))
        assertTrue(r.claimed)
    }

    @Test fun theAssertionCannotRescueAFailedReply() {
        val r = reply(true, listOf(event(
            "ovos-fallback-pipeline-plugin", "thalovant-skill-home.thalovant",
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, true) },
        )), failureEvent = ThalovantEvent("failure", buildJsonObject {}, buildJsonObject {}))
        assertFalse(r.claimed)
    }

    @Test fun assertionOnANonSpeakEventIsIgnored() {
        // Regression: a correlated event this reply happens to carry (e.g. the
        // hub's own ovos.utterance.handled) carrying the same meta shape must
        // not assert a claim -- only the skill's own speak event may.
        val r = reply(true, listOf(ThalovantEvent(
            ThalovantEvents.UTTERANCE_HANDLED,
            buildJsonObject { put("meta", buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, true) }) },
            buildJsonObject {
                put("pipeline_id", "ovos-fallback-pipeline-plugin")
                put("skill_id", "thalovant-skill-custos-fallback.thalovant")
            },
        )))
        assertFalse(r.claimed)
    }

    @Test fun onlyALiteralTrueAssertsTheClaim() {
        val badMetas = listOf(
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, false) },
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, "true") },
            buildJsonObject { put(THALOVANT_CLAIMED_META_KEY, 1) },
            buildJsonObject { put("unrelated", "value") },
            buildJsonObject {},
        )
        for (meta in badMetas) {
            val r = reply(true, listOf(event(
                "ovos-fallback-pipeline-plugin", "thalovant-skill-custos-fallback.thalovant", meta,
            )))
            assertFalse(r.claimed, "meta=$meta must not assert a claim")
        }
    }
}
