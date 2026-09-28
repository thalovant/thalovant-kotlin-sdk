package com.thalovant.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Home Assistant link: a hub asks a home, and the home always answers.
 *
 * A home skill on the hub sends [REQUEST] to the account's Home Assistant
 * connection; the integration hands the utterance to Home Assistant's
 * conversation agent and answers with [RESPONSE]. The rules every SDK keeps
 * (`home-link-vectors.json`):
 *
 * - every request gets at most one answer, and never after the hub's
 *   [REQUEST_TIMEOUT_MS], counted from its arrival: the handler's time and the
 *   reply's own sending both come out of that bound, and a reply that could
 *   only arrive late is not sent at all;
 * - the answer is a reply (OVOS-MSG-1 §5.2), so it goes back the way the
 *   request came ([replyContext]);
 * - `speech` is plain text, never markup ([plainSpeech]);
 * - `response_type` is one of [RESPONSE_TYPES], and an [ERROR] names one of
 *   [ERROR_CODES]. When the SDK has to answer for a handler -- it threw, it
 *   was too slow, it answered outside the contract -- the speech is empty:
 *   the hub speaks its own sentence for the code, in the device's language,
 *   which the SDK does not know.
 *
 * [ThalovantClient.answerHomeRequests] and [HubSession.answerHomeRequests]
 * answer every request a connection receives.
 */
public object ThalovantHome {
    /** What a hub sends: `{request_id, utterance, lang, conversation_id?}`. */
    public const val REQUEST: String = "thalovant.home.request"

    /**
     * What the SDK answers: `{request_id, speech, response_type, error_code?,
     * continue_conversation, conversation_id?}`.
     */
    public const val RESPONSE: String = "thalovant.home.response"

    /** How long the hub waits for an answer before it treats silence as [TIMEOUT]. */
    public const val REQUEST_TIMEOUT_MS: Long = 10_000

    /**
     * How long a handler has by default: a second inside the hub's
     * [REQUEST_TIMEOUT_MS], so the SDK's own [TIMEOUT] answer still lands
     * before the hub gives up.
     */
    public const val DEFAULT_HANDLER_TIMEOUT_MS: Long = REQUEST_TIMEOUT_MS - 1_000

    /** `response_type`: the home did what was asked. */
    public const val ACTION_DONE: String = "action_done"

    /** `response_type`: the home answered a question. */
    public const val QUERY_ANSWER: String = "query_answer"

    /** `response_type`: the home could not; [ERROR_CODES] says why. */
    public const val ERROR: String = "error"

    /** `error_code`: nothing in the home understood the request. */
    public const val NO_INTENT_MATCH: String = "no_intent_match"

    /** `error_code`: it understood, and nothing matched what it named. */
    public const val NO_VALID_TARGETS: String = "no_valid_targets"

    /** `error_code`: the handler threw. */
    public const val FAILED_TO_HANDLE: String = "failed_to_handle"

    /** `error_code`: an answer outside the contract, or none at all. */
    public const val UNKNOWN: String = "unknown"

    /** `error_code`: the handler did not answer in time. */
    public const val TIMEOUT: String = "timeout"

    /** `error_code`: the home's conversation agent is not there to ask. */
    public const val AGENT_UNAVAILABLE: String = "agent_unavailable"

    /** Every `response_type` the hub accepts, in the contract's order. */
    public val RESPONSE_TYPES: List<String> = listOf(ACTION_DONE, QUERY_ANSWER, ERROR)

    /** Every `error_code` the hub accepts, in the contract's order. */
    public val ERROR_CODES: List<String> =
        listOf(NO_INTENT_MATCH, NO_VALID_TARGETS, FAILED_TO_HANDLE, UNKNOWN, TIMEOUT, AGENT_UNAVAILABLE)
}

/**
 * One `thalovant.home.request`: what was said, in which language.
 *
 * [requestId] is `""` when the hub sent none -- the answer still goes out,
 * with `request_id: ""`. [event] is the message it arrived as; the answer is a
 * reply to it. Two requests are equal when their four fields are.
 */
