package com.thalovant.sdk

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decoding the frames a hub sends when it renders speech.
 *
 * `speak:synth` is not a message a client receives: a hub answers it by
 * rendering the utterance and sending a WIRE-1 BINARY frame back, so a client
 * with no synthesiser of its own can still speak. Files arrive the same way.
 *
 * The frames in `binary-frames.json` were produced by the reference encoder
 * (hivemind-bus-client's get_bitstring), not written by hand, so this is tested
 * against the wire a hub actually puts out rather than against a reading of the
 * specification. The expectations for what a decoded frame should then look
 * like are the shared `binary-vectors.json`.
 */
class BinaryFrameTest {

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private fun decode(base64: String) = HiveWire.decode(Base64.getDecoder().decode(base64))

    @Test
    fun `a rendered-speech frame from the reference encoder round-trips`() {
        for (case in fixture("binary-frames.json")["cases"]!!.jsonArray.map { it.jsonObject }) {
            val frame = decode(case["frame"]!!.jsonPrimitive.content)
            val binary = frame.binary!!
            assertEquals("bin", frame.msgType)
            assertEquals(case["expected_kind"]!!.jsonPrimitive.content, binary.kind)
            assertContentEquals(
                Base64.getDecoder().decode(case["expected_payload"]!!.jsonPrimitive.content),
                binary.data,
                binary.kind,
            )
            assertEquals(case["expected_metadata"]!!.jsonObject, binary.metadata)
        }
    }

    @Test
    fun `rendered speech carries what was said`() {
        val tts = fixture("binary-frames.json")["cases"]!!.jsonArray
            .map { it.jsonObject }.first { it["expected_kind"]!!.jsonPrimitive.content == "tts_audio" }
        val binary = decode(tts["frame"]!!.jsonPrimitive.content).binary!!
        assertEquals("Pfffft.", binary.utterance)
        assertEquals("fr-FR", binary.lang)
        assertEquals("prout.wav", binary.fileName)
    }

    @Test
    fun `metadata a hub did not send reads as absent`() {
        // An empty name is no name. Rendering it as "" would put a blank
        // filename in front of somebody as though the hub had sent one.
        val raw = fixture("binary-frames.json")["cases"]!!.jsonArray
            .map { it.jsonObject }.first { it["expected_kind"]!!.jsonPrimitive.content == "raw_audio" }
        val binary = decode(raw["frame"]!!.jsonPrimitive.content).binary!!
        assertNull(binary.utterance)
        assertNull(binary.lang)
        assertNull(binary.fileName)
    }

    @Test
    fun `a binarized bus frame is still text`() {
        // Only the BINARY type carries bytes; every other type binarized on the
        // wire is JSON and must keep decoding as it always did.
        val frame = decode(fixture("binary-frames.json")["bus_frame"]!!.jsonPrimitive.content)
        assertEquals("bus", frame.msgType)
        assertNull(frame.binary)
        assertEquals("speak", Json.parseToJsonElement(frame.text!!).jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the payload kinds are the ones the shared vectors name`() {
        val spec = fixture("binary-vectors.json")["payload_kinds"]!!.jsonObject
        for ((wire, name) in spec) {
            // Reachable only through a real frame, so assert via the map the
            // decoder uses: a wrong name would deliver speech as something else
            // and show up only as silence.
            assertEquals(name.jsonPrimitive.content, HiveWire.nameForPayloadType(wire.toInt()))
        }
    }

    @Test
    fun `an unnamed payload type still arrives`() {
        assertEquals("binary:9", HiveWire.nameForPayloadType(9))
    }

    @Test
    fun `two frames of the same bytes are equal`() {
        // ByteArray compares by identity, which inside a data class makes two
        // equal frames unequal.
        assertEquals(
            ThalovantBinary("file", byteArrayOf(1, 2), EMPTY_JSON_OBJECT),
            ThalovantBinary("file", byteArrayOf(1, 2), EMPTY_JSON_OBJECT),
        )
    }
}
