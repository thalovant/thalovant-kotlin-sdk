package com.thalovant.sdk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.math.ec.rfc7748.X25519

/** Noise revision 34 sequencing; cryptographic primitives are Bouncy Castle's. */
internal object Noise {
    val suites = listOf("25519_ChaChaPoly_SHA256", "25519_AESGCM_SHA256")
    val empty = byteArrayOf()
    fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun publicKey(privateKey: ByteArray): ByteArray = ByteArray(32).also {
        require(privateKey.size == 32)
        X25519.scalarMultBase(privateKey, 0, it, 0)
    }
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32).also {
        require(privateKey.size == 32 && publicKey.size == 32)
        check(X25519.calculateAgreement(privateKey, 0, publicKey, 0, it, 0)) { "Invalid Noise X25519 peer key." }
    }
    fun derivePsk(password: String, nodeId: String): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withIterations(3)
            .withMemoryAsKB(65536).withParallelism(1).withSalt(hash(nodeId.toByteArray())).build()
        val passwordBytes = password.toByteArray()
        return try {
            ByteArray(32).also { Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, it) }
        } finally { passwordBytes.fill(0); parameters.clear() }
    }
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it)) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    fun prologue(hello: JsonObject, offer: JsonObject, protocol: String): ByteArray =
        (canonical(hello) + canonical(offer) + protocol).toByteArray()
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" }) { "Invalid Noise hexadecimal value." }
        return ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    fun hkdf(key: ByteArray, input: ByteArray, count: Int = 2): List<ByteArray> {
        val temp = hmac(key, input)
        var previous = empty
        return (1..count).map { previous = hmac(temp, previous + it.toByte()); previous }
    }
    fun aead(suite: String, key: ByteArray, counter: Long, ad: ByteArray, input: ByteArray, encrypt: Boolean): ByteArray {
        require(suite in suites)
        val nonce = ByteBuffer.allocate(12).order(if (suite == suites[0]) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .putInt(0).putLong(counter).array()
        val cipher = if (suite == suites[0]) ChaCha20Poly1305() else GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, ad))
        val result = ByteArray(cipher.getOutputSize(input.size))
        val length = cipher.processBytes(input, 0, input.size, result, 0)
        val total = length + cipher.doFinal(result, length)
        return result.copyOf(total)
    }
}

internal class NoiseCipher(private val suite: String, private val key: ByteArray? = null) {
    private var counter = 0L
    val hasKey: Boolean get() = key != null
    fun crypt(input: ByteArray, ad: ByteArray = Noise.empty, encrypt: Boolean = true): ByteArray {
        if (key == null) return input
        check(counter != -1L) { "Noise nonce exhausted." }
        val output = Noise.aead(suite, key, counter, ad, input, encrypt)
        counter++
        return output
    }
}

internal class NoiseSymmetric(private val suite: String, name: String) {
    var hash = name.toByteArray().let { if (it.size <= 32) it.copyOf(32) else Noise.hash(it) }
    private var chainingKey = hash.copyOf()
    var cipher = NoiseCipher(suite)
    fun mixHash(data: ByteArray) { hash = Noise.hash(hash + data) }
    fun mixKey(data: ByteArray) { val keys = Noise.hkdf(chainingKey, data); chainingKey = keys[0]; cipher = NoiseCipher(suite, keys[1]) }
    fun mixKeyAndHash(data: ByteArray) {
        val keys = Noise.hkdf(chainingKey, data, 3); chainingKey = keys[0]; mixHash(keys[1]); cipher = NoiseCipher(suite, keys[2])
    }
    fun crypt(data: ByteArray, encrypt: Boolean): ByteArray {
        val result = cipher.crypt(data, hash, encrypt); mixHash(if (encrypt) result else data); return result
    }
    fun split(): Pair<NoiseCipher, NoiseCipher> = Noise.hkdf(chainingKey, Noise.empty).let { NoiseCipher(suite, it[0]) to NoiseCipher(suite, it[1]) }
}