public class HomeRequest(
    public val requestId: String,
    public val utterance: String,
    public val lang: String? = null,
    public val conversationId: String? = null,
    public val event: ThalovantEvent? = null,
) {
    override fun equals(other: Any?): Boolean = other is HomeRequest &&
        requestId == other.requestId && utterance == other.utterance &&
        lang == other.lang && conversationId == other.conversationId

    override fun hashCode(): Int = listOf(requestId, utterance, lang, conversationId).hashCode()

    override fun toString(): String =
        "HomeRequest(requestId=$requestId, utterance=$utterance, lang=$lang, conversationId=$conversationId)"

    public companion object {
        /** Reads a request from the bus event it arrived as. Anything but a non-empty string reads as absent. */
        public fun fromEvent(event: ThalovantEvent): HomeRequest = HomeRequest(
            requestId = jsonText(event.data["request_id"]) ?: "",
            utterance = jsonText(event.data["utterance"]) ?: "",
            lang = jsonText(event.data["lang"]),
            conversationId = jsonText(event.data["conversation_id"]),
            event = event,
        )
    }
}

/**
 * What a handler says back. [speech] may carry markup; it is sent as plain text.
 *
 * [responseType] is one of [ThalovantHome.RESPONSE_TYPES]; with
 * [ThalovantHome.ERROR], [errorCode] is one of [ThalovantHome.ERROR_CODES]. An
 * answer outside those is sent as `error` / `unknown`, keeping its speech.
 * [conversationId] defaults to the request's, echoed back.
 */
public data class HomeAnswer(
    public val speech: String = "",
    public val responseType: String = ThalovantHome.ACTION_DONE,
    public val errorCode: String? = null,
    public val continueConversation: Boolean = false,
    public val conversationId: String? = null,
)

/**
 * Speech a device can say as it is, made in this order, as every SDK makes it
 * (`home-link-vectors.json`):
 *
 * 1. Markup is removed: a tag -- `<` or `</` immediately followed by an ASCII
 *    letter, then everything up to the next `>` outside a quoted attribute
 *    value -- a comment (`<!--` to `-->`) or a processing instruction (`<?` to
 *    `?>`). Any other `<` is text, so "5 < 6 and 7 > 3" stays whole, and so
 *    does an unclosed `<b`.
 * 2. Character references are decoded once, left to right: numeric ones
 *    (`&#72;`, `&#x48;`), except 0, surrogates and anything past U+10FFFF,
 *    which stay as written; the five XML entities; and `&nbsp;`. Nothing
 *    else -- `&eacute;` stays `&eacute;` -- and a reference needs its `;`.
 *    An escaped `&lt;b&gt;` is therefore the text `<b>`.
 * 3. Every run of Unicode White_Space becomes one space, and White_Space --
 *    only White_Space: U+001C..U+001F are kept -- is trimmed from both ends.
 *
 * Linear in the length of [text], which comes off the network.
 *
 * No platform HTML library is used: their entity tables differ.
 */
public fun plainSpeech(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val decoded = decodeReferences(stripMarkup(text))
    val out = StringBuilder(decoded.length)
    var index = 0
    while (index < decoded.length) {
        val point = decoded.codePointAt(index)
        index += Character.charCount(point)
        if (isWhiteSpace(point)) {
            // A run becomes one space; a space is itself White_Space, so a
            // space at the end of what is built can only be this run's.
            if (out.isEmpty() || out[out.length - 1] != ' ') out.append(' ')
        } else {
            out.appendCodePoint(point)
        }
    }
    // Every White_Space run is one space by now, so trimming spaces trims
    // exactly White_Space: Kotlin's own trim() would also take U+001C..U+001F.
    return out.toString().trim(' ')
}
/**
 * The `thalovant.home.response` payload for [answer], held to the contract.
 *
 * A null [answer] is `error` / `unknown`. A `response_type` outside
 * [ThalovantHome.RESPONSE_TYPES], or an `error` whose code is outside
 * [ThalovantHome.ERROR_CODES], becomes `error` / `unknown`, keeping its speech.
 * `error_code` is sent only with `error`, and `conversation_id` only when the
 * answer or the request has one.
 */
