package com.thalovant.sdk

import kotlinx.serialization.json.*
import kotlin.test.*

class ListingTest {
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
