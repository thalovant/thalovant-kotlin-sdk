package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Carrying a conversation between the turns of a named session.
 *
 * A hub keeps nothing for one: OVOS-SESSION-2 §2.2 makes the orchestrator
 * stateless, so the carrier a client sends is the whole snapshot and whatever
 * the last turn activated is discarded the moment it ends. Without
 * `converse_handlers` the converse pipeline has no skill to poll and every
 * follow-up -- "encore un", "another one" -- reaches the fallback instead of
 * the skill that just answered.
 *
 * The cases are `contracts/conformance/conversation-vectors.json`, shared with
 * every other SDK so that "on par" is something a machine checks.
 */
class ConversationCarryTest {

    private fun vectors(name: String): JsonObject =
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    @Test
    fun `the carry matches the shared vectors`() {
        val spec = vectors("conversation-vectors.json")
        for (case in spec["cases"]!!.jsonArray.map { it.jsonObject }) {
            val carried = carryConversation(case["previous"]!!.jsonObject, case["session"]!!.jsonObject)
            assertEquals(case["expected"]!!.jsonObject, carried, case["name"].toString())
        }
    }

    @Test
    fun `the carried fields are the ones the vectors name`() {
        val spec = vectors("conversation-vectors.json")
        assertEquals(
            spec["carried_fields"]!!.jsonArray.map { it.jsonPrimitiveContent() }.sorted(),
            CONVERSATION_SESSION_FIELDS.sorted(),
        )
    }

    @Test
    fun `the fields that must never travel do not`() {
        // A remembered `lang` would pin a bilingual conversation to whichever
        // language it opened in, which is the failure this list prevents.
        val spec = vectors("conversation-vectors.json")
        for (field in spec["never_carried"]!!.jsonArray.map { it.jsonPrimitiveContent() }) {
            assertFalse(field in CONVERSATION_SESSION_FIELDS, field)
        }
    }

    @Test
    fun `the hive kinds are the ones the vectors name`() {
        val spec = vectors("mesh-vectors.json")
        assertEquals(
            spec["kinds"]!!.jsonArray.map { it.jsonPrimitiveContent() }.sorted(),
            ThalovantHive.KINDS.sorted(),
        )
    }

    @Test
    fun `the client's own traffic is not a hive kind`() {
        // `query` and `cascade` belong to ask(); subscribing to one here would
        // quietly compete for the same replies.
        val spec = vectors("mesh-vectors.json")
        for (refused in spec["refused_kinds"]!!.jsonArray.map { it.jsonPrimitiveContent() }) {
            assertTrue(refused !in ThalovantHive.KINDS, refused)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
