package com.thalovant.sdk

import kotlinx.serialization.json.*
import java.util.regex.Pattern

internal fun listingResource(name: String): JsonObject = ListingRules::class.java.getResourceAsStream("/thalovant/$name")!!.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }

/** A configured rule exceeded its bounded evaluation budget. */
public class ListingRuleLimitException(message: String): IllegalArgumentException(message)
private class RegexBudget(var remaining: Int = 200_000)
private class BudgetText(private val text: CharSequence, private val budget: RegexBudget = RegexBudget()): CharSequence {
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if (--budget.remaining < 0) throw ListingRuleLimitException("Listing rule exceeded its character-access budget")
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BudgetText(text.subSequence(startIndex,endIndex),budget)
    override fun toString(): String = text.toString()
}

/** Immutable complete language-data snapshot. Null selects bare rendering. */
public class ListingRules(data: JsonObject? = listingResource("listing.json")) {
    public val available: Boolean = data != null
    private val snapshot = data?.let { Json.parseToJsonElement(it.toString()).jsonObject } ?: JsonObject(emptyMap())
    private val languages = snapshot["languages"]?.jsonObject ?: JsonObject(emptyMap())
    public val sentenceEnds: String = snapshot["sentence_ends"]?.jsonPrimitive?.content.orEmpty()
    private val sentenceEndPoints = mutableSetOf<Int>().apply {
        var index = 0
        while (index < sentenceEnds.length) {
            val scalar = sentenceEnds.codePointAt(index)
            add(scalar); index += Character.charCount(scalar)
        }
    }
    private fun values(data: JsonObject, key: String): List<String> = data[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun compile(expression: String, ignoreCase: Boolean): Pattern {
        val boundary = "(?:(?<![\\p{L}\\p{N}_])(?=[\\p{L}\\p{N}_])|(?<=[\\p{L}\\p{N}_])(?![\\p{L}\\p{N}_]))"
        val nonBoundary = "(?:(?=[\\s\\S])|(?<=[\\s\\S]))(?:(?<=[\\p{L}\\p{N}_])(?=[\\p{L}\\p{N}_])|(?<![\\p{L}\\p{N}_])(?![\\p{L}\\p{N}_]))"
        val converted = StringBuilder(); var inClass = false; var i = 0
        while (i < expression.length) {
            val char = expression[i++]
            if (char == '\\' && i < expression.length) {
                val next = expression[i++]
                if (next == 'b' && !inClass) converted.append(boundary) else if (next == 'B' && !inClass) converted.append(nonBoundary) else converted.append(char).append(next)
            } else {
                if (char == '[') inClass = true
                if (char == ']') inClass = false
                converted.append(char)
            }
        }
        return Pattern.compile(converted.toString(), if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
    }
    private val patterns = languages.mapValues { (_,data) -> values(data.jsonObject,"question_patterns").map { compile(it,true) } }
    private val written = languages.mapValues { (_,data) -> data.jsonObject["written_forms"]?.jsonObject.orEmpty().map { (word,value) ->
        val escaped = word.map { if (it in "\\.^$|?*+()[]{}") "\\$it" else it.toString() }.joinToString("")
        compile("\\b${escaped}\\b",false) to value.jsonPrimitive.content
    } }
    private fun tag(lang: String?): String? = lang?.takeIf { it.isNotEmpty() }?.let { closestLanguage(it,languages.keys) }
    public fun languageData(lang: String?): JsonObject = tag(lang)?.let { Json.parseToJsonElement(languages.getValue(it).toString()).jsonObject } ?: JsonObject(emptyMap())
    private fun wordSet(lang: String?, key: String): Set<String> = (if (lang.isNullOrEmpty()) languages.values.map { it.jsonObject } else listOf(languageData(lang))).flatMap { values(it,key) }.map { it.lowercase() }.toSet()
    private fun words(text: String): List<String> = text.splitToSequence(Regex("(?U)\\s+")).filter { it.isNotEmpty() }.toList()
    public fun dangling(text: String,lang: String? = null): Boolean {
        var end = text.length
        while (end > 0) {
            val scalar = text.codePointBefore(end)
            if (scalar != ' '.code && scalar !in sentenceEndPoints) break
            end -= Character.charCount(scalar)
        }
        return words(text.substring(0,end)).lastOrNull()?.lowercase() in wordSet(lang,"trailing_words")
    }
    private fun matches(pattern: Pattern, text: String): Boolean = try { pattern.matcher(BudgetText(text)).find() } catch (_: StackOverflowError) { throw ListingRuleLimitException("Listing rule exceeded the regex stack budget") }
    /** Throws [ListingRuleLimitException] when a custom rule exceeds its budget. */
    public fun asks(text: String,lang: String? = null): Boolean {
        if (patterns[tag(lang)].orEmpty().any { matches(it,text) }) return true
        val words = words(text).map { it.trim { c -> c in ",;:!?.’'\"()" }.lowercase() }.filter { it.isNotEmpty() }
        return words.firstOrNull() in wordSet(lang,"question_openers") || words.any { it in wordSet(lang,"question_words_anywhere") }
    }
    public fun asSentence(raw: String,lang: String? = null): String {
        var text = raw.trim(); if (text.isEmpty()) return text
        val end = Character.charCount(text.codePointAt(0))
        text = text.substring(0,end).uppercase()+text.substring(end)
        if (text.codePointBefore(text.length) in sentenceEndPoints || dangling(text,lang)) return text
        val data = languageData(lang)
        if (lang.isNullOrEmpty() || listOf("question_openers","question_words_anywhere","question_patterns").all { values(data,it).isEmpty() }) return text
        // Literal whole-word rewrites use bounded matching too.
        for ((pattern,written) in written[tag(lang)].orEmpty()) {
            try {
                val matcher = pattern.matcher(BudgetText(text)); val result = StringBuilder(); var previous = 0
                while (matcher.find()) { result.append(text.substring(previous,matcher.start())).append(written); previous = matcher.end() }
                result.append(text.substring(previous)); text = result.toString()
            } catch (_: ListingRuleLimitException) { return text } catch (_: StackOverflowError) { return text }
        }
        return try { text + if (asks(text,lang)) "?" else "." } catch (_: ListingRuleLimitException) { text }
    }
    public fun speakable(pattern: String,slots: Map<String,String> = emptyMap(),lang: String? = null): String {
        val examples = languageData(lang)["slot_examples"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        return com.thalovant.sdk.speakable(pattern,examples+slots)
    }
    public fun rank(phrases: List<String>,lang: String? = null): List<String> = phrases.sortedWith(compareBy({ dangling(it,lang) },{ '{' in it },{ -minOf(words(it).size,8) },{ it.codePointCount(0,it.length) }))
}

public val DEFAULT_LISTING: ListingRules = ListingRules()
public fun asSentence(text: String,lang: String? = null,listing: ListingRules = DEFAULT_LISTING): String = listing.asSentence(text,lang)
public fun speakableWithLanguage(pattern: String,slots: Map<String,String> = emptyMap(),lang: String? = null,listing: ListingRules = DEFAULT_LISTING): String = listing.speakable(pattern,slots,lang)
