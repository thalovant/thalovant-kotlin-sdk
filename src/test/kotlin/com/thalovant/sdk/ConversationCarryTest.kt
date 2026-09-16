package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.runBlocking

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

/** Captures what the three senders actually put on the wire. */
private class CapturingHiveTransport : HiveMindRuntimeTransport {
    val sent = mutableListOf<JsonObject>()
    override val connected: Boolean = true
    override val handshakeComplete: Boolean = true
    override suspend fun sendHiveFrame(message: JsonObject) { sent += message }
    override suspend fun connect(timeoutMs: Long) {}
    override suspend fun disconnect() {}
    override suspend fun emitBus(eventType: String, data: JsonObject, context: JsonObject) {}
    override fun addBusListener(listener: (ThalovantEvent) -> Unit): ThalovantSubscription =
        ThalovantSubscription {}
}

class MeshEnvelopeTest {
    @Test
    fun `every sender puts the same nested envelope on the wire`() = runBlocking {
        // Asserting ThalovantHive.KINDS proves only that the names exist. A
        // regression in the envelope sendHive builds -- the nested "bus" frame
        // a hub reads message.payload as, and rewrites the route on -- would
        // have passed that untouched.
        val transport = CapturingHiveTransport()
        val client = ThalovantClient(
            ThalovantIdentity(
                ThalovantJson.parseToJsonElement(
                    """{"access_key":"access","password":"password","site_id":"site","default_master":"wss://hub.example"}"""
                ).jsonObject,
            ),
            transport = transport,
        )

        client.propagate("thalovant.test", buildJsonObject { put("n", 1) })
        client.escalate("thalovant.test", buildJsonObject { put("n", 2) })
        client.broadcast("thalovant.test", buildJsonObject { put("n", 3) })

        assertEquals(listOf("propagate", "escalate", "broadcast"),
                     transport.sent.map { it["msg_type"]!!.jsonPrimitive.content })
        transport.sent.forEachIndexed { index, frame ->
            val inner = frame["payload"]!!.jsonObject
            // Nested on purpose: a flat frame loses the route a hub rewrites.
            assertEquals("bus", inner["msg_type"]!!.jsonPrimitive.content)
            val bus = inner["payload"]!!.jsonObject
            assertEquals("thalovant.test", bus["type"]!!.jsonPrimitive.content)
            assertEquals(index + 1, bus["data"]!!.jsonObject["n"]!!.jsonPrimitive.content.toInt())
            assertTrue("context" in bus, "the envelope carries no context")
        }
    }
}
