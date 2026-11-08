package com.thalovant.sdk

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 *
 * ## A program on a desktop or a server
 *
 * The same grant signs a *program* in -- a command line, a desktop tool, a
 * service somebody starts by hand -- through
 * [ThalovantControlPlane.browserLogin], which listens on a loopback port for
 * the answer (RFC 8252 section 7.3). The pieces it is made of live here, so
 * that each can be checked against the shared `browser-login` vectors on its
 * own: [browserAuthorizationUrl] builds the address the browser opens,
 * [loopbackRedirectUri] the redirect it comes back to, [newState] the value
 * that ties the two together, [NativeSignInConfig] reads where the API says to
 * send the browser, and [canOpenBrowser] says whether this machine has a
 * browser to send. The browser that approves hands the code to its own
 * computer's `127.0.0.1`, so a link or code sent to someone on another
 * computer delivers nothing to the sender. That is all it promises: a
 * forwarded callback port (`ssh -L`, a port-forwarding tool) or another user
 * of the same computer can still complete a sign-in.
 */
public object NativeSignIn {

    /** Where a person approves the request. */
    public const val DEFAULT_DASHBOARD_URL: String = "https://dash.thalovant.com"

    /** The three a phone needs; also the three a free plan may mint. */
    public val DEFAULT_SCOPES: List<String> = listOf("hubs:read", "clients:read", "clients:write")

    /**
     * The SDKs' own registered app, which a program signs in as through the
     * browser. `GET /v1/auth/native/config` names it as `sdk_client_id`.
     */
    public const val SDK_CLIENT_ID: String = "thalovant-sdk"

    /** The one path the loopback listener of a browser sign-in answers. */
    public const val LOOPBACK_CALLBACK_PATH: String = "/callback"

    /** How long [ThalovantControlPlane.browserLogin] waits for the browser to come back by default. */
    public const val DEFAULT_BROWSER_LOGIN_TIMEOUT_MILLIS: Long = 300_000

    /**
     * The longest `device_name` the consent page shows and the token is named
     * by, in Unicode code points (the API refuses a longer one).
     */
    public const val MAX_DEVICE_NAME_LENGTH: Int = 120

    /** The name this SDK gives itself in [defaultDeviceName]. */
    internal const val SDK_DEVICE_LABEL: String = "thalovant-kotlin-sdk"

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
        /**
         * What this attempt asked the callback to arrive at. One that lands
         * anywhere else is not this attempt's, however good its state looks.
         */
        public val redirectUri: String,
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
            val uri = runCatching { URI(redirect) }.getOrNull() ?: return null
            // The callback has to arrive where this attempt asked it to. State
            // proves the answer belongs to this request; the address proves it
            // came back to the app that made it.
            if (!sameTarget(uri, redirectUri)) return null
            val query = uri.rawQuery ?: return null
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
        requireSafeDashboard(dashboardUrl)
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
            redirectUri = redirectUri.trim(),
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
     * An OAuth `state`: 24 random bytes (192 bits), base64url, no padding.
     *
     * It ties the answer the browser brings back to the request that sent it.
     * Compare it in constant time.
     */
    public fun newState(): String = randomUrlSafe(24)

    /**
     * `http://127.0.0.1:<port>/callback`, the one redirect a program is answered on.
     *
     * The IP literal, never `localhost`, which a hosts file or a resolver can
     * send elsewhere; that path; no query, no trailing slash. The API matches
     * it exactly and only for an unprivileged port, which is what a port bound
     * as port 0 always is, so any other port is refused here rather than sent.
     */
    public fun loopbackRedirectUri(port: Int): String {
        require(port in 1024..65535) { "A loopback redirect needs a port from 1024 to 65535, not $port." }
        return "http://127.0.0.1:$port$LOOPBACK_CALLBACK_PATH"
    }