public fun homeResponse(request: HomeRequest, answer: HomeAnswer?): JsonObject {
    val given = answer ?: HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.UNKNOWN)
    var responseType = given.responseType
    var errorCode = if (responseType == ThalovantHome.ERROR) given.errorCode else null
    if (responseType !in ThalovantHome.RESPONSE_TYPES ||
        (responseType == ThalovantHome.ERROR && errorCode !in ThalovantHome.ERROR_CODES)
    ) {
        responseType = ThalovantHome.ERROR
        errorCode = ThalovantHome.UNKNOWN
    }
    return buildJsonObject {
        put("request_id", request.requestId)
        put("speech", plainSpeech(given.speech))
        put("response_type", responseType)
        if (!errorCode.isNullOrEmpty()) put("error_code", errorCode)
        put("continue_conversation", given.continueConversation)
        val conversationId = given.conversationId?.takeIf { it.isNotEmpty() } ?: request.conversationId
        if (!conversationId.isNullOrEmpty()) put("conversation_id", conversationId)
    }
}

/**
 * Answers one request: runs [handler], then replies whatever happened, within
 * [hubTimeoutMs] of the request's arrival at [arrivedAtNanos].
 *
 * The hub gives up on a request after its bound, and an answer it has given
 * up on only confuses the next one. So the handler gets [timeoutMs] or what is
 * left of the bound, whichever is less, and the reply gets what the handler
 * left: it is never started after the bound, and one not sent when the bound
 * passes is withdrawn. Returns the payload sent, or null when there was no
 * time left to send one.
 *
 * The handler runs detached, and the answer goes out at its deadline whether
 * or not it has returned: a handler stuck in a call that ignores cancellation
 * must not hold the reply back. It is cancelled and left to finish on its
 * own; what it returns later is dropped.
 */
internal suspend fun answerHomeRequestWith(
    event: ThalovantEvent,
    timeoutMs: Long,
    handler: suspend (HomeRequest) -> HomeAnswer?,
    hubTimeoutMs: Long = ThalovantHome.REQUEST_TIMEOUT_MS,
    arrivedAtNanos: Long = System.nanoTime(),
    send: suspend (ThalovantEvent, JsonObject) -> Unit,
): JsonObject? {
    require(timeoutMs > 0 && hubTimeoutMs > 0) { "timeoutMs and hubTimeoutMs must be positive." }
    fun remainingMs(): Long = hubTimeoutMs - (System.nanoTime() - arrivedAtNanos) / 1_000_000
    val request = HomeRequest.fromEvent(event)
    val answer = runHandler(request, handler, minOf(timeoutMs, remainingMs()))
    val payload = homeResponse(request, answer)
    val left = remainingMs()
    if (left <= 0) return null
    return withTimeoutOrNull(left) { send(event, payload); payload }
}

private class Answered(val answer: HomeAnswer?)

/** The handler's answer, or the SDK's own for it; see [answerHomeRequestWith]. */
private suspend fun runHandler(
    request: HomeRequest,
    handler: suspend (HomeRequest) -> HomeAnswer?,
    timeoutMs: Long,
): HomeAnswer? {
    val timedOut = HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.TIMEOUT)
    if (timeoutMs <= 0) return timedOut
    // Not a child: a scope waits for its children, and a handler that ignores
    // cancellation would hold the answer until it chose to finish.
    val work = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob()).async { handler(request) }
    return try {
        val done = withTimeoutOrNull(timeoutMs) { Answered(work.await()) }
        if (done != null) done.answer else timedOut
    } catch (cancelled: CancellationException) {
        // Our own caller was cancelled: stop. Otherwise the handler cancelled
        // itself, which is a failure to handle like any other.
        if (!currentCoroutineContext().isActive) throw cancelled
        HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.FAILED_TO_HANDLE)
    } catch (_: Throwable) {
        // An Error too: a handler still at TODO() throws NotImplementedError,
        // and the hub is owed an answer all the same.
        HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.FAILED_TO_HANDLE)
    } finally {
        if (!work.isCompleted) work.cancel()
    }
}

