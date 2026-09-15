package com.thalovant.sdk

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The authorization-code grant with PKCE (RFC 7636), for an app that can open
 * a browser.
 *
 * The device grant in [ThalovantControlPlane.loginWithBrowser] exists for
 * things that cannot: somebody reads a code off one screen and types it into
 * another. An app on a phone is not in that position -- it *is* the screen --
 * and asking its owner to copy a code between two windows on the same device
 * is a worse experience than the one every other app on that phone offers.
 *
 * This was written because thalovant-android needed it and there was no SDK
 * that had it, so the app hand-rolled a verifier, an S256 challenge, a token
 * POST and a redirect check. Every app that needs this needs the same four
 * things, and getting any of them subtly wrong is a security bug rather than a
 * visible one.
 *
 * ## Using it
 *
 * ```kotlin
 * val begin = NativeSignIn.begin(clientId = "my-app", redirectUri = "myapp://auth")
 * openInBrowser(begin.authorizationUrl)          // a Custom Tab, SFSafariViewController, ...
 * // ... the browser comes back to myapp://auth?code=...&state=...
 * val code = begin.codeFrom(redirectUri)         // verifies state, refuses a mismatch
 * val token = plane.completeNativeSignIn(code, begin.verifier, "my-app", "myapp://auth")
 * ```
 *
 * [Begun.verifier] never leaves the device and never enters the browser. That
 * is the whole point of PKCE: an authorization code intercepted by another app
 * claiming the same redirect scheme is useless without it.
 */
public object NativeSignIn {

    /** Where a person approves the request. */
    public const val DEFAULT_DASHBOARD_URL: String = "https://dash.thalovant.com"

    /** The three a phone needs; also the three a free plan may mint. */
    public val DEFAULT_SCOPES: List<String> = listOf("hubs:read", "clients:read", "clients:write")

    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /**
     * One sign-in attempt in progress. Keep it until the browser comes back;
     * it holds the two secrets that make the round trip safe.
     */
    public data class Begun(
        /** Open this in a browser. */
        public val authorizationUrl: String,
        /** Proves the redirect answers *this* attempt and not a replayed one. */
        public val state: String,
        /** Never send this to the browser. Exchanged with the code, once. */
        public val verifier: String,
    ) {
        /**
         * The authorization code out of the redirect the browser came back
         * with, or null when it is not an answer to this attempt.
         *
         * Returns null rather than throwing on a state mismatch, a missing
         * code, or an `error=` response -- including one that also carries a
         * code: all of those mean "do not continue", and
         * an app that treats them alike cannot accidentally treat one of them
         * as success.
         */
        public fun codeFrom(redirect: String): String? {
            val query = runCatching { URI(redirect) }.getOrNull()?.rawQuery ?: return null
            val parameters = query.split("&").mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
            }.toMap()
            // An `error=` response is a refusal, and a refusal that also
            // carries a code is still a refusal. Checking only for a missing
            // code accepted that pair and started an exchange on it, which is
            // the opposite of what the docs above promise.
            if (parameters["state"] != state || "error" in parameters) return null
            return parameters["code"]?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Start a sign-in. Returns the URL to open and the secrets to keep.
     *
     * [redirectUri] must be one the API has registered for [clientId]; the
     * authorization endpoint matches it exactly and refuses anything else, so
     * it cannot be turned into an open redirect.
     */
    public fun begin(
        clientId: String,
        redirectUri: String,
        scopes: List<String> = DEFAULT_SCOPES,
        dashboardUrl: String = DEFAULT_DASHBOARD_URL,
    ): Begun {
        require(clientId.isNotBlank()) { "clientId is required" }
        require(redirectUri.isNotBlank()) { "redirectUri is required" }
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        val query = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "code_challenge" to challengeFor(verifier),
            // S256 only. `plain` is refused by the API, and offering it here
            // would only give a caller a way to ask for the weaker one.
            "code_challenge_method" to "S256",
            "scope" to scopes.joinToString(" "),
            "state" to state,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return Begun(
            authorizationUrl = "${dashboardUrl.trimEnd('/')}/authorize?$query",
            state = state,
            verifier = verifier,
        )
    }

    /** A PKCE verifier: 64 random bytes, base64url, no padding. */
    public fun newVerifier(): String = randomUrlSafe(64)

    /** The S256 challenge for a verifier. */
    public fun challengeFor(verifier: String): String =
        base64Url.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

    /**
     * Whether a URL belongs to Thalovant, for an app that wants to show the
     * host it is about to send somebody to. Scheme and suffix only: this is a
     * display check, not an authorization one.
     */
    public fun isThalovantUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "thalovant.com" || host.endsWith(".thalovant.com")
    }

    private fun randomUrlSafe(bytes: Int): String =
        base64Url.encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
