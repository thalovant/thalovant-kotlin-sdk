package com.thalovant.sdk

import kotlinx.serialization.json.*
import kotlin.test.*

class ListingTest {
    @Test fun `question detection matches Python reference`() {
        for (row in fixture("question-vectors.json")["cases"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(row["expected"]!!.jsonPrimitive.boolean, DEFAULT_LISTING.asks(row["text"]!!.jsonPrimitive.content,row["lang"]?.jsonPrimitive?.contentOrNull),row.toString())
        }
    }
    @Test fun `sentence marks compare whole Unicode scalars`() {
        val letter = "\uD801\uDC41"
        val mark = "\uD807\uDC41"
        assertEquals("Go $letter.",asSentence("go $letter","en"))
        assertEquals("Go $mark",asSentence("go $mark","en"))
        assertFalse(DEFAULT_LISTING.dangling("weather in$letter","en"))
        assertTrue(DEFAULT_LISTING.dangling("weather in$mark","en"))
    }
    /**
     * Exactly the code points Python's `str.isspace()` accepts, all of Unicode.
     *
     * The listing mirrors the Python reference's `str.split()`, so whitespace
     * is whatever Python says it is -- checked everywhere, not on a sample.
     */
    @Test fun `listing whitespace is exactly Python's`() {
        val python = setOf(
            0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
            0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
        )
        val differ = (0..0x10FFFF).filter { isListingSpace(it) != (it in python) }
        assertTrue(differ.isEmpty(), "differs from Python at: " + differ.take(10).joinToString { "U+%04X".format(it) })
    }

    @Test fun `a trailing word is found across any Unicode space`() {
        // A no-break space and an ideographic space split words as a plain one does.
        for (space in listOf(" ", "\u00a0", "\u3000", "\u2009")) {
            assertTrue(DEFAULT_LISTING.dangling("weather${space}in", "en"), "U+%04X".format(space[0].code))
        }
    }

    /**
     * Android's regex engine is ICU, and ICU rejects the `(?U)` flag outright.
     * The JVM this suite runs on accepts it, so a behavioural test here passes
     * while every listing on every phone throws PatternSyntaxException -- which
     * is what happened. This reads the sources instead.
     */
    @Test fun `no regex uses a flag Android rejects`() {
        val offenders = java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
                else if ("(?U)" in line || "UNICODE_CHARACTER_CLASS" in line) "${file.name}:${i + 1}" else null
            } }.toList()
        assertTrue(offenders.isEmpty(), "Android rejects these: $offenders")
    }

    private fun fixture(name: String) = javaClass.getResourceAsStream("/thalovant/$name")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private fun data(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun `matches published Python listing reference cases`() {
        for (row in fixture("listing-vectors.json")["cases"]!!.jsonArray.map { it.jsonObject }) {
            val lang = row["lang"]?.jsonPrimitive?.contentOrNull
            val actual = when (row["kind"]!!.jsonPrimitive.content) {
                "sentence" -> JsonPrimitive(asSentence(row["text"]!!.jsonPrimitive.content,lang))
                "speakable" -> JsonPrimitive(speakableWithLanguage(row["text"]!!.jsonPrimitive.content,lang=lang))
                else -> JsonArray(DEFAULT_LISTING.rank(row["phrases"]!!.jsonArray.map { it.jsonPrimitive.content },lang).map(::JsonPrimitive))
            }
            assertEquals(row["expected"],actual,row.toString())
        }
    }
    @Test fun `matches OVOS regional language reference cases`() {
        for (row in fixture("language-matching-vectors.json")["cases"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(row["expected"]!!.jsonPrimitive.contentOrNull,closestLanguage(row["target"]!!.jsonPrimitive.content,row["available"]!!.jsonArray.map { it.jsonPrimitive.content }),row.toString())
        }
    }
    @Test fun `selected locale and rendered unique limits`() {
        val intent = HubIntent("s","n","padatious",phrases=linkedMapOf("fr-FR" to listOf("volume {level} pour cent"),"en-US" to listOf("volume {level} percent")))
        assertEquals(listOf("Volume cinquante pour cent."),intent.examplesWithListing(sentence=true))
        assertEquals(listOf("Volume ten percent."),intent.examplesWithListing("en-GB",sentence=true,slots=mapOf("level" to "ten")))
        val repeated = HubIntent("s","n","padatious",phrases=mapOf("en-US" to listOf("[please]","(repeat|say) that (again|)","[please] repeat that","volume [to] {level} percent")))
        for (limit in listOf(0,2)) assertEquals(listOf("Repeat that.","Volume fifty percent."),repeated.examplesWithListing("en-US",limit,sentence=true))
        assertEquals(repeated.phrases["en-US"],repeated.examples("en-US",0))
    }
    @Test fun `custom data preserves optional question rules and snapshots input`() {
        val patterns = mutableListOf<JsonElement>(JsonPrimitive("(?i)^is it"),JsonPrimitive("(?m)^can it"))
        val rules = ListingRules(buildJsonObject { put("sentence_ends",".!?");put("languages",buildJsonObject { put("xq",buildJsonObject { put("question_patterns",JsonArray(patterns));put("slot_examples",buildJsonObject { put("thing","the widget") }) }) }) })
        patterns[0] = JsonPrimitive("never")
        assertEquals("Is it ready?",rules.asSentence("is it ready","xq"))
        assertEquals("Can it work?",rules.asSentence("can it work","xq"))
        assertEquals("Go home.",rules.asSentence("go home","xq"))
        assertEquals("What time is it",rules.asSentence("what time is it","en"))
        assertEquals("open the widget",rules.speakable("open {thing}",lang="xq-ZZ"))
        val anywhere = ListingRules(data("""{"sentence_ends":".!?","languages":{"xq":{"question_words_anywhere":["plim"]}}}"""))
        assertEquals("Go plim now?",anywhere.asSentence("go plim now","xq"))
    }
    @Test fun `no data and invalid or expensive rules fail conservatively`() {
        val bare = ListingRules(null)
        assertFalse(bare.available)
        assertEquals("Do i need a jacket",bare.asSentence("do i need a jacket","en"))
        assertEquals("volume level percent",bare.speakable("volume [to] {level} percent",lang="en"))
        assertFailsWith<IllegalArgumentException> { ListingRules(data("""{"languages":{"xq":{"question_patterns":["("]}}}""")) }
        val costly = ListingRules(data("""{"sentence_ends":".!?","languages":{"xq":{"question_patterns":["(a+)+$"]}}}"""))
        val text = "a".repeat(100000)+"x"
        assertFailsWith<ListingRuleLimitException> { costly.asks(text,"xq") }
        assertTrue(costly.asSentence(text,"xq") == "A"+text.drop(1))
    }

    @Test fun `Unicode non-boundaries share the word definition`() {
        val rules = ListingRules(data("""{"languages":{"xq":{"question_patterns":["\\Bété\\B"]}}}"""))
        assertFalse(rules.asks("été","xq"))
        assertTrue(rules.asks("pétéx","xq"))
    }
}