/**
 * Answers every request coming through [subscribe] until the caller is
 * cancelled; each on a coroutine of its own, so a slow one never holds up the
 * next, and each within the hub's bound from its own arrival. Cancelling
 * stops the answers still running. A reply that cannot be sent is dropped:
 * the hub times the request out on its own.
 */
internal suspend fun serveHomeRequests(
    subscribe: ((ThalovantEvent) -> Unit) -> ThalovantSubscription,
    timeoutMs: Long,
    hubTimeoutMs: Long = ThalovantHome.REQUEST_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
    send: suspend (ThalovantEvent, JsonObject) -> Unit,
): Nothing {
    require(timeoutMs > 0) { "timeoutMs must be positive." }
    coroutineScope {
        val requests = Channel<Pair<ThalovantEvent, Long>>(Channel.UNLIMITED)
        // Stamped as it arrives, before any queue: the hub's bound runs from here.
        val subscription = subscribe { requests.trySend(it to System.nanoTime()) }
        try {
            for ((event, arrived) in requests) {
                launch {
                    try {
                        answerHomeRequestWith(event, timeoutMs, handler, hubTimeoutMs, arrived, send)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Nothing to answer on; see above. One request that
                        // could not be answered never ends the link.
                    }
                }
            }
        } finally {
            subscription.close()
            requests.close()
        }
    }
    awaitCancellation()
}

/**
 * Answers one `thalovant.home.request` this client received: runs [handler],
 * then replies with [ThalovantHome.RESPONSE] whatever happened, all within
 * [hubTimeoutMs] from now. Returns the payload sent, or null when no reply
 * could be sent before the hub gave up on the request. See [ThalovantHome]
 * for the rules the answer keeps.
 */
public suspend fun ThalovantClient.answerHomeRequest(
    event: ThalovantEvent,
    timeoutMs: Long = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
    hubTimeoutMs: Long = ThalovantHome.REQUEST_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
): JsonObject? = answerHomeRequestWith(event, timeoutMs, handler, hubTimeoutMs) { request, payload ->
    reply(request, ThalovantHome.RESPONSE, payload)
}

/**
 * Answers every `thalovant.home.request` this client receives, until the
 * calling coroutine is cancelled; it never returns otherwise.
 *
 * ```
 * val link = scope.launch {
 *     client.answerHomeRequests { request ->
 *         val said = assist.process(request.utterance, request.lang)
 *         HomeAnswer(speech = said.speech, responseType = ThalovantHome.ACTION_DONE)
 *     }
 * }
 * // later: link.cancel()
 * ```
 *
 * Each request is answered on a coroutine of its own, so a slow one does not
 * hold up the next; its handler gets [timeoutMs] (a second inside the hub's
 * 10 s by default), and nothing is sent after the hub's 10 s from its arrival. For a link that must survive a dropped connection, use
 * [HubSession.answerHomeRequests].
 */
public suspend fun ThalovantClient.answerHomeRequests(
    timeoutMs: Long = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
): Nothing = serveHomeRequests(
    subscribe = { listener -> on(ThalovantHome.REQUEST, handler = listener) },
    timeoutMs = timeoutMs,
    handler = handler,
) { request, payload -> reply(request, ThalovantHome.RESPONSE, payload) }

