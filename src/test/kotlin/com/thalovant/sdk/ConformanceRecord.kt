package com.thalovant.sdk

import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
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
    private fun canonical(value: JsonElement, fractions: Boolean = false): String = when (value) {
        is JsonNull -> "null"
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it, fractions) }
        is JsonObject ->
            value.keys.sortedWith(::byCodePoint).joinToString(",", "{", "}") { key ->
                quote(key) + ":" + canonical(value.getValue(key), fractions)
            }
        is JsonPrimitive ->
            if (value.isString) {
                quote(value.content)
            } else if (fractions) {
                referenceNumber(value.content)
            } else {
                wholeNumber(value.content)
            }
    }

    /**
     * A string exactly as Python's `json.dumps(..., ensure_ascii=False)` writes
     * it: only `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, and `\u00xx` in
     * lowercase hex for the rest below U+0020; everything else raw -- `/`,
     * U+007F, and U+2028 and U+2029, which the speech vectors hold. Written
     * by hand so no serialiser's own choices can move a digest.
     */
    internal fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    /**
     * Keys sorted as Python sorts `str`, by code point -- not by UTF-16 unit,
     * which puts a supplementary character before U+E000..U+FFFF.
     */
    private fun byCodePoint(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /**
     * Spell a number of a *vector file* the way the reference spells it.
     *
     * The reference digests each vector file as it parsed it, with Python's
     * float repr for any fraction. The vectors keep to whole numbers now --
     * durations are whole milliseconds -- but the first Home Assistant vectors
     * did not (`poll_interval_seconds: 0.01`), and refusing one here fails in
     * a shutdown hook, where it writes no results and fails no build. That
     * digest identifies the vectors rather than anything this SDK produced,
     * so it is spelled Python's way: a whole number as an integer, anything
     * else as the shortest decimal that reads back as the same double,
     * positional down to 1e-4 and `1e-05` style below. What a case produced
     * is still held to [wholeNumber].
     */
    internal fun referenceNumber(content: String): String {
        if (content == "true" || content == "false") return content
        val decimal = content.toBigDecimalOrNull()
            ?: error("conformance: cannot canonicalise $content: not a number")
        runCatching { return decimal.toBigIntegerExact().toString() }
        val value = content.toDouble()
        check(value.isFinite()) { "conformance: cannot canonicalise $content: not finite" }
        val exact = BigDecimal(value)
        var shortest: BigDecimal? = null
        for (precision in 1..17) {
            val nearest = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            val step = BigDecimal.ONE.movePointLeft(nearest.scale())
            shortest = listOf(nearest, nearest - step, nearest + step)
                .filter { it.toDouble() == value }
                .minByOrNull { (it - exact).abs() }
            if (shortest != null) break
        }
        val digits = shortest!!.stripTrailingZeros()
        val unscaled = digits.unscaledValue().abs().toString()
        val point = unscaled.length - digits.scale()
        val sign = if (digits.signum() < 0) "-" else ""
        return sign + when {
            point <= -4 || point > 16 -> {
                val exponent = point - 1
                val mantissa = if (unscaled.length == 1) unscaled else unscaled[0] + "." + unscaled.substring(1)
                mantissa + "e" + (if (exponent < 0) "-" else "+") + kotlin.math.abs(exponent).toString().padStart(2, '0')
            }
            point <= 0 -> "0." + "0".repeat(-point) + unscaled
            point >= unscaled.length -> unscaled + "0".repeat(point - unscaled.length) + ".0"
            else -> unscaled.substring(0, point) + "." + unscaled.substring(point)
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
     * have per-language spellings. No case produces one, and if one ever does
     * this should stop rather than lie. (A vector file itself may hold one;
     * its digest goes through [referenceNumber].)
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

    /** The digest of a vector file as the reference computes it; see [referenceNumber]. */
    fun vectorDigest(value: JsonElement): String = sha256(canonical(value, fractions = true).toByteArray(Charsets.UTF_8))

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
                        put("digest", JsonPrimitive(vectorDigest(parsed)))
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
