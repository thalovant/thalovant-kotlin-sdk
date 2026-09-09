package com.thalovant.sdk

import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

/** The checked-in transcript comes from the independent Node implementation with noble primitives. */
class NoiseTest {
    private val fixture = ThalovantJson.parseToJsonElement(javaClass.getResource("/noise-node.json")!!.readText()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun key(name: String) = Noise.unhex(fixture.text(name))

    @Test fun `both patterns and suites reproduce independent handshake and sequential transport transcripts`() {
        assertEquals(fixture.text("public_i"), Noise.hex(Noise.publicKey(key("static_i"))))
        assertEquals(fixture.text("public_r"), Noise.hex(Noise.publicKey(key("static_r"))))
        for (item in fixture.getValue("exchanges").jsonArray) {
            val exchange = item.jsonObject
            val pattern = exchange.text("pattern")
            val suite = exchange.text("suite")
            val prologue = Noise.prologue(fixture.getValue("hello").jsonObject, fixture.getValue("offer").jsonObject, exchange.text("protocol"))
            assertEquals(exchange.text("prologue"), Noise.hex(prologue))
            val state = NoiseHandshake(pattern, suite, key("psk"), prologue, key("static_i"), key("public_r"), ephemeralGenerator = { key("ephemeral_i") })
            val messages = exchange.getValue("messages").jsonArray
            val payloads = exchange.getValue("payloads").jsonArray
            assertEquals(messages[0].jsonPrimitive.content, Noise.hex(state.write(payloads[0].jsonPrimitive.content.toByteArray())))
            assertEquals(payloads[1].jsonPrimitive.content, state.read(Noise.unhex(messages[1].jsonPrimitive.content)).toString(Charsets.UTF_8))
            if (pattern == "XXpsk2") assertEquals(messages[2].jsonPrimitive.content, Noise.hex(state.write()))
            assertTrue(state.finished)
            val session = state.session()
            val plain = exchange.getValue("plaintexts").jsonArray
            for (index in plain.indices) {
                val payload = plain[index].jsonPrimitive.content.toByteArray()
                assertEquals(exchange.getValue("transport_i").jsonArray[index].jsonPrimitive.content, Noise.hex(session.encrypt(payload).single()))
                val received = session.decrypt(Noise.unhex(exchange.getValue("transport_r").jsonArray[index].jsonPrimitive.content))!!
                assertTrue(received.second)
                assertContentEquals(payload, received.first)
            }
            assertFails { session.decrypt(Noise.unhex(exchange.getValue("transport_r").jsonArray[0].jsonPrimitive.content)) }
        }
    }
    @Test fun `canonical JSON uses Python Unicode codepoint order and lowercase control escapes`() {
        val value = buildJsonObject { put("\uE000", "café\u001f"); put("\uD800\uDC00", "\uD83D\uDE00") }
        assertEquals("{\"\uE000\":\"café\\u001f\",\"\uD800\uDC00\":\"\uD83D\uDE00\"}", Noise.canonical(value))
    }
    @Test fun `Argon2id matches upstream UTF8 and parameter vectors`() {
        assertEquals(fixture.text("psk"), Noise.hex(Noise.derivePsk(fixture.text("password"), fixture.text("node_id"))))
        assertEquals("988418601dbad183fbd6116e7981e9ab8ffe93be3f3f45c27eb0b70c325f9cd8", Noise.hex(Noise.derivePsk("passé-wörd", "hub-ümläut")))
    }
    @Test fun `AEAD matches independent counter 258 vectors`() {
        val bytes = ByteArray(32) { it.toByte() }
        val text = "noise-transport-vector".toByteArray()
        assertEquals("634779e501b1642347721a75d47243559573cbfac3370330645b209d163adf1c04f90a6a4bc2", Noise.hex(Noise.aead(Noise.suites[1], bytes, 258, Noise.empty, text, true)))
        assertEquals("73da914e74a79fe130e33c0c5adf0eab01c818746c83c7b909d0302385d6735a45edfcacd811", Noise.hex(Noise.aead(Noise.suites[0], bytes, 258, Noise.empty, text, true)))
    }
    @Test fun `authentication rejects altered transcript password and pin`() {
        val exchange = fixture.getValue("exchanges").jsonArray.first().jsonObject
        for (case in 0..2) {
            val state = NoiseHandshake("XXpsk2", Noise.suites[0], if (case == 0) ByteArray(32) else key("psk"),
                if (case == 1) "tampered".toByteArray() else Noise.unhex(exchange.text("prologue")), key("static_i"),
                if (case == 2) ByteArray(32) { 1 } else key("public_r"), ephemeralGenerator = { key("ephemeral_i") })
            state.write(exchange.getValue("payloads").jsonArray[0].jsonPrimitive.content.toByteArray())
            assertFails { state.read(Noise.unhex(exchange.getValue("messages").jsonArray[1].jsonPrimitive.content)) }
            assertFalse(state.finished)
        }
        assertFails { Noise.dh(key("static_i"), ByteArray(32)) }
    }
    @Test fun `chunk framing rejects interleaving and excessive outbound data`() {
        val key = ByteArray(32) { it.toByte() }
        val sender = NoiseSession(NoiseCipher(Noise.suites[1], key), NoiseCipher(Noise.suites[1], key))
        val receiver = NoiseSession(NoiseCipher(Noise.suites[1], key), NoiseCipher(Noise.suites[1], key))
        val payload = ByteArray(130100) { (it % 251).toByte() }
        val frames = sender.encrypt(payload)
        assertEquals(3, frames.size)
        assertNull(receiver.decrypt(frames[0])); assertNull(receiver.decrypt(frames[1]))
        assertContentEquals(payload, receiver.decrypt(frames[2])!!.first)
        assertFails { sender.encrypt(ByteArray(NoiseSession.MAX_MESSAGE + 1)) }
        val cipher = NoiseCipher(Noise.suites[0], key)
        val malformed = NoiseSession(NoiseCipher(Noise.suites[0], key), NoiseCipher(Noise.suites[0], key))
        assertFails { malformed.decrypt(cipher.crypt(byteArrayOf(4, 1))) }
    }
    @Test fun `persistent key and pins survive new store instances and reject rotation`() {
        val dir = Files.createTempDirectory("kotlin-noise-store")
        try {
            val first = HiveMindNoiseStore(dir)
            val second = HiveMindNoiseStore(dir)
            assertContentEquals(first.staticKey(), second.staticKey())
            first.verifyOrPin("hub", key("public_r"))
            assertContentEquals(key("public_r"), second.pin("hub"))
            assertFails { second.verifyOrPin("hub", key("public_i")) }
            assertContentEquals(key("public_r"), first.pin("hub"))
            Files.writeString(dir.resolve("noise-static.key"), "bad")
            assertFails { first.staticKey() }
        } finally { dir.toFile().deleteRecursively() }
    }
}