/**
 * Removes markup -- tags, comments, processing instructions -- and nothing
 * else; see [plainSpeech]. A tag is `<` or `</` immediately followed by an
 * ASCII letter, then everything up to the next `>` that is not inside a
 * quoted value; a quote with no partner means there is no tag.
 *
 * Linear whatever the text holds, which comes off the network: where a tag
 * scanned from each position would end is computed once, in one pass from the
 * end, and a comment or instruction closer found missing from some point on is
 * not searched for again from any later one. A regular expression for the same
 * rule backtracks, and a scan per `<` is quadratic on text such as `<a<a<a...`.
 */
internal fun stripMarkup(text: String): String {
    if ('<' !in text) return text
    var ends: IntArray? = null
    val missing = HashMap<String, Int>(2)
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (text[index] != '<') {
            val next = text.indexOf('<', index).let { if (it < 0) text.length else it }
            out.append(text, index, next)
            index = next
            continue
        }
        val closer = when {
            text.startsWith("<!--", index) -> "-->"
            text.startsWith("<?", index) -> "?>"
            else -> null
        }
        if (closer != null) {
            val begin = index + if (closer == "-->") 4 else 2
            val found = if (begin < (missing[closer] ?: Int.MAX_VALUE)) {
                text.indexOf(closer, begin).also { if (it < 0) missing[closer] = begin }
            } else {
                -1
            }
            if (found >= 0) {
                index = found + closer.length
                continue
            }
        } else {
            val name = if (index + 1 < text.length && text[index + 1] == '/') index + 2 else index + 1
            if (name < text.length && isAsciiLetter(text[name])) {
                val end = (ends ?: tagEnds(text).also { ends = it })[name + 1]
                if (end >= 0) {
                    index = end + 1
                    continue
                }
            }
        }
        out.append('<')
        index++
    }
    return out.toString()
}

/**
 * For every position, where a tag's `>` is when scanning from there, or -1.
 * A `>` ends the scan; a quote skips to its partner, and one with no partner
 * ends it with no tag; anything else moves on. So the answer from one
 * position is the answer from the next, or from just past the partner quote.
 */
private fun tagEnds(text: String): IntArray {
    val ends = IntArray(text.length + 1) { -1 }
    var afterDouble = -1
    var afterSingle = -1
    for (index in text.length - 1 downTo 0) {
        when (val char = text[index]) {
            '>' -> ends[index] = index
            '"', '\'' -> {
                val partner = if (char == '"') afterDouble else afterSingle
                ends[index] = if (partner < 0) -1 else ends[partner + 1]
                if (char == '"') afterDouble = index else afterSingle = index
            }
            else -> ends[index] = ends[index + 1]
        }
    }
    return ends
}

private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

/**
 * The Unicode White_Space property, spelled out so every SDK collapses the
 * same characters: a regex `\s` differs between languages, and the JVM's
 * `Character.isWhitespace` counts U+001C..U+001F and not U+00A0.
 */
internal fun isWhiteSpace(point: Int): Boolean = point in 0x09..0x0D || point == 0x20 || point == 0x85 ||
    point == 0xA0 || point == 0x1680 || point in 0x2000..0x200A || point == 0x2028 || point == 0x2029 ||
    point == 0x202F || point == 0x205F || point == 0x3000

/** The one small set of references every SDK decodes; see [plainSpeech]. */
private val REFERENCE = Regex("&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|(amp|lt|gt|quot|apos|nbsp));")

private val NAMED_REFERENCES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")

/** Decodes numeric references, the five XML entities and `&nbsp;`, once, left to right. */
internal fun decodeReferences(text: String): String {
    if ('&' !in text) return text
    return REFERENCE.replace(text) { match ->
        val (decimal, hexadecimal, name) = match.destructured
        if (name.isNotEmpty()) return@replace NAMED_REFERENCES.getValue(name)
        val value = if (decimal.isNotEmpty()) decimal.toInt() else hexadecimal.toInt(16)
        if (value == 0 || value in 0xD800..0xDFFF || value > 0x10FFFF) match.value else String(Character.toChars(value))
    }
}
