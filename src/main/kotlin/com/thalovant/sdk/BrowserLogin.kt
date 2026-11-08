package com.thalovant.sdk

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How [ThalovantControlPlane.interactiveLogin] signs in: [AUTO] (the browser
 * when this machine has one and the API offers it, device login otherwise),
 * or one of the two, forced.
 */
public enum class LoginMethod(public val wireName: String) {
    AUTO("auto"),
    BROWSER("browser"),
    DEVICE("device"),
    ;

    public companion object {
        /** The method named `auto`, `browser` or `device`; anything else is an [IllegalArgumentException]. */
        public fun fromWireName(name: String): LoginMethod =
            entries.firstOrNull { it.wireName == name }
                ?: throw IllegalArgumentException("method must be 'auto', 'browser' or 'device', not '$name'.")
    }
}

/** Options for [ThalovantControlPlane.browserLogin]. */
public data class BrowserLoginOptions(
    /**
     * What the token may do. Null or empty -- or only empty strings -- asks
     * for the API's default, `hubs:read` and `clients:write`.
     */
    public val scopes: List<String>? = null,
    /**
     * What the consent page and the token show; null or empty is
     * [NativeSignIn.defaultDeviceName], `thalovant-kotlin-sdk on <hostname>`.
     * Cut to 120 code points.
     */
    public val deviceName: String? = null,
    /**
     * Open the consent page in this machine's browser. The URL is printed (or
     * handed to [prompt]) either way, because opening one can fail without a
     * word.
     */
    public val openBrowser: Boolean = true,
    /** How long to wait for the browser to come back before [ThalovantDeviceLoginExpiredException]. */
    public val timeoutMillis: Long = NativeSignIn.DEFAULT_BROWSER_LOGIN_TIMEOUT_MILLIS,
    /**
     * Receives the authorization URL to present yourself, in place of the
     * printed lines. It does not stop the browser from opening; pass
     * [openBrowser] false for that.
     */
    public val prompt: ((String) -> Unit)? = null,
    /**
     * Opens a URL in a browser, in place of the platform's own
     * (`java.awt.Desktop`, else `open`, `xdg-open` or `rundll32`). Called on a
     * daemon thread of its own; what it throws is ignored.
     */
    public val opener: ((String) -> Unit)? = null,
)

/** Options for [ThalovantControlPlane.interactiveLogin]. */
public data class InteractiveLoginOptions(
    /** [LoginMethod.AUTO] chooses; [LoginMethod.BROWSER] and [LoginMethod.DEVICE] force one. */
    public val method: LoginMethod = LoginMethod.AUTO,
    /** What the token may do; null asks for the API's default, as both methods do. */
    public val scopes: List<String>? = null,
    /**
     * What the consent page and the token show (a device login's
     * `client_name`); null or empty is [NativeSignIn.defaultDeviceName].
     */
    public val deviceName: String? = null,
    /** Open the browser: the consent page, or a device login's verification page. */
    public val openBrowser: Boolean = true,
    /**
     * How long to wait; null is each method's own default: 300 seconds for
     * the browser, 900 for a device login.
     */
    public val timeoutMillis: Long? = null,
    /** Opens a URL in a browser in place of the platform's own; see [BrowserLoginOptions.opener]. */
    public val opener: ((String) -> Unit)? = null,
)

/** What a device login waits for by default, when [InteractiveLoginOptions.timeoutMillis] is null. */
internal const val DEFAULT_DEVICE_LOGIN_TIMEOUT_MILLIS: Long = 900_000

/** The `error` a consent page sends back, kept when it reads as a code and nothing else. */
private val PROBLEM_CODE = Regex("[A-Za-z0-9_.:-]{1,80}")

/** [error] as an error code worth carrying, or null. */
internal fun browserErrorCode(error: String?): String? = error?.takeIf { PROBLEM_CODE.matches(it) }

/** [millis] in seconds the way the reference prints them: `300`, `0.4`. */
internal fun secondsText(millis: Long): String = BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()

/**
 * Shows the sign-in URL -- opening a browser can fail without a word -- and
 * opens it on a daemon thread of its own: a console browser can hold the call
 * until it exits, and the listener has to be answering by then.
 */
internal fun presentBrowserLogin(
    url: String,
    prompt: ((String) -> Unit)?,
    openBrowser: Boolean,
    opener: ((String) -> Unit)?,
) {
    when {
        prompt != null -> prompt(url)
        openBrowser -> println("Opening your browser to sign in to Thalovant. If it does not open, visit:\n$url")
        else -> println("To sign in to Thalovant, visit:\n$url")
    }
    if (openBrowser) openOnBrowserThread(url, opener ?: ::openInPlatformBrowser)
}

