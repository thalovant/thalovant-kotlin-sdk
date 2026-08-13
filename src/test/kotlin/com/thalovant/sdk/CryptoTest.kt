package com.thalovant.sdk

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CryptoTest {
    @Test
    fun `runtime crypto key truncates to HiveMind key size`() {
        assertContentEquals(
            "0123456789abcdef".toByteArray(),
            HiveMindCrypto.runtimeKey("0123456789abcdef-extra"),
        )
    }

    @Test
    fun `encryptAsJson emits HiveMind compatible AES-GCM JSON-HEX payloads`() {
        val encrypted = HiveMindCrypto.encryptAsJson("0123456789abcdef-extra", "hello")
        val parsed = ThalovantJson.parseToJsonElement(encrypted).jsonObject

        assertTrue(parsed["ciphertext"]?.jsonPrimitive?.content.orEmpty().matches(Regex("^[0-9a-f]+$")))
        assertEquals(32, parsed["nonce"]?.jsonPrimitive?.content?.length)
        assertEquals(32, parsed["tag"]?.jsonPrimitive?.content?.length)
        assertEquals("hello", HiveMindCrypto.decryptFromJson("0123456789abcdef-extra", encrypted))
    }

    @Test
    fun `decryptFromJson accepts legacy 12-byte-nonce JSON-HEX payloads`() {
        val key = SecretKeySpec("0123456789abcdef".toByteArray(), "AES")
        val nonce = ByteArray(12) { 7 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val output = cipher.doFinal("hello".toByteArray())
        val ciphertext = output.copyOfRange(0, output.size - 16)
        val tag = output.copyOfRange(output.size - 16, output.size)

        val payload = buildJsonObject {
            put("ciphertext", ciphertext.toHexString())
            put("tag", tag.toHexString())
            put("nonce", nonce.toHexString())
        }

        assertEquals("hello", HiveMindCrypto.decryptFromJson("0123456789abcdef-extra", payload))
    }

    @Test
    fun `decryptFromJson accepts AES-GCM JSON-BASE64 payloads`() {
        val key = SecretKeySpec("0123456789abcdef".toByteArray(), "AES")
        val nonce = ByteArray(16) { 8 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val output = cipher.doFinal("hello".toByteArray())
        val ciphertext = output.copyOfRange(0, output.size - 16)
        val tag = output.copyOfRange(output.size - 16, output.size)
        val encoder = Base64.getEncoder()

        val payload = buildJsonObject {
            put("ciphertext", encoder.encodeToString(ciphertext))
            put("tag", encoder.encodeToString(tag))
            put("nonce", encoder.encodeToString(nonce))
        }

        assertEquals("hello", HiveMindCrypto.decryptFromJson("0123456789abcdef-extra", payload))
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