    /**
     * The address a browser sign-in opens.
     *
     * `{authorizationEndpoint}?response_type=code&client_id=...&redirect_uri=...
     * &code_challenge=...&code_challenge_method=S256&state=...&scope=...
     * &device_name=...`, in that order, every value percent-encoded as RFC 3986
     * has it (a space is `%20`). Empty strings among [scopes] are dropped and
     * the rest joined by one space; `scope` is left out when nothing is left,
     * and the API then grants its default (`hubs:read clients:write`).
     * [deviceName] is cut to [MAX_DEVICE_NAME_LENGTH] code points, and left out
     * when empty.
     */
    public fun browserAuthorizationUrl(
        authorizationEndpoint: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
        scopes: List<String>? = null,
        deviceName: String? = null,
        clientId: String = SDK_CLIENT_ID,
    ): String {
        val parameters = mutableListOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to codeChallenge,
            // S256 only: `plain` puts the verifier in the browser.
            "code_challenge_method" to "S256",
            "state" to state,
        )
        val scope = scopes.orEmpty().filter { it.isNotEmpty() }.joinToString(" ")
        if (scope.isNotEmpty()) parameters += "scope" to scope
        if (!deviceName.isNullOrEmpty()) parameters += "device_name" to firstCodePoints(deviceName, MAX_DEVICE_NAME_LENGTH)
        val query = parameters.joinToString("&") { (key, value) -> "$key=${percentEncode(value)}" }
        return "$authorizationEndpoint?$query"
    }

    /**
     * `thalovant-kotlin-sdk on <hostname>`: what the consent page and the token
     * show when the caller names nothing. Cut to [MAX_DEVICE_NAME_LENGTH] code
     * points; just `thalovant-kotlin-sdk` when this machine has no name to give.
     */
    public fun defaultDeviceName(): String = deviceNameFor(localHostName())

    /** [defaultDeviceName] for a given host name, so it can be checked without this machine's. */
    internal fun deviceNameFor(host: String?): String {
        val name = host?.trim().orEmpty()
        return firstCodePoints(if (name.isNotEmpty()) "$SDK_DEVICE_LABEL on $name" else SDK_DEVICE_LABEL, MAX_DEVICE_NAME_LENGTH)
    }

    /**
     * This machine's name, without a DNS lookup where one can be avoided:
     * Windows says it in `COMPUTERNAME`, Linux (and Android) in
     * `/proc/sys/kernel/hostname`; elsewhere -- macOS -- the JDK's own
     * `InetAddress.getLocalHost()` answers. Null when none of them does.
     */
    private fun localHostName(): String? {
        val candidates = sequence {
            yield(runCatching { System.getenv("COMPUTERNAME") }.getOrNull())
            yield(runCatching { java.io.File("/proc/sys/kernel/hostname").takeIf { it.isFile }?.readText() }.getOrNull())
            yield(runCatching { System.getenv("HOSTNAME") }.getOrNull())
            yield(runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())
        }
        return candidates.map { it?.trim() }.firstOrNull { !it.isNullOrEmpty() }
    }

    /**
     * This machine as the browser rule names it: `linux`, `macos`, `windows`,
     * or the JVM's own `os.name` in lower case without spaces (`freebsd`).
     * Android reports `linux`: [canOpenBrowser] then finds no display there,
     * which is what keeps a phone on device login (see
     * [ThalovantControlPlane.interactiveLogin]).
     */
    internal fun platformName(): String = platformNameFor(System.getProperty("os.name").orEmpty())

    /** [platformName] for a given `os.name`. */
    internal fun platformNameFor(osName: String): String {
        val name = osName.lowercase()
        return when {
            name.startsWith("mac") || name.startsWith("darwin") -> "macos"
            name.startsWith("windows") -> "windows"
            name.startsWith("linux") -> "linux"
            else -> name.replace(" ", "")
        }
    }

    /**
     * Whether this machine can show a browser to the person signing in.
     *
     * Never in CI (`CI` set) or over SSH (`SSH_CONNECTION` or `SSH_TTY` set):
     * the browser would open on another machine, or nowhere. Otherwise yes on
     * `macos` and `windows`; anywhere else -- Linux, the BSDs, Android -- only
     * with a display (`DISPLAY` or `WAYLAND_DISPLAY`). A variable counts as set
     * when it has a value: `CI=false` is set, an empty `DISPLAY` is not.
     * [environment] and [platform] default to this process's.
     */
    public fun canOpenBrowser(environment: Map<String, String>? = null, platform: String? = null): Boolean {
        val env = environment ?: System.getenv()
        fun present(name: String): Boolean = !env[name].isNullOrEmpty()
        if (present("CI") || present("SSH_CONNECTION") || present("SSH_TTY")) return false
        val system = platform ?: platformName()
        if (system == "macos" || system == "windows") return true
        return present("DISPLAY") || present("WAYLAND_DISPLAY")
    }

    /**
     * RFC 3986 percent-encoding of a query value: `A-Z a-z 0-9 - . _ ~` as
     * they are, every other byte of the UTF-8 form as `%XX` in upper-case hex,
     * so a space is `%20`. Not [java.net.URLEncoder], which writes a space as
     * `+`, a `~` as `%7E` and leaves `*` alone.
     */
    internal fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
                append(char)
            } else {
                append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** The first [limit] code points of [text], never cutting one in half. */
    internal fun firstCodePoints(text: String, limit: Int): String {
        if (text.codePointCount(0, text.length) <= limit) return text
        return text.substring(0, text.offsetByCodePoints(0, limit))
    }

    /**
     * Whether a URL belongs to Thalovant, for an app that wants to show the
     * host it is about to send somebody to. Scheme and suffix only: this is a
     * display check, not an authorization one.
     */
    public fun isThalovantUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        // Reject embedded credentials. `https://evil.test@dash.thalovant.com/`
        // has a host that passes, and thalovant-android refused it on purpose
        // before this existed: a URL somebody is about to be sent to should
        // not read as one host and resolve to another.
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "thalovant.com" || host.endsWith(".thalovant.com")
    }

    /**
     * Whether a callback arrived at the address this attempt asked for.
     *
     * Scheme, host and path, with a trailing slash treated as absent: a
     * browser may add or drop one, and it changes nothing about where the
     * callback landed. The query is deliberately not compared -- it is the
     * part carrying the answer.
     */
    private fun sameTarget(got: URI, expected: String): Boolean {
        val want = runCatching { URI(expected) }.getOrNull() ?: return false
        return got.scheme.equals(want.scheme, ignoreCase = true) &&
            got.host.equals(want.host, ignoreCase = true) &&
            got.path.orEmpty().trimEnd('/') == want.path.orEmpty().trimEnd('/')
    }

    /**
     * Refuse to hand the authorization request to a dashboard that cannot be
     * trusted with it.
     *
     * The request carries the challenge, the scopes and the state. A caller may
     * point this at their own dashboard -- a self-hosted control plane is a
     * real thing -- but not at a cleartext one, and not at one whose address
     * reads as a different host than it resolves to. Loopback is allowed: it
     * never leaves the machine.
     */
    public fun requireSafeDashboard(dashboardUrl: String) {
        val uri = runCatching { URI(dashboardUrl) }.getOrNull()
            ?: throw IllegalArgumentException("dashboardUrl is not a URL: $dashboardUrl")
        require(uri.rawUserInfo == null) { "dashboardUrl must not carry credentials." }
        // A query or a fragment breaks the address this builds. "<dash>#x"
        // becomes "<dash>#x/authorize?client_id=..." -- every parameter lands
        // in the fragment, which a browser never sends, so the authorize
        // endpoint receives nothing and says nothing. A query mangles the path
        // the same way. A plain path is fine: "<dash>/app" works.
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "dashboardUrl must not carry a query or a fragment."
        }
        if (uri.scheme.equals("https", ignoreCase = true)) return
        if (uri.scheme.equals("http", ignoreCase = true) && isLoopback(uri.host)) return
        throw IllegalArgumentException(
            "dashboardUrl must be https (or a loopback address while developing), not $dashboardUrl",
        )
    }

    /** Loopback, in both spellings a URI parser hands back for IPv6. */
    internal fun isLoopback(host: String?): Boolean =
        host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** Random bytes as base64url with no padding, for a verifier or a state. */
    private fun randomUrlSafe(bytes: Int): String =
        base64Url.encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** Percent-encode for a query value, with a space as %20 rather than +. */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Undo [encode]. A value that will not decode is returned as it arrived. */
    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * Where a program signs in through the browser, read from
 * `GET /v1/auth/native/config`.
 *
 * [authorizationEndpoint] is the dashboard page the browser opens;
 * [tokenEndpoint] the path on the API the code is exchanged at; [sdkClientId]
 * the registered app the program signs in as. The API reads them from its own
 * settings, so a staging or self-hosted API sends the browser to its own
 * dashboard. [ThalovantControlPlane.browserLogin] never uses the answer's
 * `loopback_redirect_uri`: the redirect is fixed
 * ([NativeSignIn.loopbackRedirectUri]).
 */
