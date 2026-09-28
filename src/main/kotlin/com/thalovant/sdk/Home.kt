package com.thalovant.sdk

import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
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
 * - every request gets exactly one answer, within the hub's
 *   [REQUEST_TIMEOUT_MS];
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
 * Speech a device can say as it is: markup removed, character references
 * decoded, whitespace collapsed to single spaces and trimmed.
 *
 * `<speak>It is 21&nbsp;degrees</speak>` becomes `It is 21 degrees`. Tags go
 * first, so an escaped `&lt;b&gt;` survives as the text `<b>`. References are
 * decoded exactly as Python's `html.unescape` decodes them -- the HTML5 table,
 * numeric references, and the legacy names HTML accepts without `;` -- which
 * is what the reference SDK does.
 */
public fun plainSpeech(text: String?): String {
    if (text.isNullOrEmpty()) return ""
    val unmarked = SSML_TAG.replace(text, "")
    val decoded = HtmlEntities.unescape(unmarked)
    val out = StringBuilder(decoded.length)
    var pendingSpace = false
    var index = 0
    while (index < decoded.length) {
        val point = decoded.codePointAt(index)
        if (isListingSpace(point)) {
            pendingSpace = out.isNotEmpty()
        } else {
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.appendCodePoint(point)
        }
        index += Character.charCount(point)
    }
    return out.toString()
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
 * Answers one request: runs [handler], then replies whatever happened.
 *
 * The handler runs in a child coroutine and is given [timeoutMs]; the reply
 * goes out at that deadline even when the handler does not stop, and the
 * handler is cancelled. A handler that throws is answered `failed_to_handle`,
 * one that is too slow `timeout`, one that answers null or outside the
 * contract `unknown`. [send] delivers the payload. Returns what was sent.
 */
internal suspend fun answerHomeRequestWith(
    event: ThalovantEvent,
    timeoutMs: Long,
    handler: suspend (HomeRequest) -> HomeAnswer?,
    send: suspend (ThalovantEvent, JsonObject) -> Unit,
): JsonObject {
    require(timeoutMs > 0) { "timeoutMs must be positive." }
    val request = HomeRequest.fromEvent(event)
    return supervisorScope {
        val work = async { handler(request) }
        val answer = try {
            withTimeout(timeoutMs) { work.await() }
        } catch (timeout: TimeoutCancellationException) {
            work.cancel()
            HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.TIMEOUT)
        } catch (cancelled: CancellationException) {
            work.cancel()
            // Our own caller was cancelled: stop. Otherwise the handler
            // cancelled itself, which is a failure to handle like any other.
            if (!currentCoroutineContext().isActive) throw cancelled
            HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.FAILED_TO_HANDLE)
        } catch (_: Exception) {
            HomeAnswer(responseType = ThalovantHome.ERROR, errorCode = ThalovantHome.FAILED_TO_HANDLE)
        }
        val payload = homeResponse(request, answer)
        send(event, payload)
        payload
    }
}

/**
 * Answers every request coming through [subscribe] until the caller is
 * cancelled; each on a coroutine of its own, so a slow one never holds up the
 * next. Cancelling stops the answers still running. A reply that cannot be
 * sent is dropped: the hub times the request out on its own.
 */
internal suspend fun serveHomeRequests(
    subscribe: ((ThalovantEvent) -> Unit) -> ThalovantSubscription,
    timeoutMs: Long,
    handler: suspend (HomeRequest) -> HomeAnswer?,
    send: suspend (ThalovantEvent, JsonObject) -> Unit,
): Nothing {
    require(timeoutMs > 0) { "timeoutMs must be positive." }
    coroutineScope {
        val requests = Channel<ThalovantEvent>(Channel.UNLIMITED)
        val subscription = subscribe { requests.trySend(it) }
        try {
            for (event in requests) {
                launch {
                    try {
                        answerHomeRequestWith(event, timeoutMs, handler, send)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Nothing to answer on; see above.
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
 * then replies with [ThalovantHome.RESPONSE] whatever happened. Returns the
 * payload sent. See [ThalovantHome] for the rules the answer keeps.
 */
public suspend fun ThalovantClient.answerHomeRequest(
    event: ThalovantEvent,
    timeoutMs: Long = ThalovantHome.DEFAULT_HANDLER_TIMEOUT_MS,
    handler: suspend (HomeRequest) -> HomeAnswer?,
): JsonObject = answerHomeRequestWith(event, timeoutMs, handler) { request, payload ->
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
 * hold up the next, and within [timeoutMs] (a second inside the hub's 10 s by
 * default). For a link that must survive a dropped connection, use
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

/** Tags, as the reference strips them: anything from `<` to the next `>`. */
private val SSML_TAG = Regex("</?[^>]*>")

/** Python's `html.unescape`, ported rule for rule. */
internal object HtmlEntities {
    private val REFERENCE = Regex("&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\\t\\n\\u000C <&#;]{1,32};?)")

    private val table: Map<String, String> by lazy {
        val entries = HashMap<String, String>(2400)
        HtmlEntities::class.java.getResourceAsStream("/thalovant/html-entities.tsv")!!
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val (name, points) = line.split('\t', limit = 2)
                    entries[name] = points.split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }
                }
            }
        entries
    }

    /** Windows-1252 for the C1 range, as HTML maps a numeric reference into it. */
    private val INVALID_CHARREFS: Map<Int, String> = mapOf(
        0x00 to "�", 0x0D to "\r", 0x80 to "€", 0x81 to "\u0081", 0x82 to "‚",
        0x83 to "ƒ", 0x84 to "„", 0x85 to "…", 0x86 to "†", 0x87 to "‡",
        0x88 to "ˆ", 0x89 to "‰", 0x8A to "Š", 0x8B to "‹", 0x8C to "Œ",
        0x8D to "\u008D", 0x8E to "Ž", 0x8F to "\u008F", 0x90 to "\u0090", 0x91 to "‘",
        0x92 to "’", 0x93 to "“", 0x94 to "”", 0x95 to "•", 0x96 to "–",
        0x97 to "—", 0x98 to "˜", 0x99 to "™", 0x9A to "š", 0x9B to "›",
        0x9C to "œ", 0x9D to "\u009D", 0x9E to "ž", 0x9F to "Ÿ",
    )

    /** Code points a numeric reference may not produce; each decodes to nothing. */
    private fun invalidCodePoint(point: Int): Boolean =
        point in 0x01..0x08 || point == 0x0B || point in 0x0E..0x1F || point in 0x7F..0x9F ||
            point in 0xFDD0..0xFDEF || (point and 0xFFFE) == 0xFFFE

    fun unescape(text: String): String {
        if ('&' !in text) return text
        return REFERENCE.replace(text) { match -> replacement(match.groupValues[1]) }
    }

    private fun replacement(reference: String): String {
        if (reference[0] == '#') {
            val hex = reference[1] == 'x' || reference[1] == 'X'
            val digits = reference.substring(if (hex) 2 else 1).trimEnd(';')
            val number = BigInteger(digits, if (hex) 16 else 10)
            if (number > BigInteger.valueOf(0x10FFFF)) return "�"
            val point = number.toInt()
            INVALID_CHARREFS[point]?.let { return it }
            if (point in 0xD800..0xDFFF) return "�"
            if (invalidCodePoint(point)) return ""
            return String(Character.toChars(point))
        }
        table[reference]?.let { return it }
        // The longest name that is a prefix, as the standard defines it.
        for (length in reference.length - 1 downTo 2) {
            table[reference.substring(0, length)]?.let { return it + reference.substring(length) }
        }
        return "&$reference"
    }
}