internal class NoiseHandshake(
    val pattern: String,
    suite: String,
    private val psk: ByteArray,
    prologue: ByteArray,
    private val staticKey: ByteArray,
    pinnedRemote: ByteArray? = null,
    private val initiator: Boolean = true,
    private val ephemeralGenerator: () -> ByteArray = Noise::randomKey,
) {
    private val messages = when (pattern) {
        "XXpsk2" -> listOf(listOf("e"), listOf("e", "ee", "s", "es", "psk"), listOf("s", "se"))
        "KKpsk0" -> listOf(listOf("psk", "e", "es", "ss"), listOf("e", "ee", "se"))
        else -> error("Unsupported Noise pattern.")
    }
    private val symmetric = NoiseSymmetric(suite, "Noise_${pattern}_$suite")
    private var ephemeral: ByteArray? = null
    private var remoteEphemeral: ByteArray? = null
    var remoteStatic: ByteArray? = pinnedRemote?.copyOf()
        private set
    private var index = 0
    val finished: Boolean get() = index == messages.size
    init {
        require(psk.size == 32 && staticKey.size == 32 && (pinnedRemote == null || pinnedRemote.size == 32))
        symmetric.mixHash(prologue)
        if (pattern == "KKpsk0") {
            require(pinnedRemote != null) { "KKpsk0 requires a persisted server pin." }
            listOf(if (initiator) Noise.publicKey(staticKey) else pinnedRemote,
                if (initiator) pinnedRemote else Noise.publicKey(staticKey)).forEach(symmetric::mixHash)
        }
    }
    fun write(payload: ByteArray = Noise.empty): ByteArray {
        check(!finished && (index % 2 == 0) == initiator) { "Unexpected Noise write." }
        var output = Noise.empty
        for (token in messages[index]) when (token) {
            "e" -> { ephemeral = ephemeralGenerator(); val key = Noise.publicKey(ephemeral!!); output += key; symmetric.mixHash(key); symmetric.mixKey(key) }
            "s" -> output += symmetric.crypt(Noise.publicKey(staticKey), true)
            "psk" -> symmetric.mixKeyAndHash(psk)
            else -> symmetric.mixKey(dh(token))
        }
        output += symmetric.crypt(payload, true); index++; return output
    }
    fun read(message: ByteArray): ByteArray {
        check(!finished && (index % 2 == 0) != initiator) { "Unexpected Noise read." }
        require(message.size <= 65535) { "Oversized Noise handshake." }
        var offset = 0
        fun take(count: Int): ByteArray {
            require(offset + count <= message.size) { "Truncated Noise handshake." }
            return message.copyOfRange(offset, offset + count).also { offset += count }
        }
        for (token in messages[index]) when (token) {
            "e" -> { val key = take(32); remoteEphemeral = key; symmetric.mixHash(key); symmetric.mixKey(key) }
            "s" -> {
                val key = symmetric.crypt(take(if (symmetric.cipher.hasKey) 48 else 32), false)
                check(remoteStatic == null || MessageDigest.isEqual(remoteStatic, key)) { "Noise server static key contradicts its persisted pin." }
                remoteStatic = key
            }
            "psk" -> symmetric.mixKeyAndHash(psk)
            else -> symmetric.mixKey(dh(token))
        }
        return symmetric.crypt(take(message.size - offset), false).also { index++ }
    }
    private fun dh(token: String): ByteArray = when (token) {
        "ee" -> Noise.dh(ephemeral!!, remoteEphemeral!!)
        "es" -> if (initiator) Noise.dh(ephemeral!!, remoteStatic!!) else Noise.dh(staticKey, remoteEphemeral!!)
        "se" -> if (initiator) Noise.dh(staticKey, remoteEphemeral!!) else Noise.dh(ephemeral!!, remoteStatic!!)
        "ss" -> Noise.dh(staticKey, remoteStatic!!)
        else -> error("Unknown Noise DH token.")
    }
    fun session(): NoiseSession {
        check(finished)
        val (first, second) = symmetric.split()
        return if (initiator) NoiseSession(first, second) else NoiseSession(second, first)
    }
}

internal class NoiseSession(private val send: NoiseCipher, private val receive: NoiseCipher) {
    private var pending: ByteArrayOutputStream? = null
    private var pendingJson = true
    @Synchronized fun encrypt(data: ByteArray, json: Boolean = true): List<ByteArray> {
        require(data.size <= MAX_MESSAGE) { "Noise message exceeds 32 MiB." }
        if (data.size <= CHUNK) return listOf(send.crypt(byteArrayOf(if (json) 0 else 1) + data))
        return (data.indices step CHUNK).map { offset ->
            val marker = if (offset == 0) { if (json) 2 else 3 } else if (offset + CHUNK >= data.size) 5 else 4
            send.crypt(byteArrayOf(marker.toByte()) + data.copyOfRange(offset, minOf(data.size, offset + CHUNK)))
        }
    }
    @Synchronized fun decrypt(frame: ByteArray): Pair<ByteArray, Boolean>? {
        require(frame.size in 17..65535) { "Invalid Noise frame length." }
        val plain = receive.crypt(frame, encrypt = false)
        require(plain.isNotEmpty()) { "Empty Noise frame." }
        val marker = plain[0].toInt()
        when (marker) {
            0, 1 -> { check(pending == null) { "Interrupted Noise chunk sequence." }; return plain.copyOfRange(1, plain.size) to (marker == 0) }
            2, 3 -> { check(pending == null) { "Overlapping Noise chunk sequences." }; pending = ByteArrayOutputStream(); pendingJson = marker == 2 }
            4, 5 -> check(pending != null) { "Unexpected Noise continuation." }
            else -> error("Unknown Noise frame marker.")
        }
        val buffer = pending!!
        check(buffer.size() + plain.size - 1 <= MAX_MESSAGE) { "Noise reassembly exceeds 32 MiB." }
        buffer.write(plain, 1, plain.size - 1)
        return if (marker == 5) { pending = null; buffer.toByteArray() to pendingJson } else null
    }
    companion object { const val CHUNK = 65000; const val MAX_MESSAGE = 32 * 1024 * 1024 }
}