public data class NativeSignInConfig(
    public val authorizationEndpoint: String,
    public val tokenEndpoint: String,
    public val sdkClientId: String,
    public val deviceAuthorizationEndpoint: String? = null,
) {
    public companion object {
        /**
         * Reads the answer; refuses one a browser sign-in cannot safely use
         * with an [IllegalArgumentException].
         *
         * Unusable: an answer that is not a JSON object; an
         * `authorization_endpoint` that is not https (or http on `localhost`,
         * `127.0.0.1` or `::1`), has no host, or carries credentials, a query
         * (or a bare `?`), a fragment, whitespace, control characters, port 0
         * or a port above 65535, or a host that is not, as written, a
         * bracketed IPv6 address, an IPv4 address or an ASCII DNS name (no
         * `%`, `_` or empty label; `xn--` for an international one); a
         * `token_endpoint` that is not a path on the API (one leading `/`, not
         * `//`, every `%` followed by two hex digits, no `:`, `?`, `#`, `\` or
         * whitespace);
         * an `sdk_client_id` that is not 1-64 characters of `[A-Za-z0-9._-]`;
         * or `code_challenge_methods_supported` that is not a list holding
         * `S256`. Other members are ignored.
         */
        public fun fromJson(payload: JsonElement?): NativeSignInConfig {
            val answer = payload as? JsonObject
                ?: throw IllegalArgumentException("The browser sign-in configuration is not an object.")
            val authorization = (answer["authorization_endpoint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val token = (answer["token_endpoint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val clientId = (answer["sdk_client_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val methods = answer["code_challenge_methods_supported"] as? JsonArray
            require(authorization != null && usableAuthorizationEndpoint(authorization)) {
                "The browser sign-in configuration has no usable authorization_endpoint."
            }
            require(token != null && usableTokenEndpoint(token)) {
                "The browser sign-in configuration has no usable token_endpoint."
            }
            require(clientId != null && CLIENT_ID.matches(clientId)) {
                "The browser sign-in configuration has no usable sdk_client_id."
            }
            require(methods != null && methods.any { it is JsonPrimitive && it.isString && it.content == "S256" }) {
                "The browser sign-in configuration does not offer S256."
            }
            return NativeSignInConfig(
                authorizationEndpoint = authorization,
                tokenEndpoint = token,
                sdkClientId = clientId,
                deviceAuthorizationEndpoint = (answer["device_authorization_endpoint"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() },
            )
        }

        private val CLIENT_ID = Regex("[A-Za-z0-9._-]{1,64}")
        private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val BROKEN_ESCAPE = Regex("%(?![0-9A-Fa-f]{2})")
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

        /** Whitespace as Python's `str.isspace` has it, a C0 control, DEL, or a C1 control. */
        private fun unsafe(char: Char): Boolean =
            isListingSpace(char.code) || char.code < 32 || char.code in 127..159

        /**
         * https, or http on a loopback host while developing; a host; no
         * credentials, query, fragment, whitespace or control characters; a
         * port a browser can open. Read as the reference's `urlsplit` reads it,
         * not as [java.net.URI] does, which leaves the host null for a name
         * with an underscore that a browser opens happily.
         */
        private fun usableAuthorizationEndpoint(value: String): Boolean {
            if (value.isEmpty() || value.any(::unsafe) || '?' in value || '#' in value) return false
            val colon = value.indexOf(':')
            if (colon <= 0) return false
            val scheme = value.substring(0, colon)
            if (!scheme[0].isAsciiLetter() || !scheme.all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }) return false
            val rest = value.substring(colon + 1)
            if (!rest.startsWith("//")) return false
            val authority = rest.substring(2).substringBefore('/')
            if ('@' in authority) return false
            val opens = '[' in authority
            if (opens != (']' in authority)) return false
            val host: String
            val port: String
            if (opens) {
                // Nothing before the bracket, and nothing but a port after it.
                if (!authority.startsWith('[')) return false
                host = authority.substring(1).substringBefore(']')
                val after = authority.substringAfter(']')
                if (after.isNotEmpty() && !after.startsWith(':')) return false
                port = after.removePrefix(":")
            } else {
                host = authority.substringBefore(':')
                port = authority.substringAfter(':', "")
            }
            // A port above 65535 does not parse, and no browser opens port 0 either.
            if (port.isNotEmpty() && (!port.all { it in '0'..'9' } || port.toBigInteger() > 65535.toBigInteger() ||
                    port.toBigInteger().signum() == 0)) return false
            val hostname = host.lowercase()
            if (hostname.isEmpty() || !realHost(hostname, bracketed = opens)) return false
            return when (scheme.lowercase()) {
                "https" -> true
                "http" -> hostname in LOOPBACK_HOSTS
                else -> false
            }
        }

        /**
         * Whether [host] (lower case, brackets off) names something a browser
         * can reach, read as it is written: an IPv6 address only between
         * brackets; an IPv4 address whenever the last label is a number, as a
         * browser reads it; otherwise a DNS name of ASCII labels of letters,
         * digits and inner hyphens, 1-63 characters each, at most 253 in all
         * (an international name in its `xn--` form). So `%`, `_`, an empty
         * label, a trailing dot and a non-ASCII host are unusable.
         */
        private fun realHost(host: String, bracketed: Boolean): Boolean {
            if (bracketed) return isIpv6Literal(host)
            if (isIpv4(host)) return true
            val labels = host.split('.')
            // Numeric like an IPv4 address, and not one.
            if (labels.last().isNotEmpty() && labels.last().all { it in '0'..'9' }) return false
            return host.length <= 253 && labels.all { HOST_LABEL.matches(it) }
        }

        /** Four decimal octets of 0-255, without a leading zero, as the reference's `ipaddress` reads one. */
        private fun isIpv4(text: String): Boolean {
            val octets = text.split('.')
            return octets.size == 4 && octets.all { octet ->
                octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
                    !(octet.length > 1 && octet[0] == '0') && octet.toInt() <= 255
            }
        }

        /**
         * A path on the API: one leading slash, not two; every `%` followed by
         * two hex digits; no `:`, `?`, `#`, `\`, whitespace or control character.
         *
         * No colon anywhere, because a URL joiner can read what follows the
         * slash as a URL of its own (`/https://collector.example/token`,
         * `/collector.example:443/token`), and the exchange carries the code
         * and the verifier. A broken escape (`/v1/%zz`) is caught here, while a
         * device login can still stand in, rather than after somebody approved.
         */
        private fun usableTokenEndpoint(value: String): Boolean =
            value.startsWith("/") && !value.startsWith("//") && !BROKEN_ESCAPE.containsMatchIn(value) &&
                value.none { it in ":?#\\" || unsafe(it) }

        private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

        /**
         * An IPv6 address literal as the reference's `ipaddress.ip_address`
         * reads one: eight groups of one to four hex digits, at most one `::`
         * standing for one or more of them, an IPv4 dotted quad allowed as the
         * last two, and a non-empty `%zone` after it.
         */
        private fun isIpv6Literal(text: String): Boolean {
            var address = text
            val percent = text.indexOf('%')
            if (percent >= 0) {
                val zone = text.substring(percent + 1)
                if (zone.isEmpty() || '%' in zone) return false
                address = text.substring(0, percent)
            }
            val lastColon = address.lastIndexOf(':')
            if (lastColon < 0) return false
            // Groups the address must spell out, counting a dotted quad as one
            // placeholder group standing for the two it is worth.
            var width = 8
            val tail = address.substring(lastColon + 1)
            if ('.' in tail) {
                if (!isIpv4(tail)) return false
                address = address.substring(0, lastColon + 1) + "0"
                width = 7
            }
            fun group(value: String): Boolean =
                value.length in 1..4 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            val doubled = address.indexOf("::")
            if (doubled < 0) {
                val groups = address.split(':')
                return groups.size == width && groups.all(::group)
            }
            if (address.indexOf("::", doubled + 1) >= 0) return false
            val left = address.substring(0, doubled).takeIf { it.isNotEmpty() }?.split(':').orEmpty()
            val right = address.substring(doubled + 2).takeIf { it.isNotEmpty() }?.split(':').orEmpty()
            val groups = left + right
            // `::` stands for at least one group.
            return groups.all(::group) && groups.size <= width - 1
        }
    }
}