/**
 * Runs [open] for [url] on a daemon thread of its own, named
 * `thalovant-browser`, and ignores what it throws. Whatever opens the browser
 * -- a console browser that holds the call until it exits, an opener that
 * waits for its process -- must not hold up the sign-in: neither the
 * listener's answer nor a device login's polling and deadline.
 */
internal fun openOnBrowserThread(url: String, open: (String) -> Unit) {
    Thread({ runCatching { open(url) } }, "thalovant-browser").apply { isDaemon = true }.start()
}

/**
 * Opens [url] with the platform's default browser: `java.awt.Desktop` where
 * the JVM has a desktop that can browse, else `open` (macOS), `rundll32
 * url.dll,FileProtocolHandler` (Windows; never `cmd /c start`, whose shell
 * splits the URL at its `&`) or `xdg-open` (anything else). Nothing on
 * Android, which has neither: an app there signs in with [NativeSignIn] and
 * its own redirect. Its failure changes nothing; the URL was already shown.
 */
internal fun openInPlatformBrowser(url: String) {
    if (isAndroid()) return
    if (browseWithDesktop(url)) return
    val command = browserCommand(NativeSignIn.platformName(), url)
    ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
}

/** The command that opens [url] on [platform], as separate arguments: no shell ever reads it. */
internal fun browserCommand(platform: String, url: String): List<String> = when (platform) {
    "macos" -> listOf("open", url)
    "windows" -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
    else -> listOf("xdg-open", url)
}

/** Whether this runtime is Android's, where neither `java.awt` nor `xdg-open` exists. */
internal fun isAndroid(): Boolean =
    listOf("java.vm.vendor", "java.vendor", "java.vm.name", "java.runtime.name").any { key ->
        val value = runCatching { System.getProperty(key) }.getOrNull().orEmpty().lowercase()
        "android" in value || "dalvik" in value
    }

/**
 * `java.awt.Desktop.browse`, looked up reflectively because `java.awt` is not
 * there on every JVM; false when the JVM has no desktop, cannot browse, or the
 * browse failed.
 */
private fun browseWithDesktop(url: String): Boolean = try {
    val desktopClass = Class.forName("java.awt.Desktop")
    val supported = desktopClass.getMethod("isDesktopSupported").invoke(null) as? Boolean ?: false
    if (!supported) {
        false
    } else {
        val desktop = desktopClass.getMethod("getDesktop").invoke(null)
        val actionClass = Class.forName("java.awt.Desktop\$Action")
        val browse = actionClass.enumConstants?.firstOrNull { it.toString() == "BROWSE" }
        if (browse == null || desktopClass.getMethod("isSupported", actionClass).invoke(desktop, browse) != true) {
            false
        } else {
            desktopClass.getMethod("browse", java.net.URI::class.java).invoke(desktop, java.net.URI(url))
            true
        }
    }
} catch (_: Throwable) {
    false
}

/**
 * [error], with every value in [hidden] taken out of its message and its body.
 *
 * The message of a refused request already has what the request sent
 * redacted, but its [ThalovantApiException.body] -- and so its `problem` and
 * `detail` -- is what the API sent, and an API that echoes a request back (a
 * FastAPI 422 lists each field's `input`) would hand the code and the
 * verifier to whoever logs the error. A JSON body is scrubbed member by
 * member, keys included; any other body as text. The clean copy keeps the
 * status, the kind of error and the retry hint, and carries no cause.
 */
internal fun withoutSecrets(error: ThalovantApiException, hidden: List<String?>): ThalovantApiException {
    val values = hidden.filterNotNull().filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    if (values.isEmpty()) return error
    fun scrub(text: String): String = values.fold(text) { result, value -> result.replace(value, "[redacted]") }
    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value) })
        is JsonArray -> JsonArray(element.map(::scrub))
        is JsonPrimitive -> when {
            element.isString -> JsonPrimitive(scrub(element.content))
            // A number or a literal that holds one: a string from here on.
            scrub(element.content) != element.content -> JsonPrimitive(scrub(element.content))
            else -> element
        }
    }
    val message = scrub(error.message.orEmpty())
    val body = error.body?.let { text ->
        // Only an object or an array is read as JSON: the parser takes a bare
        // word such as `<p>...</p>` for a literal, which is text all the same.
        val parsed = runCatching { ThalovantJson.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it is JsonObject || it is JsonArray }
        if (parsed != null) {
            val clean = scrub(parsed)
            if (clean == parsed) text else ThalovantJson.encodeToString(JsonElement.serializer(), clean)
        } else {
            scrub(text)
        }
    }
    if (message == error.message && body == error.body) return error
    val status = error.statusCode
    val clean = if (status != null) apiException(message, status, body.orEmpty()) else ThalovantApiException(message, null, body)
    clean.retryAfterSeconds = error.retryAfterSeconds
    if (status == null) clean.errorCode = error.errorCode?.let(::scrub)
    return clean
}
