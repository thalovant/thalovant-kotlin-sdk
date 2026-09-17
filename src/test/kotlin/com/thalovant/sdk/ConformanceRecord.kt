package com.thalovant.sdk

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Record what this SDK produced for each conformance case.
 *
 * The parity gate can check that a test *names* a vector file. It cannot check
 * that the test ran it: a name reaching a loader call is evidence of intent,
 * not of execution. So the gate stopped asking about the test and started
 * asking about its output -- this writes what we computed, and the checker
 * compares it against what the Python reference computed for the same case.
 *
 * The digest has to agree across languages, so it is deliberately the same
 * recipe as the reference's `tests/conformance_record.py`: JSON with keys
 * sorted at every depth, no insignificant whitespace, non-ASCII left as
 * itself, SHA-256 of the UTF-8 bytes, and a whole number spelled without a
 * fractional part.
 *
 * Set `THALOVANT_CONFORMANCE_OUT` to a path and run the suite; the results are
 * written when the test JVM exits.
 */
internal object ConformanceRecord {

    private val results = linkedMapOf<String, MutableMap<String, String>>()
    private val target: String? = System.getenv("THALOVANT_CONFORMANCE_OUT")

    init {
        if (target != null) {
            Runtime.getRuntime().addShutdownHook(Thread { write() })
        }
    }

    /**
     * Canonical JSON, built by hand.
     *
     * `JsonObject` keeps insertion order and `JsonPrimitive` keeps the number
     * exactly as it was written, so neither the sorting nor the spelling of a
     * whole number can be left to the serialiser. `conversation-vectors.json`
     * has `activated_at: 1.0`, which every other SDK writes as `1`.
     */
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonObject ->
            value.keys.sorted().joinToString(",", "{", "}") { key ->
                Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)) +
                    ":" + canonical(value.getValue(key))
            }
        is JsonPrimitive ->
            if (value.isString) {
                Json.encodeToString(JsonPrimitive.serializer(), value)
            } else {
                wholeNumber(value.content)
            }
    }

    /**
     * Spell a whole number the way every other language spells it.
     *
     * Through `BigDecimal`, not `Double`: a double loses integer precision
     * above 2^53 and `toLong()` clamps rather than failing, so 
     * 9223372036854775808.0 would be recorded as 9223372036854775807 -- a
     * digest for a value nobody produced.
     *
     * Anything not whole is refused rather than passed through. Only a whole
     * number is written the same way by every language here; 1.5 and 1e-7
     * have per-language spellings. No vector contains one, and if one ever
     * does this should stop rather than lie.
     */
    private fun wholeNumber(content: String): String {
        if (content == "true" || content == "false") return content
        val decimal = content.toBigDecimalOrNull()
            ?: error("conformance: cannot canonicalise $content: not a number")
        return try {
            decimal.toBigIntegerExact().toString()
        } catch (_: ArithmeticException) {
            error(
                "conformance: cannot canonicalise $content: only whole numbers are " +
                    "spelled the same way in every language",
            )
        }
    }

    private fun sha256(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }

    fun canonicalDigest(value: JsonElement): String = sha256(canonical(value).toByteArray(Charsets.UTF_8))

    /** Record what this SDK produced for one case of one vector file. */
    fun record(vectorFile: String, case: String, produced: JsonElement) {
        if (target == null) return
        val digest = canonicalDigest(produced)
        val cases = results.getOrPut(vectorFile) { linkedMapOf() }
        val previous = cases[case]
        check(previous == null || previous == digest) {
            "$vectorFile/$case: recorded twice with different outputs"
        }
        cases[case] = digest
    }

    private fun write() {
        val out = target ?: return
        // Written even when nothing ran: leaving the old file alone would let a
        // suite that executed no case at all present last week's artifact as
        // this run's output.
        val document = buildJsonObject {
            put("schema_version", JsonPrimitive(1))
            putJsonObject("results") {
                for (vectorFile in results.keys.sorted()) {
                    val parsed = Json.parseToJsonElement(
                        javaClass.getResourceAsStream("/thalovant/$vectorFile")!!
                            .bufferedReader().use { it.readText() },
                    )
                    putJsonObject(vectorFile) {
                        // The parsed JSON, not the bytes: a vendored copy is
                        // allowed to differ in indentation and line endings, and
                        // the checker accepts it on the same terms.
                        put("digest", JsonPrimitive(canonicalDigest(parsed)))
                        putJsonObject("cases") {
                            val cases = results.getValue(vectorFile)
                            for (name in cases.keys.sorted()) {
                                put(name, JsonPrimitive(cases.getValue(name)))
                            }
                        }
                    }
                }
            }
        }
        val file = File(out)
        file.parentFile?.mkdirs()
        file.writeText(
            Json { prettyPrint = true; prettyPrintIndent = "  " }
                .encodeToString(JsonObject.serializer(), document) + "\n",
        )
    }
}
