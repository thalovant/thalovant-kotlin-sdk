package com.thalovant.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The two gaps that made a phone say the wrong thing on 2026-09-15. */
class SdkGapsTest {

    private fun hub(json: String) = Json.parseToJsonElement(json).jsonObject

    // -- the refusal the API wrote, which the SDK used to bury -------------

    @Test
    fun `the sentence the API wrote for a person is reachable`() {
        val error = ThalovantApiException(
            "Thalovant API request failed with HTTP 403: {...}",
            statusCode = 403,
            body = """{"type":"about:blank","status":403,"detail":"Free plan allows up to 1 client."}""",
        )
        assertEquals("Free plan allows up to 1 client.", error.detail)
    }

    @Test
    fun `a validation failure nests its sentence one deeper`() {
        val error = ThalovantApiException(
            "422",
            statusCode = 422,
            body = """{"detail":{"detail":"That name is already taken.","errors":[]}}""",
        )
        assertEquals("That name is already taken.", error.detail)
    }

    @Test
    fun `a body that is not a problem document says nothing rather than guessing`() {
        assertNull(ThalovantApiException("x", statusCode = 500, body = "<html>502</html>").detail)
        assertNull(ThalovantApiException("x", statusCode = 500, body = "{}").detail)
        assertNull(ThalovantApiException("x", statusCode = 500, body = "").detail)
        assertNull(ThalovantApiException("local failure").detail)
    }

    // -- what to call a hub -----------------------------------------------

    @Test
    fun `a hub is called what a person was shown, not what the row is keyed by`() {
        // Exactly what a phone was offered: name IS the slug, and the readable
        // title sits in the catalog entry.
        assertEquals(
            "Ops Copilot",
            hubDisplayName(hub("""{"name":"ops-copilot","slug":"ops-copilot","spec":{"catalog":{"title":"Ops Copilot"}}}""")),
        )
    }

    @Test
    fun `a real name wins when there is no catalog entry`() {
        assertEquals("The Kitchen", hubDisplayName(hub("""{"name":"The Kitchen","slug":"kitchen"}""")))
    }

    @Test
    fun `a hub with nothing but a slug is made readable rather than shown raw`() {
        assertEquals("Daily Desk", hubDisplayName(hub("""{"slug":"daily-desk"}""")))
        assertEquals("News Stream", hubDisplayName(hub("""{"name":"news-stream","slug":"news-stream"}""")))
    }

    @Test
    fun `a hub described with nothing at all still says something`() {
        assertEquals("A Thalovant hub", hubDisplayName(hub("""{"id":"1"}""")))
        assertEquals("A Thalovant hub", hubDisplayName(hub("""{"name":"","slug":""}""")))
    }
}
