package com.thalovant.sdk

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The authorization-code grant, which every app that needed it had to write
 * for itself until this existed.
 *
 * The things tested here are the ones that are a security bug when wrong and
 * look fine when wrong: that the challenge really is S256 of the verifier,
 * that the verifier never reaches the browser, that a redirect answering a
 * different state is refused, and that `plain` cannot be asked for.
 */
class NativeSignInTest {

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split("&").associate { pair ->
            val index = pair.indexOf('=')
            java.net.URLDecoder.decode(pair.substring(0, index), "UTF-8") to
                java.net.URLDecoder.decode(pair.substring(index + 1), "UTF-8")
        }

    @Test
    fun `the challenge is the S256 of the verifier, which is what the API checks`() {
        val begun = NativeSignIn.begin(clientId = "thalovant-android", redirectUri = "thalovant://auth")
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(begun.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, query(begun.authorizationUrl)["code_challenge"])
    }

    @Test
    fun `the verifier never reaches the browser`() {
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertFalse(begun.authorizationUrl.contains(begun.verifier))
    }

    @Test
    fun `S256 is the only method offered, because plain is the weaker one`() {
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertEquals("S256", query(begun.authorizationUrl)["code_challenge_method"])
    }

    @Test
    fun `every attempt gets its own verifier and state`() {
        val first = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        val second = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.state, second.state)
    }

    @Test
    fun `the request carries what the authorize endpoint matches on`() {
        val begun = NativeSignIn.begin(
            clientId = "thalovant-android",
            redirectUri = "thalovant://auth",
            scopes = listOf("hubs:read", "clients:write"),
        )
        val parameters = query(begun.authorizationUrl)
        assertEquals("thalovant-android", parameters["client_id"])
        assertEquals("thalovant://auth", parameters["redirect_uri"])
        assertEquals("code", parameters["response_type"])
        assertEquals("hubs:read clients:write", parameters["scope"])
        assertEquals(begun.state, parameters["state"])
        assertTrue(begun.authorizationUrl.startsWith("https://dash.thalovant.com/authorize?"))
    }

    @Test
    fun `a redirect answering a different attempt is refused`() {
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertNull(begun.codeFrom("app://auth?code=abc&state=somebody-elses"))
        // ...and the right one is taken.
        assertEquals("abc", begun.codeFrom("app://auth?code=abc&state=${begun.state}"))
    }

    @Test
    fun `a redirect with no code, or an error instead, is not success`() {
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertNull(begun.codeFrom("app://auth?state=${begun.state}"))
        assertNull(begun.codeFrom("app://auth?code=&state=${begun.state}"))
        assertNull(begun.codeFrom("app://auth?error=access_denied&state=${begun.state}"))
        assertNull(begun.codeFrom("not a url at all"))
        assertNull(begun.codeFrom("app://auth"))
    }

    @Test
    fun `a code containing url-escaped characters survives the round trip`() {
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertEquals("a+b/c=", begun.codeFrom("app://auth?code=a%2Bb%2Fc%3D&state=${begun.state}"))
    }

    @Test
    fun `an empty client or redirect is refused here rather than at the API`() {
        assertFailsWith<IllegalArgumentException> { NativeSignIn.begin("", "app://auth") }
        assertFailsWith<IllegalArgumentException> { NativeSignIn.begin("app", "  ") }
    }

    @Test
    fun `a Thalovant URL is recognised by scheme and suffix, and nothing else is`() {
        assertTrue(NativeSignIn.isThalovantUrl("https://dash.thalovant.com/authorize?x=1"))
        assertTrue(NativeSignIn.isThalovantUrl("https://thalovant.com"))
        assertFalse(NativeSignIn.isThalovantUrl("http://dash.thalovant.com"))
        // The one that matters: a lookalike host ending in the same letters.
        assertFalse(NativeSignIn.isThalovantUrl("https://dash.thalovant.com.evil.test"))
        // A host that passes, reached through credentials that read as another.
        assertFalse(NativeSignIn.isThalovantUrl("https://evil.test@dash.thalovant.com"))
        assertFalse(NativeSignIn.isThalovantUrl("https://notthalovant.com"))
        assertFalse(NativeSignIn.isThalovantUrl("nonsense"))
    }

    @Test
    fun `a refusal that also carries a code is still a refusal`() {
        // CodeRabbit caught this: checking only for a missing code accepted
        // `error=access_denied&code=...` and would have started an exchange on
        // a code the authorization server had just declined to issue.
        val begun = NativeSignIn.begin(clientId = "app", redirectUri = "app://auth")
        assertNull(begun.codeFrom("app://auth?error=access_denied&code=abc&state=${begun.state}"))
        assertNull(begun.codeFrom("app://auth?code=abc&error=server_error&state=${begun.state}"))
    }

    @Test
    fun `the token exchange refuses to put the code on the wire in cleartext`() = runBlocking {
        val plane = ThalovantControlPlane(apiUrl = "http://control.example.test")
        val failure = assertFailsWith<ThalovantApiException> {
            plane.completeNativeSignIn("code", "verifier", "app", "app://auth")
        }
        assertTrue(failure.message!!.contains("cleartext"), failure.message!!)
        // ...and nothing was sent, so no token was stored.
        assertNull(plane.accessToken)
    }

    @Test
    fun `loopback is allowed, because there is no cleartext to observe`() = runBlocking {
        // Refused for the right reason -- it tried, and failed to connect --
        // rather than refused by the scheme check.
        for (host in listOf("http://localhost:8080", "http://127.0.0.1:8080")) {
            val plane = ThalovantControlPlane(apiUrl = host)
            val failure = runCatching {
                plane.completeNativeSignIn("code", "verifier", "app", "app://auth")
            }.exceptionOrNull()
            assertTrue(
                failure == null || !(failure.message ?: "").contains("cleartext"),
                "loopback was refused by the scheme check: ${failure?.message}",
            )
        }
    }
}
