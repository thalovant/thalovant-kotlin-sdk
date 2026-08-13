package com.thalovant.sdk

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HiveMind runtime payload encryption: AES-128-GCM with a 16-byte nonce, keyed
 * by the first 16 bytes of the identity `crypto_key`. JSON envelopes carry
 * hex-encoded `ciphertext`, `tag`, and `nonce` fields (legacy base64 and
 * 12-byte-nonce payloads are also accepted on decrypt).
 */
public object HiveMindCrypto {
    private const val NONCE_SIZE = 16
    private const val TAG_SIZE = 16
    private val secureRandom = SecureRandom()

    /** Truncates a raw crypto key to the 16-byte HiveMind AES key. */
    public fun runtimeKey(raw: String?): ByteArray? {
        val normalized = raw?.trim()?.ifEmpty { null } ?: return null
        return normalized.take(NONCE_SIZE).toByteArray(Charsets.UTF_8)
    }

    public fun encryptAsJson(key: String, plaintext: String): String {
        val runtimeKey = runtimeKey(key) ?: throw ThalovantConnectionException("Missing crypto key.")
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(runtimeKey, "AES"),
            GCMParameterSpec(TAG_SIZE * 8, nonce),
        )
        val output = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = output.copyOfRange(0, output.size - TAG_SIZE)
        val tag = output.copyOfRange(output.size - TAG_SIZE, output.size)
        return buildJsonObject {
            put("ciphertext", ciphertext.toHex())
            put("tag", tag.toHex())
            put("nonce", nonce.toHex())
        }.toString()
    }

    public fun decryptFromJson(key: String, payload: JsonObject): String {
        val runtimeKey = runtimeKey(key) ?: throw ThalovantConnectionException("Missing crypto key.")
        val nonceText = optionalString(payload["nonce"]).orEmpty()
        val decode = decoderFor(nonceText)
        val nonce = decode(nonceText)
        val tag = decode(optionalString(payload["tag"]).orEmpty())
        val ciphertext = decode(optionalString(payload["ciphertext"]).orEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(runtimeKey, "AES"),
            GCMParameterSpec(TAG_SIZE * 8, nonce),
        )
        return cipher.doFinal(ciphertext + tag).toString(Charsets.UTF_8)
    }

    public fun decryptFromJson(key: String, payload: String): String {
        val parsed = ThalovantJson.parseToJsonElement(payload).asObjectOrNull()
            ?: throw ThalovantConnectionException("Encrypted payload must be a JSON object.")
        return decryptFromJson(key, parsed)
    }

    private fun decoderFor(nonceText: String): (String) -> ByteArray {
        val isHex = nonceText.matches(Regex("^[0-9a-fA-F]+$")) && nonceText.length % 2 == 0 &&
            (nonceText.length / 2 == NONCE_SIZE || nonceText.length / 2 == 12)
        return if (isHex) ::fromHex else { text -> Base64.getDecoder().decode(text) }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun fromHex(text: String): ByteArray =
    ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
